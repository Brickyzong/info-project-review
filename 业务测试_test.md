# 手把手教程：从零跑通评审服务的业务测试

这份教程假设你**从没在这台机器上跑过 Java 项目**。跟着做一遍，最后你会看到 `BUILD SUCCESS`。

---

## 开场：先搞清楚要装什么

这个测试要跑起来，需要两个东西：

| 工具         | 作用                                           | 本项目要求的版本                                |
| ---------- | -------------------------------------------- | --------------------------------------- |
| **JDK 17** | Java 程序的运行环境。项目代码是 Java 写的，没有 JDK 就没法编译也没法运行 | 17（项目 `pom.xml` 里写死了 `java.version=17`） |
| **Maven**  | 项目构建工具。负责下载 Spring Boot 等一堆第三方依赖、编译代码、跑测试    | 3.x（我用 3.9.9）                           |

你可以把它们理解成：JDK 是"发动机"，Maven 是"施工队"——施工队负责把零件（依赖库）买来、按图纸（pom.xml）组装、最后试车（跑测试）。

**这台机器的现状**：系统里**没有**装 JDK 和 Maven。但已经有一份现成的放在隔离目录里，可以直接用（见第 2 步），不用重新下载。

---

## 第 1 步：检查环境

打开 **Git Bash**，依次输入：

```bash
java -version
mvn -version
```

**三种可能的结果：**

| 你看到的                                | 说明   | 下一步           |
| ----------------------------------- | ---- | ------------- |
| 打印出版本号（如 `openjdk version "17..."`） | 已装好  | 跳到第 4 步       |
| `command not found`                 | 没装   | 继续第 2、3 步     |
| 版本是 1.8 或 11 之类的**非 17**            | 版本不对 | 需要装 17，见第 2 步 |

> 这台机器当前是 `command not found`，所以继续往下。

---

## 第 2 步：准备 JDK 17

### 方案 A：用现成的（推荐，30 秒搞定）

这台机器的隔离目录里已经有一份 JDK 17，路径是：

```
C:\Users\zongwenyu\.workbuddy\binaries\java\jdk-17.0.20.1+1
```

先确认它存在：

```bash
"C:/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1/bin/java" -version
```

看到 `openjdk version "17.0.20.1"` 就说明能用。**记住这个路径，第 4 步要用。**

### 方案 B：自己下载一份

1. 打开 <https://adoptium.net/temurin/releases/?version=17>
2. 选择 **Operating System: Windows**、**Architecture: x64**、**Package Type: JDK**
3. 下载 `.zip`（约 180MB，免安装版）
4. 解压到一个**没有中文和空格**的路径，比如 `C:\dev\jdk-17`

> ⚠️ 路径里不要有中文和空格，否则后面各种诡异报错。

---

## 第 3 步：准备 Maven

### 方案 A：用现成的

隔离目录里也有一份：

```
C:\Users\zongwenyu\.workbuddy\binaries\maven\apache-maven-3.9.9
```

### 方案 B：自己下载

1. 打开 <https://maven.apache.org/download.cgi>
2. 下载 `apache-maven-3.9.9-bin.zip`（约 9MB）
3. 解压到比如 `C:\dev\apache-maven-3.9.9`

### 配置阿里云镜像（**强烈建议**）

Maven 默认从国外的中央仓库下载依赖，国内会很慢甚至超时。改成阿里云镜像能快很多。

用记事本创建/编辑 Maven 的 `conf/settings.xml`，在 `<settings>` 里加：

```xml
<mirrors>
  <mirror>
    <id>aliyun</id>
    <name>aliyun public</name>
    <url>https://maven.aliyun.com/repository/public</url>
    <mirrorOf>central</mirrorOf>
  </mirror>
</mirrors>
```

> 现成的那份 Maven 目录旁边已经有配好的 `settings.xml`，用 `-s` 参数指过去即可（见第 7 步）。

---

## 第 4 步：配置环境变量

这步的作用是告诉系统"java 和 mvn 在哪"，这样你在任何目录敲命令都能找到它们。

### 4.1 临时配置（只在当前终端窗口有效，适合先试跑）

在 Git Bash 里**逐行**执行（注意把路径换成你自己的）：

```bash
# JDK 路径 —— 注意用 C:/ 这种 Windows 格式（Java 是 Windows 程序，认这个）
export JAVA_HOME="C:/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1"

# Maven 路径
export MAVEN_HOME="C:/Users/zongwenyu/.workbuddy/binaries/maven/apache-maven-3.9.9"

# 加到 PATH —— 这里要用 /c/ 这种 Git Bash 格式（bash 认这个）
export PATH="/c/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1/bin:/c/Users/zongwenyu/.workbuddy/binaries/maven/apache-maven-3.9.9/bin:$PATH"

# 🔧 编码（UTF-8）别在这里用 export 设！见下方「4.3 项目级编码锁定」。
# 原因：env 变量只在「当前这个终端窗口」有效，关掉重开就丢失，
# 下次跑 mvn 又会退回 GBK —— 你上次遇到的 GBK 就是这个问题。
```

