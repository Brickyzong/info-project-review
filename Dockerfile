# ============================================================
# xmps-ai-review 容器化构建（多阶段）
# 说明：
#   - 构建阶段用 Maven 打包可执行 jar（需联网拉取依赖）。
#   - 运行阶段用 JRE 镜像，固定 UTF-8 编码与 8080 端口。
#   - 生产环境通过 SPRING_PROFILES_ACTIVE=prod 切换 PostgreSQL，
#     并用环境变量注入密钥（LLM_API_KEY / XMPS_API_KEYS），切勿写死。
# ============================================================

# ---------------- 构建阶段 ----------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷 pom 以利用依赖缓存层（pom 不变则依赖层不重建）
COPY pom.xml .
# 源码（含 .mvn/jvm.config 等工程配置）
COPY src ./src

# 打包：跳过测试（CI 中应单独跑集成测试）；显式 UTF-8 防止中文源码编译乱码
RUN mvn -B -q package -DskipTests -Dfile.encoding=UTF-8

# ---------------- 运行阶段 ----------------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 中文与编码（容器内固定 UTF-8，避免 Windows 下乱码问题在容器内复现）
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai

# 从构建阶段拷贝可执行 jar
COPY --from=build /build/target/xmps-ai-review-1.0.0-SNAPSHOT.jar /app/app.jar

# 上传文件、开发库数据、日志挂载点（按需持久化）
VOLUME ["/app/uploads", "/app/data", "/app/logs"]

EXPOSE 8080

# 启动参数（均可用环境变量覆盖）：
#   PORT                  服务端口（默认 8080）
#   SPRING_PROFILES_ACTIVE  生产环境设为 prod（连 PostgreSQL）
#   LLM_API_KEY / XMPS_API_KEYS  密钥（生产务必通过环境变量/密钥管理注入）
#   DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD  PostgreSQL 连接（prod 生效）
ENTRYPOINT ["sh", "-c", "java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar /app/app.jar --server.port=${PORT:-8080} ${SPRING_PROFILES_ACTIVE:+--spring.profiles.active=${SPRING_PROFILES_ACTIVE}}"]
