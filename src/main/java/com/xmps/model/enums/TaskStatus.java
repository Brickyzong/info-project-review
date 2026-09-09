package com.xmps.model.enums;

/**
 * 评审任务状态机（对齐技术方案文档契约）。
 * <pre>
 *   QUEUED → PARSING → REVIEWING → COMPLETED
 *   （任一步骤出错 → FAILED；用户主动取消 → CANCELLED）
 * </pre>
 * 细粒度步骤（文档解析 / 类型判别 / 判重 / 规则审查 / 报告生成）通过 progress 字段对外暴露。
 */
public enum TaskStatus {
    /**
     * 已接收，等待处理
     */
    QUEUED("待处理"),
    /**
     * 解析 / 类型判别 / 判重中
     */
    PARSING("解析中"),
    /**
     * 规则审查 / 报告生成中
     */
    REVIEWING("审查中"),
    /**
     * 评审完成
     */
    COMPLETED("已完成"),
    /**
     * 评审失败
     */
    FAILED("失败"),
    /**
     * 用户主动取消
     */
    CANCELLED("已取消");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