> 💡 **为什么两种路径格式混用？** 这是 Git Bash 的经典坑：
>
> - `JAVA_HOME` 是给 Java/Maven（Windows 程序）看的，要用 `C:/...`
> - `PATH` 是给 bash 自己查找命令用的，要用 `/c/...`

### 4.2 永久配置（一劳永逸，推荐）

用图形界面，最稳妥：

1. 按 `Win + R`，输入 `sysdm.cpl`，回车
2. 点「高级」选项卡 → 「环境变量」
3. 在**系统变量**区点「新建」：
   - 变量名：`JAVA_HOME`
   - 变量值：`C:\Users\zongwenyu\.workbuddy\binaries\java\jdk-17.0.20.1+1`
4. 再新建一个：
   - 变量名：`MAVEN_HOME`
   - 变量值：`C:\Users\zongwenyu\.workbuddy\binaries\maven\apache-maven-3.9.9`
5. 找到系统变量里的 `Path`，点「编辑」→「新建」，加两条：
   - `%JAVA_HOME%\bin`
   - `%MAVEN_HOME%\bin`
6. 一路确定，**重新打开终端**才生效

> 配好后就不用每次敲 export 了。

### 4.3 项目级编码锁定（🔧 推荐优先做，已帮你建好）

前面两种都是「改你电脑的环境」；最稳的是**把编码写进项目本身**——任何人 clone 下来、在任何终端跑都是 UTF-8，跟系统 / 终端设置无关。

项目根目录已放好文件 `.mvn/jvm.config`，内容就两行：

```text
-Dfile.encoding=UTF-8
-Dsun.jnu.encoding=UTF-8
```

Maven 每次启动会**自动读取**这个文件，等价于每次都带上那两个 JVM 参数。验证：

```bash
mvn.cmd -version
# 最后一行应为：Default locale: zh_CN, platform encoding: UTF-8
```

> 这个文件是提交进仓库的，团队共用一份，最不容易出岔子。如果你是**自己新建**的项目，记得在根目录建 `.mvn/jvm.config` 写上这两行。

---

## 第 5 步：验证安装

```bash
java -version
mvn.cmd -version
```

**预期输出：**

```
openjdk version "17.0.20.1" 2026-08-18
OpenJDK Runtime Environment Temurin-17.0.20.1+1 (build 17.0.20.1+1)

Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)
Maven home: C:\Users\zongwenyu\.workbuddy\binaries\maven\apache-maven-3.9.9
Java version: 17.0.20.1, vendor: Eclipse Adoptium...
Default locale: zh_CN, platform encoding: UTF-8
```

✅ **两个都要看到**。特别留意最后一行 `platform encoding: UTF-8`——如果是 `GBK`，说明 4.3 的 `.mvn/jvm.config` 没生效（或你绕过了它、改用 `export MAVEN_OPTS` 却没设上），中文会乱码。

> ⚠️ **必须用 `mvn.cmd` 而不是 `mvn`！**  
> 在 Git Bash 里，`mvn`（bash 脚本版）有个已知的路径转换 bug，会报  
> `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。  
> `mvn.cmd` 是 Windows 批处理版，没这个问题。
>
> 如果你用的是 **PowerShell 或 CMD**，那直接敲 `mvn` 就行（那里没这个坑）。

---

## 第 6 步：进入项目目录

```bash
cd "G:/yd-work/2026.7-9数智化轮岗/开发项目任务/泰兴信息化评审/1/信息化项目评审"
```

确认你在对的目录——`pom.xml` 应该就在当前目录：

```bash
ls pom.xml
```



---

## 第 7 步：编译（首次会下载依赖）

```bash
mvn.cmd -s "C:/Users/zongwenyu/.workbuddy/binaries/maven/settings.xml" test-compile
```

> `-s` 指向配了阿里云镜像的 settings.xml。如果你在第 3 步已经改了 Maven 自带的 `conf/settings.xml`，这条 `-s` 可以省略。

**第一次会下载大量依赖**（Spring Boot 全家桶 + POI + PDFBox 等），耗时几分钟，屏幕上会刷很多 `Downloading...`。这是正常的。

看到这个就成功了：

```
[INFO] BUILD SUCCESS
[INFO] Total time:  xx s
```

> 依赖下载一次后会缓存在 `C:\Users\你的用户名\.m2\repository`，之后再编译就是秒级。

---

## 第 8 步：运行测试

```bash
mvn.cmd -s "C:/Users/zongwenyu/.workbuddy/binaries/maven/settings.xml" test -Dtest=ReviewFlowIntegrationTest
```

`-Dtest=ReviewFlowIntegrationTest` 表示只跑这一个测试类（共 11 个用例）。

**测试会真实调用 DeepSeek 做 AI 评审**，所以需要联网，耗时约 **30 秒 ~ 2 分钟**。

跑的过程中你会看到：

```
[状态] PARSING — 正在解析文档...
[状态] PARSING — 正在判别项目类型...
[状态] PARSING — 正在判重...
[状态] REVIEWING — 正在执行规则审查...
[状态] REVIEWING — 正在生成评审报告...
[状态] COMPLETED — 评审完成，耗时 11024ms

