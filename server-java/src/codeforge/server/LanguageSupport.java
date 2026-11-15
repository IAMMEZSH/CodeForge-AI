package codeforge.server;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language registry shared by the console API, toolchain provisioning and the
 * multi-language runner. The C++ core owns Python, C, C++ and Java; every other
 * runtime is executed by {@link ExtendedRunner}.
 */
public final class LanguageSupport {
    public static final class Language {
        public final String id;
        public final String name;
        public final String fence;
        public final String extension;
        public final String runtime;
        public final List<String> aliases;
        public final boolean core;
        public final boolean preview;

        Language(String id, String name, String fence, String extension, String runtime,
                 List<String> aliases, boolean core, boolean preview) {
            this.id = id;
            this.name = name;
            this.fence = fence;
            this.extension = extension;
            this.runtime = runtime;
            this.aliases = aliases;
            this.core = core;
            this.preview = preview;
        }

        public boolean executable() {
            return !"browser".equals(runtime);
        }

        public Map<String, Object> describe() {
            return Json.map(
                    "id", id,
                    "name", name,
                    "fence", fence,
                    "extension", extension,
                    "runtime", runtime,
                    "core", core,
                    "executable", executable(),
                    "preview", preview);
        }
    }

    private static final List<Language> ALL = List.of(
            new Language("python", "Python", "python", ".py", "python",
                    List.of("python", "python3", "python 3", "py"), true, false),
            new Language("c", "C", "c", ".c", "gcc",
                    List.of("c", "c17", "c11", "c99"), true, false),
            new Language("cpp", "C++", "cpp", ".cpp", "gcc",
                    List.of("cpp", "c++", "cxx", "cc", "c++17", "c++20"), true, false),
            new Language("java", "Java", "java", ".java", "java",
                    List.of("java", "java17", "java 17", "java21", "java 21"), true, false),
            new Language("javascript", "JavaScript", "javascript", ".js", "node",
                    List.of("javascript", "js", "node", "nodejs", "node.js", "ecmascript"), false, false),
            new Language("typescript", "TypeScript", "typescript", ".ts", "node",
                    List.of("typescript", "ts"), false, false),
            new Language("go", "Go", "go", ".go", "go",
                    List.of("go", "golang"), false, false),
            new Language("rust", "Rust", "rust", ".rs", "rust",
                    List.of("rust", "rustlang"), false, false),
            new Language("php", "PHP", "php", ".php", "php",
                    List.of("php"), false, false),
            new Language("csharp", "C#", "csharp", ".cs", "dotnet",
                    List.of("csharp", "c#", "c-sharp", "cs", "dotnet", ".net", "net", "netcore"), false, false),
            new Language("html", "HTML", "html", ".html", "browser",
                    List.of("html", "html5", "htm", "xhtml"), false, true),
            new Language("css", "CSS", "css", ".css", "browser",
                    List.of("css", "css3", "stylesheet"), false, true));

    private LanguageSupport() {
    }

    public static List<Language> all() {
        return ALL;
    }

