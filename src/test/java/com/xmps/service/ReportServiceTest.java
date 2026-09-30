package com.xmps.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmps.model.ReviewItem;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.ProjectType;
import com.xmps.model.enums.ReviewVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportService 结构化《修改建议书》单元测试（无 LLM 调用，纯逻辑验证）。
 */
class ReportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 应生成结构化修改建议书_仅含不通过与存疑项() throws Exception {
        ReviewTask task = ReviewTask.builder()
                .id("rev_test_001")
                .projectName("测试政务云项目")
                .originalFilename("测试政务云项目.docx")
                .projectType(ProjectType.CONSTRUCTION_NEW)
                .build();

        List<ReviewItem> items = List.of(
                new ReviewItem("方案完整性", ReviewVerdict.FAIL, "缺少信息安全专项设计", "补充信息安全章节", "KB-04"),
                new ReviewItem("信创合规", ReviewVerdict.UNCERTAIN, "数据库选型待确认", "明确国产数据库型号", "KB-06"),
                new ReviewItem("绩效考核", ReviewVerdict.PASS, "符合要求", "", "KB-08"),
                new ReviewItem("准入门槛", ReviewVerdict.NOT_APPLICABLE, "不适用", "", "KB-08")
        );

        ReportService service = new ReportService(objectMapper);
        String json = service.generate(task,
                new DedupService.DedupResult(false, "不重复", "与历史项目无重复", List.of()),
                items, "方案原文内容");

        JsonNode root = objectMapper.readTree(json);
        JsonNode plan = root.path("remediationPlan");
        assertThat(plan.isArray()).isTrue();
        assertThat(plan).hasSize(2); // 仅 FAIL + UNCERTAIN

        assertThat(plan.path(0).path("sourceItem").asText()).isEqualTo("方案完整性");
        assertThat(plan.path(0).path("verdict").asText()).isEqualTo("FAIL");
        assertThat(plan.path(0).path("verdictLabel").asText()).isEqualTo("不通过");
        assertThat(plan.path(0).path("severity").asText()).isEqualTo("高");
        assertThat(plan.path(0).path("priority").asText()).isEqualTo("P0");
        assertThat(plan.path(0).path("suggestedDeadline").asText()).isEqualTo("评审前必须完成整改");

        assertThat(plan.path(1).path("sourceItem").asText()).isEqualTo("信创合规");
        assertThat(plan.path(1).path("verdict").asText()).isEqualTo("UNCERTAIN");
        assertThat(plan.path(1).path("severity").asText()).isEqualTo("中");
        assertThat(plan.path(1).path("priority").asText()).isEqualTo("P1");
        assertThat(plan.path(1).path("suggestedDeadline").asText()).isEqualTo("补充相关材料后申请复评");

        String md = root.path("remediationPlanMarkdown").asText();
        assertThat(md).contains("# 信息化项目评审·修改建议书");
        assertThat(md).contains("共识别需整改项 2 项");
        assertThat(md).contains("方案完整性");
        assertThat(md).contains("修改建议");
    }

    @Test
    void 全部通过时_修改建议书为空且提示无需整改() throws Exception {
        ReviewTask task = ReviewTask.builder()
                .id("rev_test_002")
                .projectName("全通过项目")
                .originalFilename("全通过项目.docx")
                .projectType(ProjectType.OPERATION)
                .build();

        List<ReviewItem> items = List.of(
                new ReviewItem("方案完整性", ReviewVerdict.PASS, "", "", ""),
                new ReviewItem("政务云资源", ReviewVerdict.PASS, "", "", "")
        );

        ReportService service = new ReportService(objectMapper);
        String json = service.generate(task,
                new DedupService.DedupResult(false, "不重复", "", List.of()),
                items, "原文");

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("remediationPlan")).isEmpty();
        String md = root.path("remediationPlanMarkdown").asText();
        assertThat(md).contains("本次评审全部通过，无需整改。");
    }

    @Test
    void 判重短路_reviewItems为空_修改建议书为空() throws Exception {
        ReviewTask task = ReviewTask.builder()
                .id("rev_test_003")
                .projectName("重复项目")
                .originalFilename("重复项目.docx")
                .projectType(ProjectType.CONSTRUCTION_NEW)
                .build();

        ReportService service = new ReportService(objectMapper);
        // reviewItems 为空列表（判重命中，流水线提前终止）
        String json = service.generate(task,
                new DedupService.DedupResult(true, "与历史项目重复", "高度相似", List.of()),
                List.of(), "原文");

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("remediationPlan")).isEmpty();
        String md = root.path("remediationPlanMarkdown").asText();
        assertThat(md).contains("# 信息化项目评审·修改建议书");
        assertThat(md).contains("无需整改");
    }
}
