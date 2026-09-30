# 项目长期笔记 — 信息化项目评审 AI 评审服务

## 项目定位
泰兴市数据局「政务信息化项目智能评审」的**纯后端 AI 评审服务**（无前端）。
交付形态：RESTful API，供现有项目管理系统（调用方）集成，我方出报告 JSON，对方负责展示。
Maven 坐标：`com.xmps:xmps-ai-review:1.0.0-SNAPSHOT`

## 技术栈
Java 17 + Spring Boot 3.3.1 + Spring Data JPA + Lombok
- 文档解析：Apache POI 5.2.5（docx/doc）+ PDFBox 3.0.2（pdf）+ LibreOffice headless（doc 转 docx）
- LLM：自研 RestClient 封装 OpenAI 兼容协议，默认 DeepSeek，生产切政务模型网关（只改 yml）
- DB：dev=H2 文件库（`./data/xmps`），prod=PostgreSQL 16 + HikariCP
- 异步：Spring `@Async` + `reviewTaskExecutor` 线程池（core 4 / max 8 / queue 100 / CallerRunsPolicy）

## 业务核心
设计原则：**先判属性、再行审核、分类处置、全程留痕**

三大智能体：项目判重 / 建设类审查（6 项）/ 运维类审查（4 项）
- 建设 6 项：重复性核查、准入门槛、方案完整性（八章）、信创合规（芯片/OS/DB/中间件）、绩效考核（<70 分预警）、政务云资源
- 运维 4 项：准入门槛、方案完整性（五章）、政务云资源、绩效考核

路由：判重命中重复建设 → **跳过审查直接出报告结束流程**

