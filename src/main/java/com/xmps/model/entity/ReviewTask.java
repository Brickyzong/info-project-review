package com.xmps.model.entity;

import com.xmps.model.enums.ProjectType;
import com.xmps.model.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * 评审任务主实体——一次方案评审的完整生命周期
 */
@Entity
@Table(name = "review_tasks", indexes = {
        @Index(name = "idx_task_status", columnList = "status"),
        @Index(name = "idx_task_created", columnList = "createdAt"),
        @Index(name = "idx_task_type", columnList = "projectType")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewTask {

    /**
     * 任务 ID —— 业务主键，格式 rev_YYYYMMDD_NNN（对齐技术方案文档）
     */
    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id;

    /**
     * 项目名称（提交时由调用方提供）
     */
    @Column(nullable = false)
    private String projectName;

    /**
     * 原始文件名
     */
    @Column(nullable = false)
    private String originalFilename;

    /**
     * 文件存储路径（本地暂存或对象存储 key）
     */
    @Column
    private String filePath;

    /**
     * 文件类型：application/vnd.openxmlformats-officedocument.wordprocessingml.document 等
     */
    @Column
    private String fileType;

    /**
     * 文件大小（字节），用于审计
     */
    @Column
    private Long fileSize;

    /**
     * 判别后的项目类型（建设/运维）
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProjectType projectType;

    /**
     * 项目子类型（调用方可选提供的细分类型，如 新建/续建/改建）
     */
    @Column(length = 30)
    private String projectSubtype;

    /**
     * 任务状态
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    @Builder.Default
    private TaskStatus status = TaskStatus.QUEUED;

    /**
     * 状态描述（可读的错误信息或进度描述）
     */
    @Column(length = 2000)
    private String statusMessage;

    /**
     * 调用方幂等键（request_id），唯一。重复提交同一 request_id 返回原任务。
     */
    @Column(length = 64, unique = true)
    private String requestId;

    /**
     * 回调 URL——对方平台提供的接收评审结果的地址
     */
    @Column(length = 1024)
    private String callbackUrl;

    /**
     * 回调重试次数
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * 最后的评审报告 JSON（result_json）
     */
    @Column(columnDefinition = "TEXT")
    private String resultJson;

    /**
     * 判重结论：是否有疑似重复项目
     */
    @Column
    private Boolean hasDuplicate;

    /**
     * 判重详情
     */
    @Column(length = 4000)
    private String dedupDetail;

    /**
     * 当前细粒度步骤名（progress.step）
     */
    @Column(length = 64)
    private String progressStep;

    /**
     * 当前步骤序号（progress.current）
     */
    @Builder.Default
    private int progressCurrent = 0;

    /**
     * 总步骤数（progress.total）
     */
    @Builder.Default
    private int progressTotal = 0;

    /**
     * 失败原因（error_message）
     */
    @Column(length = 2000)
    private String errorMessage;

    /**
     * 完成时间（ISO8601 UTC）
     */
    @Column
    private Instant completedAt;

    /**
     * 耗时（秒）
     */
    @Builder.Default
    private int durationSeconds = 0;

    @Column(updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReviewTask that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
