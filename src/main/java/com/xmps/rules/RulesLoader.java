package com.xmps.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 规则加载器——从 knowledge_base/ 目录加载 01-10 规则文件。
 * 规则注入 LLM system prompt 作为评审依据。
 * 优先从项目根目录 knowledge_base/ 加载（便于热更新），fallback 到 classpath。
 */
@Component
public class RulesLoader {

    private static final Logger log = LoggerFactory.getLogger(RulesLoader.class);

    private final Map<String, String> rules = new LinkedHashMap<>();

    public RulesLoader() {
        Map<String, String> loaded = loadKnowledgeBase();
        if (loaded.isEmpty()) {
            loaded = loadFromClasspath();
        }
        rules.putAll(loaded);
        if (rules.isEmpty()) {
            log.error("规则加载失败——未找到任何规则文件，评审引擎将无法正常工作");
        } else {
            log.info("规则加载完成，共 {} 条", rules.size());
        }
    }

    public Map<String, String> allRules() {
        return Map.copyOf(rules);
    }

    public String get(String name) {
        return rules.get(name);
    }

    public String toSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("【信息化项目评审规则】\n\n");
        sb.append("审查原则：先判属性、再行审核、分类处置、全程留痕。\n\n");
        String[] order = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10"};
        for (String prefix : order) {
            rules.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .forEach(e -> {
                        sb.append("---\n");
                        sb.append("## ").append(e.getKey()).append("\n\n");
                        sb.append(e.getValue()).append("\n\n");
                    });
        }
        return sb.toString();
    }

    public String getConstructionRules() {
        return filterByPrefixes("02", "04", "05", "06", "07", "08", "09", "10");
    }

    public String getOperationRules() {
        return filterByPrefixes("03", "04", "05", "07", "08", "09", "10");
    }

    private String filterByPrefixes(String... prefixes) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> set = java.util.Set.of(prefixes);
        rules.entrySet().stream()
                .filter(e -> set.stream().anyMatch(p -> e.getKey().startsWith(p)))
                .forEach(e -> {
                    sb.append("## ").append(e.getKey()).append("\n\n");
                    sb.append(e.getValue()).append("\n\n---\n\n");
                });
        return sb.toString();
    }

    private Map<String, String> loadKnowledgeBase() {
        Map<String, String> result = new LinkedHashMap<>();
        Path kbDir = Paths.get("knowledge_base");
        if (!Files.isDirectory(kbDir)) {
            log.warn("knowledge_base 目录不存在: {}，将从 classpath 加载", kbDir.toAbsolutePath());
            return result;
        }
        try (Stream<Path> files = Files.list(kbDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            result.put(p.getFileName().toString(), content);
                            log.info("规则已加载: {} ({} chars)", p.getFileName(), content.length());
                        } catch (IOException e) {
                            log.error("读取规则文件失败: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("扫描 knowledge_base 目录失败: {}", e.getMessage(), e);
        }
        return result;
    }

    private Map<String, String> loadFromClasspath() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                    new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            org.springframework.core.io.Resource[] resources =
                    resolver.getResources("classpath:knowledge_base/*.md");
            for (org.springframework.core.io.Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null) continue;
                String content = r.getContentAsString(StandardCharsets.UTF_8);
                result.put(filename, content);
                log.info("规则已加载(classpath): {} ({} chars)", filename, content.length());
            }
        } catch (IOException e) {
            log.error("从 classpath 加载规则失败: {}", e.getMessage());
        }
        return result;
    }
}
