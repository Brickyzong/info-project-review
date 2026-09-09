#!/usr/bin/env bash
# ==============================================================================
# 本项目服务启动脚本
#
# 背景：这台机器没有装 JDK / Maven，本脚本使用隔离目录下的运行环境：
#   JDK   : ~/.workbuddy/binaries/java/jdk-17.0.20.1+1
#   Maven : ~/.workbuddy/binaries/maven/apache-maven-3.9.9
#
# 为什么不用 mvn spring-boot:run？
#   项目路径含中文与特殊字符（.../2026.7-9数智化轮岗/...），
#   spring-boot:run 在 fork JVM 拼接 classpath 时会解析失败，
#   报 "找不到或无法加载主类 com.xmps.XmpsApplication"。
#   改为先打包成可执行 jar、再用 java -jar 启动，可完全绕开该问题，
#   同时也是生产环境的标准运行方式。
#
# 用法（在 Git Bash 中执行）：
#   ./run.sh                      # 重新打包并启动（默认 8080 端口）
#   ./run.sh --skip-build         # 跳过打包，直接启动已有 jar（更快）
#   ./run.sh --port=9090          # 指定其他端口
#   ./run.sh --prod               # 以 prod 配置启动（连 PostgreSQL）
#
# 启动后：
#   健康检查  curl http://localhost:8080/actuator/health
#   停止服务  Ctrl+C
# ==============================================================================
set -euo pipefail

JDK_HOME="C:/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1"
MAVEN_HOME="C:/Users/zongwenyu/.workbuddy/binaries/maven/apache-maven-3.9.9"
MAVEN_SETTINGS="C:/Users/zongwenyu/.workbuddy/binaries/maven/settings.xml"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR_WIN="$(cygpath -w "$PROJECT_DIR")"
# JAR_WIN 给 java.exe 用（Windows 格式）；JAR_BASH 给 bash 做文件存在性判断
JAR_WIN="$PROJECT_DIR_WIN\\target\\xmps-ai-review-1.0.0-SNAPSHOT.jar"
JAR_BASH="$PROJECT_DIR/target/xmps-ai-review-1.0.0-SNAPSHOT.jar"

PORT=8080
SKIP_BUILD=false
PROFILE=""

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    --port=*)     PORT="${arg#*=}" ;;
    --prod)       PROFILE="prod" ;;
  esac
done

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  echo "错误：找不到 JDK，请检查 $JDK_HOME" >&2
  exit 1
fi

cd "$PROJECT_DIR"
echo "==> JDK   : $JDK_HOME"
echo "==> 项目  : $PROJECT_DIR_WIN"
echo "==> 端口  : $PORT"
echo

# ---------- 1. 打包 ----------
if [[ "$SKIP_BUILD" == "true" && -f "$JAR_BASH" ]]; then
  echo "==> 跳过打包，使用已有 jar"
else
  echo "==> 正在打包（跳过测试）..."
  "$JDK_HOME/bin/java" \
    -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
    -classpath "$MAVEN_HOME/boot/plexus-classworlds-2.8.0.jar" \
    "-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf" \
    "-Dmaven.home=$MAVEN_HOME" \
    "-Dmaven.multiModuleProjectDirectory=$PROJECT_DIR_WIN" \
    org.codehaus.plexus.classworlds.launcher.Launcher \
    -s "$MAVEN_SETTINGS" -f "$PROJECT_DIR_WIN/pom.xml" \
    -q package -DskipTests
  echo "==> 打包完成"
fi

# ---------- 2. 启动 ----------
ARGS=("--server.port=$PORT")
if [[ -n "$PROFILE" ]]; then
  ARGS+=("--spring.profiles.active=$PROFILE")
  echo "==> 配置  : prod"
fi

echo "==> 启动服务... (Ctrl+C 停止)"
echo
exec "$JDK_HOME/bin/java" \
  -Dfile.encoding=UTF-8 \
  -Dsun.jnu.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 \
  -Dstderr.encoding=UTF-8 \
  -jar "$JAR_WIN" "${ARGS[@]}"
