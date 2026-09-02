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

/**
 * 评审接口——核心业务入口。
 * 四接口：submit / status / result / health
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
     * POST /api/v1/review/submit
     * 提交方案文档，异步启动评审流水线，立即返回 taskId。
     *
     * 请求格式：multipart/form-data
     *   - file: 方案文档 (.docx/.doc/.pdf, ≤50MB)
     *   - callbackUrl: 评审完成后回调的 URL
     */
    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam("callbackUrl") String callbackUrl,
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

        if (callbackUrl == null || callbackUrl.isBlank()) {
            return ApiResponse.fail(400, "callbackUrl 不能为空");
        }

        // ---- 创建任务记录 ----
        ReviewTask task = ReviewTask.builder()
                .originalFilename(filename)
                .fileType(file.getContentType())
                .fileSize(file.getSize())
                .callbackUrl(callbackUrl)
                .status(TaskStatus.PENDING)
                .build();

        task = taskRepository.save(task);
        log.info("评审任务已创建 — taskId={}, filename={}, size={}B", task.getId(), filename, file.getSize());

        // ---- 异步触发评审流水线 ----
        String clientIp = getClientIp(request);
        reviewService.executeAsync(task, file, clientIp);

        return ApiResponse.ok(Map.of("taskId", task.getId()));
    }

    /**
     * GET /api/v1/review/status/{taskId}
     * 查询评审状态（对方平台轮询）。
     * 返回当前状态、进度描述，若已完成则包含完整报告。
     */
    @GetMapping("/status/{taskId}")
    public ApiResponse<Map<String, Object>> status(@PathVariable String taskId) {
        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ApiResponse.fail(404, "任务不存在: " + taskId);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus().name());
        data.put("statusLabel", task.getStatus().getLabel());
        data.put("projectType", task.getProjectType() != null ? task.getProjectType().name() : null);
        data.put("hasDuplicate", task.getHasDuplicate() != null ? task.getHasDuplicate() : null);
        data.put("statusMessage", task.getStatusMessage() != null ? task.getStatusMessage() : "");
        data.put("originalFilename", task.getOriginalFilename());

        // 已完成 → 附带完整报告
        if (task.getStatus() == TaskStatus.COMPLETED && task.getReportJson() != null) {
            data.put("report", task.getReportJson());
        }

        // 失败 → 附带错误信息
        if (task.getStatus() == TaskStatus.FAILED) {
            data.put("error", task.getStatusMessage());
        }

        return ApiResponse.ok(data);
    }

    /**
     * GET /api/v1/review/result/{taskId}
     * 获取完整评审结果（仅当评审已完成）。
     */
    @GetMapping("/result/{taskId}")
    public ApiResponse<Map<String, Object>> result(@PathVariable String taskId) {
        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ApiResponse.fail(404, "任务不存在: " + taskId);
        }
        if (task.getStatus() == TaskStatus.FAILED) {
            return ApiResponse.fail(500, "评审失败: " + task.getStatusMessage());
        }
        if (task.getStatus() != TaskStatus.COMPLETED) {
            return ApiResponse.fail(400, "评审尚未完成，当前状态: " + task.getStatus().getLabel());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus().name());
        data.put("projectType", task.getProjectType() != null ? task.getProjectType().name() : null);
        data.put("hasDuplicate", task.getHasDuplicate());
        data.put("dedupDetail", task.getDedupDetail() != null ? task.getDedupDetail() : "");
        data.put("report", task.getReportJson() != null ? task.getReportJson() : "{}");

        return ApiResponse.ok(data);
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
