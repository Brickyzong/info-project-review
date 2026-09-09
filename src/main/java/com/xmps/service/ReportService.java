package com.xmps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.ReviewVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告生成服务——汇总判重、规则审查结果为结构化 JSON 报告（对齐技术方案文档契约）。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ObjectMapper objectMapper;

    public ReportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成最终评审报告 JSON。
     *
     * @param task         评审任务
     * @param dedupResult  判重结果
     * @param reviewItems  规则审查结果列表
     * @param documentText 方案原文（用于提取项目名称等基本信息）
     */
    public String generate(ReviewTask task, DedupService.DedupResult dedupResult,
                           List<ReviewEngine.ReviewItem> reviewItems, String documentText) {
        Map<String, Object> report = new LinkedHashMap<>();

        // 1. 基本信息
        report.put("taskId", task.getId());
        report.put("projectName", task.getProjectName() != null ? task.getProjectName() : task.getOriginalFilename());
        report.put("originalFilename", task.getOriginalFilename());
        report.put("projectType", task.getProjectType() != null ? task.getProjectType().getLabel() : "未知");
        report.put("projectTypeCode", task.getProjectType() != null ? task.getProjectType().name() : "UNKNOWN");
        report.put("generatedAt", Instant.now().toString());

        // 2. 判重结论
        Map<String, Object> dedup = new LinkedHashMap<>();
        dedup.put("isDuplicate", dedupResult != null ? dedupResult.duplicate() : false);
        dedup.put("description", dedupResult != null ? dedupResult.description() : "");
        dedup.put("conclusion", dedupResult != null ? dedupResult.conclusion() : "");
        if (dedupResult != null && dedupResult.similarProjects() != null && !dedupResult.similarProjects().isEmpty()) {
            dedup.put("similarProjects", dedupResult.similarProjects());
        }
        report.put("dedup", dedup);

        // 3. 审查汇总
        Map<String, Object> reviewSummary = new LinkedHashMap<>();
        String overallVerdict = "通过";
        String overallNote = "全部审查通过";
        if (reviewItems != null) {
            long pass = reviewItems.stream().filter(i -> i.verdict() == ReviewVerdict.PASS).count();
            long fail = reviewItems.stream().filter(i -> i.verdict() == ReviewVerdict.FAIL).count();
            long uncertain = reviewItems.stream().filter(i -> i.verdict() == ReviewVerdict.UNCERTAIN).count();
            long na = reviewItems.stream().filter(i -> i.verdict() == ReviewVerdict.NOT_APPLICABLE).count();

            reviewSummary.put("total", reviewItems.size());
            reviewSummary.put("pass", pass);
            reviewSummary.put("fail", fail);
            reviewSummary.put("uncertain", uncertain);
            reviewSummary.put("notApplicable", na);

            if (fail > 0) {
                overallVerdict = "不通过";
                overallNote = "存在 " + fail + " 项不通过，需修改后重新评审";
            } else if (uncertain > 0) {
                overallVerdict = "存疑";
                overallNote = "存在 " + uncertain + " 项存疑，建议补充材料";
            } else {
                overallVerdict = "通过";
                overallNote = "全部 " + pass + " 项审查通过";
            }
        }
        // 整体结论（对齐文档 summary 字段）
        report.put("summary", overallVerdict + "：" + overallNote);
        reviewSummary.put("overallVerdict", overallVerdict);
        reviewSummary.put("overallNote", overallNote);
        report.put("reviewSummary", reviewSummary);

        // 4. 逐项审查详情（对齐文档：item / conclusion / detail / suggestion / reference）
        if (reviewItems != null) {
            report.put("reviewItems", reviewItems.stream().map(item -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("item", item.item());
                m.put("conclusion", item.verdict().getLabel());
                m.put("detail", item.problem() != null ? item.problem() : "");
                m.put("suggestion", item.suggestion() != null ? item.suggestion() : "");
                m.put("reference", item.reference() != null ? item.reference() : "");
                return m;
            }).toList());
        }

        // 5. 完成时间 / 耗时（对齐文档 completed_at / duration_seconds）
        if (task.getCompletedAt() != null) {
            report.put("completed_at", ISO.format(task.getCompletedAt()));
        }
        report.put("duration_seconds", task.getDurationSeconds());

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            log.info("报告生成完成 — taskId={}, overallVerdict={}", task.getId(), overallVerdict);
            return json;
        } catch (JsonProcessingException e) {
            log.error("报告 JSON 序列化失败 — taskId={}", task.getId(), e);
            return "{\"error\": \"报告生成失败: " + e.getMessage() + "\"}";
        }
    }
}
