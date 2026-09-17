# ---------------------------------------------------------------------------
# 后端镜像（对应《双人分工实施方案v2》§9 第 8 条 / 工作清单 B-3）
#
# 两阶段是刻意的：
#   build   —— 有 JDK + Maven，负责把源码编译成可执行 jar
#   runtime —— 只有 JRE，不带编译器与依赖仓库（镜像小一截，攻击面也小一截）
#
# 依赖缓存用 BuildKit 的 cache mount，不用「单独一层」：
#
# 这里原先写的是 `COPY pom.xml` + `RUN mvn dependency:go-offline`，靠**层缓存**避免重复下载。
# 但层缓存有个致命的前提——**这一层必须成功跑完过一次**。首次构建要下上百 MB 依赖，
# 而 `go-offline` 会连 optional 与 test 传递依赖一起解析（实测把 HikariCP 的
# AWS secretsmanager optional 都拉了进来），于是在慢网络下它经常跑十几分钟还没完；
# 一旦在这期间中断构建，这层**什么都存不下来**，下次重试又从零开始——
# 越等越久、越久越想中断，正好卡死在一个自我循环里。
#
# cache mount 不依赖「层成功地存在过」：下到一半被打断，已下载的部分仍在缓存里，
# 下次接着下。这是它与层缓存的关键区别，也是这个构建能收敛的原因。
#
# 注意：`--mount=type=cache` 需要 BuildKit。**不要**为此加 `# syntax=docker/dockerfile:1`
# ——那会在构建时去 Docker Hub 拉 syntax 镜像，离线部署机器上会直接失败，
# 而本仓是有离线部署要求的。
#
# 这里有个容易踩的坑（本机实测）：`docker compose build` 有它自己的 BuildKit 路径，
# **没装 buildx 也能用** `--mount`；而裸 `docker build` 在没装 buildx 时会回落到传统
# builder，直接报 `the --mount option requires BuildKit`。所以本镜像**必须用
# `docker compose build` 来构建**。已确认仓内没有任何脚本或文档用裸 `docker build`，
# 所以这条约束不会踩到谁；但如果以后有人把它写进脚本，要按这条来。
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
# 不再单独跑 dependency:go-offline：它的作用已被下面的 cache mount 完全覆盖，
# 再跑一遍等于把同一批依赖解析两次。`package` 自己会把需要的都下齐。
COPY pom.xml .
COPY src ./src

# 跳过单测，且用 -Dmaven.test.skip=true 而不是 -DskipTests：
# 后者仍然**编译** src/test（仓库现在有 4 个测试类了，早先那句「仓库当前没有 src/test」
# 已过期），于是 go-offline 与 package 都得把 JUnit/Mockito 这些 test 依赖也拉进来，
# 而这个镜像里根本用不到它们——jar 不会包含测试类，真正的闸门是
# tools/acceptance/run-all.sh（它对容器跑同样能过）。maven.test.skip 连测试编译一起跳过，
# 顺带省掉整条 test 依赖分支。镜像构建不该承担测试职责。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -Dmaven.test.skip=true

# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# curl 只为容器的 healthcheck 用（JRE 基础镜像里没有），顺便方便进容器排查
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 不用 root 跑应用。uid/gid 固定，方便宿主侧对上传目录做权限判断
RUN groupadd --gid 10001 monitor \
 && useradd --uid 10001 --gid monitor --no-create-home --shell /usr/sbin/nologin monitor

WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar

# 上传的影像落在这里；compose 会挂一个卷上来，让重启不丢（第 8 条）
RUN mkdir -p /app/data/media && chown -R monitor:monitor /app
USER monitor

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# sh -c 是为了让 JAVA_OPTS 能按环境变量展开；exec 保证 java 是 1 号进程，
# 能正确收到 docker stop 的 SIGTERM（否则信号打到 shell 上，优雅停机失效）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
