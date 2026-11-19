#include <algorithm>
#include <cstdio>
#include <exception>
#include <filesystem>
#include <string>
#include <vector>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#include <windows.h>
#include <shellapi.h>
#else
#include <unistd.h>
#if defined(__APPLE__)
#include <mach-o/dyld.h>
#endif
#endif

#include "config.hpp"
#include "debugger.hpp"
#include "json.hpp"
#include "llm.hpp"
#include "prompts.hpp"
#include "runners.hpp"
#include "util.hpp"

namespace {

const char* kVersion = "1.0.8";

std::string arg_utf8(wchar_t* value) {
    if (value == nullptr) {
        return {};
    }
    return cf::utf8_from_wide(value);
}

std::filesystem::path executable_path() {
#ifdef _WIN32
    wchar_t buffer[32768];
    DWORD length = GetModuleFileNameW(nullptr, buffer,
                                      (DWORD)(sizeof(buffer) / sizeof(buffer[0])));
    if (length == 0) {
        return std::filesystem::current_path();
    }
    return std::filesystem::path(std::wstring(buffer, length));
#else
    std::error_code ec;
#if defined(__linux__)
    char buffer[4096];
    ssize_t got = readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
    if (got > 0) {
        buffer[got] = '\0';
        return std::filesystem::path(buffer);
    }
#elif defined(__APPLE__)
    char buffer[4096];
    std::uint32_t size = (std::uint32_t)sizeof(buffer);
    if (_NSGetExecutablePath(buffer, &size) == 0) {
        auto resolved = std::filesystem::weakly_canonical(buffer, ec);
        if (!ec) {
            return resolved;
        }
    }
#endif
    return std::filesystem::current_path(ec);
#endif
}

std::filesystem::path infer_root(const std::filesystem::path& exe) {
    std::filesystem::path dir = exe.parent_path();
    for (int i = 0; i < 6; ++i) {
        std::error_code ec;
        if (std::filesystem::exists(dir / "config.json", ec) &&
            std::filesystem::exists(dir / "server-java", ec)) {
            return dir;
        }
        if (!dir.has_parent_path()) {
            break;
        }
        dir = dir.parent_path();
    }
    return exe.parent_path().parent_path().parent_path();
}

std::string read_stdin_all() {
    std::string data;
    char buffer[8192];
    size_t got;
    while ((got = std::fread(buffer, 1, sizeof(buffer), stdin)) > 0) {
        data.append(buffer, got);
        if (data.size() > 8u * 1024u * 1024u) {
            break;
        }
    }
    return data;
}

void emit(const Json& value) {
    std::string output = value.dump();
    output.push_back('\n');
    std::fwrite(output.data(), 1, output.size(), stdout);
    std::fflush(stdout);
}

void fail(const std::string& message) {
    Json result = Json::make_object();
    result["ok"] = false;
    result["error"] = message;
    emit(result);
}

Json tool_json(const cf::ToolInfo& tool) {
    Json value = Json::make_object();
    value["name"] = tool.name;
    value["path"] = tool.path;
    value["version"] = tool.version;
    value["available"] = tool.available;
    return value;
}

Json make_status(const cf::AppConfig& config,
                 const cf::Toolchains& tools,
                 const std::filesystem::path& root) {
    Json status = Json::make_object();
    status["ok"] = true;
    status["component"] = "main-core-cpp";
    status["version"] = kVersion;
    status["root"] = cf::path_utf8(root);
    if (!config.config_error.empty()) {
        status["config_warning"] = config.config_error;
    }

    cf::BackendInfo backend = cf::detect_backend(config.llm);
    Json llm = Json::make_object();
    llm["available"] = backend.available;
    llm["kind"] = backend.kind;
    llm["base_url"] = backend.base_url;
    llm["model"] = backend.model;
    llm["error"] = backend.error;
    Json models = Json::make_array();
    for (const std::string& name : backend.models) {
        models.arr.push_back(Json(name));
    }
    llm["models"] = models;
    status["llm"] = llm;

    Json toolchains = Json::make_object();
    toolchains["python"] = tool_json(tools.python);
    toolchains["gcc"] = tool_json(tools.gcc);
    toolchains["gxx"] = tool_json(tools.gxx);
    toolchains["javac"] = tool_json(tools.javac);
    toolchains["java"] = tool_json(tools.java);
    toolchains["jdk_source"] = tools.jdk_source;
    status["toolchains"] = toolchains;

    Json debug = Json::make_object();
    debug["max_iterations"] = config.debug.max_iterations;
    debug["run_timeout_ms"] = config.debug.run_timeout_ms;
    debug["compile_timeout_ms"] = config.debug.compile_timeout_ms;
    status["debug"] = debug;
    return status;
}

Json parse_request() {
    std::string input = read_stdin_all();
    if (cf::trim(input).empty()) {
        return Json();
    }
    std::string error;
    Json request = Json::parse(input, &error);
    if (!error.empty()) {
        return Json();
    }
    return request;
}

bool valid_messages(const Json& messages, std::string& error) {
    if (!messages.is_array() || messages.arr.empty()) {
        error = "messages must be a non-empty array";
        return false;
    }
    for (const Json& message : messages.arr) {
        if (!message.is_object() || !message.find("role") || !message.find("content") ||
            !message.find("role")->is_string() || !message.find("content")->is_string()) {
            error = "each message must contain string fields role and content";
            return false;
        }
    }
    return true;
}

Json with_system_prompt(const Json& messages, const std::string& context) {
    Json decorated = Json::make_array();
    Json system = Json::make_object();
    system["role"] = "system";
    std::string content = cf::chat_system_prompt();
    if (!context.empty()) {
        content += "\n\n" + context;
    }
    system["content"] = content;
    decorated.arr.push_back(system);
    size_t start = messages.arr.size() > 40 ? messages.arr.size() - 40 : 0;
    for (size_t i = start; i < messages.arr.size(); ++i) {
        decorated.arr.push_back(messages.arr[i]);
    }
    return decorated;
}

int command_status(const std::filesystem::path& root) {
    cf::AppConfig config = cf::load_config(root);
    cf::Toolchains tools = cf::detect_toolchains(root);
    emit(make_status(config, tools, root));
    return 0;
}

int command_chat(const std::filesystem::path& root) {
    Json request = parse_request();
    if (!request.is_object()) {
        fail("Request JSON could not be parsed");
        return 0;
    }
    const Json* messages = request.find("messages");
    std::string validation;
    if (messages == nullptr || !valid_messages(*messages, validation)) {
        fail(validation.empty() ? "Missing messages" : validation);
        return 0;
    }
    cf::AppConfig config = cf::load_config(root);
    std::string route = request.get_string("route", "local");
    std::string route_error;
    bool use_online = false;
    cf::LlmConfig active_config = config.llm;
    cf::BackendInfo backend;
    if (route == "online") {
        if (!config.llm.online.configured()) {
            route_error = "Online brain is not configured (set llm.online.api_key)";
        } else {
            cf::LlmConfig online = cf::with_online_backend(config.llm);
            backend = cf::detect_backend(online);
            if (backend.available) {
                use_online = true;
                active_config = online;
            } else {
                route_error = backend.error;
            }
        }
    }
    if (!use_online) {
        backend = cf::detect_backend(config.llm);
    }
    if (!backend.available) {
        fail(backend.error);
        return 0;
    }
    Json decorated = with_system_prompt(*messages, request.get_string("context"));
    std::string model = request.get_string("model");
    std::filesystem::path stream_file;
    std::string stream_arg = request.get_string("stream_log");
    if (!stream_arg.empty()) {
        stream_file = cf::path_from_utf8(stream_arg);
        if (stream_file.is_relative()) {
            stream_file = root / stream_file;
        }
    }
    cf::LlmReply reply =
        cf::llm_complete(active_config, backend, decorated, model, !use_online,
                         cf::path_utf8(stream_file));
    if (!reply.ok && use_online) {
        route_error = "Online brain failed: " + reply.error;
        use_online = false;
        backend = cf::detect_backend(config.llm);
        if (backend.available) {
            reply = cf::llm_complete(config.llm, backend, decorated, model, true,
                                     cf::path_utf8(stream_file));
        }
    }
    if (!reply.ok) {
        fail(reply.error);
        return 0;
    }
    Json result = Json::make_object();
    result["ok"] = true;
    result["reply"] = reply.content;
    result["backend"] = reply.backend;
    result["model"] = reply.model;
    result["route"] = use_online ? "online" : "local";
    if (!route_error.empty()) {
        result["route_error"] = route_error;
    }
    emit(result);
    return 0;
}

int command_debug(const std::filesystem::path& root) {
    Json request = parse_request();
    if (!request.is_object()) {
        fail("Request JSON could not be parsed");
        return 0;
    }
    std::string code = request.get_string("code");
    if (code.empty()) {
        fail("code must not be empty");
        return 0;
    }
    if (code.size() > 200u * 1024u) {
        fail("Code exceeds the 200 KB limit");
        return 0;
    }
    cf::AppConfig config = cf::load_config(root);
    cf::Toolchains tools = cf::detect_toolchains(root);
    cf::DebugOutcome outcome = cf::debug_code(
        code,
        request.get_string("filename", "main"),
        request.get_string("language", "auto"),
        request.get_int("iterations", config.debug.max_iterations),
        request.get_string("model"),
        root, config, tools, request.get_string("context"),
        request.get_string("route", "local"));

    Json result = Json::make_object();
    result["ok"] = outcome.ok;
    result["success"] = outcome.success;
    result["error"] = outcome.error;
    result["final_code"] = outcome.final_code;
    result["session_dir"] = outcome.session_dir;
    result["backend"] = outcome.backend;
    result["model"] = outcome.model;
    result["route"] = outcome.route;
    if (!outcome.route_error.empty()) {
        result["route_error"] = outcome.route_error;
    }
    Json iterations = Json::make_array();
    for (const Json& item : outcome.iterations) {
        iterations.arr.push_back(item);
    }
    result["iterations"] = iterations;
    emit(result);
    return 0;
}

int command_run(const std::filesystem::path& root) {
    Json request = parse_request();
    if (!request.is_object()) {
        fail("Request JSON could not be parsed");
        return 0;
    }
    std::string code = request.get_string("code");
    if (code.empty()) {
        fail("code must not be empty");
        return 0;
    }
    cf::AppConfig config = cf::load_config(root);
    cf::Toolchains tools = cf::detect_toolchains(root);
    std::filesystem::path session =
        root / "workspace" / ("run-" + cf::now_stamp() + "-" + cf::unique_token());
    cf::CodeRunResult run = cf::run_code(
        code,
        request.get_string("filename", "main"),
        request.get_string("language", "auto"),
        session, tools, config.debug, root);

    Json result = Json::make_object();
    result["ok"] = run.ok;
    result["success"] = run.ok;
    result["phase"] = run.phase;
    result["language"] = run.language;
    result["source_file"] = run.source_file;
    result["command"] = run.command;
    result["exit_code"] = run.exit_code;
    result["timed_out"] = run.timed_out;
    result["duration_ms"] = (long long)run.duration_ms;
    result["stdout"] = run.out;
    result["stderr"] = run.err;
    result["error"] = run.error;
    result["session_dir"] = cf::path_utf8(session);
    emit(result);
    return 0;
}

int command_selftest(const std::filesystem::path& root) {
    cf::AppConfig config = cf::load_config(root);
    cf::Toolchains tools = cf::detect_toolchains(root);
    std::filesystem::path work = root / "workspace" / ("selftest-" + cf::unique_token());
    std::error_code ec;
    std::filesystem::create_directories(work, ec);

    Json checks = Json::make_object();
    bool all_ok = true;

    auto record = [&](const std::string& name, const cf::CodeRunResult& run,
                      const std::string& expected) {
        bool pass = run.ok && run.out.find(expected) != std::string::npos;
        Json check = Json::make_object();
        check["ok"] = pass;
        check["exit_code"] = run.exit_code;
        check["phase"] = run.phase;
        check["output"] = cf::runtime_output_text(run);
        check["error"] = run.error;
        checks[name] = check;
        if (!pass) {
            all_ok = false;
        }
    };

    record("python",
           cf::run_code("print('codeforge-ok')\n", "selftest.py", "python",
                        work / "python", tools, config.debug, root),
           "codeforge-ok");
    if (tools.gcc.available) {
        record("c",
               cf::run_code("#include <stdio.h>\nint main(void){puts(\"c-ok\");return 0;}\n",
                            "selftest.c", "c", work / "c", tools, config.debug, root),
               "c-ok");
    }
    if (tools.gxx.available) {
        record("cpp",
               cf::run_code("#include <iostream>\nint main(){std::cout<<\"cpp-ok\";}\n",
                            "selftest.cpp", "cpp", work / "cpp", tools, config.debug, root),
               "cpp-ok");
    }
    if (tools.javac.available && tools.java.available) {
        record("java",
               cf::run_code("public class Selftest { public static void main(String[] a) "
                            "{ System.out.print(\"java-ok\"); } }\n",
                            "Selftest.java", "java", work / "java", tools, config.debug, root),
               "java-ok");
    }

    Json result = Json::make_object();
    result["ok"] = all_ok;
    result["component"] = "main-core-cpp";
    result["version"] = kVersion;
    result["checks"] = checks;
    result["toolchains"] = make_status(config, tools, root)["toolchains"];
    emit(result);
    std::filesystem::remove_all(work, ec);
    return all_ok ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
#ifdef _WIN32
    (void)argc;
    (void)argv;
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);

