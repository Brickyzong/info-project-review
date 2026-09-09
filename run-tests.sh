#!/usr/bin/env bash
# ==============================================================================
# 本项目测试运行脚本
#
# 背景：这台机器没有装 JDK / Maven，本脚本使用隔离目录下的运行环境：
#   JDK   : ~/.workbuddy/binaries/java/jdk-17.0.20.1+1
#   Maven : ~/.workbuddy/binaries/maven/apache-maven-3.9.9
#
# 用法（在 Git Bash 中执行）：
#   ./run-tests.sh test-compile                       # 只编译测试代码
#   ./run-tests.sh test                               # 跑全部测试
#   ./run-tests.sh test -Dtest=ReviewFlowIntegrationTest
#   ./run-tests.sh clean package -DskipTests
#
# 常用参数说明：
#   -Dtest=类名       只跑指定的测试类
#   -DskipTests       跳过测试只打包
#   -o                离线模式（依赖已下载时可加速）
# ==============================================================================
set -euo pipefail

# 注意：java.exe 是 Windows 程序，不认 Git Bash 的 /c/... 路径，
# 这里一律使用 C:/... 形式；项目目录用 cygpath 转换。
JDK_HOME="C:/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1"
MAVEN_HOME="C:/Users/zongwenyu/.workbuddy/binaries/maven/apache-maven-3.9.9"
MAVEN_SETTINGS="C:/Users/zongwenyu/.workbuddy/binaries/maven/settings.xml"
PROJECT_DIR="$(cygpath -w "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")"

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  echo "错误：找不到 JDK，请检查 $JDK_HOME" >&2
  exit 1
fi

echo "==> JDK   : $JDK_HOME"
echo "==> Maven : $MAVEN_HOME"
echo "==> 项目  : $PROJECT_DIR"
echo

# 说明：绕开 mvn shell 脚本（Git Bash 下 classpath 转换有 bug），
# 直接调用 Maven 的 classworlds launcher。
# -Dfile.encoding=UTF-8 是必须的：Windows 默认 GBK，会导致中文文件名与
# 中文文档内容解析出现乱码。
exec "$JDK_HOME/bin/java" \
  -Dfile.encoding=UTF-8 \
  -Dsun.jnu.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 \
  -Dstderr.encoding=UTF-8 \
  -classpath "$MAVEN_HOME/boot/plexus-classworlds-2.8.0.jar" \
  "-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf" \
  "-Dmaven.home=$MAVEN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$PROJECT_DIR" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  -s "$MAVEN_SETTINGS" \
  -f "$PROJECT_DIR/pom.xml" \
  "$@"
