package com.xmps.model.enums;

/**
 * 评审任务状态机
 * <pre>
 *   PENDING → DOC_PARSING → TYPE_CLASSIFYING → DEDUP_CHECKING
 *   → RULE_REVIEWING → REPORT_GENERATING → COMPLETED
 *   （任一步骤出错 → FAILED）
 * </pre>
 */
public enum TaskStatus {
    /**
     * 已接收，等待处理
     */
    PENDING("待处理"),
    /**
     * 正在解析文档
     */
    DOC_PARSING("文档解析中"),
    /**
     * 正在分类项目类型
     */
    TYPE_CLASSIFYING("类型判别中"),
    /**
     * 正在判重
     */
    DEDUP_CHECKING("判重中"),
    /**
     * 正在执行规则审查
     */
    RULE_REVIEWING("规则审查中"),
    /**
     * 正在生成报告
     */
    REPORT_GENERATING("报告生成中"),
    /**
     * 评审完成
     */
    COMPLETED("已完成"),
    /**
     * 评审失败
     */
    FAILED("失败");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