    public static Language byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Language language : ALL) {
            if (language.id.equals(wanted)) {
                return language;
            }
        }
        return byToken(wanted);
    }

    public static Language byToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("\\s+", " ");
        if (value.isEmpty()) {
            return null;
        }
        for (Language language : ALL) {
            for (String alias : language.aliases) {
                if (alias.equals(value)) {
                    return language;
                }
            }
        }
        return null;
    }

    public static Language fromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        String lower = filename.trim().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String extension = lower.substring(dot);
        for (Language language : ALL) {
            if (language.extension.equals(extension)) {
                return language;
            }
        }
        return null;
    }

    /** Normalizes a fenced tag, explicit language field or filename to a language id. */
    public static Language normalize(String requested, String filename) {
        String value = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        int space = value.indexOf(' ');
        if (space > 0) {
            value = value.substring(0, space);
        }
        if (value.startsWith("{") || value.startsWith(".")) {
            value = value.replace("{", "").replace("}", "");
            if (value.startsWith(".") && !value.equals(".net")) {
                value = value.substring(1);
            }
        }
        if (!value.isEmpty() && !value.equals("auto")) {
            Language direct = byToken(value);
            if (direct != null) {
                return direct;
            }
        }
        return fromFilename(filename);
    }

    public static boolean isCore(Language language) {
        return language != null && language.core;
    }

    private static final class Hit {
        final Language language;
        final int start;
        final int end;
        final boolean weak;

        Hit(Language language, int start, int end, boolean weak) {
            this.language = language;
            this.start = start;
            this.end = end;
            this.weak = weak;
        }
    }

    private static void collect(List<Hit> hits, Pattern pattern, Language language,
                                String text, boolean weak) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            hits.add(new Hit(language, matcher.start(), matcher.end(), weak));
        }
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("(?i)(?<![a-z0-9_])(?:" + alternatives + ")(?![a-z0-9_])");
    }

    private static final Pattern PYTHON = word("python3?|py");
    private static final Pattern JAVA = word("java(?:\\s*(?:17|21|8))?");
    private static final Pattern CPP = word("c\\+\\+|cpp|cxx|cc");
    private static final Pattern CSHARP = word("c\\#|csharp|c-sharp|dotnet|\\.net|netcore");
    private static final Pattern JAVASCRIPT = word("javascript|node\\.?js|nodejs|js");
    private static final Pattern TYPESCRIPT = word("typescript|ts");
    private static final Pattern RUST = word("rust|rustlang");
    private static final Pattern PHP = word("php");
    private static final Pattern HTML = word("html5?|htm|xhtml");
    private static final Pattern CSS = word("css3?|stylesheet");
    private static final Pattern GO_STRONG = word("golang|go\\s*语言|go\\s*(?:1\\.\\d+|code|program|程序|代码|版本|实现|编写)");
    private static final Pattern GO_WEAK = word("go");
    private static final Pattern C_STRONG = word("c\\s*语言|c17|c11|c99|c\\s*(?:code|program|程序|代码)");
    private static final Pattern C_CUE = Pattern.compile(
            "(?i)(?:用|使用|以|改用|in|using|with|write(?:\\s+it)?\\s+in|code\\s+in)\\s*c(?![a-z0-9_#+])");

    private static final Pattern TARGET_PREFIX = Pattern.compile(
            "(?i)(?:用|使用|以|改用|换成|改成|重写|重写为|翻译成|译为|转换为|转换成|实现|编写|写|生成|做|需要|要|"
                    + "in|using|with|write(?:\\s+it)?\\s+in|rewrite(?:\\s+it)?\\s+in|convert(?:\\s+it)?\\s+to|"
                    + "translate(?:\\s+it)?\\s+to|implement(?:\\s+it)?\\s+in|generate|develop|create|code\\s+in|program\\s+in)"
                    + "\\s*$");
    private static final Pattern TARGET_SUFFIX = Pattern.compile(
            "(?i)^\\s*(?:写|编写|实现|代码|程序|版本|语言|code|program|version|implementation|script|rewrite|please)");
    private static final Pattern GENERATION_INTENT = Pattern.compile(
            "(?i)(?:写|编写|实现|生成|做个|做一个|做一|开发|修复|调试|重构|优化|代码|程序|脚本|示例|例子|函数|类|算法|游戏|"
                    + "write|implement|generate|create|build|fix|debug|refactor|optimi[sz]e|code|program|script|"
                    + "snippet|example|function|class|algorithm|game|app)");

    /**
     * Finds languages the user explicitly asked to write in, in mention order.
     * A bare language name only counts when the message also carries a coding
     * intent, so questions like "What is Python?" never trigger code generation
     * or a toolchain download.
     */
    public static List<Language> detectRequested(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        collect(hits, PYTHON, byId("python"), text, false);
        collect(hits, JAVA, byId("java"), text, false);
        collect(hits, CPP, byId("cpp"), text, false);
        collect(hits, CSHARP, byId("csharp"), text, false);
        collect(hits, JAVASCRIPT, byId("javascript"), text, false);
        collect(hits, TYPESCRIPT, byId("typescript"), text, true);
        collect(hits, RUST, byId("rust"), text, false);
        collect(hits, PHP, byId("php"), text, false);
        collect(hits, HTML, byId("html"), text, false);
        collect(hits, CSS, byId("css"), text, false);
        collect(hits, GO_STRONG, byId("go"), text, false);
        collect(hits, GO_WEAK, byId("go"), text, true);
        collect(hits, C_STRONG, byId("c"), text, false);
        collect(hits, C_CUE, byId("c"), text, false);
        if (hits.isEmpty()) {
            return List.of();
        }
        hits.sort((a, b) -> {
            if (a.start != b.start) {
                return Integer.compare(a.start, b.start);
            }
            return Integer.compare(b.end, a.end);
        });
        List<Hit> filtered = new ArrayList<>();
        int lastEnd = -1;
        for (Hit hit : hits) {
            if (hit.start >= lastEnd) {
                filtered.add(hit);
                lastEnd = hit.end;
            }
        }
        Set<Language> targeted = new LinkedHashSet<>();
        for (Hit hit : filtered) {
            String prefix = text.substring(Math.max(0, hit.start - 18), hit.start);
            String suffix = text.substring(hit.end, Math.min(text.length(), hit.end + 14));
            if (TARGET_PREFIX.matcher(prefix).find() || TARGET_SUFFIX.matcher(suffix).find()) {
                targeted.add(hit.language);
            }
        }
        if (!targeted.isEmpty()) {
            return new ArrayList<>(targeted);
        }
        if (!GENERATION_INTENT.matcher(text).find()) {
            return List.of();
        }
        for (int i = filtered.size() - 1; i >= 0; --i) {
            if (!filtered.get(i).weak) {
                return List.of(filtered.get(i).language);
            }
        }
        return List.of(filtered.get(filtered.size() - 1).language);
    }

    /** Prompt fragment that keeps generated code in the language the user asked for. */
    public static String directive(Language language) {
        if (language == null) {
            return "";
        }
        if ("html".equals(language.id)) {
            return "LANGUAGE DIRECTIVE: the user asked for HTML. Reply with one complete, self-contained "
                    + "HTML5 document inside a single ```html block. Keep CSS in a <style> element and "
                    + "JavaScript in a <script> element so the console can preview it directly. "
                    + "You may add a separate ```css block for clarity.\n";
        }
        if ("css".equals(language.id)) {
            return "LANGUAGE DIRECTIVE: the user asked for CSS. Reply with one complete stylesheet in a "
                    + "single ```css block and, when useful, a small ```html block that demonstrates it. "
                    + "The console renders both together in a live preview.\n";
        }
        if ("csharp".equals(language.id)) {
            return "LANGUAGE DIRECTIVE: the user asked for C#. Reply with one complete console program in a "
                    + "single ```csharp block (top-level statements or a Program class with Main) so it can "
                    + "be compiled and executed by the console.\n";
        }
        if (language.executable()) {
            return "LANGUAGE DIRECTIVE: the user asked for " + language.name + ". Reply with one complete, "
                    + "runnable " + language.name + " program inside a single ```" + language.fence
                    + " block, including every import and the program entry point. The console compiles "
                    + "and runs it automatically, so do not leave placeholders.\n";
        }
        return "";
    }
}
