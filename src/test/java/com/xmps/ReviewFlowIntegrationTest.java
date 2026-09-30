package com.xmps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xmps.model.entity.ReviewTask;
import com.xmps.model.enums.TaskStatus;
import com.xmps.repository.ReviewTaskRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务端到端集成测试（对齐技术方案文档契约）。
 *
 * <p>验证完整链路：上传方案文档 → 文档解析 → 类型判别 → 判重 → 规则审查 → 报告生成 → 回调推送。
 * 真实调用大模型，因此耗时较长（通常 1～3 分钟）。
 *
 * <p>测试用例文档刻意埋入了多处合规问题，用于验证评审引擎能否真正识别出这些问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        // 回调指向本地 mock 服务，缩短等待时间
        "xmps.callback.initial-delay-seconds=1",
        "xmps.callback.max-retries=2",
        "xmps.callback.retry-intervals=1,1,1"
})
class ReviewFlowIntegrationTest {

    private static final String SAMPLE_DOC = "泰兴市智慧市场监管平台二期建设方案.docx";
    private static final String VALID_API_KEY = "test-key-001";
    private static final String AUTH = "Authorization";
    private static final String BEARER = "Bearer " + VALID_API_KEY;

    @Autowired private MockMvc mockMvc;
    @Autowired private ReviewTaskRepository taskRepository;
    @Autowired private ObjectMapper objectMapper;

    private static String taskId;

    private static HttpServer callbackServer;
    private static final BlockingQueue<String> receivedCallbacks = new LinkedBlockingQueue<>();
    private static String callbackUrl;

