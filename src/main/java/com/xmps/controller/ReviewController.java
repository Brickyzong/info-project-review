package com.xmps.controller;

import com.xmps.controller.dto.ApiResponse;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.TaskStatus;
import com.xmps.repository.ReviewTaskRepository;
import com.xmps.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 评审接口——核心业务入口（对齐技术方案文档契约）。
 * 提交 / 查询（含进度与报告）/ 取消 三个接口。
 */
@RestController
@RequestMapping("/api/v1/review")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final ReviewTaskRepository taskRepository;
    private final ReviewService reviewService;

    public ReviewController(ReviewTaskRepository taskRepository, ReviewService reviewService) {
        this.taskRepository = taskRepository;
        this.reviewService = reviewService;
    }

    /**
     * POST /api/v1/review/tasks
     * 提交方案文档，异步启动评审流水线，立即返回 taskId。
     *
     * 请求格式：multipart/form-data
     *   - file:          方案文档 (.docx/.doc/.pdf, ≤50MB)  必填
     *   - project_name:  项目名称                              必填
     *   - project_type:  项目类型（可选，提示用）
     *   - callback_url:  评审完成回调地址（可选）
     *   - request_id:    幂等键（可选，重复提交返回原任务）
     */
    @PostMapping(value = "/tasks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam("project_name") String projectName,
            @RequestParam(value = "project_type", required = false) String projectType,
            @RequestParam(value = "callback_url", required = false) String callbackUrl,
            @RequestParam(value = "request_id", required = false) String requestId,
            HttpServletRequest request
    ) {
        // ---- 基础校验 ----
        if (file.isEmpty()) {
            return ApiResponse.fail(400, "文件不能为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return ApiResponse.fail(400, "文件名不能为空");
        }
        String lower = filename.toLowerCase();
        if (!(lower.endsWith(".docx") || lower.endsWith(".doc") || lower.endsWith(".pdf"))) {
            return ApiResponse.fail(400, "仅支持 .docx / .doc / .pdf 格式");
        }
        if (projectName == null || projectName.isBlank()) {
            return ApiResponse.fail(400, "project_name 不能为空");
        }

        // ---- 幂等：request_id 已存在则返回原任务 ----
        if (requestId != null && !requestId.isBlank()) {
            Optional<ReviewTask> existing = taskRepository.findByRequestId(requestId);
            if (existing.isPresent()) {
                return ApiResponse.ok(Map.of("taskId", existing.get().getId(), "requestId", requestId));
            }
        }

        // ---- 创建任务记录 ----
        String taskId = reviewService.generateTaskId();
        ReviewTask task = ReviewTask.builder()
                .id(taskId)
                .projectName(projectName)
                .originalFilename(filename)
                .fileType(file.getContentType())
                .fileSize(file.getSize())
                .projectSubtype(projectType)
                .callbackUrl(callbackUrl)
                .requestId(requestId)
                .status(TaskStatus.QUEUED)
                .build();

        task = taskRepository.save(task);
        log.info("评审任务已创建 — taskId={}, filename={}, size={}B", taskId, filename, file.getSize());

        // ---- 异步触发评审流水线 ----
        String clientIp = getClientIp(request);
        String reqId = (requestId != null && !requestId.isBlank()) ? requestId : request.getHeader("X-Request-ID");
        reviewService.executeAsync(task, file, clientIp, reqId);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        if (requestId != null) {
            data.put("requestId", requestId);
        }
        return ApiResponse.ok(data);
    }

    /**
     * GET /api/v1/review/tasks/{id}
     * 查询任务状态、进度（progress）与（完成时）完整报告。
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(@PathVariable String taskId) {
        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ApiResponse.fail(404, "任务不存在: " + taskId);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus().name());
        data.put("statusLabel", task.getStatus().getLabel());
        data.put("projectName", task.getProjectName());
        data.put("projectType", task.getProjectType() != null ? task.getProjectType().name() : null);
        data.put("hasDuplicate", task.getHasDuplicate());
        data.put("statusMessage", task.getStatusMessage() != null ? task.getStatusMessage() : "");
        data.put("progress", Map.of(
                "step", task.getProgressStep() != null ? task.getProgressStep() : "",
                "current", task.getProgressCurrent(),
                "total", task.getProgressTotal()
        ));
        data.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);

        if (task.getStatus() == TaskStatus.COMPLETED) {
            data.put("completedAt", task.getCompletedAt() != null ? task.getCompletedAt().toString() : null);
            data.put("durationSeconds", task.getDurationSeconds());
            data.put("report", task.getResultJson() != null ? task.getResultJson() : "{}");
        }
        if (task.getStatus() == TaskStatus.FAILED) {
            data.put("error", task.getErrorMessage() != null ? task.getErrorMessage() : task.getStatusMessage());
        }
        data.put("requestId", task.getRequestId());
        return ApiResponse.ok(data);
    }

    /**
     * POST /api/v1/review/tasks/{id}/cancel
     * 取消进行中的任务（终态不可取消）。
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<Map<String, String>> cancel(@PathVariable String taskId, HttpServletRequest request) {
        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ApiResponse.fail(404, "任务不存在: " + taskId);
        }
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED) {
            return ApiResponse.fail(409, "任务已终态，无法取消: " + task.getStatus().getLabel());
        }
        reviewService.cancel(task, getClientIp(request), request.getHeader("X-Request-ID"));
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "CANCELLED"));
    }

    // ---- helper ----

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
