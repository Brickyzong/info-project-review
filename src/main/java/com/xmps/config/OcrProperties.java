package com.xmps.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OCR 兜底配置属性（xmps.ocr.*）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "xmps.ocr")
public class OcrProperties {
    /**
     * 是否启用 OCR 兜底。默认 true；生产可用 XMPS_OCR_ENABLED 关闭。
     */
    private boolean enabled = true;
    /**
     * tesseract 可执行文件路径；默认取 PATH 中的 tesseract。
     */
    private String tesseractPath = "tesseract";
    /**
     * 识别语言包，默认中文简体 + 英文。
     */
    private String lang = "chi_sim+eng";
    /**
     * 主解析文本低于该字数时才尝试 OCR 兜底（避免对正常文档无谓调用）。
     */
    private int minTextLength = 50;
}
