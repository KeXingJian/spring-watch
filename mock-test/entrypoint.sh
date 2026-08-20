#!/bin/sh
set -e

echo "[mock-test] Spring Watch Agent enabled"
echo "[mock-test] Service:    ${SW_APP_NAME:-mock-test}"
echo "[mock-test] SW Metrics: :${SW_METRICS_PORT:-9464}/metrics"
echo "[mock-test] App port:   ${SERVER_PORT:-8081}"

exec java ${JAVA_OPTS} \
    -javaagent:/app/spring-watch-agent.jar \
    -Dspring.watch.metrics.host=${SW_METRICS_HOST:-0.0.0.0} \
    -Dspring.watch.metrics.port=${SW_METRICS_PORT:-9464} \
    -Dspring.watch.app.name=${SW_APP_NAME:-mock-test} \
    -Dspring.watch.log.buffer.size=${SW_LOG_BUFFER_SIZE:-65536} \
    -Dspring.watch.sql.slow.ms=${SW_SQL_SLOW_MS:-200} \
    -Dserver.port=${SERVER_PORT:-8081} \
    -cp '/app/classes:/app/lib/*' \
    com.mock.test.MockTestApplication
