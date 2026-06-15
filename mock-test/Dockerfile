# syntax=docker/dockerfile:1.4
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 配置阿里云 Maven 镜像
RUN mkdir -p /root/.m2 && printf '<?xml version="1.0" encoding="UTF-8"?>\n<settings>\n  <mirrors>\n    <mirror>\n      <id>aliyunmaven</id>\n      <name>Aliyun Maven</name>\n      <url>https://maven.aliyun.com/repository/public</url>\n      <mirrorOf>central</mirrorOf>\n    </mirror>\n  </mirrors>\n</settings>\n' > /root/.m2/settings.xml

# 1) 仅拷贝 pom.xml，单独预热依赖层（只要 pom 不变，这一层就被缓存复用）
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s /root/.m2/settings.xml -B -q dependency:go-offline

# 2) 再拷贝源码，源码变更不影响上面的依赖层
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s /root/.m2/settings.xml -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /build/target/mock-test-1.0.0.jar app.jar
COPY --from=builder /build/target/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh

EXPOSE 8081 9464

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xmx512m" \
    OTEL_ENABLED=true \
    OTEL_SERVICE_NAME=mock-test \
    OTEL_EXPORTER_PROMETHEUS_HOST=0.0.0.0 \
    OTEL_EXPORTER_PROMETHEUS_PORT=9464

ENTRYPOINT ["sh", "/app/entrypoint.sh"]
