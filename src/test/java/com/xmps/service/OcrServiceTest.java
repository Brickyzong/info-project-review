package com.xmps.service;

import com.xmps.config.OcrProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OcrService 单元测试（不依赖 Spring 容器、不调用真实 Tesseract）。
 * 本环境未安装 tesseract，重点验证「不可用即降级、绝不抛异常」的兜底语义。
 */
class OcrServiceTest {

    private OcrService enabledService() {
        OcrProperties props = new OcrProperties();
        props.setEnabled(true);
        props.setTesseractPath("tesseract");
        return new OcrService(props);
    }

    @Test
    void unavailableWhenTesseractNotInstalled() {
        // 本环境无 tesseract，应探测为不可用且不抛异常
        OcrService svc = enabledService();
        assertFalse(svc.isAvailable(), "本环境未安装 tesseract，应探测为不可用");
    }

    @Test
    void disabledSkipsProbe() {
        OcrProperties props = new OcrProperties();
        props.setEnabled(false);
        OcrService svc = new OcrService(props);
        assertFalse(svc.isAvailable());
    }

    @Test
    void ocrFileReturnsEmptyAndNeverThrowsWhenUnavailable() {
        OcrService svc = enabledService();
        // 即使传入不存在的路径，也不应抛异常，返回空串
        assertEquals("", svc.ocrFile(Path.of("no-such-file.pdf"), "x.pdf"));
    }

    @Test
    void configDefaults() {
        OcrProperties props = new OcrProperties();
        assertTrue(props.isEnabled());
        assertEquals("tesseract", props.getTesseractPath());
        assertEquals("chi_sim+eng", props.getLang());
        assertEquals(50, props.getMinTextLength());
    }
}
