package com.xmps.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmps.config.CallbackProperties;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.TaskStatus;
import com.xmps.repository.ReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 回调服务——评审完成后将结果 POST 到对方平台提供的 callbackUrl。
 * 支持指数退避重试，最多 maxRetries 次。
 */
@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final ReviewTaskRepository taskRepository;
    private final CallbackProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public CallbackService(ReviewTaskRepository taskRepository,
                           CallbackProperties properties,
                           ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    /**
     * 异步执行回调。
     * 失败自动重试，每次重试间隔递增。
     */
    @Async("reviewTaskExecutor")
    public void callbackAsync(ReviewTask task) {
        // 初始延迟
        sleepSeconds(properties.getInitialDelaySeconds());

        for (int attempt = 1; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                log.info("回调尝试 {}/{} — taskId={}, url={}",
                        attempt, properties.getMaxRetries(), task.getId(), task.getCallbackUrl());

                Map<String, Object> payload = buildPayload(task);

                String response = restClient.post()
                        .uri(task.getCallbackUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(payload))
                        .retrieve()
                        .body(String.class);

                log.info("回调成功 — taskId={}, response={}", task.getId(), response);

                task.setCallbackRetries(attempt);
                taskRepository.save(task);
                return; // 成功，退出

            } catch (Exception e) {
                log.warn("回调失败 {}/{} — taskId={}, error={}",
                        attempt, properties.getMaxRetries(), task.getId(), e.getMessage());

                task.setCallbackRetries(attempt);
                taskRepository.save(task);

                if (attempt < properties.getMaxRetries()) {
                    log.info("回调将在 {} 秒后重试 — taskId={}",
                            properties.getRetryIntervalSeconds(), task.getId());
                    sleepSeconds(properties.getRetryIntervalSeconds());
                }
            }
        }

        log.error("回调最终失败 — taskId={}, 已重试 {} 次",
                task.getId(), properties.getMaxRetries());
        // 回调失败不改变任务状态（评审已完成），只记录日志供运维排查
    }

    /**
     * 构造回调请求体
     */
    private Map<String, Object> buildPayload(ReviewTask task) {
        return Map.of(
                "taskId", task.getId(),
                "status", task.getStatus().name(),
                "statusLabel", task.getStatus().getLabel(),
                "projectType", task.getProjectType() != null ? task.getProjectType().name() : null,
                "hasDuplicate", task.getHasDuplicate() != null ? task.getHasDuplicate() : false,
                "report", task.getReportJson() != null ? task.getReportJson() : "{}",
                "completedAt", LocalDateTime.now().toString()
        );
    }

    private void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
