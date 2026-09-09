package com.xmps.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 审计日志——全程留痕，确保可追溯
 */
@Entity
@Table(name = "audit_logs", indexes = {
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
     * 操作：FILE_SAVE / PARSE_DOC / CLASSIFY / DEDUP / REVIEW / COMPLETE / CANCEL / ERROR
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
     * 请求追踪/幂等 ID（X-Request-ID 或 request_id）
     */
    @Column(length = 64)
    private String requestId;

    /**
     * 调用方 User-Agent
     */
    @Column(length = 200)
    private String userAgent;

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

    @Column(updatable = false)
    @Builder.Default
    private Instant timestamp = Instant.now();

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
