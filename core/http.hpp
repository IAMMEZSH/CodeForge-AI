#pragma once

#include <string>
#include <utility>
#include <vector>

namespace cf {

struct HttpResult {
    bool ok = false;
    int status = 0;
    std::string body;
    std::string error;
};

HttpResult http_request(const std::string& method,
                        const std::string& url,
                        const std::string& body,
                        const std::vector<std::pair<std::string, std::string>>& headers,
                        int timeout_ms);

}  // namespace cf
