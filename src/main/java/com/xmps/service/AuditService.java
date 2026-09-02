package com.xmps.service;

import com.xmps.model.entity.AuditLog;
import com.xmps.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计日志记录服务——"全程留痕"的核心执行器
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 写入一条审计日志
     */
    public void log(String taskId, String action, String detail, boolean success, Long durationMs, String clientIp) {
        try {
            AuditLog entry = AuditLog.builder()
                    .taskId(taskId)
                    .action(action)
                    .detail(sanitize(detail))
                    .success(success)
                    .durationMs(durationMs)
                    .clientIp(clientIp)
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // 审计日志写入失败不能中断主流程
            log.error("审计日志写入失败 — taskId={}, action={}, error={}", taskId, action, e.getMessage());
        }
    }

    public void log(String taskId, String action, String detail, boolean success) {
        log(taskId, action, detail, success, null, null);
    }

    /**
     * 脱敏处理——截断过长内容，移除潜在敏感信息
     */
    private String sanitize(String detail) {
        if (detail == null) return null;
        if (detail.length() > 2000) {
            return detail.substring(0, 1997) + "...";
        }
        return detail;
    }
}