================ 评审结果 ================
任务状态   : COMPLETED（已完成）
项目类型   : CONSTRUCTION_CONTINUE
审查项数量 : 5
汇总       : 通过 0 / 不通过 4 / 存疑 1 / 不适用 0
整体结论   : 不通过
------------------------------------------
  [不通过] 准入门槛审查
  [不通过] 方案完整性检查
  [不通过] 信创合规检查
  [存疑] 绩效考核风险
  [不通过] 政务云资源合规核查
==========================================
```

这个输出就是**评审智能体对测试文档的真实审查结论**。测试文档里我故意埋了新建机房、采购戴尔服务器、Oracle 数据库等违规内容，所以判定"不通过"——说明审查引擎确实在工作。

---

## 第 9 步：看结果

最后几行是关键：

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| 字段              | 含义        |
| --------------- | --------- |
| `Tests run: 11` | 跑了 11 个用例 |
| `Failures: 0`   | 断言失败 0 个  |
| `Errors: 0`     | 抛异常 0 个   |
| `BUILD SUCCESS` | 🎉 通关     |

**只要看到 `BUILD SUCCESS`，就说明环境配好了、项目能编译、业务链路能跑通。**

如果失败了，往下看排查。

---

## 常见问题排查

| 现象                                                            | 原因                      | 解决                                                            |
| ------------------------------------------------------------- | ----------------------- | ------------------------------------------------------------- |
| `java: command not found`                                     | PATH 没配对                | 检查第 4 步。Git Bash 里 PATH 要用 `/c/...` 格式                        |
| `mvn: command not found`                                      | 同上                      | 另外注意：Git Bash 里要用 `mvn.cmd`                                   |
| `找不到 plexus.classworlds.launcher.Launcher`                    | Git Bash 的 `mvn` 脚本 bug | 改用 `mvn.cmd`                                                  |
| `The JAVA_HOME environment variable is not defined correctly` | JAVA_HOME 路径错或没设        | JAVA_HOME 要用 `C:/...` 格式且指向 JDK 根目录（不是 bin）                   |
| 中文全是问号/乱码                                                     | 编码是 GBK（没锁 UTF-8）       | 确认项目根有 `.mvn/jvm.config`；临时用 `export MAVEN_OPTS=...` 但仅当前窗口有效 |
| 依赖下载极慢或卡住                                                     | 默认走国外仓库                 | 用 `-s` 指定阿里云镜像的 settings.xml                                  |
| `Tests run: 11, Failures: 1`                                  | 评审结果不符合预期               | 看输出里 `FAILURE` 那段的断言信息；AI 输出有随机性，重跑一次可能就过了                    |
| 报 `LLM 调用失败` 或超时                                              | 网络不通 / API Key 失效       | 检查网络；`application.yml` 里的 DeepSeek Key 是否还有效                  |
| `Unsupported class file major version`                        | JDK 版本太低                | 必须用 17                                                        |

---

## 速查卡（配好环境后每次用这段）

```bash
# 1. 进入项目
cd "G:/yd-work/2026.7-9数智化轮岗/开发项目任务/泰兴信息化评审/1/信息化项目评审"

# 2. 设置环境（若已做永久配置可跳过这三行）
export JAVA_HOME="C:/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1"
export PATH="/c/Users/zongwenyu/.workbuddy/binaries/java/jdk-17.0.20.1+1/bin:/c/Users/zongwenyu/.workbuddy/binaries/maven/apache-maven-3.9.9/bin:$PATH"
# 编码已由项目根 .mvn/jvm.config 锁定，无需再设 MAVEN_OPTS（见 4.3）

# 3. 跑测试
mvn.cmd -s "C:/Users/zongwenyu/.workbuddy/binaries/maven/settings.xml" test -Dtest=ReviewFlowIntegrationTest
```

或者更简单——用项目里现成的脚本（已封装以上所有细节）：

```bash
./run-tests.sh test -Dtest=ReviewFlowIntegrationTest
```

---

## 附：其他常用命令

```bash
# 只编译，不跑测试
mvn.cmd test-compile

# 跑全部测试（目前只有两个测试类）
mvn.cmd test

# 跳过测试打包成 jar
mvn.cmd clean package -DskipTests

# 清空编译产物（遇到诡异问题时先 clean 一下）
mvn.cmd clean
```
