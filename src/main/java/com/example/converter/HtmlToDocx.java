package com.example.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已经拿到手的 HTML 文本 -> DOCX（保留文本和图片）
 *
 * 用途：配合 rs-fetch.js（sdenv/瑞数场景）这类"绕过反爬拿到真实 HTML，但不是通过
 * 真实浏览器请求拿到的"场景——具体为什么不能直接对目标网址重新发起 page.navigate()，
 * 见 {@link HtmlToPdf} 类注释和 rs-fetch.js 头部注释里的完整分析。
 *
 * 渲染这一步（HTML -&gt; PDF）完全交给 {@link HtmlToPdf} 处理，这个类只多做最后
 * 一步：复用 {@link DocumentConverter#convertPdfToDocx} 把 PDF 转成 DOCX
 * （LibreOffice writer_pdf_import，和 UrlToDocx 走的是同一条转换链路）。
 *
 * 本地用法：
 *   java -cp app.jar com.example.converter.HtmlToDocx &lt;html文件路径&gt; &lt;baseUrl&gt; [output.docx]
 *
 * 例如配合 rs-fetch.js：
 *   node rs-fetch.js "https://shaanxi.chinatax.gov.cn/xxx.html" /tmp/page.html
 *   java -cp app.jar com.example.converter.HtmlToDocx /tmp/page.html "https://shaanxi.chinatax.gov.cn/" /output/result.docx
 */
public class HtmlToDocx {

    private static final Logger log = LoggerFactory.getLogger(HtmlToDocx.class);

    private static final String OUTPUT_DIR = System.getenv().getOrDefault("OUTPUT_DIR", "/output");

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法: java -cp app.jar com.example.converter.HtmlToDocx <html文件路径> <baseUrl> [output.docx]");
            System.err.println("示例: java -cp app.jar com.example.converter.HtmlToDocx /tmp/page.html https://shaanxi.chinatax.gov.cn/ /output/result.docx");
            System.exit(2);
            return;
        }

        String htmlFilePath = args[0];
        String baseUrl = args[1];
        String outputPath = args.length > 2 ? args[2] : null;

        try {
            Path result = convert(htmlFilePath, baseUrl, outputPath);
            System.out.println("DOCX 已生成: " + result.toAbsolutePath());
        } catch (Exception e) {
            log.error("HTML 转 DOCX 失败", e);
            System.err.println("失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 把本地 HTML 文件转换为保留文本和图片的 DOCX
     *
     * @param htmlFilePath 本地 HTML 文件路径（比如 rs-fetch.js 写出来的那个文件）
     * @param baseUrl      这段 HTML 原本所在网站的地址，用来解析里面的相对路径资源
     * @param outputPath   输出文件路径；为 null 时按 &lt;title&gt; + 时间戳自动生成，保存到 OUTPUT_DIR
     * @return 实际生成的 DOCX 文件路径
     */
    public static Path convert(String htmlFilePath, String baseUrl, String outputPath) throws Exception {
        Path tempDir = Files.createTempDirectory("html-to-docx-");

        try {
            // 第一步：交给 HtmlToPdf 渲染成 PDF（setContent + <base> 注入，
            // 不会对目标网站重新发起会被拦截的文档级请求）
            Path pdfPath = tempDir.resolve("page.pdf");
            HtmlToPdf.convert(htmlFilePath, baseUrl, pdfPath.toString());

            // 第二步：复用现有的 PDF -> DOCX 转换链路（LibreOffice）
            log.info("正在用 LibreOffice 转换为 DOCX...");
            DocumentConverter converter = new DocumentConverter();
            String rawHtml = new String(Files.readAllBytes(Paths.get(htmlFilePath)), StandardCharsets.UTF_8);
            Path finalPath = resolveOutputPath(outputPath, extractTitle(rawHtml));
            try {
                String convertedDocx = converter.convertPdfToDocx(pdfPath.toString(), tempDir.toString());
                Path parent = finalPath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(Paths.get(convertedDocx), finalPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                converter.cleanup();
            }

            log.info("DOCX 已保存: {}", finalPath);
            return finalPath;

        } finally {
            try {
                if (Files.exists(tempDir)) {
                    Files.walk(tempDir)
                            .sorted((a, b) -> -a.compareTo(b))
                            .forEach(path -> {
                                try { Files.delete(path); } catch (IOException e) { /* ignore */ }
                            });
                }
            } catch (Exception e) {
                log.warn("清理临时文件失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 未指定输出路径时，按 HTML 里的 &lt;title&gt;（或没有的话退化成 "page"）
     * + 时间戳自动生成文件名。
     */
    private static Path resolveOutputPath(String outputPath, String title) {
        if (outputPath != null && !outputPath.isBlank()) {
            return Paths.get(outputPath);
        }
        String safeTitle = (title == null || title.isBlank())
                ? "page"
                : title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.length() > 60) {
            safeTitle = safeTitle.substring(0, 60);
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Paths.get(OUTPUT_DIR, safeTitle + "-" + timestamp + ".docx");
    }

    /**
     * 从原始 HTML 文本里提取 &lt;title&gt;（找不到就退化成 meta description），
     * 和 rs-fetch.js 里的同名逻辑保持一致，用来给自动命名的输出文件起一个有意义
     * 的名字，不依赖 Playwright 渲染后的 page.title()（避免为了拿标题多一次
     * 渲染开销）。
     */
    private static String extractTitle(String html) {
        Matcher titleMatch = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (titleMatch.find() && !titleMatch.group(1).isBlank()) {
            return titleMatch.group(1).trim();
        }
        Matcher descMatch = Pattern.compile(
                "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (descMatch.find() && !descMatch.group(1).isBlank()) {
            return descMatch.group(1).trim();
        }
        return null;
    }
}
