package com.xmps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmps.model.ReviewItem;
import com.xmps.model.enums.ReviewVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 审查结果解析——将 LLM 返回的 JSON 数组解析为 ReviewItem 列表（确定性，无 LLM 调用）。
 *
 * <p>建设类 / 运维类审查智能体共用同一套解析与 verdict 映射逻辑，抽到此处避免重复。</p>
 */
public final class ReviewResultParser {

    private static final Logger log = LoggerFactory.getLogger(ReviewResultParser.class);

    private ReviewResultParser() {
    }

    @SuppressWarnings("unchecked")
    public static List<ReviewItem> parse(String llmResponse, String taskId, ObjectMapper objectMapper) {
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
                    .map(ReviewResultParser::toReviewItem)
                    .toList();
        } catch (JsonProcessingException e) {
            log.error("审查结果 JSON 解析失败 — taskId={}, response={}", taskId, llmResponse, e);
            return List.of(new ReviewItem(
                    "解析异常", ReviewVerdict.UNCERTAIN,
                    "审查结果解析失败", "请人工复核",
                    "LLM 返回非标准 JSON: " + llmResponse.substring(0, Math.min(200, llmResponse.length()))
            ));
        }
    }

    private static ReviewItem toReviewItem(Map<String, Object> map) {
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
}
