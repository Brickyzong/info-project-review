package com.xmps.repository;

import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewTaskRepository extends JpaRepository<ReviewTask, String> {

    /**
     * 按状态查询（用于异步调度扫描）
     */
    List<ReviewTask> findByStatus(TaskStatus status);

    /**
     * 按状态和时间排序，取最早创建的（FIFO 调度）
     */
    List<ReviewTask> findTop10ByStatusOrderByCreatedAtAsc(TaskStatus status);

    /**
     * 查某个任务（带乐观锁——业务层自行处理并发）
     */
    Optional<ReviewTask> findById(String id);
}
