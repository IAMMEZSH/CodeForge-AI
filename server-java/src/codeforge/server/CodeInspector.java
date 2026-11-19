package codeforge.server;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static code inspector and quarantine vault.
 *
 * Isolation contract: nothing in this class ever executes the inspected file.
 * Files are only read as bytes and copied into a read-only quarantine folder
 * when they are risky. After inspection every scanned file is re-hashed so the
 * report can prove that the source bytes were not modified.
 */
public final class CodeInspector {
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int HEAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 2_000_000;
    private static final int DEFAULT_MAX_FILES = 60;
    private static final int HARD_MAX_FILES = 500;
    private static final long INTEGRITY_REHASH_BUDGET = 256L * 1024 * 1024;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "bower_components", "vendor",
            "build", "target", "out", "bin", "obj", "dist", "coverage", ".venv",
            "venv", "env", "__pycache__", ".idea", ".vscode", ".gradle", ".dart_tool",
            "packages", "Pods", ".next", ".nuxt", ".cache", "tmp", "temp");

    private static final Map<String, Lang> EXT = new HashMap<>();
    private static final Map<String, Lang> NAMES = new HashMap<>();
    private static final Set<String> MANIFESTS = new LinkedHashSet<>(List.of(
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "requirements.txt", "pyproject.toml", "setup.py", "Pipfile",
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "Cargo.toml", "go.mod", "composer.json", "Gemfile", "CMakeLists.txt",
            "Makefile", "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
            "tsconfig.json", ".env", ".env.local", ".env.example",
            "gradle.properties", "nuget.config", "Directory.Build.props"));

    private static final Pattern CLASS_DEF = Pattern.compile(
            "\\b(?:class|interface|enum|record|struct|trait|impl|union|object|protocol)"
                    + "\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern FUNC_DEF = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:public|private|protected|static|final|abstract|"
                    + "async|synchronized|override|virtual|inline|extern|constexpr|unsafe|"
                    + "suspend|open|internal|partial|local|sub|procedure|def|fn|func|function|"
                    + "fun|proc|method|constructor|void|int|long|double|float|bool|boolean|"
                    + "string|char|byte|short|unsigned|signed|auto|struct|let|var|val|"
                    + "\\w+\\s*[<\\[][^\\n(]*[>\\]])\\s*"
                    + "([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern PY_DEF = Pattern.compile(
            "(?m)^[ \\t]*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern JS_ARROW = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)"
                    + "\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");
    private static final Pattern IMPORT_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:import|from|#include|using|use|require|require_relative|"
                    + "include|load)\\b[^\\n]{0,300}");
    private static final Pattern QUOTED = Pattern.compile(
            "['\"]([^'\"\\n]{1,200})['\"]");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "\\b(?:https?|wss?|ftp)://[^\\s\"'<>`\\)\\]},;]{4,300}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,24}\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}"
                    + "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    private static final Pattern SECRET_ASSIGN = Pattern.compile(
            "(?i)(?:api[_-]?key|apikey|secret|client[_-]?secret|access[_-]?key|"
                    + "auth[_-]?token|token|password|passwd|pwd|private[_-]?key)"
                    + "[ \\t]*[:=][ \\t]*['\"]([^'\"\\n]{6,200})['\"]");
    private static final Pattern SECRET_KNOWN = Pattern.compile(
            "(?i)(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|"
                    + "github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}|"
                    + "xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_\\-]{35}|"
                    + "eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,})");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----");
    private static final Pattern BASE64_CHUNK = Pattern.compile(
            "[A-Za-z0-9+/]{120,}={0,2}");
    private static final Pattern HEX_ESCAPE = Pattern.compile("\\\\x[0-9a-fA-F]{2}");
    private static final Pattern OBFUSCATED_JS = Pattern.compile("_0x[a-fA-F0-9]{4,}");

    private static final List<Rule> RULES = List.of(
            new Rule("dynamic-code-execution", "medium", "执行能力",
                    "动态代码执行（eval / exec / Function）",
                    "字符串可被动态执行。这本身是双用途能力，请确认输入来源可信。", 6,
                    Pattern.compile("(?i)\\b(?:eval|exec|execSync)\\s*\\(|"
                            + "\\bFunction\\s*\\(|"
                            + "(?:setTimeout|setInterval)\\s*\\(\\s*['\"`]")),
            new Rule("process-spawn", "medium", "执行能力",
                    "启动外部进程或 shell",
                    "代码可以启动外部程序。确认命令是否由不可信输入拼接。", 7,
                    Pattern.compile("(?i)os\\.system\\s*\\(|subprocess\\.(?:call|run|Popen)|"
                            + "Runtime\\.getRuntime\\(\\s*\\)\\.exec|ProcessBuilder|"
                            + "child_process\\.(?:exec|execSync|spawn|spawnSync)|"
                            + "shell_exec\\s*\\(|passthru\\s*\\(|\\bsystem\\s*\\(|"
                            + "Start-Process|Invoke-Expression|\\biex\\b")),
            new Rule("download-and-execute", "high", "执行能力",
                    "下载后直接执行（curl/wget 管道到 shell）",
                    "典型的恶意投放模式：从网络取回内容并立即交给 shell 执行。", 15,
                    Pattern.compile("(?is)\\b(?:curl|wget|iwr|Invoke-WebRequest)\\b[^\\n]{0,220}"
                            + "(?:\\||;|&&)[^\\n]{0,100}\\b(?:ba|z|k)?sh\\b|"
                            + "\\b(?:iwr|Invoke-WebRequest|curl|wget)\\b[^\\n]{0,220}"
                            + "\\|\\s*(?:iex|Invoke-Expression)\\b|"
                            + "\\b(?:DownloadString|DownloadFile|DownloadData)\\s*\\(|"
                            + "\\bIEX\\s*\\(")),
            new Rule("powershell-obfuscation", "high", "混淆",
                    "PowerShell 编码/混淆命令",
                    "使用 -enc / FromBase64String / Invoke-Expression 隐藏真实命令。", 12,
                    Pattern.compile("(?i)-enc(?:odedcommand)?\\s+[A-Za-z0-9+/=]{16,}|"
                            + "FromBase64String|Invoke-Expression|"
                            + "\\[Convert\\]::FromBase64String")),
            new Rule("persistence", "high", "持久化",
                    "开机自启 / 计划任务 / 服务持久化",
                    "写入 Run 键、计划任务、启动目录或系统服务可让程序长期驻留。", 13,
                    Pattern.compile("(?i)CurrentVersion\\\\Run(?:Once)?|"
                            + "\\\\Start Menu\\\\Programs\\\\Startup|schtasks(?:\\s|/)|"
                            + "New-ScheduledTask|crontab(?:\\s|$)|/etc/systemd/|"
                            + "LaunchAgents|LaunchDaemons|sc\\s+create\\b|"
                            + "New-Service\\b")),
            new Rule("shadow-copy-destruction", "critical", "破坏能力",
                    "删除卷影/备份或关闭恢复",
                    "删除卷影副本、备份与恢复配置是勒索软件的典型行为。", 25,
                    Pattern.compile("(?i)vssadmin[^\\n]{0,80}delete|"
                            + "wbadmin[^\\n]{0,80}delete|"
                            + "bcdedit[^\\n]{0,120}(?:recoveryenabled|bootstatuspolicy)|"
                            + "Disable-ComputerRestore|"
                            + "wmic\\s+shadowcopy\\s+delete")),
            new Rule("disk-destruction", "critical", "破坏能力",
                    "磁盘/分区格式化或原始写入",
                    "可能造成不可逆的数据破坏。", 22,
                    Pattern.compile("(?i)\\b(?:Format-Volume|diskpart\\s+clean|"
                            + "Clear-Disk|Initialize-Disk|mkfs(?:\\.\\w+)?\\s|"
                            + "dd\\s+if=/dev/(?:zero|random)|"
                            + "cipher\\s+/w)\\b")),
            new Rule("credential-theft", "critical", "凭据窃取",
                    "凭据/浏览器/系统密钥窃取迹象",
                    "出现 LSASS、SAM、mimikatz、浏览器密码库等敏感目标。", 22,
                    Pattern.compile("(?i)mimikatz|sekurlsa|lsass|\\bSAM\\\\SAM\\b|"
                            + "Login Data|logins\\.json|cookies\\.sqlite|"
                            + "Local State.*encrypted_key|"
                            + "Get-Process[^\\n]{0,60}lsass")),
            new Rule("keylogger", "high", "监控能力",
                    "键盘记录 / 全局钩子",
                    "全局键盘钩子或按键状态轮询通常是间谍软件行为。", 15,
                    Pattern.compile("(?i)GetAsyncKeyState|SetWindowsHookEx|"
                            + "WH_KEYBOARD|keylog(?:ger)?" )),
            new Rule("reverse-shell", "high", "远程控制",
                    "反弹 shell / 原始套接字控制",
                    "把命令执行通道连回远程主机。", 18,
                    Pattern.compile("(?i)\\b(?:nc|ncat|netcat)\\b[^\\n]{0,80}-e\\s+(?:/bin/)?"
                            + "(?:sh|bash|cmd)|/dev/tcp/|"
                            + "socket\\.socket[^\\n]{0,300}(?:dup2|connect)|"
                            + "New-Object\\s+System\\.Net\\.Sockets\\.TcpClient")),
            new Rule("crypto-mining", "high", "资源滥用",
                    "加密货币挖矿",
                    "矿池协议或挖矿程序特征。", 15,
                    Pattern.compile("(?i)xmrig|stratum\\+tcp|stratum\\+ssl|"
                            + "cryptonight|coinhive|minergate|randomx")),
            new Rule("ransomware-text", "medium", "破坏能力",
                    "勒索/加密提示文本",
                    "出现勒索说明或文件加密提示，需要结合其他行为判断。", 10,
                    Pattern.compile("(?i)your files (?:have been|are) encrypted|"
                            + "decrypt(?:ion)? key|bitcoin address|ransom|"
                            + "文件已被加密|支付赎金|解密密钥")),
            new Rule("defense-evasion", "high", "规避检测",
                    "关闭安全防护 / 清除日志 / 修改防火墙",
                    "尝试关闭 Defender、防火墙或清除事件日志。", 15,
                    Pattern.compile("(?i)Add-MpPreference[^\\n]{0,100}-Exclusion|"
                            + "Set-MpPreference[^\\n]{0,100}Disable|"
                            + "netsh[^\\n]{0,100}firewall[^\\n]{0,60}(?:disable|set.*off)|"
                            + "wevtutil\\s+cl|Clear-EventLog|"
                            + "Uninstall-WindowsFeature.*Defender")),
            new Rule("lolbin-abuse", "high", "规避检测",
                    "系统签名工具滥用（LOLBins）",
                    "mshta / certutil / bitsadmin / rundll32 常被用于下载或执行载荷。", 12,
                    Pattern.compile("(?i)\\bmshta(?:\\.exe)?\\b|"
                            + "certutil[^\\n]{0,100}-urlcache|"
                            + "bitsadmin[^\\n]{0,100}/transfer|"
                            + "regsvr32[^\\n]{0,100}/i:http|"
                            + "rundll32[^\\n]{0,100}(?:javascript|url\\.dll|shell32)")),
            new Rule("anti-analysis", "medium", "规避检测",
                    "反调试 / 虚拟机检测",
                    "检查调试器或虚拟化环境，可能是规避分析。", 7,
                    Pattern.compile("(?i)IsDebuggerPresent|CheckRemoteDebuggerPresent|"
                            + "ptrace\\s*\\(\\s*PTRACE_TRACEME|"
                            + "VMware|VirtualBox|Sandboxie|\\bWine\\b|"
                            + "Get-CimInstance[^\\n]{0,80}Win32_ComputerSystem")),
            new Rule("process-injection", "critical", "进程操纵",
                    "进程注入 API 组合",
                    "VirtualAllocEx + WriteProcessMemory + CreateRemoteThread 是注入典型组合。", 25,
                    Pattern.compile("(?i)VirtualAllocEx|WriteProcessMemory|"
                            + "CreateRemoteThread|NtUnmapViewOfSection|"
                            + "QueueUserAPC|SetThreadContext")),
            new Rule("unsafe-deserialization", "medium", "代码质量",
                    "不安全的反序列化",
                    "pickle / marshal / 不安全 YAML 载入可能执行任意对象。", 6,
                    Pattern.compile("(?i)pickle\\.loads?\\s*\\(|marshal\\.loads\\s*\\(|"
                            + "yaml\\.load\\s*\\([^\\n]{0,120}(?!Loader\\s*=\\s*yaml\\.SafeLoader)|"
                            + "ObjectInputStream|BinaryFormatter")),
            new Rule("sql-concatenation", "low", "代码质量",
                    "可能的 SQL 注入写法",
                    "SQL 由字符串拼接而不是参数化查询构造。", 4,
                    Pattern.compile("(?i)(?:execute|query)\\s*\\([^\\n]{0,80}\\+|"
                            + "f[\"'][^\\n]{0,80}(?:SELECT|INSERT|UPDATE|DELETE)[^\\n]{0,80}\\{")),
            new Rule("xss-sink", "low", "代码质量",
                    "可能的 XSS 输出点",
                    "把数据直接写入 HTML 而不转义。", 3,
                    Pattern.compile("(?i)innerHTML\\s*=|\\.html\\s*\\([^\\n]{0,20}\\$|"
                            + "document\\.write\\s*\\(|dangerouslySetInnerHTML")),
            new Rule("tls-disabled", "low", "代码质量",
                    "关闭 TLS 校验",
                    "忽略证书错误会削弱传输安全。", 3,
                    Pattern.compile("(?i)verify\\s*=\\s*False|rejectUnauthorized\\s*:\\s*false|"
                            + "CERT_NONE|TrustAllCerts|"
                            + "ServerCertificateValidationCallback[^\\n]{0,80}true")),
            new Rule("environment-tampering", "high", "系统修改",
                    "系统环境/启动配置篡改",
                    "修改启动策略、恢复环境或系统级设置。", 16,
                    Pattern.compile("(?i)bcdedit\\s+/set|"
                            + "Set-ExecutionPolicy\\s+(?:Bypass|Unrestricted)|"
                            + "reg\\s+add[^\\n]{0,120}(?:DisableAntiSpyware|EnableLUA)|"
                            + "Set-ItemProperty[^\\n]{0,120}Defender")),
            new Rule("mass-file-encryption", "high", "破坏能力",
                    "批量文件遍历并改写/加密",
                    "递归遍历文件系统并写入/加密文件，可能是勒索行为。", 14,
                    Pattern.compile("(?i)(?:os\\.walk|walk\\(|Get-ChildItem[^\\n]{0,60}-Recurse)"
                            + "[^\\n]{0,300}(?:rename|write|encrypt|Crypt|Move-Item|Remove-Item)")),
            new Rule("hardcoded-ip", "info", "网络",
                    "硬编码 IP 地址",
                    "代码包含直接写死的 IP，可能是固定回连地址；也可能是正常内网配置。", 0,
                    IPV4));

    private final Path root;
    private final Path reportDir;
    private final Path quarantineDir;

    public CodeInspector(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.reportDir = this.root.resolve("workspace").resolve("reports")
                .resolve("code-inspection");
        this.quarantineDir = this.root.resolve("workspace").resolve("quarantine");
    }

    public Path quarantineDir() {
        return quarantineDir;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kind", "code-inspector");
        out.put("report_dir", reportDir.toString());
        out.put("quarantine_dir", quarantineDir.toString());
        out.put("isolation", Json.map(
                "mode", "static-vault",
                "executes_samples", false,
                "guarantee", "被检测文件只按字节读取，永不在宿主上执行；可疑文件以只读副本隔离。",
                "shredding", "可选：报告写入后粉碎本次产生的隔离副本；原始文件永不改动。"));
        out.put("quarantine_entries", Json.asList(quarantineList().get("entries")).size());
        out.put("supported_languages", supportedLanguages());
        return out;
    }

    private static List<Object> supportedLanguages() {
        Set<String> names = new LinkedHashSet<>();
        for (Lang lang : EXT.values()) {
            names.add(lang.name);
        }
        List<Object> out = new ArrayList<>(names);
        out.sort(Comparator.comparing(String::valueOf));
        return out;
    }

    public Map<String, Object> inspect(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("kind", "inspect");
        String raw = Json.str(request, "path", Json.str(request, "file", "")).trim();
        if (raw.isBlank()) {
            out.put("error", "path is required");
            return out;
        }
        Path target = resolve(raw);
        out.put("path", target.toString());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            out.put("error", "path does not exist");
            return out;
        }
        boolean directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        out.put("target_type", directory ? "directory" : "file");
        int depth = Math.max(0, Math.min(6, Json.integer(request, "depth", 3)));
        int maxFiles = Math.max(1, Math.min(HARD_MAX_FILES,
                Json.integer(request, "max_files", DEFAULT_MAX_FILES)));
        boolean quarantineRisky = Json.bool(request, "quarantine", true);
        boolean shredQuarantine = Json.bool(request, "shred_quarantine",
                Json.bool(request, "shred", false));
        out.put("limits", Json.map(
                "depth", depth,
                "max_files", maxFiles,
                "max_file_mb", MAX_FILE_BYTES / (1024 * 1024),
                "quarantine_risky", quarantineRisky,
                "shred_quarantine", shredQuarantine));

        List<Path> files = new ArrayList<>();
        List<Path> manifests = new ArrayList<>();
        Map<String, Object> traversal = new LinkedHashMap<>();
        if (directory) {
            traverse(target, depth, maxFiles, files, manifests, traversal);
        } else {
            if (isManifest(target.getFileName().toString())) {
                manifests.add(target);
            } else {
                files.add(target);
            }
        }
        out.put("traversal", traversal);
        out.put("discovered", Json.map(
                "code_files", files.size(),
                "manifests", manifests.size()));

        List<Map<String, Object>> fileReports = new ArrayList<>();
        List<Map<String, Object>> quarantined = new ArrayList<>();
        for (Path file : files) {
            Map<String, Object> report = analyze(file, true, quarantineRisky);
            fileReports.add(report);
            Map<String, Object> q = Json.asObject(report.get("quarantine"));
            if (!q.isEmpty() && Json.bool(q, "copied", false)) {
                quarantined.add(q);
            }
        }
        List<Map<String, Object>> manifestReports = new ArrayList<>();
        for (Path manifest : manifests) {
            manifestReports.add(analyzeManifest(manifest));
        }
        Map<String, Object> integrity = verifyIntegrity(fileReports);

        Map<String, Object> aggregate = aggregate(fileReports);
        String reportId = LocalDateTime.now().format(STAMP) + "-"
                + Integer.toString((int) (Math.random() * 0xffff), 16);
        Map<String, Object> reportPaths = new LinkedHashMap<>();
        String jsonPath = "";
        String mdPath = "";
        Map<String, Object> shredSummary = new LinkedHashMap<>();
        shredSummary.put("requested", shredQuarantine);
        shredSummary.put("performed", false);
        shredSummary.put("files_shredded", 0);
        shredSummary.put("files_failed", 0);
        try {
            Files.createDirectories(reportDir);
            Path json = reportDir.resolve("inspection-" + reportId + ".json");
            Path md = reportDir.resolve("inspection-" + reportId + ".md");
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("ok", true);
            document.put("report_id", reportId);
            document.put("generated_at", Instant.now().toString());
            document.put("generated_local", LocalDateTime.now().format(ISO));
            document.put("target", target.toString());
            document.put("target_type", directory ? "directory" : "file");
            document.put("limits", out.get("limits"));
            document.put("traversal", traversal);
            document.put("aggregate", aggregate);
            document.put("integrity", integrity);
            document.put("files", fileReports);
            document.put("manifests", manifestReports);
            document.put("quarantine", quarantined);
            document.put("execution", Json.map(
                    "mode", "static-only",
                    "executed", false,
                    "notice", "本报告为纯静态检测：未运行任何样本。"));
            document.put("shred", shredSummary);
            document.put("limitations", List.of(
                    "静态启发式检测不能替代杀毒引擎或人工逆向，不能给出“绝对安全”的结论。",
                    "行数、复杂度、注释比例与依赖为近似统计，宏/生成代码可能偏差。",
                    "密钥检测只报告位置与掩码，报告中绝不写入完整密钥。",
                    "任何被检测文件都未在宿主上执行，也没有被修改；integrity 字段记录扫描前后哈希校验。",
                    "粉碎只针对本次扫描产生的隔离副本，原始文件从不删除；SSD 上的覆盖写不保证物理不可恢复。"));
            Files.writeString(json, Json.write(document), StandardCharsets.UTF_8);
            Files.writeString(md, buildMarkdown(document, fileReports, manifestReports,
                    quarantined, aggregate), StandardCharsets.UTF_8);
            jsonPath = json.toString();
            mdPath = md.toString();
            reportPaths.put("json", jsonPath);
            reportPaths.put("markdown", mdPath);
            reportPaths.put("json_bytes", Files.size(json));
            reportPaths.put("markdown_bytes", Files.size(md));
            if (shredQuarantine) {
                try {
                    Map<String, Object> shredResult = shredQuarantinedCopies(quarantined);
                    shredSummary.clear();
                    shredSummary.putAll(shredResult);
                } catch (Exception shredEx) {
                    shredSummary.put("performed", false);
                    shredSummary.put("error", "shred failed: " + shredEx.getMessage());
                }
                document.put("shred", shredSummary);
                try {
                    Files.writeString(json, Json.write(document), StandardCharsets.UTF_8);
                    Files.writeString(md, buildMarkdown(document, fileReports, manifestReports,
                            quarantined, aggregate), StandardCharsets.UTF_8);
                    reportPaths.put("json_bytes", Files.size(json));
                    reportPaths.put("markdown_bytes", Files.size(md));
                } catch (Exception rewriteEx) {
                    reportPaths.put("shred_report_error",
                            "report rewrite after shred failed: " + rewriteEx.getMessage());
                }
            } else {
                shredSummary.put("notice", "未请求粉碎；隔离副本按静态模式保留。");
            }
        } catch (Exception ex) {
            reportPaths.put("error", "report could not be written: " + ex.getMessage());
        }
        out.put("ok", true);
        out.put("files_scanned", fileReports.size());
        out.put("manifests_scanned", manifestReports.size());
        out.put("aggregate", aggregate);
        out.put("integrity", integrity);
        out.put("files", fileReports);
        out.put("manifests", manifestReports);
        out.put("quarantine", quarantined);
        out.put("shred", shredSummary);
        out.put("report_id", reportId);
        out.put("report", reportPaths);
        out.put("execution", Json.map("mode", "static-only", "executed", false));
        int shreddedFiles = Json.integer(shredSummary, "files_shredded", 0);
        String shredNote = shreddedFiles > 0
                ? "；本次产生的隔离副本已粉碎，原始文件未被改动。"
                : Json.bool(shredSummary, "requested", false)
                ? "；本次扫描没有产生需要粉碎的隔离副本，原始文件未被改动。" : "。";
        out.put("notice", "静态检测完成：未执行任何被检测代码，扫描前后哈希校验见 integrity 字段。完整报告已写入 "
                + (jsonPath.isBlank() ? reportDir.toString() : jsonPath) + shredNote);
        out.put("duration_ms", System.currentTimeMillis() - started);
        return out;
    }

    public Map<String, Object> quarantineList() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dir", quarantineDir.toString());
        List<Map<String, Object>> entries = new ArrayList<>();
        if (Files.isDirectory(quarantineDir)) {
            try (var stream = Files.list(quarantineDir)) {
                List<Path> metas = new ArrayList<>();
                stream.filter(path -> path.getFileName().toString().endsWith(".meta.json"))
                        .forEach(metas::add);
                metas.sort(Comparator.comparing(
                        (Path path) -> path.getFileName().toString()).reversed());
                for (Path meta : metas) {
                    try {
                        Map<String, Object> parsed = Json.asObject(Json.parse(
                                Files.readString(meta, StandardCharsets.UTF_8)));
                        parsed.put("meta_path", meta.toString());
                        entries.add(parsed);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ex) {
                out.put("error", ex.getMessage());
            }
        }
        out.put("entries", entries);
        out.put("count", entries.size());
        return out;
    }

    // ------------------------------------------------------------------
    // Directory discovery
    // ------------------------------------------------------------------

    private void traverse(Path start, int depth, int maxFiles, List<Path> files,
                          List<Path> manifests, Map<String, Object> stats) {
        int[] counts = new int[]{0, 0, 0, 0}; // seen, skipped dirs, size skipped, limit hit
        try {
            Files.walkFileTree(start, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    depth, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir,
                                                                 BasicFileAttributes attrs) {
                            if (!dir.equals(start)) {
                                String name = dir.getFileName().toString()
                                        .toLowerCase(Locale.ROOT);
                                if (SKIP_DIRS.contains(name)) {
                                    ++counts[1];
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            ++counts[0];
                            if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                                return FileVisitResult.CONTINUE;
                            }
                            String name = file.getFileName().toString();
                            if (isManifest(name)) {
                                if (manifests.size() < Math.min(40, maxFiles)) {
                                    manifests.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            if (!isCodePath(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (files.size() >= maxFiles) {
                                counts[3] = 1;
                                return FileVisitResult.CONTINUE;
                            }
                            if (attrs.size() > MAX_FILE_BYTES) {
                                counts[2] = 1;
                                return FileVisitResult.CONTINUE;
                            }
                            files.add(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Exception ex) {
            stats.put("error", ex.getMessage());
        }
        files.sort(Comparator.comparing(Path::toString));
        manifests.sort(Comparator.comparing(Path::toString));
        stats.put("entries_seen", counts[0]);
        stats.put("skipped_directories", counts[1]);
        stats.put("oversized_files_skipped", counts[2] != 0);
        stats.put("max_files_reached", counts[3] != 0);
    }

    private static boolean isManifest(String name) {
        return MANIFESTS.contains(name)
                || name.endsWith(".csproj") || name.endsWith(".sln")
                || name.endsWith(".vcxproj") || name.endsWith(".fsproj");
    }

    private static boolean isCodePath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && EXT.containsKey(name.substring(dot + 1))) {
            return true;
        }
        if (NAMES.containsKey(path.getFileName().toString())) {
            return true;
        }
        return isCodePathByName(name);
    }

    private static boolean isCodePathByName(String lowerName) {
        return lowerName.equals("makefile") || lowerName.equals("dockerfile")
                || lowerName.equals("cmakelists.txt") || lowerName.equals("rakefile")
                || lowerName.equals("gemfile") || lowerName.equals("jenkinsfile")
                || lowerName.equals("vagrantfile") || lowerName.equals("procfile")
                || lowerName.equals(".gitignore") || lowerName.equals(".dockerignore")
                || lowerName.equals(".editorconfig") || lowerName.endsWith(".gradle");
    }

    // ------------------------------------------------------------------
    // Single-file analysis
    // ------------------------------------------------------------------

    private Map<String, Object> analyze(Path path, boolean explicit, boolean quarantineRisky) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        out.put("name", path.getFileName().toString());
        out.put("explicit", explicit);
        out.put("quarantine", new LinkedHashMap<String, Object>());
        try {
            BasicFileAttributes attrs = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long size = attrs.size();
            out.put("size_bytes", size);
            out.put("created_at", attrs.creationTime().toInstant().toString());
            out.put("modified_at", attrs.lastModifiedTime().toInstant().toString());
            out.put("accessed_at", attrs.lastAccessTime().toInstant().toString());
            out.put("modified_local", LocalDateTime.ofInstant(
                    attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()).format(ISO));
            Map<String, Object> attributeView = new LinkedHashMap<>();
            try {
                DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class);
                attributeView.put("read_only", dos.isReadOnly());
                attributeView.put("hidden", dos.isHidden());
                attributeView.put("system", dos.isSystem());
                attributeView.put("archive", dos.isArchive());
            } catch (Exception ex) {
                attributeView.put("note", "DOS attributes unavailable");
            }
            String name = path.getFileName().toString();
            String lowerName = name.toLowerCase(Locale.ROOT);
            attributeView.put("executable_extension", EXEC_EXTS.contains(extension(lowerName)));
            out.put("attributes", attributeView);
            out.put("zone_identifier", readZoneIdentifier(path));
            out.put("hashes", hashes(path));
            byte[] head = readHead(path, HEAD_BYTES);
            out.put("head_bytes", head.length);
            out.put("file_truncated_for_analysis", size > head.length);
            BinaryInfo binaryInfo = binaryInfo(head);
            out.put("binary", binaryInfo.toMap(size));
            double entropy = entropy(head);
            out.put("entropy", Math.round(entropy * 1000.0) / 1000.0);

            Lang lang = detectLanguage(path, head);
            out.put("language", lang.name);
            out.put("language_family", lang.family);
            out.put("mime", mime(lang, path));
            out.put("is_code", lang.code);

            Decoded decoded = decode(head);
            out.put("encoding", decoded.charset);
            out.put("encoding_confidence", decoded.confidence);
            out.put("binary_without_text", decoded.binary);
            String text = decoded.text;
            boolean textTruncated = text.length() > MAX_TEXT_CHARS;
            if (textTruncated) {
                text = text.substring(0, MAX_TEXT_CHARS);
            }
            out.put("text_chars", text.length());
            out.put("text_analysis_truncated", textTruncated);
            if (binaryInfo.binary && text.isBlank()) {
                String strings = extractStrings(head, 4000);
                text = strings;
                out.put("strings_extracted", true);
            } else {
                out.put("strings_extracted", false);
            }
            Map<String, Object> metrics = metrics(text);
            out.put("metrics", metrics);
            out.put("structure", structure(text, metrics));
            out.put("dependencies", dependencies(text));
            out.put("network", networkFindings(text));
            List<Map<String, Object>> secrets = secrets(text);
            out.put("secrets", secrets);
            Map<String, Object> risk = risk(text, binaryInfo, entropy, secrets, path);
            out.put("risk", risk);
            Map<String, Object> quarantine = maybeQuarantine(path, risk, quarantineRisky);
            if (!quarantine.isEmpty()) {
                out.put("quarantine", quarantine);
            }
            out.put("preview", preview(text, lang));
            out.put("preview_redacted", true);
        } catch (Exception ex) {
            out.put("error", ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return out;
    }

    private Map<String, Object> analyzeManifest(Path path) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        out.put("name", path.getFileName().toString());
        try {
            BasicFileAttributes attrs = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            out.put("size_bytes", attrs.size());
            out.put("modified_at", attrs.lastModifiedTime().toInstant().toString());
            byte[] head = readHead(path, 1024 * 1024);
            Decoded decoded = decode(head);
            String text = decoded.text;
            out.put("encoding", decoded.charset);
            out.put("lines", text.isEmpty() ? 0 : text.split("\\n", -1).length);
            List<String> dependencies = new ArrayList<>();
            if (out.get("name").toString().equals("package.json")) {
                try {
                    Map<String, Object> json = Json.asObject(Json.parse(text));
                    out.put("name_field", Json.str(json, "name", ""));
                    out.put("version", Json.str(json, "version", ""));
                    Map<String, Object> scripts = Json.asObject(json.get("scripts"));
                    out.put("scripts", scripts);
                    Map<String, Object> deps = Json.asObject(json.get("dependencies"));
                    Map<String, Object> devDeps = Json.asObject(json.get("devDependencies"));
                    dependencies.addAll(deps.keySet());
                    dependencies.addAll(devDeps.keySet());
                } catch (Exception ex) {
                    out.put("parse_error", "package.json is invalid JSON: " + ex.getMessage());
                }
            } else if (out.get("name").toString().equals("requirements.txt")) {
                for (String line : text.split("\\r?\\n")) {
                    String clean = line.trim();
                    if (!clean.isEmpty() && !clean.startsWith("#")
                            && !clean.startsWith("-")) {
                        dependencies.add(clean.split("[=<>!~\\[]")[0].trim());
                    }
                }
            } else if (out.get("name").toString().equals("go.mod")) {
                for (String line : text.split("\\r?\\n")) {
                    String clean = line.trim();
                    if (clean.startsWith("module ") || clean.startsWith("go ")) {
                        dependencies.add(clean);
                    } else if (!clean.isEmpty() && !clean.startsWith("//")
                            && clean.contains("/")) {
                        dependencies.add(clean.split("\\s+")[0]);
                    }
                }
            } else if (out.get("name").toString().equals("Cargo.toml")
                    || out.get("name").toString().equals("pyproject.toml")) {
                String section = "";
                for (String line : text.split("\\r?\\n")) {
                    String clean = line.trim();
                    if (clean.startsWith("[")) {
                        section = clean;
                    } else if (section.toLowerCase(Locale.ROOT).contains("dependencies")
                            && clean.contains("=")) {
                        dependencies.add(clean.split("=")[0].trim());
                    } else if (clean.startsWith("name ") || clean.startsWith("version ")) {
                        dependencies.add(clean);
                    }
                }
            } else if (out.get("name").toString().equals("pom.xml")) {
                Matcher group = Pattern.compile(
                        "(?is)<(?:groupId|artifactId|version)>\\s*([^<]{1,120})\\s*<")
                        .matcher(text);
                int count = 0;
                while (group.find() && count++ < 20) {
                    dependencies.add(group.group(1).trim());
                }
            } else if (out.get("name").toString().startsWith(".env")) {
                for (String line : text.split("\\r?\\n")) {
                    String clean = line.trim();
                    if (clean.isEmpty() || clean.startsWith("#")) {
                        continue;
                    }
                    int eq = clean.indexOf('=');
                    if (eq > 0) {
                        out.put("env_file_note",
                                "变量名已列出，值全部省略，避免在报告中泄露密钥。");
                        dependencies.add(clean.substring(0, eq).trim());
                    }
                }
            }
            if (dependencies.size() > 60) {
                dependencies = new ArrayList<>(dependencies.subList(0, 60));
            }
            out.put("dependencies_sample", dependencies);
            out.put("dependency_count_sample", dependencies.size());
            List<Map<String, Object>> found = secrets(text);
            out.put("secret_findings", found);
            if (!found.isEmpty()) {
                out.put("risk", Json.map(
                        "level", "high",
                        "score", 60,
                        "summary", "清单文件包含疑似硬编码密钥，值已在报告中掩码。"));
            }
            out.put("preview", preview(text, detectLanguage(path,
                    head)));
        } catch (Exception ex) {
            out.put("error", ex.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Metrics, structure, security
    // ------------------------------------------------------------------

    private static Map<String, Object> metrics(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            out.put("lines", 0);
            out.put("blank_lines", 0);
            out.put("comment_lines", 0);
            out.put("code_lines", 0);
            return out;
        }
        String[] lines = text.split("\\r?\\n", -1);
        int blank = 0;
        int maxLine = 0;
        long chars = 0;
        int longLines = 0;
        int crlf = count(text, "\r\n");
        int cr = count(text, "\r") - crlf;
        int lf = count(text, "\n") - crlf;
        boolean inBlock = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                ++blank;
            }
            maxLine = Math.max(maxLine, line.length());
            chars += line.length();
            if (line.length() > 500) {
                ++longLines;
            }
            if (trimmed.contains("/*")) {
                inBlock = true;
            }
            if (trimmed.contains("*/")) {
                inBlock = false;
            }
        }
        int comments = 0;
        int todo = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("#")
                    || trimmed.startsWith("--") || trimmed.startsWith(";")
                    || trimmed.startsWith("REM ") || trimmed.startsWith("::")
                    || trimmed.startsWith("<!--") || trimmed.endsWith("-->")
                    || trimmed.startsWith("*") || inBlockLine(trimmed)) {
                ++comments;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.contains("TODO") || upper.contains("FIXME")
                    || upper.contains("HACK") || upper.contains("XXX")) {
                ++todo;
            }
        }
        int nonEmpty = lines.length - blank;
        int codeLines = Math.max(0, nonEmpty - Math.min(nonEmpty, comments));
        out.put("lines", lines.length);
        out.put("non_empty_lines", nonEmpty);
        out.put("blank_lines", blank);
        out.put("comment_lines_approx", comments);
        out.put("code_lines_approx", codeLines);
        out.put("comment_ratio", nonEmpty == 0 ? 0.0
                : Math.round(comments * 10000.0 / nonEmpty) / 100.0);
        out.put("max_line_length", maxLine);
        out.put("average_line_length", nonEmpty == 0 ? 0
                : Math.round((double) chars / nonEmpty));
        out.put("lines_over_500_chars", longLines);
        out.put("line_endings", Json.map(
                "crlf", crlf, "lf", lf, "cr", Math.max(0, cr),
                "style", crlf > 0 && lf + cr > 0 ? "mixed" : crlf > 0 ? "CRLF"
                        : lf > 0 ? "LF" : cr > 0 ? "CR" : "none"));
        out.put("todo_markers", todo);
        int spaces = Math.min(count(text, "    "), 10000);
        int tabs = Math.min(count(text, "\t"), 10000);
        out.put("indentation", spaces >= tabs * 2 ? "spaces" : tabs > 0 ? "tabs" : "none");
        int braces = count(text, "{");
        int maxDepth = 0;
        if (braces > 3) {
            int depth = 0;
            for (int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                if (c == '{') {
                    maxDepth = Math.max(maxDepth, ++depth);
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                }
            }
        } else {
            int indentMax = 0;
            for (String line : lines) {
                int leading = 0;
                while (leading < line.length()
                        && (line.charAt(leading) == ' ' || line.charAt(leading) == '\t')) {
                    ++leading;
                }
                indentMax = Math.max(indentMax, leading / 4);
            }
            maxDepth = indentMax;
        }
        out.put("max_nesting_depth_approx", Math.min(30, maxDepth));
        int decisions = 0;
        Matcher matcher = Pattern.compile(
                "\\b(?:if|for|while|case|catch|elif|when)\\b|&&|\\|\\|")
                .matcher(text);
        while (matcher.find() && decisions < 100000) {
            ++decisions;
        }
        out.put("decision_points_approx", decisions);
        out.put("cyclomatic_complexity_approx", decisions + 1);
        out.put("todo_note", "注释/复杂度为静态近似值，不包含宏展开或生成代码。");
        return out;
    }

    private static boolean inBlockLine(String trimmed) {
        return trimmed.startsWith("/*") || trimmed.startsWith("*")
                || trimmed.endsWith("*/");
    }

    private static Map<String, Object> structure(String text, Map<String, Object> metrics) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> classes = new ArrayList<>();
        List<Map<String, Object>> functions = new ArrayList<>();
        collectDefs(text, CLASS_DEF, "class", classes, 150);
        collectDefs(text, PY_DEF, "function", functions, 150);
        collectDefs(text, FUNC_DEF, "function", functions, 150);
        collectDefs(text, JS_ARROW, "function", functions, 150);
        out.put("classes", classes);
        out.put("functions", functions);
        out.put("class_count", classes.size());
        out.put("function_count", functions.size());
        List<String> entryPoints = new ArrayList<>();
        if (text.contains("__name__") && text.contains("__main__")) {
            entryPoints.add("Python main guard");
        }
        if (Pattern.compile("(?m)^\\s*(?:public\\s+static\\s+void\\s+main\\s*\\(|"
                + "static\\s+void\\s+main\\s*\\()").matcher(text).find()) {
            entryPoints.add("Java/C# main()");
        }
        if (Pattern.compile("(?m)^\\s*(?:int|void)\\s+main\\s*\\(").matcher(text).find()) {
            entryPoints.add("C/C++ main()");
        }
        if (Pattern.compile("(?m)^\\s*package\\s+main\\b").matcher(text).find()) {
            entryPoints.add("Go package main");
        }
        if (Pattern.compile("(?m)^\\s*fn\\s+main\\s*\\(").matcher(text).find()) {
            entryPoints.add("Rust fn main");
        }
        if (text.startsWith("#!")) {
            entryPoints.add("shebang script");
        }
        out.put("entry_points", entryPoints);
        out.put("imports", imports(text));
        out.put("package", packageName(text));
        out.put("comments_ratio", metrics.get("comment_ratio"));
        out.put("has_tests_mentioned", Pattern.compile("(?i)\\b(?:test|assert|pytest|unittest|"
                + "junit|mocha|jest)\\b").matcher(text).find());
        return out;
    }

    private static void collectDefs(String text, Pattern pattern, String type,
                                    List<Map<String, Object>> out, int limit) {
        int[] newlines = null;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && out.size() < limit) {
            String name = matcher.group(1);
            if (name == null || name.isBlank()) {
                continue;
            }
            if (newlines == null) {
                newlines = newlineIndex(text);
            }
            out.add(Json.map(
                    "name", name,
                    "type", type,
                    "line", lineOf(newlines, matcher.start())));
        }
    }

    private static int[] newlineIndex(String text) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < text.length(); ++i) {
            if (text.charAt(i) == '\n') {
                positions.add(i);
            }
        }
        int[] out = new int[positions.size()];
        for (int i = 0; i < out.length; ++i) {
            out[i] = positions.get(i);
        }
        return out;
    }

    private static int lineOf(int[] newlines, int position) {
        int low = 0;
        int high = newlines.length - 1;
        int count = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (newlines[mid] < position) {
                count = mid + 1;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return count + 1;
    }

    private static List<String> imports(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = IMPORT_LINE.matcher(text);
        while (matcher.find() && out.size() < 120) {
            String line = matcher.group().trim();
            Matcher quoted = QUOTED.matcher(line);
            if (quoted.find()) {
                out.add(quoted.group(1).trim());
                continue;
            }
            String module = line.replaceAll("(?i)^(?:import|from|#include|using|use|"
                    + "require|require_relative|include|load)\\b", "")
                    .replace("static", "").trim()
                    .replaceAll("^[<\"']|[>\"']$", "")
                    .replaceAll("\\s+", " ");
            if (!module.isBlank()) {
                out.add(MemoryStore.trim(module, 160));
            }
        }
        return new ArrayList<>(out);
    }

    private static String packageName(String text) {
        Matcher java = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;")
                .matcher(text);
        if (java.find()) {
            return java.group(1);
        }
        Matcher cpp = Pattern.compile("(?m)^\\s*namespace\\s+([\\w:]+)").matcher(text);
        if (cpp.find()) {
            return cpp.group(1);
        }
        Matcher csharp = Pattern.compile("(?m)^\\s*namespace\\s+([\\w.]+)").matcher(text);
        if (csharp.find()) {
            return csharp.group(1);
        }
        return "";
    }

    private static List<Map<String, Object>> dependencies(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        Matcher matcher = IMPORT_LINE.matcher(text);
        while (matcher.find() && out.size() < 50) {
            String line = matcher.group().trim();
            String module = line;
            Matcher quoted = QUOTED.matcher(line);
            if (quoted.find()) {
                module = quoted.group(1);
            } else {
                module = line.replaceAll("(?i)^(?:import|from|#include|using|use|"
                        + "require|require_relative|include|load)\\b", "").trim();
            }
            String root = module.split("[/.:\\s]")[0];
            if (root.isBlank() || root.equals(".") || root.equals("..")) {
                continue;
            }
            out.add(Json.map(
                    "module", MemoryStore.trim(module, 200),
                    "root", root,
                    "line", line));
        }
        return out;
    }

    private static Map<String, Object> networkFindings(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find() && urls.size() < 40) {
            urls.add(maskUrl(matcher.group()));
        }
        out.put("urls", new ArrayList<>(urls));
        Set<String> ips = new LinkedHashSet<>();
        Matcher ip = IPV4.matcher(text);
        while (ip.find() && ips.size() < 40) {
            ips.add(ip.group());
        }
        out.put("ipv4", new ArrayList<>(ips));
        Set<String> emails = new LinkedHashSet<>();
        Matcher email = EMAIL_PATTERN.matcher(text);
        while (email.find() && emails.size() < 40) {
            String value = email.group();
            int at = value.indexOf('@');
            if (at > 1) {
                value = value.charAt(0) + "***" + value.substring(at);
            }
            emails.add(value);
        }
        out.put("emails", new ArrayList<>(emails));
        return out;
    }

    private static String maskUrl(String url) {
        return url.replaceAll("(?i)(https?://)([^/@\\s]+)@", "$1***@");
    }

    private static List<Map<String, Object>> secrets(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        int[] newlines = null;
        Matcher known = SECRET_KNOWN.matcher(text);
        while (known.find() && out.size() < 40) {
            String value = known.group(1);
            String key = "known:" + value;
            if (seen.putIfAbsent(key, true) != null) {
                continue;
            }
            if (newlines == null) {
                newlines = newlineIndex(text);
            }
            out.add(Json.map(
                    "type", "known-token",
                    "line", lineOf(newlines, known.start()),
                    "value_masked", mask(value),
                    "note", "匹配到已知格式的访问令牌，值已掩码。"));
        }
        Matcher privateKey = PRIVATE_KEY.matcher(text);
        if (privateKey.find()) {
            out.add(Json.map(
                    "type", "private-key",
                    "line", lineOf(newlines == null ? newlineIndex(text) : newlines,
                            privateKey.start()),
                    "value_masked", "[private key block]",
                    "note", "包含私钥块；报告不保存私钥内容。"));
        }
        Matcher assign = SECRET_ASSIGN.matcher(text);
        while (assign.find() && out.size() < 60) {
            String value = assign.group(1).trim();
            if (value.matches("(?i)^(?:your[_-]?.*|my[_-]?.*|test|example|changeme|"
                    + "placeholder|dummy|none|null|true|false|\\$\\{.*\\}|<.*>|%s|xxx+)$")) {
                continue;
            }
            if (value.length() < 6) {
                continue;
            }
            String key = "assign:" + assign.start();
            if (seen.putIfAbsent(key, true) != null) {
                continue;
            }
            if (newlines == null) {
                newlines = newlineIndex(text);
            }
            out.add(Json.map(
                    "type", "assigned-secret",
                    "line", lineOf(newlines, assign.start()),
                    "value_masked", mask(value),
                    "note", "变量名疑似密钥，值已掩码。"));
        }
        return out;
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "***";
        }
        String clean = value.trim();
        if (clean.length() <= 8) {
            return "***";
        }
        return clean.substring(0, 3) + "…" + clean.substring(clean.length() - 2);
    }

    private static Map<String, Object> risk(String text, BinaryInfo binary, double entropy,
                                            List<Map<String, Object>> secrets, Path path) {
        List<Map<String, Object>> indicators = new ArrayList<>();
        int score = 0;
        int[] newlines = null;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern.matcher(text);
            int count = 0;
            int firstLine = 0;
            while (matcher.find() && count < 20) {
                if (count == 0) {
                    if (newlines == null) {
                        newlines = newlineIndex(text);
                    }
                    firstLine = lineOf(newlines, matcher.start());
                }
                ++count;
            }
            if (count == 0) {
                continue;
            }
            int points = rule.points * Math.min(2, count);
            score += points;
            indicators.add(Json.map(
                    "id", rule.id,
                    "severity", rule.severity,
                    "category", rule.category,
                    "title", rule.title,
                    "detail", rule.detail,
                    "count", count,
                    "line", firstLine,
                    "points", points));
        }
        if (entropy >= 7.2) {
            score += binary.binary ? 8 : 5;
            indicators.add(Json.map(
                    "id", "high-entropy",
                    "severity", "medium",
                    "category", "混淆",
                    "title", "高熵内容（可能是压缩、加密或打包）",
                    "detail", "字节熵 " + Math.round(entropy * 1000.0) / 1000.0
                            + "，常见于加壳/加密载荷，也可能是正常压缩资源。",
                    "count", 1, "line", 0, "points", binary.binary ? 8 : 5));
        }
        Matcher base64 = BASE64_CHUNK.matcher(text);
        int chunks = 0;
        while (base64.find() && chunks < 20) {
            ++chunks;
        }
        if (chunks > 0) {
            int points = Math.min(6, chunks);
            score += points;
            indicators.add(Json.map(
                    "id", "base64-blob",
                    "severity", "medium",
                    "category", "混淆",
                    "title", "超长 Base64 数据块",
                    "detail", "发现 " + chunks + " 段长度不少于 120 的 Base64 文本，可能藏有载荷。",
                    "count", chunks, "line", 0, "points", points));
        }
        int hex = 0;
        Matcher hexMatcher = HEX_ESCAPE.matcher(text);
        while (hexMatcher.find() && hex < 1000) {
            ++hex;
        }
        if (hex > 60) {
            score += 5;
            indicators.add(Json.map(
                    "id", "hex-obfuscation",
                    "severity", "medium",
                    "category", "混淆",
                    "title", "大量十六进制转义",
                    "detail", "出现 " + hex + " 个 \\xNN 转义，可能用于隐藏字符串。",
                    "count", hex, "line", 0, "points", 5));
        }
        int jsObf = 0;
        Matcher js = OBFUSCATED_JS.matcher(text);
        while (js.find() && jsObf < 100) {
            ++jsObf;
        }
        if (jsObf > 10) {
            score += 12;
            indicators.add(Json.map(
                    "id", "javascript-obfuscator",
                    "severity", "high",
                    "category", "混淆",
                    "title", "JavaScript 混淆器特征（_0x 变量）",
                    "detail", "发现 " + jsObf + " 个 _0x 混淆标识符，常见于被混淆的脚本。",
                    "count", jsObf, "line", 0, "points", 12));
        }
        if (!secrets.isEmpty()) {
            boolean privateKey = secrets.stream().anyMatch(item ->
                    "private-key".equals(String.valueOf(item.get("type"))));
            int points = privateKey ? 18 : 8;
            score += points;
            indicators.add(Json.map(
                    "id", "hardcoded-secret",
                    "severity", privateKey ? "high" : "medium",
                    "category", "凭据泄露",
                    "title", privateKey ? "硬编码私钥" : "硬编码密钥/口令",
                    "detail", "发现 " + secrets.size() + " 处疑似凭据；报告只保存掩码值。",
                    "count", secrets.size(), "line", 0, "points", points));
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String[] parts = lower.split("\\.");
        if (parts.length >= 3 && EXEC_EXTS.contains(parts[parts.length - 1])) {
            String before = parts[parts.length - 2];
            if (Set.of("pdf", "doc", "docx", "jpg", "jpeg", "png", "gif", "txt", "mp4",
                    "mp3", "zip", "rar", "xls", "xlsx", "ppt", "pptx").contains(before)) {
                score += 8;
                indicators.add(Json.map(
                        "id", "double-extension",
                        "severity", "medium",
                        "category", "伪装",
                        "title", "双扩展名伪装",
                        "detail", "文件名使用常见文档扩展名后接可执行扩展名，常用于欺骗。",
                        "count", 1, "line", 0, "points", 8));
            }
        }
        if (binary.binary) {
            String ext = extension(lower);
            boolean codeExt = EXT.containsKey(ext);
            score += codeExt ? 16 : 6;
            indicators.add(Json.map(
                    "id", codeExt ? "binary-masquerade" : "binary-payload",
                    "severity", codeExt ? "high" : "medium",
                    "category", "伪装",
                    "title", codeExt ? "代码文件实为二进制" : "二进制文件",
                    "detail", codeExt
                            ? "扩展名指向源码，但内容包含控制字符/NUL，可能隐藏可执行载荷。"
                            : "文件为二进制；静态字符串扫描不能替代完整逆向。",
                    "count", 1, "line", 0, "points", codeExt ? 16 : 6));
        }
        if (binary.binary && "PE".equals(binary.kind)) {
            indicators.add(Json.map(
                    "id", "pe-executable",
                    "severity", "info",
                    "category", "文件类型",
                    "title", "PE 可执行文件",
                    "detail", "Machine=" + binary.machine + "；本工具只做静态检查，不执行该文件。",
                    "count", 1, "line", 0, "points", 0));
        }
        if (score > 100) {
            score = 100;
        }
        String level;
        String label;
        if (score >= 70) {
            level = "high";
            label = "高风险";
        } else if (score >= 40) {
            level = "caution";
            label = "需谨慎";
        } else if (score >= 20) {
            level = "notice";
            label = "留意";
        } else {
            level = "low";
            label = "低风险";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("level", level);
        out.put("label", label);
        out.put("indicators", indicators);
        out.put("summary", summaryFor(level, indicators, secrets));
        out.put("disclaimer", "这是静态启发式评分，不是杀毒引擎结论；0 分也不代表绝对安全。");
        return out;
    }

    private static String summaryFor(String level, List<Map<String, Object>> indicators,
                                     List<Map<String, Object>> secrets) {
        if (indicators.isEmpty() && secrets.isEmpty()) {
            return "未发现明显风险特征。";
        }
        StringBuilder out = new StringBuilder();
        out.append(switch (level) {
            case "high" -> "检测到高风险特征，建议隔离并人工复核：";
            case "caution" -> "检测到需要谨慎处理的特征：";
            case "notice" -> "发现一些值得留意的特征：";
            default -> "发现少量低风险特征：";
        });
        int shown = 0;
        for (Map<String, Object> indicator : indicators) {
            if (shown++ >= 5) {
                break;
            }
            out.append(' ').append(indicator.get("title")).append('；');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Quarantine
    // ------------------------------------------------------------------

    private Map<String, Object> maybeQuarantine(Path path, Map<String, Object> risk,
                                                boolean quarantineRisky) {
        int score = Json.integer(risk, "score", 0);
        if (!quarantineRisky || score < 70) {
            return new LinkedHashMap<>();
        }
        return quarantine(path, score, Json.str(risk, "summary", ""));
    }

    public Map<String, Object> quarantine(Path path, int score, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        try {
            Files.createDirectories(quarantineDir);
            String stamp = LocalDateTime.now().format(STAMP);
            String safeName = sanitize(path.getFileName().toString());
            String base = stamp + "-" + Integer.toString((int) (Math.random() * 0xffff), 16)
                    + "-" + safeName + ".quarantined";
            Path copy = quarantineDir.resolve(base);
            Files.copy(path, copy);
            try {
                Files.setAttribute(copy, "dos:readonly", true);
            } catch (Exception ignored) {
            }
            try {
                Files.writeString(Path.of(copy + ":Zone.Identifier"),
                        "[ZoneTransfer]\r\nZoneId=3\r\n", StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            Map<String, Object> hashes = hashes(path);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("original_path", path.toString());
            meta.put("quarantine_path", copy.toString());
            meta.put("quarantined_at", Instant.now().toString());
            meta.put("score", score);
            meta.put("reason", reason);
            meta.put("sha256", hashes.get("sha256"));
            meta.put("sha1", hashes.get("sha1"));
            meta.put("md5", hashes.get("md5"));
            meta.put("mode", "static-only");
            meta.put("executed", false);
            meta.put("isolation", "只读隔离副本；不做执行；可按扫描设置粉碎本副本，原始文件永不改动。");
            Path metaPath = copy.resolveSibling(copy.getFileName() + ".meta.json");
            Files.writeString(metaPath, Json.write(meta), StandardCharsets.UTF_8);
            writeQuarantineReadme();
            out.put("ok", true);
            out.put("copied", true);
            out.putAll(meta);
            out.put("meta_path", metaPath.toString());
        } catch (Exception ex) {
            out.put("error", ex.getMessage());
        }
        return out;
    }

    private void writeQuarantineReadme() {
        Path readme = quarantineDir.resolve("README.txt");
        if (Files.exists(readme)) {
            return;
        }
        try {
            Files.writeString(readme, """
                    CodeForge quarantine vault
                    ==========================
                    Files in this folder are READ-ONLY COPIES. Nothing here is executed.
                    Every entry has a .meta.json sidecar with hashes, risk score and origin.
                    When an inspection requests shredding, only these copies are overwritten
                    and deleted after the report is written. Original files are never modified.
                    Never double-click or copy a quarantined file back into service.
                    """, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String sanitize(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.length() > 80) {
            clean = clean.substring(clean.length() - 80);
        }
        return clean.isBlank() ? "sample" : clean;
    }

    // ------------------------------------------------------------------
    // Report output
    // ------------------------------------------------------------------

    private static Map<String, Object> aggregate(List<Map<String, Object>> files) {
        Map<String, Object> out = new LinkedHashMap<>();
        long bytes = 0;
        long lines = 0;
        int high = 0;
        int caution = 0;
        int notice = 0;
        int low = 0;
        int maxScore = 0;
        Map<String, int[]> languages = new LinkedHashMap<>();
        List<Object> highRisk = new ArrayList<>();
        for (Map<String, Object> file : files) {
            bytes += Json.integer(file, "size_bytes", 0);
            Map<String, Object> metrics = Json.asObject(file.get("metrics"));
            lines += Json.integer(metrics, "lines", 0);
            String language = Json.str(file, "language", "Unknown");
            int[] stats = languages.computeIfAbsent(language, k -> new int[3]);
            ++stats[0];
            stats[1] += Json.integer(metrics, "lines", 0);
            stats[2] += Json.integer(file, "size_bytes", 0);
            Map<String, Object> risk = Json.asObject(file.get("risk"));
            int score = Json.integer(risk, "score", 0);
            maxScore = Math.max(maxScore, score);
            switch (Json.str(risk, "level", "low")) {
                case "high" -> {
                    ++high;
                    highRisk.add(Json.map(
                            "path", file.get("path"),
                            "score", score,
                            "summary", risk.get("summary")));
                }
                case "caution" -> ++caution;
                case "notice" -> ++notice;
                default -> ++low;
            }
        }
        List<Map<String, Object>> languageList = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : languages.entrySet()) {
            languageList.add(Json.map(
                    "language", entry.getKey(),
                    "files", entry.getValue()[0],
                    "lines", entry.getValue()[1],
                    "bytes", entry.getValue()[2]));
        }
        out.put("files", files.size());
        out.put("total_bytes", bytes);
        out.put("total_lines", lines);
        out.put("languages", languageList);
        out.put("risk", Json.map(
                "max_score", maxScore,
                "high", high,
                "caution", caution,
                "notice", notice,
                "low", low,
                "high_risk_files", highRisk));
        return out;
    }

    private Map<String, Object> verifyIntegrity(List<Map<String, Object>> files) {
        Map<String, Object> out = new LinkedHashMap<>();
        long remaining = INTEGRITY_REHASH_BUDGET;
        long used = 0;
        int checked = 0;
        int skipped = 0;
        List<Map<String, Object>> changed = new ArrayList<>();
        List<Map<String, Object>> unreadable = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (Map<String, Object> report : files) {
            if (report.containsKey("error")) {
                ++skipped;
                continue;
            }
            String path = Json.str(report, "path", "");
            Map<String, Object> hashes = Json.asObject(report.get("hashes"));
            String before = Json.str(hashes, "sha256", "");
            long size = Json.lon(report, "size_bytes", 0);
            if (path.isBlank() || before.isBlank()) {
                ++skipped;
                continue;
            }
            if (size > remaining) {
                ++skipped;
                continue;
            }
            remaining -= size;
            used += size;
            try {
                String after = sha256(Path.of(path));
                entries.add(after + "  " + path);
                ++checked;
                if (!after.equalsIgnoreCase(before)) {
                    changed.add(Json.map(
                            "path", path,
                            "sha256_before", before,
                            "sha256_after", after));
                }
            } catch (Exception ex) {
                unreadable.add(Json.map(
                        "path", path,
                        "error", ex.getMessage() == null
                                ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }
        Collections.sort(entries);
        boolean applicable = !files.isEmpty();
        boolean unchanged = changed.isEmpty() && unreadable.isEmpty();
        out.put("mode", "rehash-after-inspection");
        out.put("applicable", applicable);
        out.put("unchanged", unchanged);
        out.put("content_verified", applicable && skipped == 0 && unchanged);
        out.put("files_checked", checked);
        out.put("files_skipped", skipped);
        out.put("files_changed", changed.size());
        out.put("files_unreadable", unreadable.size());
        out.put("budget_bytes", INTEGRITY_REHASH_BUDGET);
        out.put("budget_used_bytes", used);
        out.put("changed", changed);
        out.put("unreadable", unreadable);
        out.put("digest", digestEntries(entries));
        out.put("notice", unchanged
                ? "扫描完成后逐个重算 SHA-256，与扫描开始时一致：源文件字节未被修改。"
                : "扫描完成后发现文件字节发生变化，详见 changed / unreadable。");
        return out;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String digestEntries(List<String> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entry : entries) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return hex(digest.digest());
        } catch (Exception ex) {
            return "";
        }
    }

    private Map<String, Object> shredQuarantinedCopies(List<Map<String, Object>> quarantined) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", true);
        out.put("performed", true);
        List<Map<String, Object>> entries = new ArrayList<>();
        int shredded = 0;
        int failed = 0;
        for (Map<String, Object> entry : quarantined) {
            String copy = Json.str(entry, "quarantine_path", "");
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("path", copy);
            record.put("sha256", entry.get("sha256"));
            try {
                if (copy.isBlank()) {
                    throw new IllegalArgumentException("quarantine_path is missing");
                }
                Path path = Path.of(copy);
                if (!Files.exists(path)) {
                    record.put("status", "missing");
                    ++failed;
                    entries.add(record);
                    continue;
                }
                long bytes = Files.size(path);
                shredFile(path);
                String at = Instant.now().toString();
                record.put("status", "shredded");
                record.put("bytes", bytes);
                record.put("method", "single-pass random overwrite + truncate + delete");
                record.put("shredded_at", at);
                entry.put("shredded", true);
                entry.put("shredded_at", at);
                entry.put("shred_method", record.get("method"));
                markQuarantineMetaShredded(entry, at);
                ++shredded;
            } catch (Exception ex) {
                record.put("status", "failed");
                record.put("error", ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage());
                entry.put("shredded", false);
                ++failed;
            }
            entries.add(record);
        }
        out.put("files_shredded", shredded);
        out.put("files_failed", failed);
        out.put("entries", entries);
        out.put("notice", shredded == 0 && failed == 0
                ? "本次扫描没有产生需要粉碎的隔离副本；原始文件未被改动。"
                : "已粉碎本次扫描产生的隔离副本；原始文件从未被修改。SSD 上的覆盖写不保证物理不可恢复。");
        return out;
    }

    private static void shredFile(Path path) throws Exception {
        try {
            Files.setAttribute(path, "dos:readonly", false);
        } catch (Exception ignored) {
        }
        long size = Files.size(path);
        byte[] buffer = new byte[64 * 1024];
        java.security.SecureRandom random = new java.security.SecureRandom();
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                path, StandardOpenOption.WRITE)) {
            long remaining = size;
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                random.nextBytes(buffer);
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(buffer, 0, chunk);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                remaining -= chunk;
            }
            channel.force(true);
            channel.truncate(0);
            channel.force(true);
        }
        Files.delete(path);
    }

    private static void markQuarantineMetaShredded(Map<String, Object> entry, String at) {
        try {
            String metaPath = Json.str(entry, "meta_path", "");
            if (metaPath.isBlank()) {
                return;
            }
            Path meta = Path.of(metaPath);
            if (!Files.exists(meta)) {
                return;
            }
            Map<String, Object> document = Json.asObject(Json.parse(
                    Files.readString(meta, StandardCharsets.UTF_8)));
            document.put("shredded", true);
            document.put("shredded_at", at);
            document.put("shred_method", "single-pass random overwrite + truncate + delete");
            document.put("original_untouched", true);
            Files.writeString(meta, Json.write(document), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String buildMarkdown(Map<String, Object> document,
                                        List<Map<String, Object>> files,
                                        List<Map<String, Object>> manifests,
                                        List<Map<String, Object>> quarantined,
                                        Map<String, Object> aggregate) {
        StringBuilder md = new StringBuilder();
        md.append("# CodeForge 代码检测数据报告\n\n");
        md.append("- 报告编号：`").append(document.get("report_id")).append("`\n");
        md.append("- 生成时间：").append(document.get("generated_local")).append('\n');
        md.append("- 目标：`").append(document.get("target")).append("`\n");
        md.append("- 类型：").append(document.get("target_type")).append('\n');
        md.append("- 隔离模式：纯静态检测（未执行任何样本）\n\n");
        md.append("> 静态启发式报告不是杀毒结论。代码文件只被按字节读取，")
                .append("绝不在宿主上执行。扫描完成后会逐个重算 SHA-256，证明源文件未被修改。\n\n");

        md.append("## 总览\n\n");
        md.append("| 指标 | 数值 |\n|---|---|\n");
        md.append("| 文件数 | ").append(aggregate.get("files")).append(" |\n");
        md.append("| 总字节数 | ").append(aggregate.get("total_bytes")).append(" |\n");
        md.append("| 总行数 | ").append(aggregate.get("total_lines")).append(" |\n");
        Map<String, Object> riskSummary = Json.asObject(aggregate.get("risk"));
        md.append("| 最高风险分 | ").append(riskSummary.get("max_score")).append(" / 100 |\n");
        md.append("| 高风险 / 谨慎 / 留意 / 低风险 | ")
                .append(riskSummary.get("high")).append(" / ")
                .append(riskSummary.get("caution")).append(" / ")
                .append(riskSummary.get("notice")).append(" / ")
                .append(riskSummary.get("low")).append(" |\n\n");
        md.append("### 语言分布\n\n| 语言 | 文件 | 行数 | 字节 |\n|---|---:|---:|---:|\n");
        for (Object raw : Json.asList(aggregate.get("languages"))) {
            Map<String, Object> item = Json.asObject(raw);
            md.append("| ").append(item.get("language")).append(" | ")
                    .append(item.get("files")).append(" | ")
                    .append(item.get("lines")).append(" | ")
                    .append(item.get("bytes")).append(" |\n");
        }
        md.append('\n');
        if (!Json.asList(riskSummary.get("high_risk_files")).isEmpty()) {
            md.append("### 高风险文件\n\n");
            for (Object raw : Json.asList(riskSummary.get("high_risk_files"))) {
                Map<String, Object> item = Json.asObject(raw);
                md.append("- `").append(item.get("path")).append("` · ")
                        .append(item.get("score")).append("/100 · ")
                        .append(item.get("summary")).append('\n');
            }
            md.append('\n');
        }

        Map<String, Object> integrity = Json.asObject(document.get("integrity"));
        md.append("### 完整性校验\n\n| 指标 | 数值 |\n|---|---|\n");
        md.append("| 扫描后重算 SHA-256 | ").append(integrity.get("files_checked")).append(" |\n");
        md.append("| 跳过（预算/不可读） | ").append(integrity.get("files_skipped")).append(" |\n");
        md.append("| 内容变化 | ").append(integrity.get("files_changed")).append(" |\n");
        md.append("| 读取失败 | ").append(integrity.get("files_unreadable")).append(" |\n");
        md.append("| 字节未变化 | ").append(Json.bool(integrity, "unchanged", false) ? "是" : "否").append(" |\n");
        md.append("| 整批校验通过 | ").append(Json.bool(integrity, "content_verified", false) ? "是" : "否").append(" |\n");
        md.append("| 集合摘要 SHA-256 | `").append(integrity.get("digest")).append("` |\n\n");
        for (Object raw : Json.asList(integrity.get("changed"))) {
            Map<String, Object> item = Json.asObject(raw);
            md.append("- 内容变化：`").append(item.get("path")).append("` · ")
                    .append(item.get("sha256_before")).append(" → ")
                    .append(item.get("sha256_after")).append('\n');
        }
        for (Object raw : Json.asList(integrity.get("unreadable"))) {
            Map<String, Object> item = Json.asObject(raw);
            md.append("- 读取失败：`").append(item.get("path")).append("` · ")
                    .append(item.get("error")).append('\n');
        }
        md.append('\n');

        Map<String, Object> shred = Json.asObject(document.get("shred"));
        md.append("### 隔离副本粉碎\n\n");
        if (Json.bool(shred, "requested", false)) {
            md.append("- 已请求：是；执行：")
                    .append(Json.bool(shred, "performed", false) ? "是" : "否")
                    .append("；粉碎 ").append(shred.get("files_shredded"))
                    .append(" 个，失败 ").append(shred.get("files_failed")).append(" 个。\n");
            for (Object raw : Json.asList(shred.get("entries"))) {
                Map<String, Object> item = Json.asObject(raw);
                md.append("- `").append(item.get("path")).append("` · ")
                        .append(item.get("status"));
                if (item.get("error") != null) {
                    md.append(" · ").append(item.get("error"));
                }
                md.append('\n');
            }
            md.append("- 原始文件从未被删除或修改。\n\n");
        } else {
            md.append("- 未请求粉碎，隔离副本保持只读保留。原始文件从未被修改。\n\n");
        }

        md.append("## 文件详情\n\n");
        int index = 0;
        for (Map<String, Object> file : files) {
            ++index;
            md.append("### ").append(index).append(". `")
                    .append(file.get("path")).append("`\n\n");
            if (file.containsKey("error")) {
                md.append("- 检测错误：").append(file.get("error")).append("\n\n");
                continue;
            }
            md.append("| 属性 | 值 |\n|---|---|\n");
            row(md, "语言", file.get("language"));
            row(md, "语言族", file.get("language_family"));
            row(md, "MIME", file.get("mime"));
            row(md, "大小（字节）", file.get("size_bytes"));
            row(md, "修改时间", file.get("modified_local"));
            row(md, "编码", file.get("encoding"));
            row(md, "编码置信度", file.get("encoding_confidence"));
            row(md, "熵", file.get("entropy"));
            row(md, "二进制", Json.asObject(file.get("binary")).get("binary"));
            row(md, "文件类型", Json.asObject(file.get("binary")).get("type"));
            Map<String, Object> metrics = Json.asObject(file.get("metrics"));
            row(md, "总行数", metrics.get("lines"));
            row(md, "空行", metrics.get("blank_lines"));
            row(md, "注释行（近似）", metrics.get("comment_lines_approx"));
            row(md, "代码行（近似）", metrics.get("code_lines_approx"));
            row(md, "注释比例（%）", metrics.get("comment_ratio"));
            row(md, "最长行", metrics.get("max_line_length"));
            row(md, "平均行长", metrics.get("average_line_length"));
            row(md, "行尾", Json.asObject(metrics.get("line_endings")).get("style"));
            row(md, "最大嵌套深度（近似）", metrics.get("max_nesting_depth_approx"));
            row(md, "圈复杂度（近似）", metrics.get("cyclomatic_complexity_approx"));
            row(md, "TODO/FIXME", metrics.get("todo_markers"));
            md.append('\n');

            Map<String, Object> hashes = Json.asObject(file.get("hashes"));
            md.append("**哈希**\n\n");
            md.append("- SHA-256：`").append(hashes.get("sha256")).append("`\n");
            md.append("- SHA-1：`").append(hashes.get("sha1")).append("`\n");
            md.append("- MD5：`").append(hashes.get("md5")).append("`\n\n");

            Map<String, Object> structure = Json.asObject(file.get("structure"));
            md.append("**结构**\n\n");
            md.append("- 包/命名空间：").append(str(structure.get("package"))).append('\n');
            md.append("- 类/结构数量：").append(structure.get("class_count")).append('\n');
            md.append("- 函数数量（近似）：").append(structure.get("function_count")).append('\n');
            List<Object> classes = Json.asList(structure.get("classes"));
            if (!classes.isEmpty()) {
                md.append("- 类型定义：");
                int shown = 0;
                for (Object raw : classes) {
                    if (shown++ >= 30) {
                        md.append("…");
                        break;
                    }
                    Map<String, Object> item = Json.asObject(raw);
                    md.append(" `").append(item.get("name")).append("`(L")
                            .append(item.get("line")).append(")");
                }
                md.append('\n');
            }
            List<Object> functions = Json.asList(structure.get("functions"));
            if (!functions.isEmpty()) {
                md.append("- 函数定义（前 40）：");
                int shown = 0;
                for (Object raw : functions) {
                    if (shown++ >= 40) {
                        md.append("…");
                        break;
                    }
                    Map<String, Object> item = Json.asObject(raw);
                    md.append(" `").append(item.get("name")).append("`(L")
                            .append(item.get("line")).append(")");
                }
                md.append('\n');
            }
            List<Object> entries = Json.asList(structure.get("entry_points"));
            if (!entries.isEmpty()) {
                md.append("- 入口：").append(String.join(", ", toStringList(entries)))
                        .append('\n');
            }
            List<Object> imports = Json.asList(structure.get("imports"));
            md.append("- 导入数：").append(imports.size()).append('\n');
            if (!imports.isEmpty()) {
                md.append("- 导入样例：");
                int shown = 0;
                for (Object importItem : imports) {
                    if (shown++ >= 25) {
                        md.append("…");
                        break;
                    }
                    md.append(" `").append(importItem).append("`");
                }
                md.append('\n');
            }
            md.append('\n');

            Map<String, Object> network = Json.asObject(file.get("network"));
            List<Object> urls = Json.asList(network.get("urls"));
            List<Object> ips = Json.asList(network.get("ipv4"));
            if (!urls.isEmpty() || !ips.isEmpty()) {
                md.append("**网络线索**\n\n");
                if (!urls.isEmpty()) {
                    md.append("- URL：");
                    int shown = 0;
                    for (Object url : urls) {
                        if (shown++ >= 20) {
                            md.append(" …");
                            break;
                        }
                        md.append(" `").append(url).append("`");
                    }
                    md.append('\n');
                }
                if (!ips.isEmpty()) {
                    md.append("- IPv4：");
                    int shown = 0;
                    for (Object ip : ips) {
                        if (shown++ >= 20) {
                            md.append(" …");
                            break;
                        }
                        md.append(" `").append(ip).append("`");
                    }
                    md.append('\n');
                }
                md.append('\n');
            }

            List<Object> secretFindings = Json.asList(file.get("secrets"));
            md.append("**密钥与凭据（值已掩码）**\n\n");
            if (secretFindings.isEmpty()) {
                md.append("- 未发现明显的硬编码密钥格式。\n\n");
            } else {
                for (Object raw : secretFindings) {
                    Map<String, Object> item = Json.asObject(raw);
                    md.append("- L").append(item.get("line")).append(" · ")
                            .append(item.get("type")).append(" · `")
                            .append(item.get("value_masked")).append("`\n");
                }
                md.append('\n');
            }

            Map<String, Object> risk = Json.asObject(file.get("risk"));
            md.append("**风险评分：" ).append(risk.get("score")).append("/100 · ")
                    .append(risk.get("label")).append("**\n\n");
            md.append(risk.get("summary")).append("\n\n");
            List<Object> indicators = Json.asList(risk.get("indicators"));
            if (!indicators.isEmpty()) {
                md.append("| 严重度 | 类别 | 特征 | 次数 | 行 | 分值 | 建议 |\n")
                        .append("|---|---|---|---:|---:|---:|---|\n");
                for (Object raw : indicators) {
                    Map<String, Object> item = Json.asObject(raw);
                    md.append("| ").append(item.get("severity")).append(" | ")
                            .append(item.get("category")).append(" | ")
                            .append(escapeCell(str(item.get("title")))).append(" | ")
                            .append(item.get("count")).append(" | ")
                            .append(item.get("line")).append(" | ")
                            .append(item.get("points")).append(" | ")
                            .append(escapeCell(str(item.get("detail")))).append(" |\n");
                }
                md.append('\n');
            }
            Map<String, Object> quarantine = Json.asObject(file.get("quarantine"));
            if (Json.bool(quarantine, "copied", false)) {
                md.append("**隔离**\n\n- 已复制到只读隔离区：`")
                        .append(quarantine.get("quarantine_path")).append("`\n")
                        .append("- 未执行；原始文件保留原位。")
                        .append(Json.bool(quarantine, "shredded", false)
                                ? "隔离副本已按设置粉碎。" : "")
                        .append("\n\n");
            }
            String preview = str(file.get("preview"));
            if (!preview.isBlank()) {
                md.append("<details><summary>源码预览（已掩码敏感值，前 60 行）</summary>\n\n")
                        .append("```\n").append(preview).append("\n```\n\n</details>\n\n");
            }
        }

        if (!manifests.isEmpty()) {
            md.append("## 项目清单\n\n");
            for (Map<String, Object> manifest : manifests) {
                md.append("### `").append(manifest.get("path")).append("`\n\n");
                if (manifest.containsKey("error")) {
                    md.append("- 错误：").append(manifest.get("error")).append("\n\n");
                    continue;
                }
                md.append("- 依赖样例：");
                List<Object> deps = Json.asList(manifest.get("dependencies_sample"));
                if (deps.isEmpty()) {
                    md.append("无");
                } else {
                    int shown = 0;
                    for (Object dep : deps) {
                        if (shown++ >= 30) {
                            md.append(" …");
                            break;
                        }
                        md.append(" `").append(dep).append("`");
                    }
                }
                md.append('\n');
                Map<String, Object> manifestRisk = Json.asObject(manifest.get("risk"));
                if (!manifestRisk.isEmpty()) {
                    md.append("- 风险：").append(manifestRisk.get("summary")).append('\n');
                }
                md.append('\n');
            }
        }

        if (!quarantined.isEmpty()) {
            md.append("## 隔离区\n\n");
            for (Map<String, Object> item : quarantined) {
                md.append("- `").append(item.get("quarantine_path")).append("` ← `")
                        .append(item.get("original_path")).append("` · SHA-256 `")
                        .append(item.get("sha256")).append("`")
                        .append(Json.bool(item, "shredded", false) ? " · 已粉碎" : "")
                        .append('\n');
            }
            md.append('\n');
        }
        md.append("## 方法与限制\n\n");
        for (Object limitation : Json.asList(document.get("limitations"))) {
            md.append("- ").append(limitation).append('\n');
        }
        md.append('\n');
        return md.toString();
    }

    private static void row(StringBuilder md, String key, Object value) {
        md.append("| ").append(key).append(" | ")
                .append(escapeCell(str(value))).append(" |\n");
    }

    private static String escapeCell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> toStringList(List<Object> values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static String preview(String text, Lang lang) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String line : lines) {
            if (shown >= 60 || out.length() > 8000) {
                break;
            }
            out.append(redact(MemoryStore.trim(line, 400))).append('\n');
            ++shown;
        }
        return out.toString();
    }

    private static String redact(String text) {
        String out = SECRET_KNOWN.matcher(text).replaceAll("[redacted-token]");
        out = SECRET_ASSIGN.matcher(out).replaceAll(match ->
                match.group().replace(match.group(1), "***"));
        out = PRIVATE_KEY.matcher(out).replaceAll("[redacted-private-key]");
        return out;
    }

    // ------------------------------------------------------------------
    // Encoding and binary helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> hashes(Path path) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] algorithms = {"SHA-256", "SHA-1", "MD5"};
        String[] keys = {"sha256", "sha1", "md5"};
        try {
            MessageDigest[] digests = new MessageDigest[algorithms.length];
            for (int i = 0; i < algorithms.length; ++i) {
                digests[i] = MessageDigest.getInstance(algorithms[i]);
            }
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    for (MessageDigest digest : digests) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            for (int i = 0; i < digests.length; ++i) {
                out.put(keys[i], hex(digests[i].digest()));
            }
            out.put("complete", true);
        } catch (Exception ex) {
            for (String key : keys) {
                out.put(key, "");
            }
            out.put("error", ex.getMessage());
            out.put("complete", false);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value));
        }
        return out.toString();
    }

    private static byte[] readHead(Path path, int max) throws Exception {
        long size = Files.size(path);
        int capacity = (int) Math.min(size, max);
        byte[] buffer = new byte[capacity];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int offset = 0;
            while (offset < capacity) {
                int read = in.read(buffer, offset, capacity - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset < capacity) {
                byte[] shorter = new byte[offset];
                System.arraycopy(buffer, 0, shorter, 0, offset);
                return shorter;
            }
        }
        return buffer;
    }

    private static Decoded decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8),
                    "UTF-8 (BOM)", 100, false);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            return new Decoded(new String(bytes, 2, bytes.length - 2,
                    StandardCharsets.UTF_16LE), "UTF-16LE (BOM)", 100, false);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            return new Decoded(new String(bytes, 2, bytes.length - 2,
                    StandardCharsets.UTF_16BE), "UTF-16BE (BOM)", 100, false);
        }
        int nul = 0;
        int controls = 0;
        for (byte value : bytes) {
            int b = value & 0xFF;
            if (b == 0) {
                ++nul;
            } else if (b < 9 || (b > 13 && b < 32)) {
                ++controls;
            }
        }
        double controlRatio = bytes.length == 0 ? 1.0
                : (double) (nul + controls) / bytes.length;
        if (bytes.length > 0 && (nul > 0 || controlRatio > 0.08)) {
            return new Decoded("", "binary", 0, true);
        }
        try {
            CharsetDecoderHelper helper = new CharsetDecoderHelper(StandardCharsets.UTF_8);
            String text = helper.decode(bytes);
            return new Decoded(text, "UTF-8", 95, false);
        } catch (CharacterCodingException ignored) {
        }
        try {
            Charset gb = Charset.forName("GB18030");
            CharsetDecoderHelper helper = new CharsetDecoderHelper(gb);
            String text = helper.decode(bytes);
            return new Decoded(text, "GB18030", 65, false);
        } catch (Exception ignored) {
        }
        return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1),
                "ISO-8859-1 (fallback)", 35, false);
    }

    private static double entropy(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int[] counts = new int[256];
        for (byte value : bytes) {
            ++counts[value & 0xFF];
        }
        double entropy = 0;
        for (int count : counts) {
            if (count == 0) {
                continue;
            }
            double p = (double) count / bytes.length;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private static BinaryInfo binaryInfo(byte[] bytes) {
        BinaryInfo out = new BinaryInfo();
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 'M' && (bytes[1] & 0xFF) == 'Z') {
            out.kind = "PE";
            out.type = "PE/DOS executable";
            if (bytes.length > 0x40) {
                int peOffset = (bytes[0x3C] & 0xFF) | ((bytes[0x3D] & 0xFF) << 8)
                        | ((bytes[0x3E] & 0xFF) << 16) | ((bytes[0x3F] & 0xFF) << 24);
                if (peOffset > 0 && peOffset + 24 < bytes.length
                        && bytes[peOffset] == 'P' && bytes[peOffset + 1] == 'E') {
                    int machine = (bytes[peOffset + 4] & 0xFF)
                            | ((bytes[peOffset + 5] & 0xFF) << 8);
                    out.machine = switch (machine) {
                        case 0x14c -> "x86 (I386)";
                        case 0x8664 -> "x64 (AMD64)";
                        case 0xaa64 -> "ARM64";
                        case 0x1c4 -> "ARM";
                        case 0x1c0 -> "ARM (Thumb)";
                        default -> "unknown (0x" + Integer.toHexString(machine) + ")";
                    };
                    out.type = "PE executable · " + out.machine;
                }
            }
        } else if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x7F
                && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
            out.kind = "ELF";
            out.type = "ELF executable";
        } else if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x50
                && (bytes[1] & 0xFF) == 0x4B && (bytes[2] & 0xFF) == 3
                && (bytes[3] & 0xFF) == 4) {
            out.kind = "ZIP";
            out.type = "ZIP archive (may be JAR/Office/APK)";
        } else if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x4D
                && (bytes[1] & 0xFF) == 0x5A) {
            out.kind = "PE";
        } else if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x7F
                && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
            out.kind = "ELF";
        } else if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0xCA
                && (bytes[1] & 0xFF) == 0xFE && (bytes[2] & 0xFF) == 0xBA
                && (bytes[3] & 0xFF) == 0xBE) {
            out.kind = "Mach-O";
            out.type = "Mach-O universal binary";
        }
        int nul = 0;
        int controls = 0;
        for (byte value : bytes) {
            int b = value & 0xFF;
            if (b == 0) {
                ++nul;
            } else if (b < 9 || (b > 13 && b < 32)) {
                ++controls;
            }
        }
        out.nulBytes = nul;
        out.controlBytes = controls;
        out.binary = bytes.length > 0 && (nul > 0 || (double) controls / bytes.length > 0.08);
        if (out.type.isBlank()) {
            out.type = out.binary ? "binary data" : "text";
        }
        return out;
    }

    private static String extractStrings(byte[] bytes, int limit) {
        StringBuilder out = new StringBuilder();
        StringBuilder current = new StringBuilder();
        int found = 0;
        for (byte value : bytes) {
            int b = value & 0xFF;
            if (b >= 32 && b <= 126) {
                if (current.length() < 500) {
                    current.append((char) b);
                }
            } else {
                if (current.length() >= 5) {
                    out.append(current).append('\n');
                    if (++found >= limit) {
                        break;
                    }
                }
                current.setLength(0);
            }
        }
        if (found < limit && current.length() >= 5) {
            out.append(current).append('\n');
        }
        return out.toString();
    }

    private static Lang detectLanguage(Path path, byte[] head) {
        String name = path.getFileName().toString();
        Lang byName = NAMES.get(name);
        if (byName != null) {
            return byName;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0) {
            Lang lang = EXT.get(lower.substring(dot + 1));
            if (lang != null) {
                return lang;
            }
        }
        Lang bySpecial = NAMES.get(lower);
        if (bySpecial != null) {
            return bySpecial;
        }
        try {
            String first = new String(head, StandardCharsets.UTF_8);
            int newline = first.indexOf('\n');
            String shebang = (newline >= 0 ? first.substring(0, newline) : first).trim();
            if (shebang.startsWith("#!")) {
                String lowerShebang = shebang.toLowerCase(Locale.ROOT);
                if (lowerShebang.contains("python")) {
                    return new Lang("Python", "hash", true);
                }
                if (lowerShebang.contains("node")) {
                    return new Lang("JavaScript", "c", true);
                }
                if (lowerShebang.contains("bash") || lowerShebang.contains("/sh")) {
                    return new Lang("Shell", "hash", true);
                }
                if (lowerShebang.contains("perl")) {
                    return new Lang("Perl", "hash", true);
                }
                return new Lang("Script", "hash", true);
            }
        } catch (Exception ignored) {
        }
        return new Lang("Unknown", "c", false);
    }

    private static String mime(Lang lang, Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".xml") || lower.endsWith(".csproj")) {
            return "application/xml";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain; code=" + lang.name;
    }

    private static String extension(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        return dot >= 0 ? lowerName.substring(dot + 1) : "";
    }

    private static String readZoneIdentifier(Path path) {
        try {
            Path ads = Path.of(path.toString() + ":Zone.Identifier");
            if (!Files.exists(ads)) {
                return "";
            }
            return Files.readString(ads, StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private Path resolve(String raw) {
        try {
            Path path = Path.of(raw);
            if (!path.isAbsolute()) {
                path = root.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return root.resolve(raw).toAbsolutePath().normalize();
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while (true) {
            int at = text.indexOf(needle, index);
            if (at < 0 || count >= 1_000_000) {
                break;
            }
            ++count;
            index = at + needle.length();
        }
        return count;
    }

    private static String trim(String text, int max) {
        return MemoryStore.trim(text, max);
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    private record Lang(String name, String family, boolean code) {
    }

    private record Decoded(String text, String charset, int confidence, boolean binary) {
    }

    private static final class BinaryInfo {
        boolean binary;
        String kind = "";
        String type = "";
        String machine = "";
        int nulBytes;
        int controlBytes;

        Map<String, Object> toMap(long size) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("binary", binary);
            out.put("kind", kind);
            out.put("type", type);
            out.put("machine", machine);
            out.put("nul_bytes", nulBytes);
            out.put("control_bytes", controlBytes);
            out.put("size_bytes", size);
            return out;
        }
    }

    private record Rule(String id, String severity, String category, String title,
                        String detail, int points, Pattern pattern) {
    }

    private static final class CharsetDecoderHelper {
        private final Charset charset;

        CharsetDecoderHelper(Charset charset) {
            this.charset = charset;
        }

        String decode(byte[] bytes) throws CharacterCodingException {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        }
    }

    private static final Set<String> EXEC_EXTS = Set.of(
            "exe", "dll", "sys", "scr", "com", "pif", "cpl", "msi", "msp",
            "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf",
            "wsh", "hta", "reg", "lnk", "jar", "war", "apk", "app", "dmg",
            "elf", "so", "dylib", "sh", "bash", "zsh", "fish", "py", "pyw",
            "pl", "rb", "lua", "php", "asp", "aspx", "jsp", "cgi");

    static {
        put("c", "C", "c", true);
        put("h", "C/C++ header", "c", true);
        put("cpp", "C++", "c", true);
        put("cc", "C++", "c", true);
        put("cxx", "C++", "c", true);
        put("hpp", "C++ header", "c", true);
        put("hh", "C++ header", "c", true);
        put("hxx", "C++ header", "c", true);
        put("m", "Objective-C", "c", true);
        put("mm", "Objective-C++", "c", true);
        put("cs", "C#", "c", true);
        put("java", "Java", "c", true);
        put("kt", "Kotlin", "c", true);
        put("kts", "Kotlin", "c", true);
        put("scala", "Scala", "c", true);
        put("groovy", "Groovy", "c", true);
        put("swift", "Swift", "c", true);
        put("go", "Go", "c", true);
        put("rs", "Rust", "c", true);
        put("dart", "Dart", "c", true);
        put("py", "Python", "hash", true);
        put("pyw", "Python", "hash", true);
        put("pyi", "Python", "hash", true);
        put("js", "JavaScript", "c", true);
        put("mjs", "JavaScript", "c", true);
        put("cjs", "JavaScript", "c", true);
        put("jsx", "JavaScript (JSX)", "c", true);
        put("ts", "TypeScript", "c", true);
        put("tsx", "TypeScript (TSX)", "c", true);
        put("rb", "Ruby", "hash", true);
        put("rake", "Ruby", "hash", true);
        put("php", "PHP", "c", true);
        put("pl", "Perl", "hash", true);
        put("pm", "Perl", "hash", true);
        put("lua", "Lua", "lua", true);
        put("sh", "Shell", "hash", true);
        put("bash", "Shell", "hash", true);
        put("zsh", "Shell", "hash", true);
        put("fish", "Shell", "hash", true);
        put("ps1", "PowerShell", "hash", true);
        put("psm1", "PowerShell", "hash", true);
        put("psd1", "PowerShell", "hash", true);
        put("bat", "Batch", "batch", true);
        put("cmd", "Batch", "batch", true);
        put("vbs", "VBScript", "hash", true);
        put("vbe", "VBScript", "hash", true);
        put("wsf", "Windows Script", "hash", true);
        put("hta", "HTML Application", "html", true);
        put("sql", "SQL", "sql", true);
        put("html", "HTML", "html", true);
        put("htm", "HTML", "html", true);
        put("xhtml", "HTML", "html", true);
        put("vue", "Vue", "html", true);
        put("svelte", "Svelte", "html", true);
        put("astro", "Astro", "html", true);
        put("css", "CSS", "c", true);
        put("scss", "SCSS", "c", true);
        put("sass", "Sass", "c", true);
        put("less", "Less", "c", true);
        put("json", "JSON", "c", true);
        put("jsonc", "JSONC", "c", true);
        put("json5", "JSON5", "c", true);
        put("yaml", "YAML", "hash", true);
        put("yml", "YAML", "hash", true);
        put("toml", "TOML", "hash", true);
        put("xml", "XML", "html", true);
        put("ini", "INI", "hash", true);
        put("cfg", "Config", "hash", true);
        put("conf", "Config", "hash", true);
        put("properties", "Properties", "hash", true);
        put("env", "Env file", "hash", true);
        put("md", "Markdown", "hash", true);
        put("markdown", "Markdown", "hash", true);
        put("rst", "reStructuredText", "hash", true);
        put("tex", "LaTeX", "c", true);
        put("r", "R", "hash", true);
        put("rmd", "R Markdown", "hash", true);
        put("jl", "Julia", "hash", true);
        put("ex", "Elixir", "hash", true);
        put("exs", "Elixir", "hash", true);
        put("erl", "Erlang", "hash", true);
        put("hrl", "Erlang", "hash", true);
        put("hs", "Haskell", "hash", true);
        put("lhs", "Haskell", "hash", true);
        put("clj", "Clojure", "c", true);
        put("cljs", "ClojureScript", "c", true);
        put("asm", "Assembly", "c", true);
        put("s", "Assembly", "c", true);
        put("f", "Fortran", "c", true);
        put("f90", "Fortran", "c", true);
        put("f95", "Fortran", "c", true);
        put("pas", "Pascal", "c", true);
        put("pp", "Pascal", "c", true);
        put("vb", "Visual Basic", "c", true);
        put("vba", "VBA", "c", true);
        put("bas", "BASIC", "c", true);
        put("sol", "Solidity", "c", true);
        put("proto", "Protocol Buffers", "c", true);
        put("thrift", "Thrift", "c", true);
        put("gradle", "Gradle", "c", true);
        put("cmake", "CMake", "hash", true);
        put("mk", "Makefile", "hash", true);
        put("dockerfile", "Dockerfile", "hash", true);
        put("tf", "Terraform", "hash", true);
        put("tfvars", "Terraform", "hash", true);
        put("hcl", "HCL", "hash", true);
        put("ipynb", "Jupyter Notebook", "c", true);
        put("pem", "PEM", "hash", true);
        put("key", "Key file", "hash", true);
        put("crt", "Certificate", "hash", true);
        put("cer", "Certificate", "hash", true);
        put("csr", "Certificate request", "hash", true);

        name("Makefile", "Makefile", "hash", true);
        name("makefile", "Makefile", "hash", true);
        name("Dockerfile", "Dockerfile", "hash", true);
        name("CMakeLists.txt", "CMake", "hash", true);
        name("Rakefile", "Ruby", "hash", true);
        name("Gemfile", "Ruby", "hash", true);
        name("Jenkinsfile", "Groovy", "c", true);
        name("Vagrantfile", "Ruby", "hash", true);
        name("Procfile", "Procfile", "hash", true);
        name(".gitignore", "Git config", "hash", false);
        name(".dockerignore", "Docker config", "hash", false);
        name(".editorconfig", "Editor config", "hash", false);
        name("package.json", "JSON", "c", true);
        name("tsconfig.json", "JSON", "c", true);
        name("requirements.txt", "Requirements", "hash", true);
        name("pyproject.toml", "TOML", "hash", true);
        name("pom.xml", "XML", "html", true);
        name("build.gradle", "Gradle", "c", true);
        name("go.mod", "Go module", "c", true);
        name("Cargo.toml", "TOML", "hash", true);
        name("composer.json", "JSON", "c", true);
    }

    private static void put(String ext, String language, String family, boolean code) {
        EXT.put(ext, new Lang(language, family, code));
    }

    private static void name(String fileName, String language, String family, boolean code) {
        NAMES.put(fileName, new Lang(language, family, code));
    }
}