    int wargc = 0;
    wchar_t** wargv = CommandLineToArgvW(GetCommandLineW(), &wargc);
    if (wargv == nullptr) {
        fail("cannot read the Windows command line");
        return 0;
    }
    std::vector<std::string> args;
    args.reserve((size_t)wargc);
    for (int i = 0; i < wargc; ++i) {
        args.push_back(arg_utf8(wargv[i]));
    }
    LocalFree(wargv);
#else
    std::vector<std::string> args;
    args.reserve((size_t)argc);
    for (int i = 0; i < argc; ++i) {
        args.push_back(argv[i] != nullptr ? argv[i] : "");
    }
#endif

    if (args.size() < 2) {
        fail("usage: CodeForgeCore <status|chat|debug|run|selftest|version> "
             "[--root <project_root>]");
        return 0;
    }

    std::string command = args[1];
    std::string root_arg;
    for (size_t i = 2; i < args.size(); ++i) {
        if (args[i] == "--root" && i + 1 < args.size()) {
            root_arg = args[i + 1];
            ++i;
        } else if (args[i].rfind("--root=", 0) == 0) {
            root_arg = args[i].substr(7);
        }
    }

    std::filesystem::path root;
    if (!root_arg.empty()) {
        root = cf::path_from_utf8(root_arg);
    } else {
        root = infer_root(executable_path());
    }
    std::error_code ec;
    root = std::filesystem::weakly_canonical(root, ec);
    if (ec) {
        root = std::filesystem::absolute(root);
    }

    try {
        if (command == "version") {
            Json result = Json::make_object();
            result["ok"] = true;
            result["component"] = "main-core-cpp";
            result["version"] = kVersion;
            result["root"] = cf::path_utf8(root);
            emit(result);
            return 0;
        }
        if (command == "status") return command_status(root);
        if (command == "chat") return command_chat(root);
        if (command == "debug") return command_debug(root);
        if (command == "run") return command_run(root);
        if (command == "selftest") return command_selftest(root);
        fail("unknown command: " + command);
        return 0;
    } catch (const std::exception& ex) {
        fail(std::string("internal error: ") + ex.what());
        return 1;
    }
}
