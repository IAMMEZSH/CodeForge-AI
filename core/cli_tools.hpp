#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "json.hpp"

namespace cf {

// Each function returns the JSON envelope that main.cpp prints on stdout.
Json cli_download(const std::filesystem::path& root,
                  const std::filesystem::path& exe_path,
                  const std::vector<std::string>& args);
Json cli_install(const std::filesystem::path& root,
                 const std::filesystem::path& exe_path,
                 const std::vector<std::string>& args);
Json cli_path(const std::filesystem::path& root,
              const std::filesystem::path& exe_path,
              const std::vector<std::string>& args);

}  // namespace cf