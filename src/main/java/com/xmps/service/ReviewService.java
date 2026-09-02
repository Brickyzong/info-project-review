package com.xmps.service;

import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.TaskStatus;
import com.xmps.repository.ReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 评审流水线编排——异步执行完整评审流程。
 *
 * <pre>
 * 流水线步骤：
 *   1. 保存文件
 *   2. 文档解析       → DOC_PARSING
 *   3. 类型判别       → TYPE_CLASSIFYING   (LLM)
 *   4. 判重           → DEDUP_CHECKING      (LLM + 向量检索, M4)
 *   5. 规则审查       → RULE_REVIEWING      (规则引擎 + LLM, M5)
 *   6. 报告生成       → REPORT_GENERATING   (M5)
 *   7. 回调           → callbackService
 * </pre>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

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
     * 异步启动评审流水线。
     * 对方平台调用 POST /submit 后，接口立即返回 taskId，评审在后台执行。
     */
    @Async("reviewTaskExecutor")
    public void executeAsync(ReviewTask task, MultipartFile file, String clientIp) {
        long startTime = System.currentTimeMillis();
        log.info("评审流水线启动 — taskId={}, filename={}", task.getId(), task.getOriginalFilename());

        try {
            // ============================================================
            // Step 1: 保存文件
            // ============================================================
            updateStatus(task, TaskStatus.DOC_PARSING, "正在解析文档...");
            auditService.log(task.getId(), "FILE_SAVE",
                    "文件已保存: " + task.getOriginalFilename(), true, null, clientIp);

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
                        "解析成功, 字符数=" + documentText.length(), true);
            } catch (Exception e) {
                failTask(task, "文档解析失败: " + e.getMessage());
                auditService.log(task.getId(), "PARSE_DOC",
                        "解析失败: " + e.getMessage(), false);
                return;
            }

            // ============================================================
            // Step 3: 类型判别 (LLM)
            // ============================================================
            updateStatus(task, TaskStatus.TYPE_CLASSIFYING, "正在判别项目类型...");
            classifyProjectType(documentText, task);
            auditService.log(task.getId(), "CLASSIFY",
                    "判别结果: " + task.getProjectType(), true);

            // ============================================================
            // Step 4: 判重 (M4 — DedupService)
            // ============================================================
            updateStatus(task, TaskStatus.DEDUP_CHECKING, "正在判重...");
            DedupService.DedupResult dedupResult;
            try {
                dedupResult = dedupService.analyze(documentText, task);
                auditService.log(task.getId(), "DEDUP",
                        "判重完成, duplicate=" + dedupResult.duplicate(), true);
            } catch (Exception e) {
                log.error("判重失败 — taskId={}, error={}", task.getId(), e.getMessage(), e);
                dedupResult = new DedupService.DedupResult(false, "判重服务异常，默认不重复", "", List.of());
                auditService.log(task.getId(), "DEDUP",
                        "判重异常: " + e.getMessage(), false);
            }

            // 判重发现重复建设 → 直接结束
            if (dedupResult.duplicate()) {
                String reportJson = reportService.generate(task, dedupResult, List.of(), documentText);
                task.setReportJson(reportJson);
                long elapsed = System.currentTimeMillis() - startTime;
                updateStatus(task, TaskStatus.COMPLETED,
                        "发现重复建设，评审终止。耗时 " + elapsed + "ms");
                auditService.log(task.getId(), "COMPLETE",
                        "重复建设, 耗时=" + elapsed + "ms", true, elapsed, clientIp);
                callbackService.callbackAsync(task);
                return;
            }

            // ============================================================
            // Step 5: 规则审查 (M5 — ReviewEngine)
            // ============================================================
            updateStatus(task, TaskStatus.RULE_REVIEWING, "正在执行规则审查...");
            List<ReviewEngine.ReviewItem> reviewItems;
            try {
                reviewItems = reviewEngine.review(documentText, task);
                auditService.log(task.getId(), "REVIEW",
                        "审查完成, items=" + reviewItems.size(), true);
            } catch (Exception e) {
                log.error("规则审查失败 — taskId={}, error={}", task.getId(), e.getMessage(), e);
                reviewItems = List.of(new ReviewEngine.ReviewItem(
                        "审查异常", com.xmps.model.enums.ReviewVerdict.UNCERTAIN,
                        "审查引擎异常: " + e.getMessage(), "请人工复核", ""));
                auditService.log(task.getId(), "REVIEW",
                        "审查异常: " + e.getMessage(), false);
            }

            // ============================================================
            // Step 6: 报告生成 (M5 — ReportService)
            // ============================================================
            updateStatus(task, TaskStatus.REPORT_GENERATING, "正在生成评审报告...");
            String reportJson = reportService.generate(task, dedupResult, reviewItems, documentText);
            task.setReportJson(reportJson);
            taskRepository.save(task);

            // ============================================================
            // Step 7: 完成
            // ============================================================
            long elapsed = System.currentTimeMillis() - startTime;
            updateStatus(task, TaskStatus.COMPLETED, "评审完成，耗时 " + elapsed + "ms");
            auditService.log(task.getId(), "COMPLETE",
                    "评审完成, 耗时=" + elapsed + "ms", true, elapsed, clientIp);

            // 异步回调
            callbackService.callbackAsync(task);

            log.info("评审流水线完成 — taskId={}, elapsed={}ms", task.getId(), elapsed);

        } catch (Exception e) {
            log.error("评审流水线异常 — taskId={}, error={}", task.getId(), e.getMessage(), e);
            failTask(task, "评审流水线异常: " + e.getMessage());
            auditService.log(task.getId(), "ERROR", "流水线异常: " + e.getMessage(), false);
        }
    }

    // ============================================================
    // 类型判别（LLM）
    // ============================================================

    private void classifyProjectType(String documentText, ReviewTask task) {
        // 先用关键词快速判别（低成本），LLM 精准分类作为 M5 增强
        String text = documentText.toLowerCase();
        if (text.contains("运维") || text.contains("保障服务")) {
            task.setProjectType(com.xmps.model.enums.ProjectType.OPERATION);
        } else if (text.contains("续建") || text.contains("二期") || text.contains("扩建")) {
            task.setProjectType(com.xmps.model.enums.ProjectType.CONSTRUCTION_CONTINUE);
        } else if (text.contains("改建") || text.contains("改造")) {
            task.setProjectType(com.xmps.model.enums.ProjectType.CONSTRUCTION_RENOVATE);
        } else {
            task.setProjectType(com.xmps.model.enums.ProjectType.CONSTRUCTION_NEW);
        }
        taskRepository.save(task);
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private void updateStatus(ReviewTask task, TaskStatus status, String message) {
        task.setStatus(status);
        task.setStatusMessage(message);
        taskRepository.save(task);
        log.info("状态变更 — taskId={}, status={}, message={}", task.getId(), status, message);
    }

    private void failTask(ReviewTask task, String reason) {
        task.setStatus(TaskStatus.FAILED);
        task.setStatusMessage(reason);
        taskRepository.save(task);
        log.error("评审任务失败 — taskId={}, reason={}", task.getId(), reason);
    }
}
