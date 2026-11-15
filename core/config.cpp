#include "config.hpp"

#include <algorithm>
#include <cstdlib>
#include <fstream>

#include "json.hpp"
#include "util.hpp"

namespace cf {

static int env_int(const char* name, int fallback) {
    std::string raw = get_env_utf8(name);
    if (raw.empty()) {
        return fallback;
    }
    try {
        return std::stoi(raw);
    } catch (...) {
        return fallback;
    }
}

static double env_double(const char* name, double fallback) {
    std::string raw = get_env_utf8(name);
    if (raw.empty()) {
        return fallback;
    }
    try {
        return std::stod(raw);
    } catch (...) {
        return fallback;
    }
}

static std::string env_string(const char* name, const std::string& fallback) {
    std::string raw = get_env_utf8(name);
    return raw.empty() ? fallback : raw;
}

static std::string normalize_url(std::string url) {
    while (!url.empty() && (url.back() == '/' || url.back() == ' ')) {
        url.pop_back();
    }
    return url;
}

AppConfig load_config(const std::filesystem::path& root) {
    AppConfig config;
    std::string raw;
    std::filesystem::path path = root / "config.json";
    if (!read_file_utf8(path, raw)) {
        config.config_error = "config.json not found, using defaults";
    } else {
        std::string parse_error;
        Json parsed = Json::parse(raw, &parse_error);
        if (!parse_error.empty()) {
            config.config_error = "config.json parse error: " + parse_error;
        } else if (parsed.is_object()) {
            const Json* llm = parsed.find("llm");
            if (llm != nullptr && llm->is_object()) {
                config.llm.backend = llm->get_string("backend", config.llm.backend);
                config.llm.ollama_url = llm->get_string("ollama_url", config.llm.ollama_url);
                config.llm.openai_url = llm->get_string("openai_url", config.llm.openai_url);
                config.llm.api_key = llm->get_string("api_key", config.llm.api_key);
                config.llm.model = llm->get_string("model", config.llm.model);
                config.llm.temperature = llm->get_double("temperature", config.llm.temperature);
                config.llm.max_tokens = llm->get_int("max_tokens", config.llm.max_tokens);
                config.llm.request_timeout_ms =
                    llm->get_int("request_timeout_ms", config.llm.request_timeout_ms);
                const Json* online = llm->find("online");
                if (online != nullptr && online->is_object()) {
                    config.llm.online.enabled =
                        online->get_bool("enabled", config.llm.online.enabled);
                    config.llm.online.base_url =
                        online->get_string("base_url", config.llm.online.base_url);
                    config.llm.online.api_key =
                        online->get_string("api_key", config.llm.online.api_key);
                    config.llm.online.model =
                        online->get_string("model", config.llm.online.model);
                    config.llm.online.timeout_ms =
                        online->get_int("timeout_ms", config.llm.online.timeout_ms);
                }
            }
            const Json* debug = parsed.find("debug");
            if (debug != nullptr && debug->is_object()) {
                config.debug.max_iterations =
                    debug->get_int("max_iterations", config.debug.max_iterations);
                config.debug.run_timeout_ms =
                    debug->get_int("run_timeout_ms", config.debug.run_timeout_ms);
                config.debug.compile_timeout_ms =
                    debug->get_int("compile_timeout_ms", config.debug.compile_timeout_ms);
            }
            const Json* server = parsed.find("server");
            if (server != nullptr && server->is_object()) {
                config.server_port = server->get_int("port", config.server_port);
            }
        } else {
            config.config_error = "config.json root must be an object";
        }
    }

    config.llm.backend = env_string("CODEFORGE_LLM_BACKEND", config.llm.backend);
    config.llm.ollama_url = env_string("CODEFORGE_OLLAMA_URL", config.llm.ollama_url);
    config.llm.openai_url = env_string("CODEFORGE_OPENAI_URL", config.llm.openai_url);
    config.llm.api_key = env_string("CODEFORGE_LLM_API_KEY", config.llm.api_key);
    config.llm.model = env_string("CODEFORGE_LLM_MODEL", config.llm.model);
    config.llm.temperature = env_double("CODEFORGE_LLM_TEMPERATURE", config.llm.temperature);
    config.llm.max_tokens = env_int("CODEFORGE_LLM_MAX_TOKENS", config.llm.max_tokens);
    config.llm.request_timeout_ms =
        env_int("CODEFORGE_REQUEST_TIMEOUT_MS", config.llm.request_timeout_ms);
    std::string online_switch = env_string("CODEFORGE_ONLINE_LLM", "");
    if (!online_switch.empty()) {
        config.llm.online.enabled = online_switch != "0" && online_switch != "false" &&
                                     online_switch != "off";
    }
    config.llm.online.base_url =
        env_string("CODEFORGE_ONLINE_LLM_URL", config.llm.online.base_url);
    config.llm.online.api_key =
        env_string("CODEFORGE_ONLINE_LLM_KEY", config.llm.online.api_key);
    config.llm.online.model =
        env_string("CODEFORGE_ONLINE_LLM_MODEL", config.llm.online.model);
    config.llm.online.timeout_ms =
        env_int("CODEFORGE_ONLINE_LLM_TIMEOUT_MS", config.llm.online.timeout_ms);
    config.debug.max_iterations =
        env_int("CODEFORGE_DEBUG_ITERATIONS", config.debug.max_iterations);
    config.debug.run_timeout_ms =
        env_int("CODEFORGE_RUN_TIMEOUT_MS", config.debug.run_timeout_ms);
    config.debug.compile_timeout_ms =
        env_int("CODEFORGE_COMPILE_TIMEOUT_MS", config.debug.compile_timeout_ms);
    config.server_port = env_int("CODEFORGE_SERVER_PORT", config.server_port);

    config.llm.ollama_url = normalize_url(config.llm.ollama_url);
    config.llm.openai_url = normalize_url(config.llm.openai_url);
    config.llm.online.base_url = normalize_url(config.llm.online.base_url);
    if (config.llm.online.base_url.empty()) {
        config.llm.online.base_url = "https://api.deepseek.com";
    }
    config.llm.online.timeout_ms =
        std::clamp(config.llm.online.timeout_ms, 5000, 600000);
    config.llm.temperature = std::clamp(config.llm.temperature, 0.0, 2.0);
    config.llm.max_tokens = std::clamp(config.llm.max_tokens, 64, 32768);
    config.llm.request_timeout_ms =
        std::clamp(config.llm.request_timeout_ms, 5000, 3600000);
    config.debug.max_iterations = std::clamp(config.debug.max_iterations, 1, 8);
    config.debug.run_timeout_ms = std::clamp(config.debug.run_timeout_ms, 1000, 600000);
    config.debug.compile_timeout_ms =
        std::clamp(config.debug.compile_timeout_ms, 5000, 600000);
    config.server_port = std::clamp(config.server_port, 1024, 65535);
    return config;
}

LlmConfig with_online_backend(const LlmConfig& config) {
    LlmConfig online = config;
    online.backend = "openai";
    online.openai_url = config.online.base_url;
    online.api_key = config.online.api_key;
    online.model = config.online.model;
    online.request_timeout_ms = config.online.timeout_ms;
    return online;
}

}  // namespace cf
