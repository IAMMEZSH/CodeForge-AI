#include "debugger.hpp"

#include <algorithm>
#include <cctype>

#include "llm.hpp"
#include "prompts.hpp"
#include "util.hpp"

namespace cf {

static std::string resolve_language(const std::string& requested,
                                    const std::string& filename) {
    std::string language = to_lower_ascii(trim(requested));
    if (language.empty() || language == "auto") {
        language = detect_language_from_filename(filename);
    }
    if (language == "py" || language == "python3") language = "python";
    if (language == "c++" || language == "cc" || language == "cxx") language = "cpp";
    if (language != "python" && language != "java" && language != "c" &&
        language != "cpp") {
        return {};
    }
    return language;
}

static std::string extension_for(const std::string& language) {
    if (language == "python") return ".py";
    if (language == "java") return ".java";
    if (language == "c") return ".c";
    return ".cpp";
}

static std::string ensure_filename(const std::string& filename,
                                   const std::string& language) {
    std::string name = basename_sanitize(filename);
    if (name.empty()) {
        name = language == "java" ? "Main" : "main";
    }
    std::string ext = extension_for(language);
    if (!ends_with_ci(name, ext)) {
        size_t dot = name.find_last_of('.');
        if (dot != std::string::npos) {
            name = name.substr(0, dot);
        }
        name += ext;
    }
    return name;
}

static std::string aliases_for(const std::string& language) {
    if (language == "python") return "python py python3";
    if (language == "java") return "java";
    if (language == "c") return "c";
    if (language == "cpp") return "cpp c++ cc cxx";
    return {};
}

static bool info_matches_language(const std::string& info,
                                  const std::string& language) {
    std::string lower = to_lower_ascii(trim(info));
    if (lower.empty()) {
        return true;
    }
    std::string aliases = aliases_for(language);
    std::string token;
    for (char c : aliases + " ") {
        if (c == ' ') {
            if (!token.empty() && token == lower) {
                return true;
            }
            token.clear();
        } else {
            token.push_back(c);
        }
    }
    return false;
}

std::string extract_fenced_code(const std::string& response,
                                const std::string& language) {
    struct Block {
        std::string info;
        std::string code;
    };
    std::vector<Block> blocks;
    size_t cursor = 0;
    while (true) {
        size_t start = response.find("```", cursor);
        if (start == std::string::npos) {
            break;
        }
        size_t info_start = start + 3;
        size_t line_end = response.find('\n', info_start);
        if (line_end == std::string::npos) {
            break;
        }
        std::string info = trim(response.substr(info_start, line_end - info_start));
        size_t content_start = line_end + 1;
        size_t end = response.find("```", content_start);
        if (end == std::string::npos) {
            break;
        }
        blocks.push_back({info, response.substr(content_start, end - content_start)});
        cursor = end + 3;
    }
    if (blocks.empty()) {
        return {};
    }

    const Block* best_match = nullptr;
    const Block* best_plain = nullptr;
    for (const Block& block : blocks) {
        std::string lower_info = to_lower_ascii(trim(block.info));
        if (lower_info == "diff" || lower_info == "patch") {
            continue;
        }
        if (!trim(block.info).empty() && info_matches_language(block.info, language)) {
            if (best_match == nullptr || block.code.size() > best_match->code.size()) {
                best_match = &block;
            }
        }
        if (trim(block.info).empty()) {
            if (best_plain == nullptr || block.code.size() > best_plain->code.size()) {
                best_plain = &block;
            }
        }
    }
    const Block* chosen = best_match;
    if (chosen == nullptr) chosen = best_plain;
    if (chosen == nullptr) {
        for (const Block& block : blocks) {
            std::string lower_info = to_lower_ascii(trim(block.info));
            if (lower_info == "diff" || lower_info == "patch") {
                continue;
            }
            if (chosen == nullptr || block.code.size() > chosen->code.size()) {
                chosen = &block;
            }
        }
    }
    if (chosen == nullptr) {
        return {};
    }
    std::string code = chosen->code;
    if (!code.empty() && code.back() != '\n') {
        code.push_back('\n');
    }
    return code;
}

static Json iteration_record(int index, const CodeRunResult& result) {
    Json record = Json::make_object();
    record["index"] = index;
    record["phase"] = result.phase;
    record["ok"] = result.ok;
    record["timed_out"] = result.timed_out;
    record["exit_code"] = result.exit_code;
    record["duration_ms"] = (long long)result.duration_ms;
    record["command"] = result.command;
    record["source_file"] = result.source_file;
    record["output"] = runtime_output_text(result);
    return record;
}

static void persist_report(const std::filesystem::path& session,
                           const DebugOutcome& outcome) {
    Json report = Json::make_object();
    report["success"] = outcome.success;
    report["error"] = outcome.error;
    report["backend"] = outcome.backend;
    report["model"] = outcome.model;
    Json iterations = Json::make_array();
    for (const Json& item : outcome.iterations) {
        iterations.arr.push_back(item);
    }
    report["iterations"] = iterations;
    report["final_code"] = outcome.final_code;
    write_file_utf8(session / "report.json", report.dump());
}

DebugOutcome debug_code(const std::string& code,
                        const std::string& filename,
                        const std::string& language,
                        int max_iterations,
                        const std::string& model_override,
                        const std::filesystem::path& root,
                        const AppConfig& config,
                        const Toolchains& tools,
                        const std::string& context,
                        const std::string& route) {
    DebugOutcome outcome;
    std::string lang = resolve_language(language, filename);
    if (lang.empty()) {
        outcome.error = "Could not detect the language. Specify Python, C, C++ or Java";
        return outcome;
    }
    if (trim(code).empty()) {
        outcome.error = "Code is empty";
        return outcome;
    }
    int limit = max_iterations > 0 ? max_iterations : config.debug.max_iterations;
    limit = std::clamp(limit, 1, 8);

    std::filesystem::path session =
        root / "workspace" / ("debug-" + now_stamp() + "-" + unique_token());
    std::error_code ec;
    std::filesystem::create_directories(session, ec);
    if (!std::filesystem::is_directory(session, ec)) {
        outcome.error = "Cannot create the debug workspace";
        return outcome;
    }
    outcome.session_dir = path_utf8(session);
    std::string source_name = ensure_filename(filename, lang);
    std::string current = code;
    std::string display_lang;
    display_lang = (lang == "python") ? "Python" : (lang == "java") ? "Java"
                 : (lang == "c") ? "C" : "C++";

    LlmConfig active_config = config.llm;
    BackendInfo backend;
    bool use_online = false;
    std::string route_error;
    if (route == "online") {
        if (!config.llm.online.configured()) {
            route_error = "Online brain is not configured (set llm.online.api_key)";
        } else {
            LlmConfig online = with_online_backend(config.llm);
            BackendInfo probe = detect_backend(online);
            if (probe.available) {
                backend = probe;
                active_config = online;
                use_online = true;
            } else {
                route_error = probe.error;
            }
        }
    }
    if (!use_online) {
        backend = detect_backend(config.llm);
    }
    outcome.route = use_online ? "online" : "local";
    outcome.route_error = route_error;
    if (!backend.available) {
        CodeRunResult check =
            run_code(current, source_name, lang, session, tools, config.debug, root);
        outcome.iterations.push_back(iteration_record(1, check));
        outcome.final_code = current;
        outcome.error = "AI debugging is unavailable:\n" + backend.error;
        write_file_utf8(session / ("final" + extension_for(lang)), current);
        persist_report(session, outcome);
        return outcome;
    }

    outcome.backend = backend.kind;
    outcome.model = model_override.empty() ? backend.model : model_override;

    for (int iteration = 1; iteration <= limit; ++iteration) {
        CodeRunResult result =
            run_code(current, source_name, lang, session, tools, config.debug, root);
        outcome.iterations.push_back(iteration_record(iteration, result));

        if (result.ok) {
            outcome.ok = true;
            outcome.success = true;
            outcome.final_code = current;
            write_file_utf8(session / ("final" + extension_for(lang)), current);
            persist_report(session, outcome);
            return outcome;
        }

        if (result.phase == "setup") {
            outcome.error = "Incomplete runtime environment: " + result.error;
            outcome.final_code = current;
            write_file_utf8(session / ("final" + extension_for(lang)), current);
            persist_report(session, outcome);
            return outcome;
        }

        if (iteration == limit) {
            outcome.error = "Reached the iteration limit; the code still fails";
            break;
        }

        std::string output = runtime_output_text(result);
        std::string fence = "```" + lang + "\n" +
                            truncate_text(current, 60u * 1024u) + "\n```";
        std::string prompt =
            "Language: " + display_lang + "\n" +
            "File: " + source_name + "\n" +
            "Iteration: " + std::to_string(iteration) + "\n" +
            "Phase: " + (result.phase.empty() ? "run" : result.phase) + "\n" +
            "Command: " + result.command + "\n" +
            "Exit code: " + std::to_string(result.exit_code) +
            (result.timed_out ? " (timed out)" : "") + "\n\n" +
            "Execution output:\n" + truncate_text(output, 8u * 1024u) + "\n\n" +
            "Current file content:\n" + fence + "\n\n" +
            "Fix the failure. Return only the complete corrected source file "
            "in a single fenced code block.";

        Json messages = Json::make_array();
        Json system_message = Json::make_object();
        system_message["role"] = "system";
        system_message["content"] = context.empty()
            ? debug_system_prompt()
            : std::string(debug_system_prompt()) + "\n\n" + context;
        messages.arr.push_back(system_message);
        Json user_message = Json::make_object();
        user_message["role"] = "user";
        user_message["content"] = prompt;
        messages.arr.push_back(user_message);

        LlmReply reply = llm_complete(active_config, backend, messages, model_override);
        if (!reply.ok && use_online) {
            outcome.route_error = "Online brain failed: " + reply.error;
            use_online = false;
            BackendInfo fallback = detect_backend(config.llm);
            if (fallback.available) {
                backend = fallback;
                reply = llm_complete(config.llm, backend, messages, model_override);
            }
        }
        outcome.route = use_online ? "online" : "local";
        if (!reply.ok) {
            outcome.error = "AI repair request failed: " + reply.error;
            outcome.final_code = current;
            write_file_utf8(session / ("final" + extension_for(lang)), current);
            persist_report(session, outcome);
            return outcome;
        }
        outcome.backend = reply.backend;
        outcome.model = reply.model;

        std::string fixed = extract_fenced_code(reply.content, lang);
        if (trim(fixed).empty()) {
            outcome.error = "The model returned no usable code block";
            outcome.final_code = current;
            write_file_utf8(session / ("final" + extension_for(lang)), current);
            persist_report(session, outcome);
            return outcome;
        }
        if (trim(fixed) == trim(current)) {
            outcome.error = "The model returned the same code that already failed";
            outcome.final_code = current;
            write_file_utf8(session / ("final" + extension_for(lang)), current);
            persist_report(session, outcome);
            return outcome;
        }
        current = fixed;
    }

    outcome.final_code = current;
    write_file_utf8(session / ("final" + extension_for(lang)), current);
    persist_report(session, outcome);
    return outcome;
}

}  // namespace cf
