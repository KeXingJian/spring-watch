FROM eclipse-temurin:25-jre-jammy

WORKDIR /app
COPY target/mock-test-1.0.0.jar app.jar
COPY target/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY entrypoint.sh /app/entrypoint.sh

EXPOSE 8081

ENTRYPOINT ["sh", "/app/entrypoint.sh"]