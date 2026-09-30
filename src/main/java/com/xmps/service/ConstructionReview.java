package com.xmps.service;

import com.xmps.llm.LlmClient;
import com.xmps.model.ReviewItem;
import com.xmps.model.entity.ReviewTask;
import com.xmps.rules.RulesLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 建设类审查智能体——执行建设类审查项（重复性核查由 DedupService 独立执行，此处不含）。
 *
 * <p>提示词来自外置模板 review-system / review-user，规则取自知识库建设类规则。</p>
 */
@Service
public class ConstructionReview {

    private static final Logger log = LoggerFactory.getLogger(ConstructionReview.class);

    private final LlmClient llmClient;
    private final RulesLoader rulesLoader;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper;

    public ConstructionReview(LlmClient llmClient, RulesLoader rulesLoader,
                              PromptTemplateLoader promptTemplateLoader, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.rulesLoader = rulesLoader;
        this.promptTemplateLoader = promptTemplateLoader;
        this.objectMapper = objectMapper;
    }

    public List<ReviewItem> review(String documentText, ReviewTask task) {
        List<String> items = List.of(
                "准入门槛审查",
                "方案完整性检查",
                "信创合规检查",
                "绩效考核风险",
                "政务云资源合规核查"
        );
        String system = promptTemplateLoader.render("review-system", Map.of(
                "RULES", rulesLoader.getConstructionRules()));
        String user = promptTemplateLoader.render("review-user", Map.of(
                "ITEMS", items.stream().map(i -> "- " + i).collect(Collectors.joining("\n")),
                "DOCUMENT", documentText));
        log.info("建设类审查启动 — taskId={}, items={}", task.getId(), items.size());
        String resp = llmClient.chat(system, user);
        List<ReviewItem> results = ReviewResultParser.parse(resp, task.getId(), objectMapper);
        log.info("建设类审查完成 — taskId={}, results={}", task.getId(), results.size());
        return results;
    }
}
