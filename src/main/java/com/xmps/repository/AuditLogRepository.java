package com.xmps.repository;

import com.xmps.model.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    /**
     * 按任务 ID 查全部审计日志
     */
    List<AuditLog> findByTaskIdOrderByTimestampAsc(String taskId);

    /**
     * 最近 N 条日志（运维排查用）
     */
    List<AuditLog> findTop100ByOrderByTimestampDesc();
}
