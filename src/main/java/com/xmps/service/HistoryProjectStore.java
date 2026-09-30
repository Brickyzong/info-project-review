package com.xmps.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 历史项目库——判重智能体的一期比对依据（M4 要求的"内置样例项目库"）。
 *
 * <p>从 classpath:history-projects.json 加载，供 DedupService 注入判重提示词，
 * 使判重从"凭空判断"变为"与历史项目实际比对"。二期接入向量库后可并行保留此库作为兜底。</p>
 */
@Component
public class HistoryProjectStore {

    private static final Logger log = LoggerFactory.getLogger(HistoryProjectStore.class);

    private final List<HistoryProject> projects;

    public HistoryProjectStore(ObjectMapper objectMapper) {
        List<HistoryProject> loaded = List.of();
        try {
            var res = new org.springframework.core.io.ClassPathResource("history-projects.json");
            loaded = objectMapper.readValue(res.getInputStream(), new TypeReference<>() {
            });
        } catch (IOException e) {
            log.error("历史项目库加载失败——classpath:history-projects.json", e);
        }
        this.projects = loaded;
        log.info("历史项目库加载完成，共 {} 条", this.projects.size());
    }

    public List<HistoryProject> getProjects() {
        return Collections.unmodifiableList(projects);
    }

    /**
     * 格式化为判重提示词可用的比对文本。
     */
    public String getContext() {
        if (projects.isEmpty()) {
            return "（暂无历史项目数据）";
        }
        StringBuilder sb = new StringBuilder();
        for (var p : projects) {
            sb.append("- ").append(p.name()).append("\n");
            sb.append("  核心建设内容：").append(p.coreContent()).append("\n");
            sb.append("  功能点：").append(String.join("、", p.functions())).append("\n");
            sb.append("  服务对象：").append(p.serviceObject()).append("\n");
            sb.append("  技术路线：").append(p.techRoute()).append("\n\n");
        }
        return sb.toString().trim();
    }

    public record HistoryProject(
            String name,
            String coreContent,
            List<String> functions,
            String serviceObject,
            String techRoute
    ) {
    }
}
