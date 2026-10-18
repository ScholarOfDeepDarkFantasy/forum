# syntax=docker/dockerfile:1
#
# JavaForum 后端镜像（多阶段构建）
#
#   构建：docker build -t javaforum-backend:latest .
#   运行：docker compose up -d --build（推荐，见 docker-compose.yml）
#
# 构建上下文只需要 pom.xml 与 src/，其余由 .dockerignore 排除。
# 镜像内不包含任何密码或密钥：DB_PASSWORD / JWT_SECRET 一律运行时通过环境变量注入。

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先单独复制 pom.xml 并预拉依赖。
# 只要 pom.xml 不变，改源码时这一层仍命中构建缓存，不必每次重新下载依赖。
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline || true

# 再复制源码打包。测试在本地/CI 跑，镜像构建跳过以缩短时间。
COPY src ./src
RUN mvn -B -DskipTests package

# 按 Spring Boot 分层格式展开 fat jar：
# 依赖、快照依赖、应用类各成一层，日常改代码只需重建最后的 application 层。
RUN java -Djarmode=tools -jar target/forum-0.0.1-SNAPSHOT.jar extract \
      --layers --launcher --destination /build/extracted

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre-alpine

# curl：供 HEALTHCHECK 使用
# tzdata：容器默认 UTC，若不设置时区，JPA 审计写入的 create_time/update_time 会比本地时间少 8 小时
RUN apk add --no-cache curl tzdata \
 && addgroup -g 1001 -S app \
 && adduser  -u 1001 -S -G app app

WORKDIR /app

# 分层复制，变化频率从低到高，最大化缓存命中
COPY --from=build --chown=app:app /build/extracted/dependencies/          ./
COPY --from=build --chown=app:app /build/extracted/spring-boot-loader/    ./
COPY --from=build --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /build/extracted/application/           ./

# prod profile 会把日志写到 logs/javaforum.log，需要可写目录
RUN mkdir -p /app/logs && chown -R app:app /app

USER app

ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# 探针走 /api/health（公开接口）。
# 不能用 /actuator/health：本项目给 actuator 配了 hasRole('ADMIN')，匿名探测会得到 401。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/api/health" || exit 1

# 用 sh -c + exec，让 java 成为 PID 1，从而正确接收 SIGTERM 完成优雅停机。
# 直接用 exec 形式则无法展开 JAVA_OPTS。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
