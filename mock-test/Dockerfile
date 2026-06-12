FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /build/target/mock-test-1.0.0.jar app.jar
COPY --from=builder /build/target/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8081 9464

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xmx512m" \
    OTEL_ENABLED=true \
    OTEL_SERVICE_NAME=mock-test \
    OTEL_EXPORTER_PROMETHEUS_HOST=0.0.0.0 \
    OTEL_EXPORTER_PROMETHEUS_PORT=9464

ENTRYPOINT ["sh", "/app/entrypoint.sh"]
