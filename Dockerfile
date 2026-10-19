# JavaForum 后端运行时镜像（单阶段）
#
# 为什么不是多阶段：部署主机（raspi）上没有 maven 镜像，也不随意拉新镜像；
# 具体来说 jar 由 Jenkins 用 Maven 3.8.7 + JDK17 构建，Dockerfile 只负责组装运行时。
#
# 构建：
#   mvn -B -DskipTests clean package
#   podman build --format docker -t localhost/forum-backend:latest .
#   （必须带 --format docker：OCI 镜像格式会丢弃下面的 HEALTHCHECK）
#
# 基础镜像 eclipse-temurin:17-jre-jammy 在 raspi 上已有（arm64），无需联网。
# 镜像内不包含任何密码或密钥：DB_PASSWORD / JWT_SECRET 一律运行时注入。

FROM docker.io/library/eclipse-temurin:17-jre-jammy

ENV TZ=Asia/Shanghai \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    LOG_FILE=/app/logs/javaforum.log \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# 用数字 UID/GID 而不是 useradd：精简基础镜像不一定带 passwd 包，数字形式最稳。
# 1001 只存在于容器内，raspi 宿主上没有对应用户。
WORKDIR /app
COPY --chown=1001:1001 target/forum-0.0.1-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R 1001:1001 /app
USER 1001:1001

EXPOSE 8080

# jammy 镜像没有 curl，用 bash 的 /dev/tcp 探测；探针走公开接口 /api/health
# （/actuator/health 需要 ADMIN 角色，匿名探测会 401）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

# 用 exec 让 java 成为 PID 1，正确接收 SIGTERM 完成优雅停机；
# 用 shell 形式是为了展开 JAVA_OPTS（exec 形式不会展开环境变量）。
ENTRYPOINT ["/bin/bash", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
