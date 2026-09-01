package com.example.converter;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已经拿到手的 HTML 文本 -> PDF
 *
 * 用途：配合 rs-fetch.js（sdenv/瑞数场景）这类"绕过反爬拿到真实 HTML，但不是通过
 * 真实浏览器请求拿到的"场景——这段 HTML 不能再让 Playwright 重新对目标网址发起
 * 一次 page.navigate()（那样又会撞回瑞数的验证拦截，见 rs-fetch.js 头部注释里的
 * 完整分析），只能在本地把这段 HTML 内容"注入"进一个真实浏览器页面渲染，不产生新
 * 的文档级请求。
 *
 * 流程：
 *   1. 读入 HTML 文本，在 &lt;head&gt; 里插入一个 &lt;base href="baseUrl"&gt; 标签
 *      ——图片 / CSS / 字体这些用相对路径写的资源，Chromium 解析时会自动基于这个
 *      base 拼成绝对 URL 去下载，不用我们自己写 JS 挨个改 img.src。
 *   2. Playwright 用 page.setContent(html) 把这段 HTML 直接注入渲染（不是
 *      page.navigate(url)，不会向目标站点重新发起一次可能被拦截的文档请求）。
 *   3. 等图片等资源加载完（networkidle），page.pdf() 打印成 PDF。
 *
 * 这个类只做到 PDF 这一步；如果要 DOCX（保留文本和图片可编辑），用
 * {@link HtmlToDocx}——它内部就是调用这个类拿到 PDF，再用 LibreOffice 转换。
 *
 * 本地用法：
 *   java -cp app.jar com.example.converter.HtmlToPdf &lt;html文件路径&gt; &lt;baseUrl&gt; [output.pdf]
 *
 * 例如配合 rs-fetch.js：
 *   node rs-fetch.js "https://shaanxi.chinatax.gov.cn/xxx.html" /tmp/page.html
 *   java -cp app.jar com.example.converter.HtmlToPdf /tmp/page.html "https://shaanxi.chinatax.gov.cn/" /output/result.pdf
 */
public class HtmlToPdf {

    private static final Logger log = LoggerFactory.getLogger(HtmlToPdf.class);

    private static final String OUTPUT_DIR = System.getenv().getOrDefault("OUTPUT_DIR", "/output");
    private static final int RENDER_TIMEOUT_MS = parseIntEnv("RENDER_TIMEOUT_MS", 20000);
    private static final int EXTRA_WAIT_MS = parseIntEnv("EXTRA_WAIT_MS", 3000);

    private static final Pattern HEAD_OPEN_TAG = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法: java -cp app.jar com.example.converter.HtmlToPdf <html文件路径> <baseUrl> [output.pdf]");
            System.err.println("示例: java -cp app.jar com.example.converter.HtmlToPdf /tmp/page.html https://shaanxi.chinatax.gov.cn/ /output/result.pdf");
            System.exit(2);
            return;
        }

        String htmlFilePath = args[0];
        String baseUrl = args[1];
        String outputPath = args.length > 2 ? args[2] : null;

        try {
            Path result = convert(htmlFilePath, baseUrl, outputPath);
            System.out.println("PDF 已生成: " + result.toAbsolutePath());
        } catch (Exception e) {
            log.error("HTML 转 PDF 失败", e);
            System.err.println("失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 把本地 HTML 文件转换为 PDF
     *
     * @param htmlFilePath 本地 HTML 文件路径（比如 rs-fetch.js 写出来的那个文件）
     * @param baseUrl      这段 HTML 原本所在网站的地址，用来解析里面的相对路径资源
     *                     （形如 "https://shaanxi.chinatax.gov.cn/"）
     * @param outputPath   输出文件路径；为 null 时按 &lt;title&gt; + 时间戳自动生成，保存到 OUTPUT_DIR
     * @return 实际生成的 PDF 文件路径
     */
    public static Path convert(String htmlFilePath, String baseUrl, String outputPath) throws Exception {
        String rawHtml = new String(Files.readAllBytes(Paths.get(htmlFilePath)), StandardCharsets.UTF_8);
        return convertHtml(rawHtml, baseUrl, outputPath);
    }

    /**
     * 和 {@link #convert(String, String, String)} 一样，只是直接传 HTML 字符串
     * （而不是文件路径），方便调用方（比如 HtmlToDocx）内部复用，不用先落盘。
     */
    public static Path convertHtml(String rawHtml, String baseUrl, String outputPath) throws Exception {
        String html = injectBaseTag(rawHtml, baseUrl);

        Path finalPath = resolveOutputPath(outputPath, null);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            try {
                Page page = browser.newPage();
                page.setDefaultTimeout(RENDER_TIMEOUT_MS);

                // 关键：setContent 是本地注入 DOM，不会对 baseUrl 发起新的文档级
                // HTTP 请求；里面 <img>/<link> 等相对路径资源会各自按 <base> 解析后
                // 发起独立的资源请求，这些资源请求通常不受同样严格的反爬校验。
                page.setContent(html, new Page.SetContentOptions().setTimeout(RENDER_TIMEOUT_MS));

                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(RENDER_TIMEOUT_MS));
                } catch (Exception e) {
                    log.warn("等待 networkidle 超时，继续执行: {}", e.getMessage());
                }

                if (EXTRA_WAIT_MS > 0) {
                    page.waitForTimeout(EXTRA_WAIT_MS);
                }

                String title = page.title();
                log.info("页面标题: {}", title);

                // outputPath 为 null 时，等拿到标题后再重新按标题生成一次文件名
                // （构造函数阶段还不知道 title，先给了个占位路径）。
                if (outputPath == null || outputPath.isBlank()) {
                    finalPath = resolveOutputPath(null, title);
                }

                Path parent = finalPath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                page.pdf(new Page.PdfOptions()
                        .setPath(finalPath)
                        .setFormat("A4")
                        .setPrintBackground(true));

                log.info("PDF 已生成: {}", finalPath);
                return finalPath;
            } finally {
                browser.close();
            }
        }
    }

    /**
     * 在 &lt;head&gt; 标签后插入 &lt;base href="baseUrl"&gt;，让文档里所有相对路径的
     * 资源引用（图片、CSS、字体等）都能正确解析成绝对 URL。如果原始 HTML 里已经有
     * 自己的 &lt;base&gt; 标签，不覆盖它——尊重页面原有设定。
     */
    static String injectBaseTag(String html, String baseUrl) {
        if (Pattern.compile("<base[\\s>]", Pattern.CASE_INSENSITIVE).matcher(html).find()) {
            log.info("HTML 里已经有 <base> 标签，不重复插入");
            return html;
        }
        String baseTag = "<base href=\"" + baseUrl.replace("\"", "&quot;") + "\">";
        Matcher m = HEAD_OPEN_TAG.matcher(html);
        if (m.find()) {
            return new StringBuilder(html).insert(m.end(), baseTag).toString();
        }
        // 没有 <head> 标签的极端情况：兜底塞在最前面，多数浏览器解析器依然会认可。
        log.warn("HTML 里没有找到 <head> 标签，把 <base> 标签插到文档最前面");
        return baseTag + html;
    }

    static Path resolveOutputPath(String outputPath, String title) {
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
        return Paths.get(OUTPUT_DIR, safeTitle + "-" + timestamp + ".pdf");
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
