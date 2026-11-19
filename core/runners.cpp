#include "runners.hpp"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <unistd.h>
#endif

#include <algorithm>
#include <cctype>
#include <regex>
#include <sstream>

#include "process.hpp"
#include "util.hpp"

namespace cf {

#ifdef _WIN32
static constexpr const char* kJavacName = "javac.exe";
static constexpr const char* kJavaName = "java.exe";
static constexpr const char* kProgramName = "program.exe";
#else
static constexpr const char* kJavacName = "javac";
static constexpr const char* kJavaName = "java";
static constexpr const char* kProgramName = "program";
#endif

static std::string find_on_path(const wchar_t* name) {
#ifdef _WIN32
    wchar_t buffer[32768];
    DWORD length = SearchPathW(nullptr, name, nullptr,
                               (DWORD)(sizeof(buffer) / sizeof(buffer[0])), buffer, nullptr);
    if (length == 0 || length >= sizeof(buffer) / sizeof(buffer[0])) {
        return {};
    }
    return utf8_from_wide(buffer);
#else
    std::string target = utf8_from_wide(name);
    if (ends_with_ci(target, ".exe")) {
        target.resize(target.size() - 4);
    }
    std::stringstream search(get_env_utf8("PATH"));
    std::string dir;
    while (std::getline(search, dir, ':')) {
        if (dir.empty()) {
            continue;
        }
        std::filesystem::path candidate = std::filesystem::path(dir) / target;
        if (access(candidate.c_str(), X_OK) == 0) {
            return candidate.string();
        }
    }
    return {};
#endif
}

static bool path_exists(const std::filesystem::path& path) {
    std::error_code ec;
    return std::filesystem::exists(path, ec);
}

static std::string probe_version(const std::string& path,
                                 const std::string& arg,
                                 const std::filesystem::path& root) {
    if (path.empty()) {
        return {};
    }
    ProcessResult result = run_process({path, arg}, root, 8000, 8192);
    std::string text = result.out + result.err;
    std::istringstream stream(text);
    std::string line;
    while (std::getline(stream, line)) {
        line = trim(line);
        if (!line.empty()) {
            return line;
        }
    }
    return {};
}

static void locate_jdk(const std::filesystem::path& root,
                       std::string& javac_path,
                       std::string& java_path,
                       std::string& source) {
    std::filesystem::path jdk_root = root / "native" / "jdk";
    std::error_code ec;
    std::vector<std::filesystem::path> candidates;
    candidates.push_back(jdk_root);
    if (std::filesystem::is_directory(jdk_root, ec)) {
        for (const auto& entry : std::filesystem::directory_iterator(jdk_root, ec)) {
            if (entry.is_directory()) {
                candidates.push_back(entry.path());
                std::error_code inner_ec;
                for (const auto& child : std::filesystem::directory_iterator(entry.path(), inner_ec)) {
                    if (child.is_directory()) {
                        candidates.push_back(child.path());
                    }
                }
            }
        }
    }
    std::string java_home = get_env_utf8("JAVA_HOME");
    if (!java_home.empty()) {
        candidates.push_back(path_from_utf8(java_home));
    }
    for (const auto& base : candidates) {
        std::filesystem::path javac = base / "bin" / kJavacName;
        std::filesystem::path java = base / "bin" / kJavaName;
        if (path_exists(javac) && path_exists(java)) {
            javac_path = path_utf8(javac);
            java_path = path_utf8(java);
            source = path_utf8(base);
            return;
        }
    }

    std::string path_javac = find_on_path(L"javac.exe");
    if (!path_javac.empty()) {
        javac_path = path_javac;
        std::filesystem::path java = std::filesystem::path(path_from_utf8(path_javac))
                                         .parent_path() / kJavaName;
        if (path_exists(java)) {
            java_path = path_utf8(java);
        } else {
            java_path = find_on_path(L"java.exe");
        }
        source = "PATH";
    }
}

static std::string find_python() {
    const wchar_t* names[] = {L"python.exe", L"python3.exe", L"py.exe"};
    for (const wchar_t* name : names) {
        std::string path = find_on_path(name);
        if (!path.empty()) {
            return path;
        }
    }
#ifdef _WIN32
    std::string local_app_data = get_env_utf8("LOCALAPPDATA");
    if (!local_app_data.empty()) {
        std::filesystem::path alias =
            path_from_utf8(local_app_data) / "Microsoft" / "WindowsApps" / "python.exe";
        if (path_exists(alias)) {
            return path_utf8(alias);
        }
    }
#endif
    return {};
}

Toolchains detect_toolchains(const std::filesystem::path& root) {
    Toolchains tools;
    tools.python.path = find_python();
    tools.python.available = !tools.python.path.empty();
    tools.python.name = "Python";
    tools.python.version = probe_version(tools.python.path, "--version", root);

    tools.gcc.path = find_on_path(L"gcc.exe");
    if (tools.gcc.path.empty()) {
        tools.gcc.path = find_on_path(L"clang.exe");
    }
    tools.gcc.available = !tools.gcc.path.empty();
    tools.gcc.name = "C compiler";
    tools.gcc.version = probe_version(tools.gcc.path, "--version", root);

    tools.gxx.path = find_on_path(L"g++.exe");
    if (tools.gxx.path.empty()) {
        tools.gxx.path = find_on_path(L"clang++.exe");
    }
    tools.gxx.available = !tools.gxx.path.empty();
    tools.gxx.name = "C++ compiler";
    tools.gxx.version = probe_version(tools.gxx.path, "--version", root);

    std::string javac_path;
    std::string java_path;
    locate_jdk(root, javac_path, java_path, tools.jdk_source);
    tools.javac.path = javac_path;
    tools.javac.available = !javac_path.empty();
    tools.javac.name = "javac";
    tools.javac.version = probe_version(javac_path, "-version", root);
    tools.java.path = java_path;
    tools.java.available = !java_path.empty();
    tools.java.name = "java";
    tools.java.version = probe_version(java_path, "-version", root);
    return tools;
}

std::string detect_language_from_filename(const std::string& filename) {
    std::string lower = to_lower_ascii(filename);
    if (lower.size() >= 3 && lower.substr(lower.size() - 3) == ".py") return "python";
    if (lower.size() >= 4 && lower.substr(lower.size() - 4) == ".java") return "java";
    if (lower.size() >= 2 && lower.substr(lower.size() - 2) == ".c") return "c";
    if (lower.size() >= 4 &&
        (lower.substr(lower.size() - 4) == ".cpp" ||
         lower.substr(lower.size() - 4) == ".cxx")) {
        return "cpp";
    }
    if (lower.size() >= 3 && lower.substr(lower.size() - 3) == ".cc") return "cpp";
    return {};
}

static std::string normalize_language(const std::string& requested,
                                      const std::string& filename) {
    std::string language = to_lower_ascii(trim(requested));
    if (language.empty() || language == "auto") {
        language = detect_language_from_filename(filename);
    }
    if (language == "py" || language == "python3") language = "python";
    if (language == "c++" || language == "cc" || language == "cxx") language = "cpp";
    return language;
}

static std::string extension_for_language(const std::string& language) {
    if (language == "python") return ".py";
    if (language == "java") return ".java";
    if (language == "c") return ".c";
    if (language == "cpp") return ".cpp";
    return ".txt";
}

static std::string display_language(const std::string& language) {
    if (language == "python") return "Python";
    if (language == "java") return "Java";
    if (language == "c") return "C";
    if (language == "cpp") return "C++";
    return language;
}

static std::string make_source_name(const std::string& filename,
                                    const std::string& language) {
    std::string base = basename_sanitize(filename);
    if (base.empty()) {
        base = (language == "java") ? "Main" : "main";
    }
    std::string wanted_ext = extension_for_language(language);
    if (!wanted_ext.empty()) {
        if (!ends_with_ci(base, wanted_ext)) {
            size_t dot = base.find_last_of('.');
            if (dot != std::string::npos) {
                base = base.substr(0, dot);
            }
            base += wanted_ext;
        }
    }
    return base;
}

static std::string java_public_type(const std::string& code) {
    static const std::regex public_type(
        R"(\bpublic\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+)?(?:class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*))");
    static const std::regex any_type(
        R"(\b(?:class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*))");
    std::smatch match;
    if (std::regex_search(code, match, public_type)) {
        return match[1].str();
    }
    if (std::regex_search(code, match, any_type)) {
        return match[1].str();
    }
    return {};
}

static std::string java_package(const std::string& code) {
    static const std::regex package_re(
        R"((?:^|\n)\s*package\s+([A-Za-z_][A-Za-z0-9_\.]*)\s*;)");
    std::smatch match;
    if (std::regex_search(code, match, package_re)) {
        return match[1].str();
    }
    return {};
}

std::string runtime_output_text(const CodeRunResult& result) {
    std::string text;
    if (!result.error.empty()) {
        text += "engine: " + result.error + "\n";
    }
    if (!result.out.empty()) {
        text += "stdout:\n" + result.out;
        if (text.back() != '\n') text += "\n";
    }
    if (!result.err.empty()) {
        text += "stderr:\n" + result.err;
        if (text.back() != '\n') text += "\n";
    }
    if (text.empty()) {
        text = "(no output)";
    }
    return truncate_text(text, 8192);
}

CodeRunResult run_code(const std::string& code,
                       const std::string& filename,
                       const std::string& language,
                       const std::filesystem::path& workdir,
                       const Toolchains& tools,
                       const DebugSettings& settings,
                       const std::filesystem::path& root) {
    (void)root;
    CodeRunResult result;
    std::string lang = normalize_language(language, filename);
    result.language = display_language(lang);
    if (lang != "python" && lang != "java" && lang != "c" && lang != "cpp") {
        result.phase = "setup";
        result.error = "Unsupported language. Use Python, C, C++ or Java";
        return result;
    }
    std::error_code ec;
    std::filesystem::create_directories(workdir, ec);
    if (!std::filesystem::is_directory(workdir, ec)) {
        result.phase = "setup";
        result.error = "Cannot create the working directory";
        return result;
    }

    std::string source_name = make_source_name(filename, lang);
    if (lang == "java") {
        std::string class_name = java_public_type(code);
        if (class_name.empty()) {
            class_name = source_name.substr(0, source_name.size() - 5);
            std::string cleaned;
            for (unsigned char c : class_name) {
                if (std::isalnum(c) || c == '_') cleaned.push_back((char)c);
            }
            if (cleaned.empty() || std::isdigit((unsigned char)cleaned[0])) {
                cleaned = "Main";
            }
            class_name = cleaned;
        }
        source_name = class_name + ".java";
    }

    std::filesystem::path source = workdir / path_from_utf8(source_name);
    if (!write_file_utf8(source, code)) {
        result.phase = "setup";
        result.error = "Cannot write the source file";
        return result;
    }
    result.source_file = source_name;

    if (lang == "python") {
        if (!tools.python.available) {
            result.phase = "setup";
            result.error = "Python interpreter not found. Install Python and add it to PATH";
            return result;
        }
        result.phase = "run";
        ProcessResult process = run_process(
            {tools.python.path, "-X", "utf8", "-u", path_utf8(source)},
            workdir, settings.run_timeout_ms);
        result.command = process.command;
        result.out = process.out;
        result.err = process.err;
        result.timed_out = process.timed_out;
        result.exit_code = process.exit_code;
        result.duration_ms = process.duration_ms;
        if (!process.launched) {
            result.error = process.error;
            return result;
        }
        result.ok = !process.timed_out && process.exit_code == 0;
        if (process.timed_out) result.error = "Program timed out";
        return result;
    }

    if (lang == "java") {
        if (!tools.javac.available || !tools.java.available) {
            result.phase = "setup";
            result.error = "JDK not found (javac/java). Run aux-python/bootstrap.py --all to provision JDK 17";
            return result;
        }
        std::filesystem::path classes = workdir / "classes";
        std::filesystem::remove_all(classes, ec);
        std::filesystem::create_directories(classes, ec);

        result.phase = "compile";
        ProcessResult compile = run_process(
            {tools.javac.path, "-encoding", "UTF-8", "-d", path_utf8(classes),
             path_utf8(source)},
            workdir, settings.compile_timeout_ms);
        result.command = compile.command;
        result.out = compile.out;
        result.err = compile.err;
        result.timed_out = compile.timed_out;
        result.exit_code = compile.exit_code;
        result.duration_ms = compile.duration_ms;
        if (!compile.launched) {
            result.error = compile.error;
            return result;
        }
        if (compile.timed_out) {
            result.error = "Java compilation timed out";
            return result;
        }
        if (compile.exit_code != 0) {
            result.error = "Java compilation failed";
            return result;
        }

        std::string package = java_package(code);
        std::string class_name = java_public_type(code);
        if (class_name.empty()) {
            class_name = source_name.substr(0, source_name.size() - 5);
        }
        std::string fqcn = package.empty() ? class_name : package + "." + class_name;
        result.phase = "run";
        ProcessResult run = run_process(
            {tools.java.path, "-Dfile.encoding=UTF-8", "-cp", path_utf8(classes), fqcn},
            workdir, settings.run_timeout_ms);
        result.command = run.command;
        result.out = run.out;
        result.err = run.err;
        result.timed_out = run.timed_out;
        result.exit_code = run.exit_code;
        result.duration_ms = run.duration_ms;
        if (!run.launched) {
            result.error = run.error;
            return result;
        }
        result.ok = !run.timed_out && run.exit_code == 0;
        if (run.timed_out) result.error = "Program timed out";
        return result;
    }

    const ToolInfo& compiler = (lang == "c") ? tools.gcc : tools.gxx;
    if (!compiler.available) {
        result.phase = "setup";
        result.error = (lang == "c")
                           ? "C compiler not found (gcc/clang). Install MinGW or LLVM"
                           : "C++ compiler not found (g++/clang++). Install MinGW or LLVM";
        return result;
    }
    std::filesystem::path exe = workdir / kProgramName;
    std::filesystem::remove(exe, ec);
    result.phase = "compile";
    std::vector<std::string> compile_cmd = {
        compiler.path,
        "-O2",
        lang == "c" ? "-std=c17" : "-std=c++17",
        "-Wall",
        "-Wextra",
        path_utf8(source),
        "-o",
        path_utf8(exe)};
    if (lang == "c") {
        compile_cmd.push_back("-lm");
    }
    ProcessResult compile = run_process(compile_cmd, workdir, settings.compile_timeout_ms);
    result.command = compile.command;
    result.out = compile.out;
    result.err = compile.err;
    result.timed_out = compile.timed_out;
    result.exit_code = compile.exit_code;
    result.duration_ms = compile.duration_ms;
    if (!compile.launched) {
        result.error = compile.error;
        return result;
    }
    if (compile.timed_out) {
        result.error = "Compilation timed out";
        return result;
    }
    if (compile.exit_code != 0) {
        result.error = "Compilation failed";
        return result;
    }

    result.phase = "run";
    ProcessResult run = run_process({path_utf8(exe)}, workdir, settings.run_timeout_ms);
    result.command = run.command;
    result.out = run.out;
    result.err = run.err;
    result.timed_out = run.timed_out;
    result.exit_code = run.exit_code;
    result.duration_ms = run.duration_ms;
    if (!run.launched) {
        result.error = run.error;
        return result;
    }
    result.ok = !run.timed_out && run.exit_code == 0;
    if (run.timed_out) result.error = "Program timed out";
    return result;
}

}  // namespace cf
