package com.example.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PDF -> DOCX 转换（通过 LibreOffice Headless）
 *
 * 只保留 url-to-docx 工具实际用到的能力。历史上这个类还带过网页抓取
 * （Playwright）、PDFBox 文本抽取、POI 拼 docx 等功能，但 UrlToDocx/UrlToPdf
 * 从未调用过它们——为了精简镜像和依赖，连同 pdfbox/poi-ooxml 这两个第三方库
 * 一起从 pom.xml 里去掉了。如果之后要恢复文本抽取之类的功能，从 git 历史里
 * 找回来即可。
 *
 * 使用示例：
 * <pre>
 * {@code
 * DocumentConverter converter = new DocumentConverter();
 * converter.convertPdfToDocx("input.pdf", "/output/dir");
 * converter.cleanup();
 * }
 * </pre>
 */
public class DocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);

    // LibreOffice 路径（容器内）
    private static final String LIBREOFFICE_CMD = "libreoffice";

    private final Path tempDir;

    public DocumentConverter() throws IOException {
        this.tempDir = Files.createTempDirectory("doc-converter-");
        log.info("Temp directory: {}", tempDir);
    }

    /**
     * 将 PDF 文件转换为 DOCX 格式
     *
     * @param inputPdf  输入的 PDF 文件路径
     * @param outputDir 输出的目录（DOCX 文件将保存在此）
     * @return 转换后的 DOCX 文件路径
     */
    public String convertPdfToDocx(String inputPdf, String outputDir)
            throws IOException, InterruptedException {

        log.info("Converting PDF to DOCX: {}", inputPdf);

        Path outputPath = Paths.get(outputDir);
        if (!Files.exists(outputPath)) {
            Files.createDirectories(outputPath);
        }

        // 调用 LibreOffice Headless
        // 注意：必须显式指定 --infilter="writer_pdf_import"。
        // LibreOffice 默认会把 PDF 当作 Draw（绘图）文档打开，而 Draw 文档没有到 docx 的
        // 导出路径，若不指定会报 "no export filter for xxx.docx found, aborting"。
        // 指定 writer_pdf_import 后会以 Writer 方式导入 PDF，可正常导出为 docx，
        // 且文本与图片都会被保留（不同于 PDFBox 只能抽取纯文本）。
        ProcessBuilder pb = new ProcessBuilder(
            LIBREOFFICE_CMD,
            "--headless",
            "--infilter=writer_pdf_import",
            "--convert-to", "docx",
            "--outdir", outputPath.toString(),
            inputPdf
        );

        // 捕获输出以便调试
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 读取输出
        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error("LibreOffice conversion failed: {}", output);
            throw new IOException("Conversion failed with exit code: " + exitCode);
        }

        // 查找生成的 DOCX 文件
        String baseName = Paths.get(inputPdf).getFileName().toString();
        baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        Path docxPath = outputPath.resolve(baseName + ".docx");

        if (!Files.exists(docxPath)) {
            throw new IOException("Expected DOCX file not found: " + docxPath);
        }

        log.info("Conversion successful: {}", docxPath);
        return docxPath.toString();
    }

    /**
     * 读取进程输出流
     */
    private String readStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().reduce("", (a, b) -> a + "\n" + b);
        }
    }

    /**
     * 清理临时文件
     */
    public void cleanup() {
        try {
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                     .sorted((a, b) -> -a.compareTo(b))
                     .forEach(path -> {
                         try { Files.delete(path); } catch (IOException e) { /* ignore */ }
                     });
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp files", e);
        }
    }
}
