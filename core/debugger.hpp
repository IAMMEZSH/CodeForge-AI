#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "config.hpp"
#include "json.hpp"
#include "runners.hpp"

namespace cf {

struct DebugOutcome {
    bool ok = false;
    bool success = false;
    std::string error;
    std::string final_code;
    std::string session_dir;
    std::string backend;
    std::string model;
    std::string route;
    std::string route_error;
    std::vector<Json> iterations;
};

DebugOutcome debug_code(const std::string& code,
                        const std::string& filename,
                        const std::string& language,
                        int max_iterations,
                        const std::string& model_override,
                        const std::filesystem::path& root,
                        const AppConfig& config,
                        const Toolchains& tools,
                        const std::string& context = "",
                        const std::string& route = "local");

std::string extract_fenced_code(const std::string& response,
                                const std::string& language);

}  // namespace cf
