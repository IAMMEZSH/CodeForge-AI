#pragma once

#include <filesystem>
#include <string>
#include <vector>

namespace cf {

struct ProcessResult {
    bool launched = false;
    bool timed_out = false;
    int exit_code = -1;
    long long duration_ms = 0;
    std::string out;
    std::string err;
    std::string error;
    std::string command;
};

ProcessResult run_process(const std::vector<std::string>& argv,
                          const std::filesystem::path& cwd,
                          int timeout_ms,
                          size_t max_output_bytes = 256u * 1024u);

std::string join_command(const std::vector<std::string>& argv);

}  // namespace cf
