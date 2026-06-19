#!/bin/sh
set -e

if [ "${OTEL_ENABLED}" = "true" ]; then
    echo "[mock-test] OpenTelemetry Agent enabled"
    echo "[mock-test] Service:    ${OTEL_SERVICE_NAME:-mock-test}"
    echo "[mock-test] Metrics:    :${OTEL_EXPORTER_PROMETHEUS_PORT:-9464}/metrics"
    echo "[mock-test] App port:   ${SERVER_PORT:-8081}"

    exec java ${JAVA_OPTS} \
        -javaagent:/app/opentelemetry-javaagent.jar \
        -Dotel.service.name=${OTEL_SERVICE_NAME:-mock-test} \
        -Dotel.resource.attributes=service.name=${OTEL_SERVICE_NAME:-mock-test},deployment.environment=docker \
        -Dotel.metrics.exporter=prometheus \
        -Dotel.traces.exporter=none \
        -Dotel.logs.exporter=none \
        -Dotel.exporter.prometheus.host=${OTEL_EXPORTER_PROMETHEUS_HOST:-0.0.0.0} \
        -Dotel.exporter.prometheus.port=${OTEL_EXPORTER_PROMETHEUS_PORT:-9464} \
        -Dotel.instrumentation.common.default-enabled=true \
        -Dotel.instrumentation.runtime-telemetry.enabled=true \
        -Dotel.instrumentation.oshi.enabled=true \
        -Dotel.instrumentation.oshi.experimental-metrics.enabled=true \
        -Dotel.instrumentation.executors.enabled=true \
        -Dotel.instrumentation.micrometer.enabled=true \
        -Dotel.instrumentation.http-server.enabled=true \
        -Dotel.instrumentation.tomcat.enabled=true \
        -Dotel.instrumentation.jdbc.enabled=true \
        -Dotel.instrumentation.hikaricp.enabled=true \
        -Dserver.port=${SERVER_PORT:-8081} \
        -cp '/app/classes:/app/lib/*' \
        com.mock.test.MockTestApplication
else
    echo "[mock-test] OpenTelemetry Agent disabled, running plain Java"
    exec java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT:-8081} -cp '/app/classes:/app/lib/*' com.mock.test.MockTestApplication
fi
