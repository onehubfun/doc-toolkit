package com.example.converter;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 网站转 DOCX 命令行工具
 *
 * 只做一件事：输入一个网址，输出一个保留了文本和图片的 DOCX 文件。
 * 流程：Playwright 把网页打印成 PDF -> LibreOffice 把 PDF 转成 DOCX
 * （不用 PDFBox 抽文本重排，是真正的格式转换，图片和排版都会保留）。
 *
 * 本地用法：
 *   java -cp app.jar com.example.converter.UrlToDocx <url> [output.docx]
 *
 * Docker 用法：
 *   docker run --rm -v "$(pwd)/output:/output" url-to-docx "https://example.com"
 *   docker run --rm -v "$(pwd)/output:/output" url-to-docx "https://example.com" /output/my.docx
 *
 * 可选环境变量：
 *   OUTPUT_DIR      未指定输出文件名时的默认保存目录（默认 /output）
 *   NAV_TIMEOUT_MS  页面导航超时时间，毫秒（默认 30000）
 *   EXTRA_WAIT_MS   networkidle 后额外等待时间，用于等待懒加载内容，毫秒（默认 3000）
 */
public class UrlToDocx {

    private static final Logger log = LoggerFactory.getLogger(UrlToDocx.class);

    private static final String OUTPUT_DIR = System.getenv().getOrDefault("OUTPUT_DIR", "/output");
    private static final int NAV_TIMEOUT_MS = parseIntEnv("NAV_TIMEOUT_MS", 30000);
    private static final int EXTRA_WAIT_MS = parseIntEnv("EXTRA_WAIT_MS", 3000);
    private static final String DESKTOP_USER_AGENT = System.getenv().getOrDefault("USER_AGENT",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("用法: java -cp app.jar com.example.converter.UrlToDocx <url> [output.docx]");
            System.err.println("示例: docker run --rm -v \"$(pwd)/output:/output\" url-to-docx https://example.com");
            System.exit(2);
            return;
        }

        String url = normalizeUrl(args[0].trim());
        String outputPath = args.length > 1 ? args[1] : null;

        try {
            Path result = convert(url, outputPath);
            System.out.println("DOCX 已生成: " + result.toAbsolutePath());
        } catch (Exception e) {
            log.error("网页转 DOCX 失败: {}", url, e);
            System.err.println("失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 抓取指定 URL，导出为保留文本和图片的 DOCX
     *
     * @param url        目标网址
     * @param outputPath 输出文件路径；为 null 时按页面标题+时间戳自动生成，保存到 OUTPUT_DIR
     * @return 实际生成的 DOCX 文件路径
     */
    public static Path convert(String url, String outputPath) throws Exception {
        Path tempDir = Files.createTempDirectory("url-to-docx-");
        Path pdfPath = tempDir.resolve("page.pdf");

        try {
            log.info("正在打开网页: {}", url);
            String title;

            // 第一步：用 Playwright 把网页打印成 PDF
            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch();
                try {
                    // 部分网站（尤其国内政府站点常见的 WAF/反爬网关）会检测到 UA 里的
                    // "HeadlessChrome" 字样直接空响应断连（net::ERR_EMPTY_RESPONSE），
                    // 但放行普通桌面 Chrome UA。这里换成一个不带 Headless 标识的 UA。
                    Page page = browser.newPage(new Browser.NewPageOptions().setUserAgent(DESKTOP_USER_AGENT));
                    page.setViewportSize(1920, 1080);
                    page.setDefaultTimeout(NAV_TIMEOUT_MS);

                    page.navigate(url);

                    try {
                        page.waitForLoadState(LoadState.NETWORKIDLE);
                    } catch (Exception e) {
                        log.warn("等待 networkidle 超时，继续执行: {}", e.getMessage());
                    }

                    if (EXTRA_WAIT_MS > 0) {
                        page.waitForTimeout(EXTRA_WAIT_MS);
                    }

                    title = page.title();
                    log.info("页面标题: {}", title);

                    page.pdf(new Page.PdfOptions()
                            .setPath(pdfPath)
                            .setFormat("A4")
                            .setPrintBackground(true));

                    log.info("PDF 已生成: {}", pdfPath);
                } finally {
                    browser.close();
                }
            }

            // 第二步：用 LibreOffice 把 PDF 直接转换为 DOCX（保留文本和图片）
            log.info("正在用 LibreOffice 转换为 DOCX...");
            DocumentConverter converter = new DocumentConverter();
            Path finalPath = resolveOutputPath(outputPath, title);
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

    private static String normalizeUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }

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

    private static int parseIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("环境变量 {} 不是合法整数: {}，使用默认值 {}", name, value, defaultValue);
            return defaultValue;
        }
    }
}
