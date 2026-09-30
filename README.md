# xmps-ai-review（信息化项目评审 · AI 评审服务）

泰兴市数据局「政务信息化项目智能评审」的**纯后端 AI 评审服务**（无前端）。
对外提供 RESTful API：调用方上传方案文档，服务异步完成「文档解析 → 类型判别 → 判重 → 规则审查（LLM + 独立规则引擎）→ 报告生成 → 回调」全链路，并返回结构化评审报告 JSON。

---

## 1. 技术栈

- **语言/框架**：Java 17 + Spring Boot 3.3.1 + Spring Data JPA + Lombok
- **文档解析**：Apache POI 5.2.5（.docx/.doc）+ PDFBox 3.0.2（.pdf）
- **LLM**：自研轻量 RestClient 封装 OpenAI 兼容协议，默认 DeepSeek，生产切政务模型网关（只改 yml）
- **数据库**：dev = H2 文件库（`./data/xmps`）；prod = PostgreSQL 16 + HikariCP
- **异步**：Spring `@Async` + `reviewTaskExecutor` 线程池（core 4 / max 8 / queue 100 / CallerRunsPolicy）

---

## 2. 目录结构（核心）

```
src/main/java/com/xmps/
  controller/   ReviewController（提交/查询/取消）、HealthController（/health）
  service/      ReviewService（流水线编排）、RulesEngine（确定性二次校验）
               ConstructionReview/MaintenanceReview（建设/运维审查）、DedupService（判重）
               DocumentParserService、OcrService（Tesseract OCR 兜底）、ReportService、CallbackService、FileStorageService、AuditService
  llm/          LlmClient（OpenAI 兼容封装）
  rules/        RulesLoader（加载 knowledge_base/*.md 知识库）
  model/        entity（ReviewTask/AuditLog）、enums（ProjectType/TaskStatus/ReviewVerdict）、ReviewItem
  security/     ApiKeyFilter（Authorization 头鉴权）、IpWhitelistFilter（IP 白名单，应用层）
  vector/       VectorStore 接口 + NoopVectorStore（一期空实现，二期换 ChromaDB/Milvus）
src/main/resources/
  application.yml / application-prod.yml   配置（prod 连 PostgreSQL）
  prompt-templates/                        Prompt 模板（classify/dedup/review，{{KEY}} 双花括号渲染）
  history-projects.json                    判重内置样例历史项目库
src/test/java/com/xmps/ReviewFlowIntegrationTest.java   端到端集成测试（真实调用 LLM）
```

---

## 3. 本地构建与运行

> 本机若未装 JDK/Maven，请用隔离目录环境（见 `run-tests.sh` / `run.sh` 内注释）。

**打包 + 启动（默认 8080）：**
```bash
./run.sh                 # 重新打包并启动
./run.sh --skip-build    # 跳过打包，直接启动已有 jar
./run.sh --port=9090     # 指定端口
./run.sh --prod          # 以 prod 配置启动（连 PostgreSQL）
```

**仅用 Maven：**
```bash
mvn -q package -DskipTests
java -Dfile.encoding=UTF-8 -jar target/xmps-ai-review-1.0.0-SNAPSHOT.jar --server.port=8080
```

**跑集成测试（真实调用 DeepSeek，约 1~3 分钟）：**
```bash
./run-tests.sh clean test -Dtest=ReviewFlowIntegrationTest
```
> ⚠️ 判重 LLM 结论**非确定**：同一文档可能判重复也可能不判。测试已按 `hasDuplicate` 分支处理，命中重复时流水线提前终止（reviewItems 为空属正确），不会误报失败。

---

## 4. 配置说明

配置前缀统一 `xmps.*`。**所有密钥走环境变量，勿写死在 yml**。

| 配置项 | 说明 | 环境变量 |
|---|---|---|
| `xmps.llm.base-url` / `model` / `timeout-seconds` | LLM 网关地址/模型/超时 | — |
| `xmps.llm.api-key` | LLM 密钥 | `LLM_API_KEY` |
| `xmps.security.api-key.header` | 鉴权头名（默认 `Authorization`） | — |
| `xmps.security.api-key.keys` | 允许多个 Key（逗号分隔） | `XMPS_API_KEYS` |
| `xmps.async.*` | 异步线程池（core/max/queue） | — |
| `xmps.callback.*` | 回调重试（max-retries / retry-intervals / initial-delay） | — |
| `xmps.vector.enabled` | 向量库开关（一期 false） | — |
| `xmps.ocr.enabled` / `tesseract-path` / `lang` / `min-text-length` | OCR 兜底（扫描件/图片型文档）：是否启用 / tesseract 路径 / 语言包 / 主解析低于该字数才触发 | `XMPS_OCR_ENABLED` / `XMPS_TESSERACT_PATH` |
| `spring.datasource.*` | 数据库连接（prod 用环境变量） | `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` |

激活方式：dev 为默认；生产加 `--spring.profiles.active=prod`（ddl-auto 为 `validate`，防自动改表）。

---

## 5. API 接口

鉴权：`Authorization: Bearer <api-key>`（Key 来自 `XMPS_API_KEYS`）。

### 5.1 提交评审
`POST /api/v1/review/tasks`（multipart/form-data）

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | 方案文档（.docx/.doc/.pdf，≤50MB） |
| `project_name` | 是 | 项目名称 |
| `project_type` | 否 | 项目类型提示 |
| `callback_url` | 否 | 评审完成回调地址 |
| `request_id` | 否 | 幂等键（重复提交返回原任务） |

```bash
curl -X POST http://localhost:8080/api/v1/review/tasks \
  -H "Authorization: Bearer test-key-001" \
  -F "file=@方案.docx" \
  -F "project_name=泰兴市智慧市场监管平台二期建设项目" \
  -F "callback_url=http://your-system/callback"
# => {"code":200,"data":{"taskId":"rev_20260930_001"}}
```

