package codeforge.server;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.util.Units;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Universal office document engine: Word, Excel, PowerPoint (OOXML and the
 * classic binary formats), PDF, CSV, Markdown and plain text. It renders
 * live previews, exposes a structured view for editing, creates new files,
 * updates existing ones, moves deletions to a trash folder, and renders PDF
 * pages as images for the console.
 */
public final class OfficeService {
    public static final List<String> DOC_KINDS = List.of(
            "docx", "doc", "xlsx", "xls", "xlsm", "pptx", "ppt", "pdf",
            "csv", "tsv", "txt", "md", "markdown", "json", "log",
            "odt", "ods", "odp", "rtf");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final int PREVIEW_ROWS = 250;
    private static final int PREVIEW_COLS = 40;
    private static final int PREVIEW_CHARS = 400_000;

    private final Path root;
    private final Map<String, byte[]> pdfCache = new ConcurrentHashMap<>();

    public OfficeService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean supported(String path) {
        return DOC_KINDS.contains(extension(path));
    }

    public Path pathFor(String rawPath) {
        return resolve(rawPath);
    }

    public Map<String, Object> open(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        try {
            Path path = resolve(Json.str(request, "path", ""));
            if (!Files.exists(path)) {
                return Json.map("ok", false, "error", "File not found: " + path);
            }
            if (Files.isDirectory(path)) {
                return Json.map("ok", false, "error", "That is a folder: " + path);
            }
            String kind = extension(path.getFileName().toString());
            if (!DOC_KINDS.contains(kind)) {
                return Json.map("ok", false,
                        "error", "Unsupported document type: ." + kind,
                        "supported", DOC_KINDS,
                        "path", path.toString());
            }
            Map<String, Object> out = baseInfo(path, kind);
            out.put("include_grid", Json.bool(request, "include_grid", true));
            switch (kind) {
                case "docx" -> previewDocx(path, out);
                case "doc" -> previewDoc(path, out);
                case "xlsx", "xlsm", "xls" -> previewWorkbook(path, out);
                case "pptx" -> previewPptx(path, out);
                case "ppt" -> previewPpt(path, out);
                case "pdf" -> previewPdf(path, out);
                case "csv", "tsv" -> previewCsv(path, out, kind);
                case "odt", "ods", "odp" -> previewOdf(path, out);
                case "rtf" -> previewRtf(path, out);
                default -> previewText(path, out);
            }
            out.put("ok", true);
            out.put("duration_ms", System.currentTimeMillis() - started);
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex),
                    "duration_ms", System.currentTimeMillis() - started);
        }
    }

    public Map<String, Object> version(Map<String, Object> request) {
        try {
            Path path = resolve(Json.str(request, "path", ""));
            if (!Files.isRegularFile(path)) {
                return Json.map("ok", false, "error", "File not found: " + path);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("path", path.toString());
            out.put("bytes", Files.size(path));
            out.put("modified_ms", Files.getLastModifiedTime(path).toMillis());
            out.put("version", Files.getLastModifiedTime(path).toMillis() + "-"
                    + Files.size(path));
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public Map<String, Object> create(Map<String, Object> request) {
        try {
            String raw = Json.str(request, "path", "").trim();
            String type = Json.str(request, "type", "").trim().toLowerCase(Locale.ROOT);
            if (raw.isEmpty()) {
                return Json.map("ok", false, "error", "path is required");
            }
            if (type.isEmpty()) {
                type = extension(raw);
            }
            if (!List.of("docx", "xlsx", "pptx", "pdf", "txt", "md", "csv", "tsv")
                    .contains(type)) {
                return Json.map("ok", false,
                        "error", "Create supports docx, xlsx, pptx, pdf, txt, md, csv, tsv");
            }
            Path path = resolve(raw);
            if (Files.exists(path) && !Json.bool(request, "overwrite", false)) {
                return Json.map("ok", false, "error", "File already exists: " + path,
                        "exists", true);
            }
            guard(path);
            Files.createDirectories(path.getParent());
            String title = Json.str(request, "title", "").trim();
            String content = Json.str(request, "content", "");
            switch (type) {
                case "docx" -> createDocx(path, title, content);
                case "xlsx" -> createWorkbook(path, title, content);
                case "pptx" -> createPptx(path, title, content);
                case "pdf" -> createPdf(path, title, content);
                default -> Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            Map<String, Object> out = open(Json.map("path", path.toString()));
            out.put("created", true);
            out.put("message", "Created " + path);
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public Map<String, Object> update(Map<String, Object> request) {
        try {
            Path path = resolve(Json.str(request, "path", ""));
            if (!Files.isRegularFile(path)) {
                return Json.map("ok", false, "error", "File not found: " + path);
            }
            guard(path);
            String kind = extension(path.getFileName().toString());
            String action = Json.str(request, "action", "").trim().toLowerCase(Locale.ROOT);
            switch (kind) {
                case "docx" -> updateDocx(path, action, request);
                case "doc" -> updateDoc(path, action, request);
                case "xlsx", "xlsm" -> updateWorkbook(path, action, request, false);
                case "xls" -> updateWorkbook(path, action, request, true);
                case "pptx" -> updatePptx(path, action, request);
                case "ppt" -> updatePpt(path, action, request);
                case "pdf" -> updatePdf(path, action, request);
                case "csv", "tsv", "txt", "md", "markdown", "json", "log" ->
                        updateText(path, action, request);
                default -> {
                    return Json.map("ok", false,
                            "error", "Editing ." + kind + " is not supported yet; "
                                    + "it opens read-only.");
                }
            }
            Map<String, Object> out = open(Json.map("path", path.toString(),
                    "include_grid", true));
            out.put("updated", true);
            out.put("action", action);
            out.put("message", "Saved " + path.getFileName());
            return out;
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public Map<String, Object> delete(Map<String, Object> request) {
        try {
            Path path = resolve(Json.str(request, "path", ""));
            if (!Files.exists(path)) {
                return Json.map("ok", false, "error", "File not found: " + path);
            }
            guard(path);
            if (!Json.bool(request, "confirm", false)) {
                return Json.map("ok", false, "confirmation_required", true,
                        "error", "Deleting needs confirm=true. The file is moved to the trash folder.");
            }
            Path trash = root.resolve("workspace").resolve(".trash").resolve("office");
            Files.createDirectories(trash);
            String name = path.getFileName().toString();
            Path target = trash.resolve(STAMP.format(Instant.now()) + "-" + name);
            int n = 1;
            while (Files.exists(target)) {
                target = trash.resolve(STAMP.format(Instant.now()) + "-" + n++ + "-" + name);
            }
            if (Json.bool(request, "permanent", false)) {
                Files.delete(path);
                return Json.map("ok", true, "path", path.toString(),
                        "permanent", true, "message", "Deleted permanently");
            }
            Files.move(path, target);
            return Json.map("ok", true, "path", target.toString(),
                    "original", path.toString(), "permanent", false,
                    "trash", trash.toString(),
                    "message", "Moved to the office trash folder");
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    public Map<String, Object> search(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        String query = Json.str(request, "query", "").trim();
        if (query.isEmpty()) {
            return Json.map("ok", false, "error", "query is required");
        }
        String raw = Json.str(request, "path", "").trim();
        Path base = raw.isEmpty() ? root : resolve(raw);
        int limit = Math.max(1, Math.min(200, Json.integer(request, "limit", 30)));
        int maxFiles = Math.max(1, Math.min(2000, Json.integer(request, "max_files", 300)));
        long deadline = System.currentTimeMillis() + 60_000;
        List<Map<String, Object>> hits = new ArrayList<>();
        List<Path> candidates = new ArrayList<>();
        try {
            if (Files.isRegularFile(base)) {
                candidates.add(base);
            } else {
                try (var stream = Files.walk(base, 8)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> supported(p.getFileName().toString()))
                            .filter(p -> !p.toString().contains(".trash"))
                            .limit(maxFiles)
                            .forEach(candidates::add);
                }
            }
            for (Path file : candidates) {
                if (hits.size() >= limit || System.currentTimeMillis() > deadline) {
                    break;
                }
                try {
                    String text = extractText(file);
                    int index = text.toLowerCase(Locale.ROOT)
                            .indexOf(query.toLowerCase(Locale.ROOT));
                    if (index >= 0) {
                        int start = Math.max(0, index - 150);
                        int end = Math.min(text.length(), index + query.length() + 220);
                        hits.add(Json.map(
                                "path", file.toString(),
                                "name", file.getFileName().toString(),
                                "kind", extension(file.getFileName().toString()),
                                "bytes", Files.size(file),
                                "excerpt", text.substring(start, end)
                                        .replaceAll("\\s+", " ").trim()));
                    }
                } catch (Exception ignored) {
                }
            }
            return Json.map("ok", true, "query", query, "base", base.toString(),
                    "files_scanned", candidates.size(), "count", hits.size(),
                    "duration_ms", System.currentTimeMillis() - started, "hits", hits);
        } catch (Exception ex) {
            return Json.map("ok", false, "error", message(ex));
        }
    }

    /** Renders a PDF page to PNG for the live preview. */
    public byte[] renderPdfPage(String rawPath, int pageIndex, float scale)
            throws IOException {
        Path path = resolve(rawPath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("File not found: " + path);
        }
        if (!"pdf".equals(extension(path.getFileName().toString()))) {
            throw new IOException("Not a PDF: " + path);
        }
        long modified = Files.getLastModifiedTime(path).toMillis();
        long size;
        try {
            size = Files.size(path);
        } catch (IOException ex) {
            size = 0;
        }
        float safeScale = Math.max(0.5f, Math.min(2.5f, scale));
        String key = path + "|" + modified + "|" + size + "|" + pageIndex + "|" + safeScale;
        byte[] cached = pdfCache.get(key);
        if (cached != null) {
            return cached;
        }
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                throw new IOException("PDF has " + doc.getNumberOfPages() + " pages");
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImage(pageIndex, safeScale, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            byte[] bytes = out.toByteArray();
            if (pdfCache.size() > 24) {
                pdfCache.clear();
            }
            pdfCache.put(key, bytes);
            return bytes;
        }
    }

    public String extractText(Path file) throws IOException {
        String kind = extension(file.getFileName().toString());
        return switch (kind) {
            case "docx" -> {
                try (InputStream in = Files.newInputStream(file);
                     XWPFDocument doc = new XWPFDocument(in)) {
                    StringBuilder text = new StringBuilder();
                    for (XWPFParagraph paragraph : doc.getParagraphs()) {
                        text.append(paragraph.getText()).append('\n');
                    }
                    for (XWPFTable table : doc.getTables()) {
                        for (XWPFTableRow row : table.getRows()) {
                            for (XWPFTableCell cell : row.getTableCells()) {
                                text.append(cell.getText()).append('\t');
                            }
                            text.append('\n');
                        }
                    }
                    yield text.toString();
                }
            }
            case "doc" -> {
                try (InputStream in = Files.newInputStream(file);
                     HWPFDocument doc = new HWPFDocument(in)) {
                    yield doc.getRange().text();
                }
            }
            case "xlsx", "xlsm", "xls" -> workbookText(file);
            case "pptx" -> {
                try (InputStream in = Files.newInputStream(file);
                     XMLSlideShow show = new XMLSlideShow(in)) {
                    StringBuilder text = new StringBuilder();
                    for (XSLFSlide slide : show.getSlides()) {
                        text.append("--- Slide ---\n");
                        for (XSLFShape shape : slide.getShapes()) {
                            if (shape instanceof XSLFTextShape textShape) {
                                text.append(textShape.getText()).append('\n');
                            }
                        }
                    }
                    yield text.toString();
                }
            }
            case "ppt" -> {
                try (InputStream in = Files.newInputStream(file);
                     HSLFSlideShow show = new HSLFSlideShow(in)) {
                    StringBuilder text = new StringBuilder();
                    for (HSLFSlide slide : show.getSlides()) {
                        text.append("--- Slide ---\n");
                        for (var shape : slide.getShapes()) {
                            if (shape instanceof HSLFTextShape textShape) {
                                text.append(textShape.getText()).append('\n');
                            }
                        }
                    }
                    yield text.toString();
                }
            }
            case "pdf" -> {
                try (PDDocument doc = Loader.loadPDF(file.toFile())) {
                    yield new PDFTextStripper().getText(doc);
                }
            }
            case "odt", "ods", "odp" -> odfText(file);
            default -> Files.readString(file, StandardCharsets.UTF_8);
        };
    }

    /* ---------------------------------------------------------------- */
    /* previews                                                          */
    /* ---------------------------------------------------------------- */

    private void previewDocx(Path path, Map<String, Object> out) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder html = new StringBuilder();
            StringBuilder text = new StringBuilder();
            List<Object> paragraphs = new ArrayList<>();
            int index = 0;
            for (var element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String value = paragraph.getText();
                    text.append(value).append('\n');
                    String style = paragraph.getStyle() == null ? "" : paragraph.getStyle();
                    String tag = "p";
                    String cls = "doc-p";
                    String lower = style.toLowerCase(Locale.ROOT);
                    if (lower.contains("heading") || lower.contains("title")) {
                        int level = 2;
                        for (char c : lower.toCharArray()) {
                            if (c >= '1' && c <= '6') {
                                level = c - '0';
                                break;
                            }
                        }
                        if (lower.contains("title")) {
                            level = 1;
                        }
                        tag = "h" + Math.max(1, Math.min(6, level));
                        cls = "doc-heading";
                    }
                    html.append('<').append(tag).append(" class=\"").append(cls).append("\">")
                            .append(escape(value.isEmpty() ? " " : value))
                            .append("</").append(tag).append('>');
                    paragraphs.add(Json.map("index", index, "text", value, "style", style,
                            "empty", value.isBlank()));
                    ++index;
                } else if (element instanceof XWPFTable table) {
                    html.append("<table class=\"doc-table\">");
                    for (XWPFTableRow row : table.getRows()) {
                        html.append("<tr>");
                        for (XWPFTableCell cell : row.getTableCells()) {
                            html.append("<td>").append(escape(cell.getText())).append("</td>");
                            text.append(cell.getText()).append('\t');
                        }
                        html.append("</tr>");
                        text.append('\n');
                    }
                    html.append("</table>");
                }
            }
            out.put("doc_kind", "word");
            out.put("paragraphs", paragraphs);
            out.put("paragraph_count", paragraphs.size());
            out.put("tables", doc.getTables().size());
            out.put("text", cap(text.toString(), PREVIEW_CHARS));
            out.put("html", html.toString());
            out.put("editable", true);
            out.put("note", "段落与表格文本可编辑；图片、页眉页脚保持原样。");
        }
    }

    private void previewDoc(Path path, Map<String, Object> out) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             HWPFDocument doc = new HWPFDocument(in)) {
            Range range = doc.getRange();
            String text = range.text();
            StringBuilder html = new StringBuilder();
            for (String line : text.split("\\R", -1)) {
                html.append("<p class=\"doc-p\">").append(escape(line)).append("</p>");
            }
            out.put("doc_kind", "word-legacy");
            out.put("text", cap(text, PREVIEW_CHARS));
            out.put("html", html.toString());
            out.put("editable", true);
            out.put("note", "旧版 .doc：支持全文替换与追加段落；复杂排版会保留原结构。");
        }
    }

    private void previewWorkbook(Path path, Map<String, Object> out) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = openWorkbook(path, in)) {
            out.put("doc_kind", "excel");
            out.put("editable", true);
            out.put("note", "双击单元格编辑，保存后写回工作簿；公式保持为公式。");
            DataFormatter formatter = new DataFormatter(Locale.getDefault());
            FormulaEvaluator evaluator = null;
            try {
                evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            } catch (Exception ignored) {
            }
            List<Object> sheets = new ArrayList<>();
            int sheetCount = workbook.getNumberOfSheets();
            for (int s = 0; s < Math.min(sheetCount, 12); ++s) {
                Sheet sheet = workbook.getSheetAt(s);
                int lastRow = sheet.getLastRowNum();
                int rows = Math.max(0, lastRow + 1);
                int cols = 0;
                for (int r = 0; r <= lastRow && r < PREVIEW_ROWS; ++r) {
                    Row row = sheet.getRow(r);
                    if (row != null) {
                        cols = Math.max(cols, Math.max(0, row.getLastCellNum()));
                    }
                }
                cols = Math.min(PREVIEW_COLS, Math.max(1, cols));
                List<Object> grid = new ArrayList<>();
                StringBuilder html = new StringBuilder("<table class=\"sheet-table\">");
                html.append("<thead><tr><th></th>");
                for (int c = 0; c < cols; ++c) {
                    html.append("<th>").append(CellReference.convertNumToColString(c)).append("</th>");
                }
                html.append("</tr></thead><tbody>");
                int previewRows = Math.min(PREVIEW_ROWS, Math.max(1, rows));
                for (int r = 0; r < previewRows; ++r) {
                    html.append("<tr><th>").append(r + 1).append("</th>");
                    List<Object> rowValues = new ArrayList<>();
                    Row row = sheet.getRow(r);
                    for (int c = 0; c < cols; ++c) {
                        Cell cell = row == null ? null : row.getCell(c);
                        String value = "";
                        if (cell != null) {
                            try {
                                value = evaluator == null
                                        ? formatter.formatCellValue(cell)
                                        : formatter.formatCellValue(cell, evaluator);
                            } catch (Exception ex) {
                                value = formatter.formatCellValue(cell);
                            }
                        }
                        rowValues.add(value);
                        html.append("<td>").append(escape(value)).append("</td>");
                    }
                    grid.add(rowValues);
                    html.append("</tr>");
                }
                html.append("</tbody></table>");
                sheets.add(Json.map(
                        "index", s,
                        "name", sheet.getSheetName(),
                        "rows", rows,
                        "cols", cols,
                        "preview_rows", previewRows,
                        "truncated", rows > previewRows || cols >= PREVIEW_COLS,
                        "grid", grid,
                        "html", html.toString()));
            }
            out.put("sheets", sheets);
            out.put("sheet_count", sheetCount);
            if (!sheets.isEmpty()) {
                out.put("html", Json.str(Json.asObject(sheets.get(0)), "html", ""));
            }
            out.put("text", cap(workbookText(workbook), PREVIEW_CHARS));
        }
    }

    private void previewPptx(Path path, Map<String, Object> out) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             XMLSlideShow show = new XMLSlideShow(in)) {
            List<Object> slides = new ArrayList<>();
            StringBuilder html = new StringBuilder();
            int index = 0;
            for (XSLFSlide slide : show.getSlides()) {
                List<Object> shapes = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                String title = "";
                int shapeIndex = 0;
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String value = textShape.getText();
                        if (value == null) {
                            value = "";
                        }
                        text.append(value).append('\n');
                        if (title.isEmpty()
                                && textShape.getTextType() == org.apache.poi.sl.usermodel.Placeholder.TITLE) {
                            title = value;
                        }
                        shapes.add(Json.map(
                                "index", shapeIndex,
                                "name", shape.getShapeName(),
                                "text", value,
                                "type", textShape.getTextType() == null ? "text"
                                        : textShape.getTextType().name()));
                        ++shapeIndex;
                    }
                }
                if (title.isEmpty() && !text.isEmpty()) {
                    title = text.toString().split("\\R", 2)[0];
                }
                slides.add(Json.map(
                        "index", index,
                        "title", title,
                        "text", text.toString().trim(),
                        "shapes", shapes));
                html.append("<section class=\"slide-preview\"><div class=\"slide-no\">SLIDE ")
                        .append(index + 1).append("</div>");
                if (!title.isBlank()) {
                    html.append("<h3>").append(escape(title)).append("</h3>");
                }
                if (!text.isEmpty()) {
                    for (String line : text.toString().split("\\R", -1)) {
                        if (!line.isBlank()) {
                            html.append("<p>").append(escape(line)).append("</p>");
                        }
                    }
                } else {
                    html.append("<p class=\"dim\">此页没有文本；图片/图表保持原样。</p>");
                }
                html.append("</section>");
                ++index;
            }
            out.put("doc_kind", "powerpoint");
            out.put("slide_count", slides.size());
            out.put("slides", slides);
            out.put("html", html.toString());
            out.put("editable", true);
            out.put("note", "可编辑每页文本框、添加或删除页面；版式与图片保持原样。");
        }
    }

    private void previewPpt(Path path, Map<String, Object> out) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             HSLFSlideShow show = new HSLFSlideShow(in)) {
            List<Object> slides = new ArrayList<>();
            StringBuilder html = new StringBuilder();
            int index = 0;
            for (HSLFSlide slide : show.getSlides()) {
                List<Object> shapes = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                int shapeIndex = 0;
                for (var shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String value = textShape.getText() == null ? "" : textShape.getText();
                        text.append(value).append('\n');
                        shapes.add(Json.map("index", shapeIndex,
                                "name", shape.getShapeName(), "text", value, "type", "text"));
                        ++shapeIndex;
                    }
                }
                String title = text.toString().split("\\R", 2)[0];
                slides.add(Json.map("index", index, "title", title,
                        "text", text.toString().trim(), "shapes", shapes));
                html.append("<section class=\"slide-preview\"><div class=\"slide-no\">SLIDE ")
                        .append(index + 1).append("</div><h3>").append(escape(title))
                        .append("</h3><p>").append(escape(text.toString().trim()))
                        .append("</p></section>");
                ++index;
            }
            out.put("doc_kind", "powerpoint-legacy");
            out.put("slide_count", slides.size());
            out.put("slides", slides);
            out.put("html", html.toString());
            out.put("editable", true);
            out.put("note", "旧版 .ppt：支持替换文本与删除页面；复杂版式保持原样。");
        }
    }

    private void previewPdf(Path path, Map<String, Object> out) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            int pages = doc.getNumberOfPages();
            out.put("doc_kind", "pdf");
            out.put("page_count", pages);
            out.put("editable", true);
            out.put("note", "PDF 支持全文检索、元数据、增删页、旋转与合并；"
                    + "正文文字本身不可原位改写。");
            PDDocumentInformation info = doc.getDocumentInformation();
            out.put("pdf_title", safe(info.getTitle()));
            out.put("pdf_author", safe(info.getAuthor()));
            out.put("pdf_subject", safe(info.getSubject()));
            out.put("pdf_keywords", safe(info.getKeywords()));
            out.put("pdf_creator", safe(info.getCreator()));
            out.put("pdf_producer", safe(info.getProducer()));
            String text = "";
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(Math.min(pages, 40));
                text = stripper.getText(doc);
            } catch (Exception ex) {
                text = "PDF text extraction failed: " + ex.getMessage();
            }
            out.put("text", cap(text, PREVIEW_CHARS));
            StringBuilder html = new StringBuilder();
            String[] pageTexts = text.split("\\f", -1);
            for (int i = 0; i < pageTexts.length; ++i) {
                html.append("<section class=\"pdf-text-page\"><div class=\"slide-no\">PAGE ")
                        .append(i + 1).append("</div><pre>").append(escape(pageTexts[i]))
                        .append("</pre></section>");
            }
            out.put("html", html.toString());
            out.put("render_url", "/api/office/pdf-page?path="
                    + java.net.URLEncoder.encode(path.toString(), StandardCharsets.UTF_8)
                    + "&page=0&scale=1.25");
        }
    }

    private void previewCsv(Path path, Map<String, Object> out, String kind) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        char delimiter = "tsv".equals(kind) ? '\t' : ',';
        List<List<String>> rows = parseDelimited(text, delimiter, PREVIEW_ROWS, PREVIEW_COLS);
        StringBuilder html = new StringBuilder("<table class=\"sheet-table\">");
        for (int r = 0; r < rows.size(); ++r) {
            html.append(r == 0 ? "<thead><tr>" : (r == 1 ? "</thead><tbody><tr>" : "<tr>"));
            for (String value : rows.get(r)) {
                String tag = r == 0 ? "th" : "td";
                html.append('<').append(tag).append('>').append(escape(value))
                        .append("</").append(tag).append('>');
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        out.put("doc_kind", "table");
        out.put("text", cap(text, PREVIEW_CHARS));
        out.put("html", html.toString());
        out.put("rows", rows.size());
        out.put("editable", true);
        out.put("note", "表格内容可直接在下方编辑器修改并保存为 UTF-8。");
    }

    private void previewText(Path path, Map<String, Object> out) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        out.put("doc_kind", "text");
        out.put("text", cap(text, PREVIEW_CHARS));
        out.put("html", "<pre class=\"doc-text\">" + escape(cap(text, PREVIEW_CHARS)) + "</pre>");
        out.put("editable", true);
    }

    private void previewOdf(Path path, Map<String, Object> out) throws IOException {
        String text = odfText(path);
        out.put("doc_kind", "open-document");
        out.put("text", cap(text, PREVIEW_CHARS));
        out.put("html", "<pre class=\"doc-text\">" + escape(cap(text, PREVIEW_CHARS)) + "</pre>");
        out.put("editable", false);
        out.put("note", "ODF 文档当前为只读预览；可导出为 DOCX/PDF 后编辑。");
    }

    private void previewRtf(Path path, Map<String, Object> out) throws IOException {
        String raw = Files.readString(path, StandardCharsets.US_ASCII);
        String text = raw.replaceAll("\\\\[a-zA-Z]+-?\\d* ?", "")
                .replace("\\'", "").replace("{", "").replace("}", "");
        out.put("doc_kind", "rtf");
        out.put("text", cap(text, PREVIEW_CHARS));
        out.put("html", "<pre class=\"doc-text\">" + escape(cap(text, PREVIEW_CHARS)) + "</pre>");
        out.put("editable", false);
    }

    /* ---------------------------------------------------------------- */
    /* update actions                                                    */
    /* ---------------------------------------------------------------- */

    private void updateDocx(Path path, String action, Map<String, Object> request)
            throws IOException {
        Path temp = tempFor(path);
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(in)) {
            switch (action) {
                case "set_paragraph" -> {
                    int index = Json.integer(request, "index", -1);
                    List<XWPFParagraph> paragraphs = doc.getParagraphs();
                    if (index < 0 || index >= paragraphs.size()) {
                        throw new IOException("Paragraph index out of range: " + index);
                    }
                    setParagraphText(paragraphs.get(index), Json.str(request, "text", ""));
                }
                case "append_paragraph" -> {
                    XWPFParagraph paragraph = doc.createParagraph();
                    String style = Json.str(request, "style", "");
                    if (!style.isBlank()) {
                        paragraph.setStyle(style);
                    }
                    paragraph.createRun().setText(Json.str(request, "text", ""));
                }
                case "delete_paragraph" -> {
                    int index = Json.integer(request, "index", -1);
                    List<XWPFParagraph> paragraphs = doc.getParagraphs();
                    if (index < 0 || index >= paragraphs.size()) {
                        throw new IOException("Paragraph index out of range: " + index);
                    }
                    doc.removeBodyElement(doc.getPosOfParagraph(paragraphs.get(index)));
                }
                case "replace_all" -> {
                    String find = Json.str(request, "find", "");
                    String replace = Json.str(request, "replace", "");
                    if (find.isEmpty()) {
                        throw new IOException("find is required");
                    }
                    int count = 0;
                    for (XWPFParagraph paragraph : doc.getParagraphs()) {
                        if (paragraph.getText().contains(find)) {
                            setParagraphText(paragraph, paragraph.getText().replace(find, replace));
                            ++count;
                        }
                    }
                    for (XWPFTable table : doc.getTables()) {
                        for (XWPFTableRow row : table.getRows()) {
                            for (XWPFTableCell cell : row.getTableCells()) {
                                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                                    if (paragraph.getText().contains(find)) {
                                        setParagraphText(paragraph,
                                                paragraph.getText().replace(find, replace));
                                        ++count;
                                    }
                                }
                            }
                        }
                    }
                    if (count == 0) {
                        throw new IOException("Text not found: " + find);
                    }
                }
                case "set_table_cell" -> {
                    int tableIndex = Json.integer(request, "table", 0);
                    int rowIndex = Json.integer(request, "row", 0);
                    int colIndex = Json.integer(request, "col", 0);
                    List<XWPFTable> tables = doc.getTables();
                    if (tableIndex < 0 || tableIndex >= tables.size()) {
                        throw new IOException("Table index out of range");
                    }
                    XWPFTable table = tables.get(tableIndex);
                    if (rowIndex < 0 || rowIndex >= table.getRows().size()) {
                        throw new IOException("Row index out of range");
                    }
                    XWPFTableRow row = table.getRow(rowIndex);
                    if (colIndex < 0 || colIndex >= row.getTableCells().size()) {
                        throw new IOException("Column index out of range");
                    }
                    XWPFTableCell cell = row.getCell(colIndex);
                    cell.removeParagraph(0);
                    cell.addParagraph().createRun().setText(Json.str(request, "text", ""));
                }
                default -> throw new IOException("Unsupported DOCX action: " + action);
            }
            try (var out = Files.newOutputStream(temp)) {
                doc.write(out);
            }
        }
        replace(temp, path);
    }

    private void updateDoc(Path path, String action, Map<String, Object> request)
            throws IOException {
        Path temp = tempFor(path);
        try (InputStream in = Files.newInputStream(path);
             HWPFDocument doc = new HWPFDocument(in)) {
            Range range = doc.getRange();
            switch (action) {
                case "replace_all" -> {
                    String find = Json.str(request, "find", "");
                    if (find.isEmpty()) {
                        throw new IOException("find is required");
                    }
                    if (!range.text().contains(find)) {
                        throw new IOException("Text not found: " + find);
                    }
                    range.replaceText(find, Json.str(request, "replace", ""));
                }
                case "append_paragraph" -> range.insertAfter("\r"
                        + Json.str(request, "text", ""));
                default -> throw new IOException("Unsupported DOC action: " + action);
            }
            try (var out = Files.newOutputStream(temp)) {
                doc.write(out);
            }
        }
        replace(temp, path);
    }

    private void updateWorkbook(Path path, String action, Map<String, Object> request,
                                boolean legacy) throws IOException {
        Path temp = tempFor(path);
        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = openWorkbook(path, in)) {
            switch (action) {
                case "set_cell" -> {
                    Sheet sheet = sheet(workbook, Json.str(request, "sheet", ""));
                    String ref = Json.str(request, "cell", "").toUpperCase(Locale.ROOT);
                    if (ref.isEmpty()) {
                        throw new IOException("cell is required, for example B3");
                    }
                    CellReference cellRef = new CellReference(ref);
                    Row row = sheet.getRow(cellRef.getRow());
                    if (row == null) {
                        row = sheet.createRow(cellRef.getRow());
                    }
                    Cell cell = row.getCell(cellRef.getCol());
                    if (cell == null) {
                        cell = row.createCell(cellRef.getCol());
                    }
                    Object raw = request.get("value");
                    String value = raw == null ? Json.str(request, "text", "") : String.valueOf(raw);
                    String type = Json.str(request, "value_type", "auto").toLowerCase(Locale.ROOT);
                    if ("formula".equals(type) || value.startsWith("=")) {
                        cell.setCellFormula(value.startsWith("=") ? value.substring(1) : value);
                    } else if ("number".equals(type)
                            || ("auto".equals(type) && value.matches("[-+]?\\d+(\\.\\d+)?"))) {
                        try {
                            cell.setCellValue(Double.parseDouble(value));
                        } catch (NumberFormatException ex) {
                            cell.setCellValue(value);
                        }
                    } else if ("boolean".equals(type)) {
                        cell.setCellValue(Boolean.parseBoolean(value));
                    } else if ("blank".equals(type)) {
                        cell.setBlank();
                    } else {
                        cell.setCellValue(value);
                    }
                }
                case "add_row" -> {
                    Sheet sheet = sheet(workbook, Json.str(request, "sheet", ""));
                    int rowIndex = Math.max(0, Json.integer(request, "row", sheet.getLastRowNum() + 1));
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        row = sheet.createRow(rowIndex);
                    }
                    Object raw = request.get("values");
                    List<Object> values = raw instanceof List<?> list ? new ArrayList<>(list) : List.of();
                    for (int c = 0; c < values.size(); ++c) {
                        Cell cell = row.getCell(c);
                        if (cell == null) {
                            cell = row.createCell(c);
                        }
                        Object value = values.get(c);
                        if (value instanceof Number number) {
                            cell.setCellValue(number.doubleValue());
                        } else {
                            cell.setCellValue(value == null ? "" : String.valueOf(value));
                        }
                    }
                }
                case "delete_row" -> {
                    Sheet sheet = sheet(workbook, Json.str(request, "sheet", ""));
                    int rowIndex = Json.integer(request, "row", -1);
                    if (rowIndex < 0) {
                        throw new IOException("row is required");
                    }
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        throw new IOException("Row does not exist: " + (rowIndex + 1));
                    }
                    sheet.removeRow(row);
                    if (rowIndex < sheet.getLastRowNum()) {
                        sheet.shiftRows(rowIndex + 1, sheet.getLastRowNum(), -1);
                    }
                }
                case "add_sheet" -> {
                    String name = Json.str(request, "name", "Sheet" + (workbook.getNumberOfSheets() + 1));
                    if (workbook.getSheet(name) != null) {
                        throw new IOException("Sheet already exists: " + name);
                    }
                    workbook.createSheet(name);
                }
                case "delete_sheet" -> {
                    Sheet sheet = sheet(workbook, Json.str(request, "sheet", ""));
                    if (workbook.getNumberOfSheets() <= 1) {
                        throw new IOException("Cannot delete the last sheet");
                    }
                    workbook.removeSheetAt(workbook.getSheetIndex(sheet));
                }
                case "rename_sheet" -> {
                    Sheet sheet = sheet(workbook, Json.str(request, "sheet", ""));
                    String name = Json.str(request, "name", "").trim();
                    if (name.isEmpty()) {
                        throw new IOException("name is required");
                    }
                    workbook.setSheetName(workbook.getSheetIndex(sheet), name);
                }
                default -> throw new IOException("Unsupported spreadsheet action: " + action);
            }
            try (var out = Files.newOutputStream(temp)) {
                workbook.write(out);
            }
        }
        replace(temp, path);
        if (legacy) {
            // keep the .xls extension and binary format written by HSSFWorkbook
        }
    }

    private void updatePptx(Path path, String action, Map<String, Object> request)
            throws IOException {
        Path temp = tempFor(path);
        try (InputStream in = Files.newInputStream(path);
             XMLSlideShow show = new XMLSlideShow(in)) {
            switch (action) {
                case "set_text" -> {
                    int slideIndex = Json.integer(request, "slide", -1);
                    int shapeIndex = Json.integer(request, "shape", -1);
                    if (slideIndex < 0 || slideIndex >= show.getSlides().size()) {
                        throw new IOException("Slide index out of range");
                    }
                    XSLFSlide slide = show.getSlides().get(slideIndex);
                    List<XSLFTextShape> shapes = new ArrayList<>();
                    for (XSLFShape shape : slide.getShapes()) {
                        if (shape instanceof XSLFTextShape textShape) {
                            shapes.add(textShape);
                        }
                    }
                    if (shapeIndex < 0 || shapeIndex >= shapes.size()) {
                        throw new IOException("Text shape index out of range");
                    }
                    shapes.get(shapeIndex).setText(Json.str(request, "text", ""));
                }
                case "add_slide" -> {
                    XSLFSlide slide = show.createSlide();
                    Dimension size = show.getPageSize();
                    XSLFTextBox title = slide.createTextBox();
                    title.setAnchor(new java.awt.Rectangle(50, 30,
                            Math.max(200, size.width - 100), 80));
                    title.setText(Json.str(request, "title", "New slide"));
                    String body = Json.str(request, "text", "");
                    if (!body.isBlank()) {
                        XSLFTextBox box = slide.createTextBox();
                        box.setAnchor(new java.awt.Rectangle(60, 130,
                                Math.max(200, size.width - 120), Math.max(150, size.height - 180)));
                        box.setText(body);
                    }
                }
                case "delete_slide" -> {
                    int slideIndex = Json.integer(request, "slide", -1);
                    if (slideIndex < 0 || slideIndex >= show.getSlides().size()) {
                        throw new IOException("Slide index out of range");
                    }
                    show.removeSlide(slideIndex);
                }
                default -> throw new IOException("Unsupported PPTX action: " + action);
            }
            try (var out = Files.newOutputStream(temp)) {
                show.write(out);
            }
        }
        replace(temp, path);
    }

    private void updatePpt(Path path, String action, Map<String, Object> request)
            throws IOException {
        Path temp = tempFor(path);
        try (InputStream in = Files.newInputStream(path);
             HSLFSlideShow show = new HSLFSlideShow(in)) {
            switch (action) {
                case "set_text" -> {
                    int slideIndex = Json.integer(request, "slide", -1);
                    int shapeIndex = Json.integer(request, "shape", -1);
                    if (slideIndex < 0 || slideIndex >= show.getSlides().size()) {
                        throw new IOException("Slide index out of range");
                    }
                    List<HSLFTextShape> shapes = new ArrayList<>();
                    for (var shape : show.getSlides().get(slideIndex).getShapes()) {
                        if (shape instanceof HSLFTextShape textShape) {
                            shapes.add(textShape);
                        }
                    }
                    if (shapeIndex < 0 || shapeIndex >= shapes.size()) {
                        throw new IOException("Text shape index out of range");
                    }
                    shapes.get(shapeIndex).setText(Json.str(request, "text", ""));
                }
                case "delete_slide" -> {
                    int slideIndex = Json.integer(request, "slide", -1);
                    if (slideIndex < 0 || slideIndex >= show.getSlides().size()) {
                        throw new IOException("Slide index out of range");
                    }
                    show.removeSlide(slideIndex);
                }
                default -> throw new IOException("Unsupported PPT action: " + action);
            }
            try (var out = Files.newOutputStream(temp)) {
                show.write(out);
            }
        }
        replace(temp, path);
    }

    private void updatePdf(Path path, String action, Map<String, Object> request)
            throws IOException {
        Path temp = tempFor(path);
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            switch (action) {
                case "set_metadata" -> {
                    PDDocumentInformation info = doc.getDocumentInformation();
                    if (request.containsKey("title")) {
                        info.setTitle(Json.str(request, "title", ""));
                    }
                    if (request.containsKey("author")) {
                        info.setAuthor(Json.str(request, "author", ""));
                    }
                    if (request.containsKey("subject")) {
                        info.setSubject(Json.str(request, "subject", ""));
                    }
                    if (request.containsKey("keywords")) {
                        info.setKeywords(Json.str(request, "keywords", ""));
                    }
                    doc.setDocumentInformation(info);
                }
                case "add_page" -> {
                    String text = Json.str(request, "text", "");
                    PDPage page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    writePdfText(doc, page, text);
                }
                case "delete_page" -> {
                    int page = Json.integer(request, "page", -1);
                    if (page < 0 || page >= doc.getNumberOfPages()) {
                        throw new IOException("Page index out of range");
                    }
                    if (doc.getNumberOfPages() <= 1) {
                        throw new IOException("Cannot delete the last page");
                    }
                    doc.removePage(page);
                }
                case "rotate_page" -> {
                    int page = Json.integer(request, "page", -1);
                    if (page < 0 || page >= doc.getNumberOfPages()) {
                        throw new IOException("Page index out of range");
                    }
                    int degrees = Json.integer(request, "degrees", 90);
                    PDPage target = doc.getPage(page);
                    target.setRotation((target.getRotation() + degrees + 360) % 360);
                }
                case "merge" -> {
                    Object raw = request.get("paths");
                    List<Object> paths = raw instanceof List<?> list ? new ArrayList<>(list) : List.of();
                    if (paths.isEmpty()) {
                        throw new IOException("paths is required");
                    }
                    for (Object item : paths) {
                        Path source = resolve(String.valueOf(item));
                        if (!Files.isRegularFile(source)) {
                            throw new IOException("Missing PDF: " + source);
                        }
                        try (PDDocument other = Loader.loadPDF(source.toFile())) {
                            for (PDPage page : other.getPages()) {
                                doc.importPage(page);
                            }
                        }
                    }
                }
                default -> throw new IOException("Unsupported PDF action: " + action);
            }
            doc.save(temp.toFile());
        }
        replace(temp, path);
    }

    private void updateText(Path path, String action, Map<String, Object> request)
            throws IOException {
        switch (action) {
            case "set_text" -> Files.writeString(path, Json.str(request, "text", ""),
                    StandardCharsets.UTF_8);
            case "append" -> Files.writeString(path, Json.str(request, "text", ""),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            default -> throw new IOException("Unsupported text action: " + action);
        }
    }

    /* ---------------------------------------------------------------- */
    /* creation                                                          */
    /* ---------------------------------------------------------------- */

    private void createDocx(Path path, String title, String content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            if (!title.isBlank()) {
                XWPFParagraph heading = doc.createParagraph();
                heading.setStyle("Title");
                var run = heading.createRun();
                run.setText(title);
                run.setBold(true);
                run.setFontSize(20);
            }
            for (String line : content.split("\\R", -1)) {
                XWPFParagraph paragraph = doc.createParagraph();
                paragraph.createRun().setText(line);
            }
            try (var out = Files.newOutputStream(path)) {
                doc.write(out);
            }
        }
    }

    private void createWorkbook(Path path, String title, String content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(title.isBlank() ? "Sheet1" : title);
            String[] lines = content.split("\\R", -1);
            for (int r = 0; r < lines.length; ++r) {
                if (lines[r].isEmpty() && r > 0) {
                    continue;
                }
                String[] values = lines[r].split("\t|,", -1);
                Row row = sheet.createRow(r);
                for (int c = 0; c < values.length; ++c) {
                    Cell cell = row.createCell(c);
                    String value = values[c].trim();
                    if (value.matches("[-+]?\\d+(\\.\\d+)?")) {
                        cell.setCellValue(Double.parseDouble(value));
                    } else {
                        cell.setCellValue(value);
                    }
                }
            }
            if (lines.length == 1 && lines[0].isEmpty()) {
                sheet.createRow(0).createCell(0).setCellValue("");
            }
            try (var out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
    }

    private void createPptx(Path path, String title, String content) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide slide = show.createSlide();
            Dimension size = show.getPageSize();
            XSLFTextBox titleBox = slide.createTextBox();
            titleBox.setAnchor(new java.awt.Rectangle(50, 40,
                    Math.max(200, size.width - 100), 90));
            titleBox.setText(title.isBlank() ? "Untitled" : title);
            if (!content.isBlank()) {
                XSLFTextBox body = slide.createTextBox();
                body.setAnchor(new java.awt.Rectangle(60, 150,
                        Math.max(200, size.width - 120), Math.max(150, size.height - 200)));
                body.setText(content);
            }
            try (var out = Files.newOutputStream(path)) {
                show.write(out);
            }
        }
    }

    private void createPdf(Path path, String title, String content) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            if (!title.isBlank()) {
                info.setTitle(title);
            }
            doc.setDocumentInformation(info);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            String text = (title.isBlank() ? "" : title + "\n\n") + content;
            writePdfText(doc, page, text);
            doc.save(path.toFile());
        }
    }

    private void writePdfText(PDDocument doc, PDPage first, String text) throws IOException {
        var font = pdfFont(doc);
        float fontSize = 12f;
        float margin = 56f;
        float leading = 16f;
        PDPage page = first;
        PDPageContentStream stream = new PDPageContentStream(doc, page);
        boolean textOpen = false;
        try {
            stream.beginText();
            stream.setFont(font, fontSize);
            float y = page.getMediaBox().getHeight() - margin;
            stream.newLineAtOffset(margin, y);
            textOpen = true;
            for (String rawLine : text.split("\\R", -1)) {
                for (String line : wrapPdfLine(rawLine.isEmpty() ? " " : rawLine, 78)) {
                    if (y < margin) {
                        stream.endText();
                        textOpen = false;
                        stream.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        stream = new PDPageContentStream(doc, page);
                        stream.beginText();
                        stream.setFont(font, fontSize);
                        y = page.getMediaBox().getHeight() - margin;
                        stream.newLineAtOffset(margin, y);
                        textOpen = true;
                    }
                    stream.showText(sanitizePdfText(line, font));
                    stream.newLineAtOffset(0, -leading);
                    y -= leading;
                }
            }
        } finally {
            if (textOpen) {
                stream.endText();
            }
            stream.close();
        }
    }

    private org.apache.pdfbox.pdmodel.font.PDFont pdfFont(PDDocument doc) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String windir = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
            candidates.add(Path.of(windir, "Fonts", "msyh.ttc"));
            candidates.add(Path.of(windir, "Fonts", "simhei.ttf"));
            candidates.add(Path.of(windir, "Fonts", "simsun.ttc"));
        } else if (os.contains("mac")) {
            candidates.add(Path.of("/System/Library/Fonts/PingFang.ttc"));
            candidates.add(Path.of("/System/Library/Fonts/STHeiti Light.ttc"));
        } else {
            candidates.add(Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"));
            candidates.add(Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"));
            candidates.add(Path.of("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"));
            candidates.add(Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return PDType0Font.load(doc, candidate.toFile());
                } catch (Exception ignored) {
                }
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static String sanitizePdfText(String text,
            org.apache.pdfbox.pdmodel.font.PDFont font) {
        try {
            font.encode(text);
            return text;
        } catch (Exception ex) {
            StringBuilder safe = new StringBuilder();
            for (char c : text.toCharArray()) {
                try {
                    font.encode(String.valueOf(c));
                    safe.append(c);
                } catch (Exception ignored) {
                    safe.append('?');
                }
            }
            return safe.toString();
        }
    }

    private static List<String> wrapPdfLine(String line, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = 0;
        for (String word : line.split(" ")) {
            int wordLength = word.length();
            if (length > 0 && length + 1 + wordLength > width) {
                out.add(current.toString());
                current.setLength(0);
                length = 0;
            }
            if (length > 0) {
                current.append(' ');
                ++length;
            }
            current.append(word);
            length += wordLength;
            while (length > width) {
                out.add(current.substring(0, width));
                current.delete(0, width);
                length = current.length();
            }
        }
        if (length > 0 || out.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                           */
    /* ---------------------------------------------------------------- */

    private Map<String, Object> baseInfo(Path path, String kind) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        out.put("name", path.getFileName().toString());
        out.put("kind", kind);
        out.put("bytes", Files.size(path));
        out.put("modified_ms", Files.getLastModifiedTime(path).toMillis());
        out.put("modified", STAMP.format(Instant.ofEpochMilli(
                Files.getLastModifiedTime(path).toMillis())));
        out.put("version", Files.getLastModifiedTime(path).toMillis() + "-" + Files.size(path));
        return out;
    }

    private Workbook openWorkbook(Path path, InputStream in) throws IOException {
        String kind = extension(path.getFileName().toString());
        if ("xls".equals(kind)) {
            return new HSSFWorkbook(in);
        }
        return new XSSFWorkbook(in);
    }

    private static Sheet sheet(Workbook workbook, String name) throws IOException {
        Sheet sheet = name == null || name.isBlank() ? null : workbook.getSheet(name);
        if (sheet == null) {
            sheet = workbook.getSheetAt(0);
        }
        if (sheet == null) {
            throw new IOException("Workbook has no sheets");
        }
        return sheet;
    }

    private String workbookText(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             Workbook workbook = openWorkbook(file, in)) {
            return workbookText(workbook);
        }
    }

    private String workbookText(Workbook workbook) {
        DataFormatter formatter = new DataFormatter(Locale.getDefault());
        FormulaEvaluator evaluator = null;
        try {
            evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        } catch (Exception ignored) {
        }
        StringBuilder text = new StringBuilder();
        for (int s = 0; s < workbook.getNumberOfSheets(); ++s) {
            Sheet sheet = workbook.getSheetAt(s);
            text.append("--- ").append(sheet.getSheetName()).append(" ---\n");
            for (int r = 0; r <= sheet.getLastRowNum(); ++r) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = 0; c < row.getLastCellNum(); ++c) {
                    Cell cell = row.getCell(c);
                    if (cell == null) {
                        text.append('\t');
                        continue;
                    }
                    try {
                        text.append(evaluator == null
                                ? formatter.formatCellValue(cell)
                                : formatter.formatCellValue(cell, evaluator));
                    } catch (Exception ex) {
                        text.append(formatter.formatCellValue(cell));
                    }
                    if (c + 1 < row.getLastCellNum()) {
                        text.append('\t');
                    }
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static void setParagraphText(XWPFParagraph paragraph, String text) {
        List<org.apache.poi.xwpf.usermodel.XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            paragraph.createRun().setText(text);
            return;
        }
        runs.get(0).setText(text, 0);
        for (int i = runs.size() - 1; i >= 1; --i) {
            paragraph.removeRun(i);
        }
    }

    private static String odfText(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("content.xml");
            if (entry == null) {
                return "";
            }
            String xml;
            try (InputStream in = zip.getInputStream(entry)) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            xml = xml.replaceAll("(?is)<text:tab\\s*/>", "\t")
                    .replaceAll("(?is)<text:line-break\\s*/>", "\n")
                    .replaceAll("(?is)</text:p>", "\n")
                    .replaceAll("(?is)</text:h>", "\n")
                    .replaceAll("(?is)<[^>]+>", "");
            return unescapeXml(xml);
        }
    }

    private static String unescapeXml(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static List<List<String>> parseDelimited(String text, char delimiter,
                                                     int maxRows, int maxCols) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quotes = false;
        for (int i = 0; i < text.length() && rows.size() < maxRows; ++i) {
            char c = text.charAt(i);
            if (quotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        ++i;
                    } else {
                        quotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quotes = true;
            } else if (c == delimiter) {
                if (current.size() >= maxCols - 1) {
                    break;
                }
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    ++i;
                }
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!current.isEmpty() || field.length() > 0) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    private Path resolve(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("~")) {
            value = System.getProperty("user.home", "");
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", "") + value.substring(1);
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private void guard(Path path) {
        String text = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (text.contains("/windows/system32") || text.contains("/windows/syswow64")
                || text.contains("/program files/windowsapps")
                || text.contains("/system volume information")
                || text.contains("/$recycle.bin")) {
            throw new IllegalArgumentException("Protected system path: " + path);
        }
    }

    private Path tempFor(Path target) {
        String name = target.getFileName().toString();
        return target.resolveSibling("." + name + ".cforge-" + System.nanoTime() + ".tmp");
    }

    private static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "\n…[truncated]";
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}