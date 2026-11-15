#pragma once

#include <string>
#include <vector>

#include "config.hpp"
#include "json.hpp"

namespace cf {

struct BackendInfo {
    bool available = false;
    std::string kind;
    std::string base_url;
    std::string model;
    std::string error;
    std::vector<std::string> models;
};

struct LlmReply {
    bool ok = false;
    std::string content;
    std::string error;
    std::string backend;
    std::string model;
};

BackendInfo detect_backend(const LlmConfig& config);
LlmReply llm_complete(const LlmConfig& config,
                      const BackendInfo& backend,
                      const Json& messages,
                      const std::string& model_override = "");

}  // namespace cf
