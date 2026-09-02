package com.xmps.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档解析服务——从 .docx / .pdf / .doc 文件中提取纯文本。
 * .doc 老格式通过 LibreOffice headless 转 .docx 后解析。
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    /**
     * 解析文件提取全文文本。
     *
     * @param filePath 文件的绝对路径
     * @param filename 原始文件名（用于判断格式）
     * @return 提取的纯文本，截断至 maxLength 字符
     */
    public String parse(String filePath, String filename) {
        return parse(filePath, filename, 50_000);
    }

    /**
     * 解析文件，指定最大返回长度。
     */
    public String parse(String filePath, String filename, int maxLength) {
        String lower = filename.toLowerCase();
        try {
            String text;
            if (lower.endsWith(".docx")) {
                text = parseDocx(filePath);
            } else if (lower.endsWith(".doc")) {
                text = parseDoc(filePath);
            } else if (lower.endsWith(".pdf")) {
                text = parsePdf(filePath);
            } else {
                throw new IllegalArgumentException("不支持的文件格式: " + filename);
            }

            // 空文本检测——扫描件 / 图片型 PDF 的兜底
            if (text.isEmpty()) {
                log.warn("文档内容为空，可能是扫描件或图片型文档 — filename={}", filename);
                throw new RuntimeException(
                        "文档解析结果为空，可能为扫描件或图片型 PDF。" +
                        "请上传可编辑的 .docx 格式方案文档。若确为扫描件，请联系管理员启用 OCR 功能。"
                );
            }

            if (text.length() > maxLength) {
                log.info("文档内容过长，截断 — original={}, truncated={}", text.length(), maxLength);
                text = text.substring(0, maxLength) + "\n\n[... 内容过长，已截断 ...]";
            }

            log.info("文档解析完成 — filename={}, chars={}", filename, text.length());
            return text;

        } catch (Exception e) {
            log.error("文档解析失败 — filename={}, error={}", filename, e.getMessage(), e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 .docx（Apache POI XWPF）
     */
    private String parseDocx(String filePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = Files.newInputStream(Path.of(filePath));
             XWPFDocument doc = new XWPFDocument(is)) {

            doc.getParagraphs().forEach(p -> {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            });

            // 表格内容
            doc.getTables().forEach(table ->
                table.getRows().forEach(row ->
                    row.getTableCells().forEach(cell ->
                        sb.append(cell.getText()).append("\t")
                    )
                )
            );
        }
        return sb.toString().trim();
    }

    /**
     * 解析 .pdf（Apache PDFBox）
     */
    private String parsePdf(String filePath) throws IOException {
        try (PDDocument doc = Loader.loadPDF(Path.of(filePath).toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).trim();
        }
    }

    /**
     * 解析 .doc 老格式——通过 LibreOffice headless 转为 .docx 后再解析。
     * 要求系统已安装 LibreOffice（生产环境部署步骤之一）。
     */
    private String parseDoc(String filePath) throws IOException, InterruptedException {
        Path src = Path.of(filePath);
        Path tmpDir = src.getParent();

        // 调用 LibreOffice headless 转换
        ProcessBuilder pb = new ProcessBuilder(
                "libreoffice", "--headless", "--convert-to", "docx",
                "--outdir", tmpDir.toString(),
                src.toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("LibreOffice 转换失败 (exit=" + exitCode + "): " + output);
        }

        // 找到生成的 .docx 文件
        String baseName = src.getFileName().toString();
        // foo.doc → foo.docx
        String docxName = baseName.substring(0, baseName.lastIndexOf('.')) + ".docx";
        Path docxPath = tmpDir.resolve(docxName);

        if (!Files.exists(docxPath)) {
            throw new RuntimeException("LibreOffice 转换后找不到 .docx 文件: " + docxPath);
        }

        try {
            return parseDocx(docxPath.toString());
        } finally {
            // 清理临时 .docx
            try { Files.deleteIfExists(docxPath); } catch (IOException ignored) {}
        }
    }
}
