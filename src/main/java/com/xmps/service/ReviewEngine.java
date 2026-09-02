package com.xmps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmps.llm.LlmClient;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.ProjectType;
import com.xmps.model.enums.ReviewVerdict;
import com.xmps.rules.RulesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 评审引擎——执行建设类六项审查 / 运维类四项审查。
 *
 * 设计：规则引擎（确定性判定）+ LLM（语义理解深化），
 * 每项输出 (通过/不通过/存疑/不适用) + 问题描述 + 修改建议 + 参考依据。
 */
@Service
public class ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngine.class);

    private final LlmClient llmClient;
    private final RulesLoader rulesLoader;
    private final ObjectMapper objectMapper;

    public ReviewEngine(LlmClient llmClient, RulesLoader rulesLoader, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.rulesLoader = rulesLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行全部审查。
     *
     * @param documentText 方案文档全文
     * @param task         评审任务（含 projectType）
     * @return 审查结果列表
     */
    public List<ReviewItem> review(String documentText, ReviewTask task) {
        List<String> items;
        if (task.getProjectType() == ProjectType.OPERATION) {
            items = List.of(
                    "准入门槛审查",
                    "方案完整性检查",
                    "政务云资源合规核查",
                    "绩效考核风险"
            );
        } else {
            items = List.of(
                    "准入门槛审查",
                    "方案完整性检查",
                    "信创合规检查",
                    "绩效考核风险",
                    "政务云资源合规核查"
            );
            // 建设类共六项：判重已单独执行，此处执行其余五项
            // 判重结果通过 task.hasDuplicate / task.dedupDetail 获取
        }

        String systemPrompt = buildReviewSystemPrompt(task.getProjectType());
        String userPrompt = buildReviewUserPrompt(documentText, items);

        log.info("评审引擎启动 — taskId={}, type={}, items={}",
                task.getId(), task.getProjectType(), items.size());

        String llmResponse = llmClient.chat(systemPrompt, userPrompt);

        List<ReviewItem> results = parseReviewResults(llmResponse, task.getId());

        log.info("评审引擎完成 — taskId={}, results={}", task.getId(), results.size());
        return results;
    }

    // ============================================================
    // Prompt 构造
    // ============================================================

    private String buildReviewSystemPrompt(ProjectType type) {
        String rules = type == ProjectType.OPERATION
                ? rulesLoader.getOperationRules()
                : rulesLoader.getConstructionRules();

        return """
        你是泰兴市政务信息化项目评审智能体。你的职责是对方案文档逐项执行合规性审查。

        ## 审查原则
        先判属性、再行审核、分类处置、全程留痕。

        ## 审查规则（来自知识库）
        %s

        ## 审查要求

        1. 逐项审查，不可跳过
        2. 每个审查项给出结论（通过/不通过/存疑/不适用）
        3. 不通过或存疑时，必须给出：问题描述、修改建议、参考依据
        4. 依据规则，不主观臆断

        ## 输出格式（严格 JSON 数组，不要 markdown 包裹）

        [
          {
            "item": "审查项名称",
            "verdict": "通过 | 不通过 | 存疑 | 不适用",
            "problem": "问题描述（通过/不适用时为空）",
            "suggestion": "修改建议（通过/不适用时为空）",
            "reference": "参考依据（引用规则原文或条款）"
          }
        ]
        """.formatted(rules);
    }

    private String buildReviewUserPrompt(String documentText, List<String> items) {
        return """
        ## 审查项清单
        %s

        ## 方案文档内容
        %s

        请按审查项清单逐项审查，输出 JSON 数组。
        """.formatted(
                items.stream().map(i -> "- " + i).collect(Collectors.joining("\n")),
                documentText
        );
    }

    // ============================================================
    // 结果解析
    // ============================================================

    @SuppressWarnings("unchecked")
    private List<ReviewItem> parseReviewResults(String llmResponse, String taskId) {
        try {
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.substring(json.indexOf("\n") + 1);
                if (json.endsWith("```")) {
                    json = json.substring(0, json.lastIndexOf("```")).trim();
                }
            }
            List<Map<String, Object>> raw = objectMapper.readValue(json, List.class);
            return raw.stream()
                    .map(this::toReviewItem)
                    .toList();
        } catch (JsonProcessingException e) {
            log.error("审查结果 JSON 解析失败 — taskId={}, response={}", taskId, llmResponse, e);
            return List.of(new ReviewItem(
                    "解析异常", ReviewVerdict.UNCERTAIN,
                    "审查结果解析失败", "请人工复核", "LLM 返回非标准 JSON: " + llmResponse.substring(0, Math.min(200, llmResponse.length()))
            ));
        }
    }

    private ReviewItem toReviewItem(Map<String, Object> map) {
        String verdictStr = (String) map.getOrDefault("verdict", "存疑");
        ReviewVerdict verdict = switch (verdictStr) {
            case "通过" -> ReviewVerdict.PASS;
            case "不通过" -> ReviewVerdict.FAIL;
            case "不适用" -> ReviewVerdict.NOT_APPLICABLE;
            default -> ReviewVerdict.UNCERTAIN;
        };
        return new ReviewItem(
                (String) map.getOrDefault("item", ""),
                verdict,
                (String) map.getOrDefault("problem", ""),
                (String) map.getOrDefault("suggestion", ""),
                (String) map.getOrDefault("reference", "")
        );
    }

    // ============================================================
    // 内部类
    // ============================================================

    public record ReviewItem(
            String item,
            ReviewVerdict verdict,
            String problem,
            String suggestion,
            String reference
    ) {}
}
