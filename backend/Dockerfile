# ---------------------------------------------------------------------------
# 后端镜像（对应《双人分工实施方案v2》§9 第 8 条 / 工作清单 B-3）
#
# 两阶段是刻意的：
#   build   —— 有 JDK + Maven，负责把源码编译成可执行 jar
#   runtime —— 只有 JRE，不带编译器与依赖仓库（镜像小一截，攻击面也小一截）
#
# 依赖层单独缓存：`pom.xml` 先于 `src/` 拷入，所以改 Java 代码不会重下依赖，
# 只有 pom 变了才重下。首次构建要下 ~150MB 依赖，慢是正常的。
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
# -B 无交互；dependency:go-offline 把 pom 里的依赖预取到本地仓库，
# 目的只是让这一层可缓存（漏掉的少数依赖会在下一步顺带补下，不影响正确性）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# 跳过单测：仓库当前没有 src/test（真正的闸门是 tools/acceptance/run-all.sh，
# 它对容器跑同样能过）。留着 -DskipTests 是防止以后加了单测、构建被拖慢或
# 因环境差异（如要连库）失败——镜像构建不该承担测试职责。
RUN mvn -B -q package -DskipTests

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
