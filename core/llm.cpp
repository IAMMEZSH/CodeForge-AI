#include "llm.hpp"

#include <algorithm>
#include <cctype>

#include "http.hpp"
#include "util.hpp"

namespace cf {

static std::vector<std::pair<std::string, std::string>> base_headers(const LlmConfig& config,
                                                                     bool with_auth) {
    std::vector<std::pair<std::string, std::string>> headers;
    headers.emplace_back("Accept", "application/json");
    if (with_auth && !config.api_key.empty()) {
        headers.emplace_back("Authorization", "Bearer " + config.api_key);
    }
    return headers;
}

static std::string openai_root(const std::string& base) {
    std::string trimmed = base;
    while (!trimmed.empty() && trimmed.back() == '/') {
        trimmed.pop_back();
    }
    if (trimmed.empty()) {
        trimmed = "http://127.0.0.1:8080";
    }
    if (to_lower_ascii(trimmed).size() >= 3 &&
        to_lower_ascii(trimmed).substr(to_lower_ascii(trimmed).size() - 3) == "/v1") {
        return trimmed;
    }
    return trimmed + "/v1";
}

static std::string choose_model(const std::vector<std::string>& models,
                                const std::string& configured) {
    if (!configured.empty()) {
        return configured;
    }
    for (const std::string& name : models) {
        if (to_lower_ascii(name).find("coder") != std::string::npos) {
            return name;
        }
    }
    for (const std::string& name : models) {
        if (to_lower_ascii(name).find("qwen") != std::string::npos) {
            return name;
        }
    }
    return models.empty() ? std::string() : models.front();
}

static BackendInfo probe_ollama(const LlmConfig& config) {
    BackendInfo info;
    info.kind = "ollama";
    info.base_url = config.ollama_url;
    std::string endpoint = config.ollama_url + "/api/tags";
    HttpResult response = http_request("GET", endpoint, "", base_headers(config, false), 3000);
    if (!response.ok) {
        info.error = "Cannot connect to Ollama (" + endpoint + "): " + response.error;
        return info;
    }
    if (response.status < 200 || response.status >= 300) {
        info.error = "Ollama returned HTTP " + std::to_string(response.status);
        return info;
    }
    std::string parse_error;
    Json doc = Json::parse(response.body, &parse_error);
    if (!parse_error.empty()) {
        info.error = "Ollama returned unparseable data";
        return info;
    }
    const Json* models = doc.find("models");
    if (models != nullptr && models->is_array()) {
        for (const Json& item : models->arr) {
            std::string name = item.get_string("name");
            if (name.empty()) {
                name = item.get_string("model");
            }
            if (!name.empty()) {
                info.models.push_back(name);
            }
        }
    }
    info.model = choose_model(info.models, config.model);
    if (info.model.empty()) {
        info.error = "Ollama is reachable but exposes no models. Run ollama pull <model> first";
        return info;
    }
    info.available = true;
    return info;
}

static BackendInfo probe_openai(const LlmConfig& config) {
    BackendInfo info;
    info.kind = "openai";
    info.base_url = openai_root(config.openai_url);
    std::string endpoint = info.base_url + "/models";
    HttpResult response =
        http_request("GET", endpoint, "", base_headers(config, true), 3000);
    if (!response.ok) {
        info.error = "Cannot connect to the OpenAI-compatible service (" + endpoint + "): " + response.error;
        return info;
    }
    if (response.status == 401 || response.status == 403) {
        info.error = "The OpenAI-compatible service requires an API key (HTTP " +
                     std::to_string(response.status) + ")";
        return info;
    }
    if (response.status < 200 || response.status >= 300) {
        info.error = "The OpenAI-compatible service returned HTTP " + std::to_string(response.status);
        return info;
    }
    std::string parse_error;
    Json doc = Json::parse(response.body, &parse_error);
    if (!parse_error.empty()) {
        info.error = "The OpenAI-compatible service returned unparseable data";
        return info;
    }
    const Json* data = doc.find("data");
    if (data != nullptr && data->is_array()) {
        for (const Json& item : data->arr) {
            std::string id = item.get_string("id");
            if (!id.empty()) {
                info.models.push_back(id);
            }
        }
    }
    info.model = choose_model(info.models, config.model);
    if (info.model.empty()) {
        info.error = "The OpenAI-compatible service is reachable but lists no models";
        return info;
    }
    info.available = true;
    return info;
}

BackendInfo detect_backend(const LlmConfig& config) {
    std::string kind = to_lower_ascii(config.backend);
    if (kind == "ollama") {
        return probe_ollama(config);
    }
    if (kind == "openai" || kind == "openai-compatible" || kind == "llamacpp") {
        return probe_openai(config);
    }

    BackendInfo ollama = probe_ollama(config);
    if (ollama.available) {
        return ollama;
    }
    BackendInfo openai = probe_openai(config);
    if (openai.available) {
        return openai;
    }

    BackendInfo failed;
    failed.kind = "none";
    failed.error =
        "No usable AI backend was detected.\n"
        "Ollama: " + ollama.error + "\n"
        "OpenAI-compatible service: " + openai.error + "\n"
        "Download a model and the llama.cpp runtime from the Models tab, then start local inference, "
        "or install Ollama and configure the llm section of config.json.";
    return failed;
}

static std::string response_snippet(const std::string& body) {
    return truncate_text(body, 400);
}

LlmReply llm_complete(const LlmConfig& config,
                      const BackendInfo& backend,
                      const Json& messages,
                      const std::string& model_override) {
    LlmReply reply;
    reply.backend = backend.kind;
    if (!backend.available) {
        reply.error = backend.error.empty() ? "AI backend is unavailable" : backend.error;
        return reply;
    }
    if (!messages.is_array() || messages.arr.empty()) {
        reply.error = "messages must be a non-empty array";
        return reply;
    }
    std::string model = model_override.empty() ? backend.model : model_override;
    if (model.empty()) {
        reply.error = "No model selected";
        return reply;
    }
    reply.model = model;

    Json payload = Json::make_object();
    payload["model"] = model;
    payload["messages"] = messages;
    payload["stream"] = false;
    std::string endpoint;
    std::string body;

    if (backend.kind == "ollama") {
        endpoint = backend.base_url + "/api/chat";
        payload["keep_alive"] = "30m";
        Json options = Json::make_object();
        options["temperature"] = config.temperature;
        options["num_predict"] = config.max_tokens;
        payload["options"] = options;
    } else {
        endpoint = backend.base_url + "/chat/completions";
        payload["temperature"] = config.temperature;
        payload["max_tokens"] = config.max_tokens;
    }
    body = payload.dump();

    auto headers = base_headers(config, backend.kind != "ollama");
    headers.emplace_back("Content-Type", "application/json");
    HttpResult response = http_request("POST", endpoint, body, headers,
                                       config.request_timeout_ms);
    if (!response.ok) {
        reply.error = "AI request failed (" + endpoint + "): " + response.error;
        return reply;
    }
    if (response.status < 200 || response.status >= 300) {
        reply.error = "AI service returned HTTP " + std::to_string(response.status) + ": " +
                      response_snippet(response.body);
        return reply;
    }

    std::string parse_error;
    Json doc = Json::parse(response.body, &parse_error);
    if (!parse_error.empty()) {
        reply.error = "AI service returned unparseable data: " + response_snippet(response.body);
        return reply;
    }

    const Json* error_field = doc.find("error");
    if (error_field != nullptr) {
        if (error_field->is_string()) {
            reply.error = "AI service error: " + error_field->text;
        } else {
            reply.error = "AI service error: " + response_snippet(error_field->dump());
        }
        return reply;
    }

    std::string content;
    if (backend.kind == "ollama") {
        const Json* message = doc.find("message");
        if (message != nullptr) {
            content = message->get_string("content");
        }
    } else {
        const Json* choices = doc.find("choices");
        if (choices != nullptr && choices->is_array() && !choices->arr.empty()) {
            const Json& first = choices->arr.front();
            const Json* message = first.find("message");
            if (message != nullptr) {
                content = message->get_string("content");
            }
            if (content.empty()) {
                content = first.get_string("text");
            }
        }
    }

    if (trim(content).empty()) {
        reply.error = "AI returned empty content";
        return reply;
    }
    reply.content = content;
    reply.ok = true;
    return reply;
}

}  // namespace cf