## 模块地图（src/main/java/com/xmps/）
| 包 | 关键类 |
|---|---|
| `controller` | ReviewController（submit/status/result）、HealthController |
| `service` | **ReviewService**（流水线编排）、ReviewEngine（审查）、DedupService（判重）、DocumentParserService（解析）、ReportService（报告）、CallbackService（回调）、FileStorageService、AuditService |
| `llm` | LlmClient（+ `llm.config` 三个 Chat POJO） |
| `rules` | RulesLoader（启服加载 knowledge_base/*.md） |
| `model.entity` | ReviewTask、AuditLog |
| `model.enums` | ProjectType、TaskStatus（7 态机）、ReviewVerdict（PASS/FAIL/UNCERTAIN/NOT_APPLICABLE） |
| `security` | ApiKeyFilter（校验 `X-API-Key` 头） |
| `vector` | VectorStore 接口 + NoopVectorStore（一期空实现，二期换 ChromaDB/Milvus） |

## 状态机
`PENDING → DOC_PARSING → TYPE_CLASSIFYING → DEDUP_CHECKING → RULE_REVIEWING → REPORT_GENERATING → COMPLETED`，任一出错 → `FAILED`

## 降级策略（重要设计）
- **文档解析是唯一硬失败**（解析失败直接 FAILED）
- 判重异常 → 降级为「不重复」继续走流程
- 审查异常 → 构造一条 UNCERTAIN 项继续生成报告
- 审计日志写入失败 → 只 log.error，绝不阻断主流程
- 回调最终失败 → 不改任务状态（评审已完成），调用方仍可轮询

## 配置约定
- yml 前缀统一 `xmps.*`：`llm` / `security.api-key` / `async` / `callback` / `vector`
- 敏感值走环境变量：`LLM_API_KEY`、`XMPS_API_KEYS`、`XMPS_ALLOWED_IPS`（IP 白名单）、`DB_USERNAME`、`DB_PASSWORD`
- prod 激活：`--spring.profiles.active=prod`；`ddl-auto` dev=update / prod=validate（防生产自动改表）

## 已知债（对照 C-技术方案V2.0 三/四/五/七章核对）
**第一档 接口契约不符（①②③④⑤⑥⑦⑧）— 已完成并验证**：2026-09-03~09-09 改造，16 文件 +395/-217 行，端到端 11/11 全绿；cancel 接口一并完成。详见 `2026-09-09.md`。

**第二档 待改进（12 项）**，按优先级（✅=已解决）：
- 业务实现偏差（高优先）— **全部已解决**：
  - ①独立规则引擎二次校验 ✅（RulesEngine.java，纯确定性扫描，仅追加不覆盖 LLM 结论）
  - ②ConstructionReview/MaintenanceReview 拆分 ✅（09-18）
  - ③PromptTemplates 外置 ✅（resources/prompt-templates/*.txt，{{KEY}} 双花括号渲染；09-18）
  - ④判重历史库样例数据 ✅（HistoryProjectStore 加载 history-projects.json；09-18）
  - ⑤类型判别升语义理解 ✅（ReviewService.classifyByLlm + classify-system/user.txt，失败降级关键词；09-30）
- 安全加固（中优先）：⑥IP 白名单应用层 ✅（IpWhitelistFilter + IpWhitelistProperties，order=0 先于 ApiKeyFilter；支持精确 IP/CIDR、X-Forwarded-For 代理解析、排除探针路径；默认关闭，生产 XMPS_ALLOWED_IPS 注入；09-30） ⑦评审后文件清理 ✅（ReviewService.cleanupFiles 终态清理本任务目录；09-30） ⑧OCR(Tesseract)兜底 ✅（OcrService 调系统 tesseract CLI；主解析文本<min-text-length 先尝试 PDF 逐页/docx 内嵌图片识别，仍为空才硬失败；未安装自动关闭不抛异常；09-30）
- 工程化交付（阻塞）：⑨结构化《修改建议书》（未做） ⑩Dockerfile+README+部署文档 ✅（Dockerfile/.dockerignore/docker-compose.yml/README.md；09-30） ⑪第一档代码/测试资产 git 提交 ✅（19edd7e）；**第二档已提交 a349801** ⑫端口随机根因(52415，已绕过未查明)

注：模块地图/状态机段落已滞后（仍写 7 态机、X-API-Key、submit/status/result），属笔记描述过时，非项目债。

## 版本控制（2026-09-09 发现，此前一直没用）
项目**有 git 仓库**，基线提交 `5a94df3 init:信息化项目评审初始代码与文档`，
之后是 `28bdd0e 移除大文件`、`65af092 移除项目内业务文档文件`。
**第一档（接口契约）改动全部未提交**，因此 `git diff` 工作区 == 第一档改动全集。
核对改动一律用 `git diff --stat -- src/`，不要靠记忆复述。
详见 `2026-09-09.md`（含逐文件的准确改动清单）。

## 运行环境（本机没装 JDK / Maven，用隔离目录）
- JDK 17.0.20.1：`C:\Users\zongwenyu\.workbuddy\binaries\java\jdk-17.0.20.1+1`
- Maven 3.9.9：`C:\Users\zongwenyu\.workbuddy\binaries\maven\apache-maven-3.9.9`（同目录有阿里云镜像 settings.xml）
- 编码锁定：`项目根/.mvn/jvm.config`（两行 `-Dfile.encoding=UTF-8` / `-Dsun.jnu.encoding=UTF-8`），Maven 自动读取，**不受终端/系统影响**，优先于 MAVEN_OPTS。
- 教程：`手把手教程-从零跑通业务测试.md`（项目根）

**Git Bash 里手动跑（2026-09-03 已验证跑通 10/10）**：
```bash
export JAVA_HOME="C:/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1"   # Windows 格式
export PATH="/c/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1/bin:/c/Users/zongwenyu/.workbuddy/binaries/maven/apache-maven-3.9.9/bin:$PATH"   # Git Bash 格式
# 编码已由 .mvn/jvm.config 锁定，无需 MAVEN_OPTS；mvn.cmd 非 mvn（避开 bash 路径 bug）
mvn.cmd -s "C:/Users/zongwenyu/.workbuddy/binaries/maven/settings.xml" test -Dtest=ReviewFlowIntegrationTest
```

**三个必须注意的坑**（都是踩过的）：
1. Git Bash 里用 **`mvn.cmd`** 不是 `mvn`——mvn 的 bash 脚本有 classpath 转换 bug，
   会报 `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。
   PowerShell / CMD 里直接敲 `mvn` 就没这问题。
2. **路径格式混用**：`JAVA_HOME` 用 `C:/...`（给 Java/Maven 这种 Windows 程序看），
   `PATH` 用 `/c/...`（给 bash 自己查找命令用）。写反了会 `command not found`。
3. **必须设 UTF-8**：Windows 默认是 GBK，不设中文全乱码。最稳的是写进项目 `.mvn/jvm.config`（已建好），**不要依赖 `export MAVEN_OPTS`**——env 变量只在当前终端窗口有效，关掉重开就失效（用户 2026-09-03 实测踩坑：看到 GBK 就是因为 MAVEN_OPTS 没生效）。

**懒人方式**：项目根 `./run-tests.sh test -Dtest=ReviewFlowIntegrationTest`（已封装以上所有细节）。
测试真实调用 DeepSeek（yml 里配的 key 有效），完整跑一次约 1～3 分钟（含 3 次 LLM 调用：类型判别+判重+审查）。

⚠️ **判重非确定性（测试必读）**：`DedupService.analyze` 调 LLM，返回 `duplicate` 结论**非确定**——同一份测试文档曾判 `true` 也曾判 `false`。集成测试已按 `task.getHasDuplicate()` 分支处理：命中重复→流水线在判重步骤提前终止、`reviewItems=[]`（正确行为），断言只校验 `report.dedup.isDuplicate==true`；非重复跑才校验「≥5/4 审查项 + 含`确定性校验`项」。**改测试断言务必保留此分支**，否则会偶发失败（曾误报 2 处）。

## 启动服务（2026-09-04 新增，已验证跑通）
项目根 `./run.sh`：打包 + `java -jar` 启动，默认 **8080** 端口。
- `./run.sh` 重新打包并启动；`--skip-build` 跳过打包（约 15 秒起）；`--port=xxxx`；`--prod`
- 健康检查：`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

**为什么不用 `mvn spring-boot:run`**：项目路径含中文与特殊字符
（`.../2026.7-9数智化轮岗/...`），spring-boot:run fork JVM 拼 classpath 时解析失败，
报「找不到或无法加载主类 com.xmps.XmpsApplication」。改打 jar 再 `java -jar` 可绕开。
（`mvn test` 不受影响，surefire 有自己的 classpath 处理，所以测试一直正常。）

**坑 4（新增）**：`java -jar` 的路径必须用 **Windows 格式**（`G:/...`），
传 Git Bash 的 `/g/...` 会报 `Unable to access jarfile`。run.sh 里已区分
`JAR_WIN`（给 java.exe）与 `JAR_BASH`（给 bash 做 `-f` 判断）。

**未解之谜**：application.yml 写的 `server.port: 8080`，jar 内也是 8080，环境变量也没设，
但直接 `java -jar` 会起随机端口（如 52415）。已用启动参数 `--server.port=8080` 强制固定，根因未查明。

## 讲解偏好（用户 2026-09-03 明确反馈，后续照此执行）
- **按模块 / 文件讲，不要逐行讲**：不要出现"第 N 行做了什么"这种粒度，用户会找不到也记不住
- **每个 .java 文件用一句话说清职责**，按文件夹分组呈现，讲清"它在哪、干什么、跟谁配合"
- **实现细节后置**：异步线程池调度、耗时量级、@Async 点火时机等，等用户熟悉文件结构后再补
- 用户当前处于"熟悉项目结构"阶段，优先建立空间感（哪层装了哪些文件）
