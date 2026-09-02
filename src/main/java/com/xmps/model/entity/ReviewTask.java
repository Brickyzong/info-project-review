package com.xmps.model.entity;

import com.xmps.model.enums.ProjectType;
import com.xmps.model.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 评审任务主实体——一次方案评审的完整生命周期
 */
@Entity
@Table(name = "review_task", indexes = {
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

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    @Builder.Default
    private String id = UUID.randomUUID().toString();

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
     * 判别后的项目类型
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProjectType projectType;

    /**
     * 任务状态
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /**
     * 状态描述（可读的错误信息或进度描述）
     */
    @Column(length = 2000)
    private String statusMessage;

    /**
     * 回调 URL——对方平台提供的接收评审结果的地址
     */
    @Column(length = 1024)
    private String callbackUrl;

    /**
     * 回调重试次数
     */
    @Builder.Default
    private int callbackRetries = 0;

    /**
     * 最后的评审报告 JSON（可存储在数据库或对象存储）
     */
    @Column(columnDefinition = "TEXT")
    private String reportJson;

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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

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