    @BeforeAll
    static void startCallbackServer() throws Exception {
        callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
        callbackServer.createContext("/callback", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedCallbacks.offer(body);
            byte[] resp = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        callbackServer.start();
        callbackUrl = "http://localhost:" + callbackServer.getAddress().getPort() + "/callback";
        System.out.println("\n[测试] 回调接收服务已启动: " + callbackUrl);
    }

    @AfterAll
    static void stopCallbackServer() {
        if (callbackServer != null) callbackServer.stop(0);
    }

    // ============================================================
    // 1. 鉴权与基础可用性
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("健康检查接口应返回 UP")
    void healthCheck() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @Order(2)
    @DisplayName("缺少 Authorization 头应返回 401")
    void submitWithoutAuthShouldBe401() throws Exception {
        mockMvc.perform(multipart("/api/v1/review/tasks")
                        .file(new MockMultipartFile("file", "a.docx", "application/octet-stream", "x".getBytes()))
                        .param("project_name", "测试项目")
                        .param("callback_url", callbackUrl))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    @DisplayName("错误 API Key 应返回 403")
    void submitWithWrongKeyShouldBe403() throws Exception {
        mockMvc.perform(multipart("/api/v1/review/tasks")
                        .file(new MockMultipartFile("file", "a.docx", "application/octet-stream", "x".getBytes()))
                        .header(AUTH, "Bearer wrong-key-xxx")
                        .param("project_name", "测试项目")
                        .param("callback_url", callbackUrl))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    @DisplayName("缺少 project_name 或不支持的文件格式应返回 400")
    void submitWithBadInputShouldFail() throws Exception {
        // 缺 project_name
        mockMvc.perform(multipart("/api/v1/review/tasks")
                        .file(new MockMultipartFile("file", "a.docx", "application/octet-stream", "x".getBytes()))
                        .header(AUTH, BEARER)
                        .param("callback_url", callbackUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 不支持的格式
        mockMvc.perform(multipart("/api/v1/review/tasks")
                        .file(new MockMultipartFile("file", "方案.txt", "text/plain", "x".getBytes()))
                        .header(AUTH, BEARER)
                        .param("project_name", "测试项目")
                        .param("callback_url", callbackUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ============================================================
    // 2. 核心：提交真实方案文档
    // ============================================================

    @Test
    @Order(10)
    @DisplayName("提交方案文档应成功并返回 rev_ 格式 taskId")
    void submitSampleDocument() throws Exception {
        byte[] docBytes = loadSampleDoc();
        System.out.println("[测试] 载入测试文档，大小 " + docBytes.length + " 字节");

        String response = mockMvc.perform(multipart("/api/v1/review/tasks")
                        .file(new MockMultipartFile("file", SAMPLE_DOC,
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docBytes))
                        .header(AUTH, BEARER)
                        .param("project_name", "泰兴市智慧市场监管平台二期建设项目")
                        .param("callback_url", callbackUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskId").exists())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        taskId = objectMapper.readTree(response).path("data").path("taskId").asText();
        System.out.println("[测试] 提交成功，taskId = " + taskId);

        assertThat(taskId).startsWith("rev_");

        ReviewTask task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.getOriginalFilename()).isEqualTo(SAMPLE_DOC);
        assertThat(task.getProjectName()).isEqualTo("泰兴市智慧市场监管平台二期建设项目");
        assertThat(task.getCallbackUrl()).isEqualTo(callbackUrl);
    }

    // ============================================================
    // 3. 核心：等待异步流水线跑完，校验评审结果
    // ============================================================

    @Test
    @Order(20)
    @DisplayName("异步流水线应完成评审并生成结构化报告")
    void pipelineShouldProduceReport() throws Exception {
        assertThat(taskId).as("依赖前一个测试提交的任务").isNotBlank();

        System.out.println("[测试] 等待异步评审流水线完成（真实调用大模型，请耐心等待）...");
        ReviewTask task = awaitTermination(taskId, 8);

        System.out.println("\n================ 评审结果 ================");
        System.out.println("任务状态   : " + task.getStatus() + "（" + task.getStatus().getLabel() + "）");
        System.out.println("项目类型   : " + task.getProjectType());
        System.out.println("是否重复   : " + task.getHasDuplicate());
        System.out.println("进度       : " + task.getProgressStep() + " (" + task.getProgressCurrent() + "/" + task.getProgressTotal() + ")");
        System.out.println("耗时       : " + task.getDurationSeconds() + "s");
        System.out.println("------------------------------------------");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getProjectType()).isNotNull();
        assertThat(task.getResultJson()).isNotBlank();

        JsonNode report = objectMapper.readTree(task.getResultJson());
        assertThat(report.has("taskId")).isTrue();
        assertThat(report.has("dedup")).isTrue();
        assertThat(report.has("reviewSummary")).isTrue();
        assertThat(report.has("reviewItems")).isTrue();
        // 文档契约新增字段
        assertThat(report.has("summary")).isTrue();
        assertThat(report.has("completed_at")).isTrue();
        assertThat(report.has("duration_seconds")).isTrue();

        boolean isDup = task.getHasDuplicate();
        System.out.println("是否重复建设(提前终止) : " + isDup);

        JsonNode items = report.path("reviewItems");
        int itemCount = items.size();
        System.out.println("审查项数量 : " + itemCount);

        if (isDup) {
            // 判重命中重复建设：流水线在判重步骤即提前终止，不进入 LLM 审查 / 确定性二次校验，
            // reviewItems 为 0 属正确行为，跳过审查相关断言，仅校验判重结论字段。
            assertThat(report.path("dedup").path("isDuplicate").asBoolean())
                    .as("判重命中重复建设，报告 dedup.isDuplicate 应为 true").isTrue();
            System.out.println("[判重命中] 流水线提前终止，审查项数=0（符合预期），跳过 LLM/确定性校验断言");
        } else {
            if (task.getProjectType().name().startsWith("CONSTRUCTION")) {
                assertThat(itemCount).as("建设类项目应至少审查 5 项(LLM)+独立规则引擎二次校验项").isGreaterThanOrEqualTo(5);
            } else {
                assertThat(itemCount).as("运维类项目应至少审查 4 项(LLM)+独立规则引擎二次校验项").isGreaterThanOrEqualTo(4);
            }
            // 验证独立规则引擎（确定性二次校验）确实参与了评审
            boolean hasDeterministic = false;
            for (JsonNode it : items) {
                if (it.path("item").asText().contains("确定性校验")) {
                    hasDeterministic = true;
                    break;
                }
            }
            assertThat(hasDeterministic).as("独立规则引擎应产出确定性二次校验项(方案完整性/信创/准入门槛)").isTrue();

            JsonNode summary = report.path("reviewSummary");
            System.out.println("汇总       : 通过 " + summary.path("pass").asInt()
                    + " / 不通过 " + summary.path("fail").asInt()
                    + " / 存疑 " + summary.path("uncertain").asInt()
                    + " / 不适用 " + summary.path("notApplicable").asInt());
            System.out.println("整体结论   : " + summary.path("overallVerdict").asText());
            assertThat(summary.path("total").asInt()).isEqualTo(itemCount);
            assertThat(summary.path("overallVerdict").asText()).isNotEmpty();

            System.out.println("------------------------------------------");
            for (JsonNode it : items) {
                System.out.printf("  [%s] %s%n", it.path("conclusion").asText(), it.path("item").asText());
                String problem = it.path("detail").asText("");
                if (!problem.isBlank()) {
                    System.out.println("        问题: " + abbreviate(problem));
                    System.out.println("        建议: " + abbreviate(it.path("suggestion").asText("")));
                }
            }
            System.out.println("==========================================\n");

            int flagged = summary.path("fail").asInt() + summary.path("uncertain").asInt();
            assertThat(flagged).as("测试文档刻意埋了多处合规问题，应至少被识别出一项").isGreaterThan(0);
        }
    }

    // ============================================================
    // 4. 回调推送
    // ============================================================

    @Test
    @Order(30)
    @DisplayName("评审完成后应主动推送结果到 callbackUrl")
    void callbackShouldReceiveResult() throws Exception {
        assertThat(taskId).isNotBlank();

        String body = receivedCallbacks.poll(30, TimeUnit.SECONDS);
        assertThat(body).as("等待回调推送超时").isNotNull();

        JsonNode payload = objectMapper.readTree(body);
        System.out.println("[测试] 收到回调 payload 字段: " + payload.fieldNames().hasNext());

        assertThat(payload.path("taskId").asText()).isEqualTo(taskId);
        assertThat(payload.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(payload.path("report").asText()).isNotBlank();
        assertThat(payload.path("completed_at").asText()).isNotBlank();

        JsonNode cbReport = objectMapper.readTree(payload.path("report").asText());
        assertThat(cbReport.path("taskId").asText()).isEqualTo(taskId);
    }

    // ============================================================
    // 5. 查询接口（合并 status + result）
    // ============================================================

    @Test
    @Order(40)
    @DisplayName("GET /tasks/{id} 应返回状态、进度与（完成时）报告")
    void getTaskApiShouldWork() throws Exception {
        assertThat(taskId).isNotBlank();
        mockMvc.perform(get("/api/v1/review/tasks/" + taskId)
                        .header(AUTH, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress.step").exists())
                .andExpect(jsonPath("$.data.progress.current").exists())
                .andExpect(jsonPath("$.data.progress.total").exists())
                .andExpect(jsonPath("$.data.report").exists());
    }

    @Test
    @Order(41)
    @DisplayName("GET /tasks/{id} 报告字段应包含 reviewItems 且 conclusion/detail 命名正确")
    void getTaskReportFieldsShouldMatchContract() throws Exception {
        assertThat(taskId).isNotBlank();
        String body = mockMvc.perform(get("/api/v1/review/tasks/" + taskId)
                        .header(AUTH, BEARER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String reportStr = objectMapper.readTree(body).path("data").path("report").asText();
        JsonNode report = objectMapper.readTree(reportStr);
        assertThat(report.path("reviewItems").isArray()).isTrue();
        // 判重命中重复建设时流水线提前终止，reviewItems 为空属正确行为，跳过条数断言
        boolean isDup = report.path("dedup").path("isDuplicate").asBoolean();
        if (!isDup) {
            assertThat(report.path("reviewItems")).hasSizeGreaterThan(0);
        }
        JsonNode first = report.path("reviewItems").get(0);
        assertThat(first.has("conclusion")).isTrue();
        assertThat(first.has("detail")).isTrue();
        assertThat(first.has("item")).isTrue();
        assertThat(report.path("reviewSummary").path("overallVerdict").asText()).isNotEmpty();
    }

    @Test
    @Order(42)
    @DisplayName("查询不存在的任务应返回 404")
    void queryNonExistingTaskShouldBe404() throws Exception {
        mockMvc.perform(get("/api/v1/review/tasks/not-exist-task-999")
                        .header(AUTH, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ============================================================
    // 6. 取消接口
    // ============================================================

    @Test
    @Order(50)
    @DisplayName("取消接口应可用（200 或 409 终态）")
    void cancelApiShouldWork() throws Exception {
        // 新提交一个任务并立即取消——可能已完成(409)或成功取消(200)，均说明接口可用且安全
        byte[] docBytes = loadSampleDoc();
        String resp = mockMvc.perform(multipart("/api/v1/review/tasks")
                        .file(new MockMultipartFile("file", SAMPLE_DOC,
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docBytes))
                        .header(AUTH, BEARER)
                        .param("project_name", "取消测试项目")
                        .param("callback_url", callbackUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String newTaskId = objectMapper.readTree(resp).path("data").path("taskId").asText();

        int code = mockMvc.perform(post("/api/v1/review/tasks/" + newTaskId + "/cancel")
                        .header(AUTH, BEARER))
                .andReturn().getResponse().getStatus();
        System.out.println("[测试] 取消接口返回状态码: " + code);
        assertThat(code == 200 || code == 409).as("取消接口应返回 200(已取消) 或 409(已终态)").isTrue();
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private ReviewTask awaitTermination(String id, int timeoutMinutes) {
        long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
        TaskStatus lastSeen = null;
        while (System.currentTimeMillis() < deadline) {
            Optional<ReviewTask> opt = taskRepository.findById(id);
            if (opt.isPresent()) {
                TaskStatus st = opt.get().getStatus();
                if (st != lastSeen) {
                    System.out.println("  [状态] " + st + " — " + opt.get().getStatusMessage());
                    lastSeen = st;
                }
                if (st == TaskStatus.COMPLETED || st == TaskStatus.FAILED) {
                    return opt.get();
                }
            }
            sleep(2000);
        }
        throw new AssertionError("评审任务在 " + timeoutMinutes + " 分钟内未进入终态，最后状态: " + lastSeen);
    }

    private byte[] loadSampleDoc() throws Exception {
        ClassPathResource cpr = new ClassPathResource("sample/" + SAMPLE_DOC);
        if (cpr.exists()) {
            return cpr.getInputStream().readAllBytes();
        }
        Path p = Paths.get("src", "test", "resources", "sample", SAMPLE_DOC);
        if (Files.exists(p)) {
            return Files.readAllBytes(p);
        }
        throw new IllegalStateException("找不到测试文档: " + SAMPLE_DOC);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "...";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