### 5.2 查询任务（含进度与完成报告）
`GET /api/v1/review/tasks/{taskId}`
```bash
curl http://localhost:8080/api/v1/review/tasks/rev_20260930_001 \
  -H "Authorization: Bearer test-key-001"
```

### 5.3 取消任务（终态不可取消）
`POST /api/v1/review/tasks/{taskId}/cancel`
```bash
curl -X POST http://localhost:8080/api/v1/review/tasks/rev_20260930_001/cancel \
  -H "Authorization: Bearer test-key-001"
# 已完成/失败返回 409；成功取消返回 200
```

### 5.4 健康检查
`GET /actuator/health` 或 `GET /health` → `{"status":"UP"}`

---

## 6. 评审流程

```
提交 → 保存文件 → 文档解析 → 类型判别(LLM语义,失败降级关键词)
     → 判重(LLM,命中重复则跳过审查直接出报告结束)
     → 规则审查(建设6项/运维4项 LLM) + 独立规则引擎确定性二次校验(仅追加)
     → 报告生成 → 回调推送 → 终态清理上传文件
```
- **独立规则引擎（RulesEngine）**：纯文本确定性扫描（方案完整性八章/五章、信创国产白名单、准入门槛禁止项），不调 LLM，仅追加发现项、绝不覆盖 LLM 结论。
- **降级策略**：文档解析失败是唯一硬失败；判重/审查异常降级继续；审计/回调失败不阻断主流程。

---

## 7. 容器化部署

### 7.1 仅构建镜像（dev / H2）
```bash
docker build -t xmps-ai-review:1.0.0 .
docker run -d --name xmps -p 8080:8080 \
  -e LLM_API_KEY=sk-xxx \
  -e XMPS_API_KEYS=prod-key-001 \
  xmps-ai-review:1.0.0
```

### 7.2 生产编排（PostgreSQL，推荐）
```bash
export LLM_API_KEY=sk-xxx XMPS_API_KEYS=prod-key-001
docker compose up -d --build
```
- `xmps` 以 `prod` profile 启动，连接 `postgres` 服务。
- 密钥通过环境变量注入（建议配合 `.env` 或密钥管理服务，**勿提交明文**）。
- `uploads` / `logs` / `pgdata` 通过命名卷持久化。
- 端口：`${PORT:-8080}`。

### 7.3 镜像说明
- 多阶段构建：Maven 打包 → JRE 运行；运行镜像固定 `UTF-8` 与 `-Dfile.encoding=UTF-8`，规避中文乱码。
- 容器内端口固定 8080，可用 `PORT` 环境变量或 `-p` 映射覆盖。
- **端口优先级（高→低）**：命令行 `--server.port` ＞ 环境变量 `SERVER_PORT` ＞ `application.yml` 的 `server.port`。⚠️ 切勿在运行环境设置 `SERVER_PORT=0`，否则 Spring Boot 会进入随机端口模式（监听 OS 随机高端口，如 52415/56739），导致服务端口不可预期；`run.sh` 已用 `--server.port=8080` 强制固定，不受该变量影响。
- `.dockerignore` 已排除 `data/`、`target/`、`.workbuddy/`、`.vscode/`、业务文档等，避免敏感/冗余内容进入构建上下文。

---

## 8. 运维与监控

- **日志**：`logs/xmps-ai-review.log`（容器内 `/app/logs`），可挂载卷持久化。
- **健康检查**：`GET /actuator/health`（actuator 暴露 `health,info`）。
- **回调**：评审完成主动 POST `callback_url`，含 `taskId/status/report/completed_at`；失败按 `retry-intervals` 梯度重试。

---

## 9. 已知限制 / 后续

- 端口随机根因（52415）**已查明**：非配置/代码问题，而是运行环境曾设置 `SERVER_PORT=0`（经 Spring relaxed binding 覆盖 yml 的 `server.port: 8080`）触发随机端口模式。yml 自首次提交起始终 8080、代码无端口硬编码；直接用 `java -jar`（不设该变量）即稳定 8080。现用 `--server.port=8080` 双保险固定（详见第 7.3 节）。
- 向量库（二期）一期为空实现，判重依赖 LLM + 内置样例历史库（`history-projects.json`）。
- 待办：结构化《修改建议书》。
- 已加固：IP 白名单应用层（⑥）已实现——`xmps.security.whitelist.allowed-ips` 支持精确 IP 与 CIDR，默认关闭，生产通过 `XMPS_ALLOWED_IPS` 注入；来源 IP 解析支持 `X-Forwarded-For`/`X-Real-IP`（前置可信代理场景）。
- 已增强：OCR 兜底（⑧）已实现——`OcrService` 调用系统 `tesseract` CLI，主解析文本 < `min-text-length`(默认50) 时先尝试 PDF 逐页 / docx 内嵌图片识别，仍为空才硬失败；tesseract 未安装时自动关闭、绝不抛异常。生产需安装 `tesseract` 及 `chi_sim` 语言包。

---

## 10. 提交记录（本仓库）

- `19edd7e` 第一档：业务实现偏差三项改造及接口契约资产入库
- `a349801` 第二档：独立规则引擎、LLM 语义判别、文件清理与测试修复
- `06ae2e8` 工程化交付：Dockerfile / docker-compose / README（部署阻塞项 ⑩）
- `9477352` 安全加固：应用层 IP 白名单（⑥）
- `8f7cbea` 安全增强：OCR(Tesseract) 兜底（⑧）
- （待提交）结构化《修改建议书》（⑨）：ReportService 新增 remediationPlan + remediationPlanMarkdown + 单元测试
