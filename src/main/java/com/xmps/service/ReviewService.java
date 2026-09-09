package com.xmps.service;

import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.ProjectType;
import com.xmps.model.enums.ReviewVerdict;
import com.xmps.model.enums.TaskStatus;
import com.xmps.repository.ReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 评审流水线编排——异步执行完整评审流程（对齐技术方案文档契约）。
 *
 * <pre>
 * 流水线步骤（progress.total = 5）：
 *   1. 保存文件       → PARSING
 *   2. 文档解析       → PARSING
 *   3. 类型判别       → PARSING
 *   4. 判重           → PARSING
 *   5. 规则审查       → REVIEWING
 *   6. 报告生成       → REVIEWING
 *   完成 / 失败 / 取消 → COMPLETED / FAILED / CANCELLED
 * </pre>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int TOTAL_STEPS = 5;

    private final ReviewTaskRepository taskRepository;
    private final FileStorageService fileStorageService;
    private final DocumentParserService documentParserService;
    private final DedupService dedupService;
    private final ReviewEngine reviewEngine;
    private final ReportService reportService;
    private final CallbackService callbackService;
    private final AuditService auditService;

    public ReviewService(ReviewTaskRepository taskRepository,
                         FileStorageService fileStorageService,
                         DocumentParserService documentParserService,
                         DedupService dedupService,
                         ReviewEngine reviewEngine,
                         ReportService reportService,
                         CallbackService callbackService,
                         AuditService auditService) {
        this.taskRepository = taskRepository;
        this.fileStorageService = fileStorageService;
        this.documentParserService = documentParserService;
        this.dedupService = dedupService;
        this.reviewEngine = reviewEngine;
        this.reportService = reportService;
        this.callbackService = callbackService;
        this.auditService = auditService;
    }

    /**
     * 生成业务任务 ID：rev_YYYYMMDD_NNN（对齐技术方案文档）
     */
    public synchronized String generateTaskId() {
        String prefix = "rev_" + DATE.format(LocalDate.now(ZoneOffset.UTC)) + "_";
        long count = taskRepository.countByIdStartingWith(prefix);
        return prefix + String.format("%03d", count + 1);
    }

    /**
     * 异步启动评审流水线。
     * 对方平台调用 POST /api/v1/review/tasks 后，接口立即返回 taskId，评审在后台执行。
     */
    @Async("reviewTaskExecutor")
    public void executeAsync(ReviewTask task, MultipartFile file, String clientIp, String requestId) {
        long startTime = System.currentTimeMillis();
        log.info("评审流水线启动 — taskId={}, filename={}", task.getId(), task.getOriginalFilename());

        try {
            // ============================================================
            // Step 1: 保存文件
            // ============================================================
            updateStatus(task, TaskStatus.PARSING, "正在解析文档...", 1, TOTAL_STEPS);
            auditService.log(task.getId(), "FILE_SAVE",
                    "文件已保存: " + task.getOriginalFilename(), true, null, clientIp, requestId);

            String filePath = fileStorageService.store(file, task.getId());
            task.setFilePath(filePath);
            taskRepository.save(task);

            // ============================================================
            // Step 2: 文档解析
            // ============================================================
            String documentText;
            try {
                documentText = documentParserService.parse(filePath, task.getOriginalFilename());
                auditService.log(task.getId(), "PARSE_DOC",
                        "解析成功, 字符数=" + documentText.length(), true, null, clientIp, requestId);
            } catch (Exception e) {
                failTask(task, "文档解析失败: " + e.getMessage());
                auditService.log(task.getId(), "PARSE_DOC",
                        "解析失败: " + e.getMessage(), false, null, clientIp, requestId);
                return;
            }

            // ============================================================
            // Step 3: 类型判别
            // ============================================================
            if (isCancelled(task)) return;
            updateStatus(task, TaskStatus.PARSING, "正在判别项目类型...", 2, TOTAL_STEPS);
            classifyProjectType(documentText, task);
            auditService.log(task.getId(), "CLASSIFY",
                    "判别结果: " + task.getProjectType(), true, null, clientIp, requestId);

            // ============================================================
            // Step 4: 判重 (M4 — DedupService)
            // ============================================================
            if (isCancelled(task)) return;
            updateStatus(task, TaskStatus.PARSING, "正在判重...", 3, TOTAL_STEPS);
            DedupService.DedupResult dedupResult;
            try {
                dedupResult = dedupService.analyze(documentText, task);
                auditService.log(task.getId(), "DEDUP",
                        "判重完成, duplicate=" + dedupResult.duplicate(), true, null, clientIp, requestId);
            } catch (Exception e) {
                log.error("判重失败 — taskId={}, error={}", task.getId(), e.getMessage(), e);
                dedupResult = new DedupService.DedupResult(false, "判重服务异常，默认不重复", "", List.of());
                auditService.log(task.getId(), "DEDUP",
                        "判重异常: " + e.getMessage(), false, null, clientIp, requestId);
            }

            // 判重发现重复建设 → 直接结束
            if (dedupResult.duplicate()) {
                completeTask(task, startTime, clientIp, requestId, "发现重复建设，评审终止");
                String reportJson = reportService.generate(task, dedupResult, List.of(), documentText);
                task.setResultJson(reportJson);
                taskRepository.save(task);
                callbackService.callbackAsync(task);
                return;
            }

            // ============================================================
            // Step 5: 规则审查 (M5 — ReviewEngine)
            // ============================================================
            if (isCancelled(task)) return;
            updateStatus(task, TaskStatus.REVIEWING, "正在执行规则审查...", 4, TOTAL_STEPS);
            List<ReviewEngine.ReviewItem> reviewItems;
            try {
                reviewItems = reviewEngine.review(documentText, task);
                auditService.log(task.getId(), "REVIEW",
                        "审查完成, items=" + reviewItems.size(), true, null, clientIp, requestId);
            } catch (Exception e) {
                log.error("规则审查失败 — taskId={}, error={}", task.getId(), e.getMessage(), e);
                reviewItems = List.of(new ReviewEngine.ReviewItem(
                        "审查异常", ReviewVerdict.UNCERTAIN,
                        "审查引擎异常: " + e.getMessage(), "请人工复核", ""));
                auditService.log(task.getId(), "REVIEW",
                        "审查异常: " + e.getMessage(), false, null, clientIp, requestId);
            }

            // ============================================================
            // Step 6: 报告生成 (M5 — ReportService)
            // ============================================================
            if (isCancelled(task)) return;
            updateStatus(task, TaskStatus.REVIEWING, "正在生成评审报告...", 5, TOTAL_STEPS);
            // 先落完成时间与耗时，保证报告包含 completed_at / duration_seconds 字段
            completeTask(task, startTime, clientIp, requestId, "评审完成");
            String reportJson = reportService.generate(task, dedupResult, reviewItems, documentText);
            task.setResultJson(reportJson);
            taskRepository.save(task);
            callbackService.callbackAsync(task);

            log.info("评审流水线完成 — taskId={}", task.getId());

        } catch (Exception e) {
            log.error("评审流水线异常 — taskId={}, error={}", task.getId(), e.getMessage(), e);
            failTask(task, "评审流水线异常: " + e.getMessage());
            auditService.log(task.getId(), "ERROR", "流水线异常: " + e.getMessage(), false, null, clientIp, requestId);
        }
    }

    /**
     * 用户主动取消任务（仅 queued/parsing/reviewing 可取消）
     */
    public void cancel(ReviewTask task, String clientIp, String requestId) {
        if (task.getStatus() == TaskStatus.COMPLETED
                || task.getStatus() == TaskStatus.FAILED
                || task.getStatus() == TaskStatus.CANCELLED) {
            return;
        }
        task.setStatus(TaskStatus.CANCELLED);
        task.setStatusMessage("用户已取消");
        task.setProgressStep("已取消");
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        auditService.log(task.getId(), "CANCEL", "用户取消评审", true, null, clientIp, requestId);
        log.info("评审任务已取消 — taskId={}", task.getId());
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private void completeTask(ReviewTask task, long startTime, String clientIp, String requestId, String msg) {
        long elapsed = System.currentTimeMillis() - startTime;
        task.setCompletedAt(Instant.now());
        task.setDurationSeconds((int) (elapsed / 1000));
        updateStatus(task, TaskStatus.COMPLETED, msg + "，耗时 " + elapsed + "ms", TOTAL_STEPS, TOTAL_STEPS);
        auditService.log(task.getId(), "COMPLETE",
                msg + ", 耗时=" + elapsed + "ms", true, elapsed, clientIp, requestId);
    }

    private boolean isCancelled(ReviewTask task) {
        ReviewTask latest = taskRepository.findById(task.getId()).orElse(null);
        if (latest != null && latest.getStatus() == TaskStatus.CANCELLED) {
            task.setStatus(TaskStatus.CANCELLED);
            task.setStatusMessage("用户已取消");
            task.setProgressStep("已取消");
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);
            log.info("检测到取消信号，流水线提前退出 — taskId={}", task.getId());
            return true;
        }
        return false;
    }

    private void classifyProjectType(String documentText, ReviewTask task) {
        // 先用关键词快速判别（低成本），LLM 精准分类作为 M5 增强
        String text = documentText.toLowerCase();
        if (text.contains("运维") || text.contains("保障服务")) {
            task.setProjectType(ProjectType.OPERATION);
        } else if (text.contains("续建") || text.contains("二期") || text.contains("扩建")) {
            task.setProjectType(ProjectType.CONSTRUCTION_CONTINUE);
        } else if (text.contains("改建") || text.contains("改造")) {
            task.setProjectType(ProjectType.CONSTRUCTION_RENOVATE);
        } else {
            task.setProjectType(ProjectType.CONSTRUCTION_NEW);
        }
        taskRepository.save(task);
    }

    private void updateStatus(ReviewTask task, TaskStatus status, String message, int current, int total) {
        task.setStatus(status);
        task.setStatusMessage(message);
        task.setProgressStep(message);
        task.setProgressCurrent(current);
        task.setProgressTotal(total);
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        log.info("状态变更 — taskId={}, status={}, step={}/{}", task.getId(), status, current, total);
    }

    private void failTask(ReviewTask task, String reason) {
        task.setStatus(TaskStatus.FAILED);
        task.setStatusMessage(reason);
        task.setErrorMessage(reason);
        task.setProgressStep("失败");
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        log.error("评审任务失败 — taskId={}, reason={}", task.getId(), reason);
    }
}
