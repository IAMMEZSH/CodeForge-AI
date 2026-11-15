#pragma once

#include <filesystem>
#include <string>

#include "config.hpp"

namespace cf {

struct ToolInfo {
    std::string name;
    std::string path;
    std::string version;
    bool available = false;
};

struct Toolchains {
    ToolInfo python;
    ToolInfo gcc;
    ToolInfo gxx;
    ToolInfo javac;
    ToolInfo java;
    std::string jdk_source;
};

struct CodeRunResult {
    bool ok = false;
    bool timed_out = false;
    int exit_code = -1;
    long long duration_ms = 0;
    std::string phase;
    std::string language;
    std::string source_file;
    std::string command;
    std::string out;
    std::string err;
    std::string error;
};

Toolchains detect_toolchains(const std::filesystem::path& root);
std::string detect_language_from_filename(const std::string& filename);

CodeRunResult run_code(const std::string& code,
                       const std::string& filename,
                       const std::string& language,
                       const std::filesystem::path& workdir,
                       const Toolchains& tools,
                       const DebugSettings& settings,
                       const std::filesystem::path& root);

std::string runtime_output_text(const CodeRunResult& result);

}  // namespace cf
