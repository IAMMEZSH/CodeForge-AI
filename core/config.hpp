#pragma once

#include <filesystem>
#include <string>

namespace cf {

struct OnlineLlmConfig {
    bool enabled = true;
    std::string base_url = "https://api.deepseek.com";
    std::string api_key;
    std::string model = "deepseek-chat";
    int timeout_ms = 120000;
    int max_tokens = 8192;

    bool configured() const {
        return enabled && !api_key.empty();
    }
};

struct LlmConfig {
    std::string backend = "auto";
    std::string ollama_url = "http://127.0.0.1:11434";
    std::string openai_url = "http://127.0.0.1:8080";
    std::string api_key;
    std::string model;
    double temperature = 0.2;
    int max_tokens = 393216;
    int request_timeout_ms = 600000;
    OnlineLlmConfig online;
};

struct DebugSettings {
    int max_iterations = 3;
    int run_timeout_ms = 20000;
    int compile_timeout_ms = 60000;
};

struct AppConfig {
    LlmConfig llm;
    DebugSettings debug;
    int server_port = 8765;
    std::string config_error;
};

AppConfig load_config(const std::filesystem::path& root);
LlmConfig with_online_backend(const LlmConfig& config);

}  // namespace cf
