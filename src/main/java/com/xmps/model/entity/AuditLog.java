package com.xmps.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 审计日志——全程留痕，确保可追溯
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_task", columnList = "taskId"),
        @Index(name = "idx_audit_time", columnList = "timestamp"),
        @Index(name = "idx_audit_action", columnList = "action")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /**
     * 关联的评审任务
     */
    @Column(length = 36, nullable = false)
    private String taskId;

    /**
     * 操作：UPLOAD / PARSE_DOC / CLASSIFY / DEDUP / REVIEW / GENERATE_REPORT / CALLBACK / ERROR
     */
    @Column(length = 50, nullable = false)
    private String action;

    /**
     * 操作详情（脱敏后）
     */
    @Column(length = 4000)
    private String detail;

    /**
     * 调用方 IP
     */
    @Column(length = 45)
    private String clientIp;

    /**
     * 耗时（毫秒）
     */
    @Column
    private Long durationMs;

    /**
     * 操作是否成功
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean success = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditLog that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
