package com.xmps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmps.llm.LlmClient;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.ProjectType;
import com.xmps.rules.RulesLoader;
import com.xmps.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 判重服务——语义识别项目类型，与历史项目库比对，判断重复建设。
 *
 * 一期（M4）：LLM 语义判重（无需向量库）。
 * 二期接入向量库后，先向量检索 Top-N 相似历史项目，再交给 LLM 精细比对。
 */
@Service
public class DedupService {

    private static final Logger log = LoggerFactory.getLogger(DedupService.class);

    private final LlmClient llmClient;
    private final RulesLoader rulesLoader;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptTemplateLoader;
    private final HistoryProjectStore historyProjectStore;

    public DedupService(LlmClient llmClient, RulesLoader rulesLoader,
                        VectorStore vectorStore, ObjectMapper objectMapper,
                        PromptTemplateLoader promptTemplateLoader, HistoryProjectStore historyProjectStore) {
        this.llmClient = llmClient;
        this.rulesLoader = rulesLoader;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.promptTemplateLoader = promptTemplateLoader;
        this.historyProjectStore = historyProjectStore;
    }

    /**
     * 执行判重分析。
     *
     * @param documentText 方案文档全文
     * @param task         评审任务（已设置 projectType）
     * @return 判重结果 JSON 字符串
     */
    public DedupResult analyze(String documentText, ReviewTask task) {
        String systemPrompt = buildDedupSystemPrompt();
        String userPrompt = buildDedupUserPrompt(documentText, task);

        log.info("判重分析开始 — taskId={}, type={}", task.getId(), task.getProjectType());
        String llmResponse = llmClient.chat(systemPrompt, userPrompt);

        DedupResult result = parseResult(llmResponse, task.getId());
        task.setHasDuplicate(result.duplicate);
        task.setDedupDetail(formatDedupDetail(result));

        log.info("判重分析完成 — taskId={}, duplicate={}, similarCount={}",
                task.getId(), result.duplicate, result.similarProjects != null ? result.similarProjects.size() : 0);

        return result;
    }

    /**
     * 构造判重 system prompt
     */
    private String buildDedupSystemPrompt() {
        String rules = rulesLoader.get("05-项目类型判定规则.md") != null
                ? rulesLoader.get("05-项目类型判定规则.md")
                : rulesLoader.getConstructionRules();
        return promptTemplateLoader.render("dedup-system", Map.of(
                "RULES", rules,
                "HISTORY", historyProjectStore.getContext()
        ));
    }

    private String buildDedupUserPrompt(String documentText, ReviewTask task) {
        return promptTemplateLoader.render("dedup-user", Map.of(
                "PROJECT_TYPE", task.getProjectType() != null ? task.getProjectType().getLabel() : "未知",
                "DOCUMENT", documentText
        ));
    }

    /**
     * 解析 LLM 返回的 JSON 为 DedupResult
     */
    private DedupResult parseResult(String llmResponse, String taskId) {
        try {
            // 移除可能的 markdown 包裹
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.substring(json.indexOf("\n") + 1);
                if (json.endsWith("```")) {
                    json = json.substring(0, json.lastIndexOf("```")).trim();
                }
            }
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return new DedupResult(
                    Boolean.TRUE.equals(map.get("duplicate")),
                    (String) map.getOrDefault("conclusion", ""),
                    (String) map.getOrDefault("description", ""),
                    parseSimilarProjects(map.get("similarProjects"))
            );
        } catch (JsonProcessingException e) {
            log.error("判重结果 JSON 解析失败 — taskId={}, response={}", taskId, llmResponse, e);
            return new DedupResult(false, "判重分析异常，默认不重复", "", List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private List<SimilarProject> parseSimilarProjects(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        Map<String, Object> m = (Map<String, Object>) item;
                        return new SimilarProject(
                                (String) m.getOrDefault("name", ""),
                                (String) m.getOrDefault("similarity", ""),
                                (String) m.getOrDefault("overlap", "")
                        );
                    })
                    .toList();
        }
        return List.of();
    }

    private String formatDedupDetail(DedupResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"duplicate\":" + result.duplicate + ",\"conclusion\":\"" + result.conclusion + "\"}";
        }
    }

    // ============================================================
    // 内部类
    // ============================================================

    public record DedupResult(
            boolean duplicate,
            String conclusion,
            String description,
            List<SimilarProject> similarProjects
    ) {}

    public record SimilarProject(String name, String similarity, String overlap) {}
}
