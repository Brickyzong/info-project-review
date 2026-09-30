package com.xmps.service;

import com.xmps.config.OcrProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OCR 兜底识别服务（调用系统已安装的 Tesseract CLI）。
 *
 * <p>定位：仅作为文档主解析（POI / PDFBox）抽不到足够文本时的<strong>兜底</strong>。
 * <ul>
 *   <li>不引入额外 Maven 依赖，直接调用系统 {@code tesseract} 命令，部署侧自行安装语言包。</li>
 *   <li>若 tesseract 未安装 / 不在 PATH / 语言包缺失，所有方法返回空串并记 WARN，
 *       <strong>绝不抛异常、绝不阻断主流程</strong>——由调用方决定是否真的解析失败。</li>
 *   <li>可用性探测结果缓存，仅首次触发 OCR 时探测一次。</li>
 * </ul>
 */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final boolean enabled;
    private final String tesseractPath;
    private final String lang;
    private volatile Boolean available;

    @Autowired
    public OcrService(OcrProperties props) {
        this.enabled = props.isEnabled();
        this.tesseractPath = (props.getTesseractPath() == null || props.getTesseractPath().isBlank())
                ? "tesseract" : props.getTesseractPath();
        this.lang = (props.getLang() == null || props.getLang().isBlank()) ? "chi_sim+eng" : props.getLang();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 探测 tesseract 是否可用（结果缓存，仅探测一次）。
     */
    public boolean isAvailable() {
        if (!enabled) return false;
        Boolean a = available;
        if (a != null) return a;
        synchronized (this) {
            if (available != null) return available;
            try {
                Process p = new ProcessBuilder(tesseractPath, "--version").start();
                p.waitFor(10, TimeUnit.SECONDS);
                available = true;
                log.info("[OCR] Tesseract 探测可用 — path={}", tesseractPath);
            } catch (Throwable t) {
                available = false;
                log.warn("[OCR] Tesseract 不可用（未安装或不在 PATH），OCR 兜底关闭 — {}", t.getMessage());
            }
            return available;
        }
    }

    /**
     * 对单个图片做 OCR。未知 / 失败返回空串。
     */
    public String ocrImage(BufferedImage img) {
        if (!isAvailable() || img == null) return "";
        Path png = null, out = null;
        try {
            png = Files.createTempFile("ocr-", ".png");
            out = Files.createTempFile("ocr-", "");
            ImageIO.write(img, "png", png.toFile());

            List<String> cmd = new ArrayList<>();
            cmd.add(tesseractPath);
            cmd.add(png.toAbsolutePath().toString());
            cmd.add(out.toAbsolutePath().toString());
            cmd.add("-l");
            cmd.add(lang);

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor(120, TimeUnit.SECONDS);

            Path txt = Path.of(out.toAbsolutePath() + ".txt");
            if (Files.exists(txt)) {
                return new String(Files.readAllBytes(txt), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            log.warn("[OCR] 单图识别失败 — {}", t.getMessage());
        } finally {
            safeDelete(png);
            safeDelete(out);
            safeDelete(Path.of(out.toAbsolutePath() + ".txt"));
        }
        return "";
    }

    /**
     * 对 PDF 逐页渲染后 OCR（扫描件兜底）。
     */
    public String ocrPdf(Path pdfPath) {
        if (!isAvailable()) return "";
        StringBuilder sb = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 200);
                String pageText = ocrImage(img);
                if (!pageText.isBlank()) sb.append(pageText).append("\n");
            }
        } catch (Throwable t) {
            log.warn("[OCR] PDF 渲染/OCR 失败 — {}", t.getMessage());
        }
        return sb.toString().trim();
    }

    /**
     * 对 docx 内嵌图片做 OCR（图片型 docx 兜底）。
     */
    public String ocrDocxImages(Path docxPath) {
        if (!isAvailable()) return "";
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(docxPath))) {
            for (XWPFPictureData pic : doc.getAllPictures()) {
                try {
                    byte[] data = pic.getData();
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        String t = ocrImage(img);
                        if (!t.isBlank()) sb.append(t).append("\n");
                    }
                } catch (Throwable ignore) {
                    // 单张图失败不影响整体
                }
            }
        } catch (Throwable t) {
            log.warn("[OCR] docx 图片提取/OCR 失败 — {}", t.getMessage());
        }
        return sb.toString().trim();
    }

    /**
     * 统一入口：按扩展名选择 OCR 策略。
     */
    public String ocrFile(Path filePath, String filename) {
        if (!isAvailable()) return "";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return ocrPdf(filePath);
        if (lower.endsWith(".docx")) return ocrDocxImages(filePath);
        // .doc 已走 LibreOffice 转 docx，OCR 价值低，跳过
        return "";
    }

    private void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件清理失败不阻断主流程
        }
    }
}
