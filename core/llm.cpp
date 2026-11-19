#include "llm.hpp"

#include <algorithm>
#include <cctype>
#include <fstream>

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

static bool has_version_suffix(const std::string& lower) {
    const std::size_t slash = lower.find_last_of('/');
    if (slash == std::string::npos) {
        return false;
    }
    const std::string last = lower.substr(slash + 1);
    if (last.size() < 2 || last[0] != 'v') {
        return false;
    }
    for (std::size_t i = 1; i < last.size(); ++i) {
        if (!std::isdigit(static_cast<unsigned char>(last[i]))) {
            return false;
        }
    }
    return true;
}

static std::string openai_root(const std::string& base) {
    std::string trimmed = base;
    while (!trimmed.empty() && trimmed.back() == '/') {
        trimmed.pop_back();
    }
    if (trimmed.empty()) {
        trimmed = "http://127.0.0.1:8080";
    }
    // Providers such as Zhipu expose /api/paas/v4; only append /v1 when the
    // configured base has no version segment of its own.
    if (has_version_suffix(to_lower_ascii(trimmed))) {
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

static bool context_overflow(const HttpResult& response) {
    if (response.status != 400 && response.status != 500) {
        return false;
    }
    std::string body = to_lower_ascii(response.body);
    return body.find("context") != std::string::npos &&
           (body.find("exceed") != std::string::npos ||
            body.find("too large") != std::string::npos ||
            body.find("available context") != std::string::npos);
}

static int response_timeout_ms(const LlmConfig& config, int max_tokens) {
    // Local generation can take days for a large reply budget. Keep waiting
    // until the model stops or the user cancels, with a 288-hour safety cap.
    long budget = 120000L + static_cast<long>(std::max(1, max_tokens)) * 500L;
    long configured = std::max<long>(5000L, config.request_timeout_ms);
    return static_cast<int>(std::min<long>(288L * 60L * 60L * 1000L,
            std::max(configured, budget)));
}

// Streams reasoning/content deltas into a newline-delimited JSON trace that the
// Java progress endpoint reads while the core call is still running.
class StreamTrace {
public:
    bool open(const std::string& path) {
        if (path.empty()) {
            return false;
        }
        file.open(path, std::ios::binary | std::ios::trunc);
        return file.is_open();
    }

    void reset() {
        if (file.is_open()) {
            file.close();
        }
        pending.clear();
        reasoning.clear();
        content.clear();
        error.clear();
        model.clear();
        events = 0;
        finished = false;
    }

    void start(const std::string& backend, const std::string& model, int max_tokens) {
        Json event = Json::make_object();
        event["t"] = "start";
        event["b"] = backend;
        event["m"] = model;
        event["x"] = max_tokens;
        write(event);
    }

    void feed(const char* data, size_t size) {
        pending.append(data, size);
        size_t cut;
        while ((cut = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, cut);
            pending.erase(0, cut + 1);
            handle(line);
        }
    }

    void finish() {
        if (!pending.empty()) {
            handle(pending);
            pending.clear();
        }
        Json event = Json::make_object();
        event["t"] = "done";
        event["r"] = static_cast<int>(reasoning.size());
        event["c"] = static_cast<int>(content.size());
        write(event);
        finished = true;
        if (file.is_open()) {
            file.flush();
            file.close();
        }
    }

    bool has_text() const {
        return !reasoning.empty() || !content.empty();
    }

    bool failed() const {
        return !error.empty();
    }

    const std::string& reasoning_text() const { return reasoning; }
    const std::string& content_text() const { return content; }
    const std::string& error_text() const { return error; }
    const std::string& stream_model() const { return model; }

private:
    void write(const Json& event) {
        if (!file.is_open()) {
            return;
        }
        file << event.dump() << '\n';
        file.flush();
    }

    void emit(const char* type, const std::string& text) {
        if (text.empty()) {
            return;
        }
        Json event = Json::make_object();
        event["t"] = type;
        event["d"] = text;
        write(event);
        ++events;
    }

    void append_reasoning(const std::string& text) {
        if (text.empty()) {
            return;
        }
        reasoning += text;
        emit("think", text);
    }

    void append_content(const std::string& text) {
        if (text.empty()) {
            return;
        }
        content += text;
        emit("text", text);
    }

    void take_string(const Json* object, const char* key, bool thinking) {
        if (object == nullptr) {
            return;
        }
        std::string value = object->get_string(key);
        if (value.empty()) {
            return;
        }
        if (thinking) {
            append_reasoning(value);
        } else {
            append_content(value);
        }
    }

    void handle(std::string line) {
        while (!line.empty()
               && (line.back() == '\r' || line.back() == '\n')) {
            line.pop_back();
        }
        std::string payload = trim(line);
        if (payload.empty() || payload[0] == ':') {
            return;
        }
        if (payload.rfind("data:", 0) == 0) {
            payload = trim(payload.substr(5));
        } else if (payload.rfind("event:", 0) == 0
                   || payload.rfind("id:", 0) == 0
                   || payload.rfind("retry:", 0) == 0) {
            return;
        }
        if (payload == "[DONE]") {
            finished = true;
            return;
        }
        if (payload.empty() || payload[0] != '{') {
            return;
        }
        std::string parse_error;
        Json doc = Json::parse(payload, &parse_error);
        if (!parse_error.empty()) {
            return;
        }
        const Json* err_field = doc.find("error");
        if (err_field != nullptr && !err_field->is_null()) {
            error = err_field->is_string()
                    ? err_field->text : err_field->dump();
            Json event = Json::make_object();
            event["t"] = "error";
            event["e"] = error;
            write(event);
            return;
        }
        std::string name = doc.get_string("model");
        if (!name.empty()) {
            model = name;
        }
        const Json* usage = doc.find("usage");
        if (usage != nullptr && usage->is_object()) {
            Json event = Json::make_object();
            event["t"] = "usage";
            event["p"] = usage->get_int("prompt_tokens", 0);
            event["c"] = usage->get_int("completion_tokens", 0);
            event["a"] = usage->get_int("total_tokens", 0);
            write(event);
        }

        const Json* message = doc.find("message");
        if (message != nullptr && message->is_object()) {
            take_string(message, "thinking", true);
            take_string(message, "content", false);
        }
        if (doc.get_bool("done", false)) {
            finished = true;
        }

        const Json* choices = doc.find("choices");
        if (choices != nullptr && choices->is_array() && !choices->arr.empty()) {
            const Json& first = choices->arr.front();
            const Json* delta = first.find("delta");
            const Json* choice_message = first.find("message");
            bool used_delta = false;
            if (delta != nullptr && delta->is_object()) {
                used_delta = true;
                take_string(delta, "reasoning_content", true);
                take_string(delta, "reasoning", true);
                take_string(delta, "content", false);
            }
            if (!used_delta && choice_message != nullptr
                    && choice_message->is_object()) {
                take_string(choice_message, "reasoning_content", true);
                take_string(choice_message, "reasoning", true);
                take_string(choice_message, "content", false);
            }
            if (delta == nullptr && choice_message == nullptr) {
                take_string(&first, "text", false);
            }
            std::string finish_reason = first.get_string("finish_reason");
            if (!finish_reason.empty()) {
                finished = true;
            }
        }
    }

    std::ofstream file;
    std::string pending;
    std::string reasoning;
    std::string content;
    std::string error;
    std::string model;
    long events = 0;
    bool finished = false;
};

// Keeps system prompts plus the most recent turns so an oversized chat history
// still gets an answer instead of an HTTP 400 from the inference server.
static Json keep_recent_messages(const Json& messages, size_t keep_tail) {
    Json result = Json::make_array();
    if (!messages.is_array()) {
        return result;
    }
    size_t total = messages.arr.size();
    size_t tail = std::min(total, keep_tail);
    size_t begin = total - tail;
    for (size_t i = 0; i < begin; ++i) {
        if (messages.arr[i].get_string("role") == "system") {
            result.arr.push_back(messages.arr[i]);
        }
    }
    for (size_t i = begin; i < total; ++i) {
        result.arr.push_back(messages.arr[i]);
    }
    return result;
}

LlmReply llm_complete(const LlmConfig& config,
                      const BackendInfo& backend,
                      const Json& messages,
                      const std::string& model_override,
                      bool extend_timeout,
                      const std::string& stream_path) {
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
    bool streaming = !stream_path.empty() && backend.kind != "ollama";
    payload["stream"] = streaming;
    if (streaming) {
        Json stream_options = Json::make_object();
        stream_options["include_usage"] = true;
        payload["stream_options"] = stream_options;
    }
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
    HttpResult response;
    Json active = messages;
    int max_tokens = std::max(64, config.max_tokens);
    StreamTrace trace;
    for (int attempt = 0; attempt < 4; ++attempt) {
        payload["messages"] = active;
        if (backend.kind == "ollama") {
            payload["options"]["num_predict"] = max_tokens;
        } else {
            payload["max_tokens"] = max_tokens;
        }
        body = payload.dump();
        int timeout_ms = extend_timeout
                ? response_timeout_ms(config, max_tokens)
                : config.request_timeout_ms;
        if (streaming) {
            trace.reset();
            if (!trace.open(stream_path)) {
                streaming = false;
                payload["stream"] = false;
            } else {
                trace.start(backend.kind, model, max_tokens);
            }
        }
        if (streaming) {
            response = http_request_stream(
                "POST", endpoint, body, headers, timeout_ms,
                [&trace](const char* data, size_t size) {
                    trace.feed(data, size);
                    return true;
                });
            trace.finish();
        } else {
            response = http_request("POST", endpoint, body, headers, timeout_ms);
        }
        if (!response.ok || response.status < 400) {
            break;
        }
        if (!context_overflow(response) || !active.is_array()
            || active.arr.size() <= 1) {
            break;
        }
        bool shrank = false;
        if (max_tokens > 512) {
            max_tokens = std::max(512, max_tokens / 2);
            shrank = true;
        }
        size_t keep = std::max<size_t>(1, active.arr.size() / 2);
        Json trimmed = keep_recent_messages(active, keep);
        if (trimmed.arr.size() < active.arr.size()) {
            active = std::move(trimmed);
            shrank = true;
        }
        if (!shrank) {
            break;
        }
    }
    if (!response.ok && (!streaming || !trace.has_text())) {
        reply.error = "AI request failed (" + endpoint + "): " + response.error;
        return reply;
    }
    if (response.status < 200 || response.status >= 300) {
        if (context_overflow(response)) {
            reply.error =
                "The request is larger than the available local context. Raise the "
                "context size in Runtime settings, start a new chat, or shorten the "
                "message. Details: " + response_snippet(response.body);
        } else {
            reply.error = "AI service returned HTTP " + std::to_string(response.status) +
                          ": " + response_snippet(response.body);
        }
        return reply;
    }

    if (streaming) {
        if (trace.failed()) {
            reply.error = "AI service error: " + response_snippet(trace.error_text());
            return reply;
        }
        std::string streamed = trace.content_text();
        if (trim(streamed).empty()) {
            streamed = trace.reasoning_text();
        }
        if (trim(streamed).empty()) {
            reply.error = "AI returned empty content";
            return reply;
        }
        reply.content = streamed;
        reply.ok = true;
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
