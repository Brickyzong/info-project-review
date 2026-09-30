package com.xmps.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 模板加载器——从 classpath:prompt-templates/ 加载提示词模板。
 *
 * <p>模板使用 {@code {{KEY}}} 双花括号占位符，render 时按变量替换。
 * 刻意不使用 String.format，避免模板正文中字面百分号（如 70%）被误解析为格式说明符。</p>
 */
@Component
public class PromptTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);

    private final Map<String, String> templates = new LinkedHashMap<>();

    public PromptTemplateLoader() {
        try {
            var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            var resources = resolver.getResources("classpath:prompt-templates/*.txt");
            for (var r : resources) {
                String name = r.getFilename();
                if (name == null) continue;
                // key 去掉 .txt 后缀，调用方使用无后缀语义名（如 "dedup-system"）
                String key = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
                String content = r.getContentAsString(StandardCharsets.UTF_8);
                templates.put(key, content);
                log.info("Prompt 模板已加载: {} ({} chars)", name, content.length());
            }
            if (templates.isEmpty()) {
                log.error("Prompt 模板加载失败——classpath:prompt-templates/ 下未找到任何 .txt");
            }
        } catch (IOException e) {
            log.error("加载 Prompt 模板失败", e);
        }
    }

    public String get(String name) {
        return templates.get(name);
    }

    /**
     * 渲染模板：将 {@code {{key}}} 占位符替换为 vars 中的值。
     */
    public String render(String name, Map<String, String> vars) {
        String template = templates.get(name);
        if (template == null) {
            throw new IllegalStateException("Prompt 模板不存在: " + name);
        }
        String result = template;
        for (var e : vars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }
}
