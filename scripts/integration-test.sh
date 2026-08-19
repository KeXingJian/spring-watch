#!/bin/bash
# integration-test.sh - mock-test + Agent 端到端验收
# 用法: ./integration-test.sh [--keep-running]
set -e

HOST=localhost
AGENT_PORT=9464
APP_PORT=8080
KEEP=${1:-}

cd "$(dirname "$0")/.."

echo "=== 0. 检查前置 ==="
[ -f spring-watch-agent/agent-shade/target/spring-watch-agent.jar ] || {
    echo "缺少 agent-shade.jar,请先 mvn package"
    exit 1
}

echo "=== 1. 启动 mock-test(挂载 Agent) ==="
cd mock-test
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-javaagent:$(realpath ../spring-watch-agent/agent-shade/target/spring-watch-agent.jar) \
  -Dspring.watch.metrics.port=${AGENT_PORT} \
  -Dspring.watch.log.buffer.size=65536 \
  -Dspring.watch.app.name=mock-test \
  -Dspring.watch.sql.slow.ms=200" \
  > /tmp/mock-test-agent.log 2>&1 &
APP_PID=$!
cd ..

echo "PID=$APP_PID,等待启动..."
for i in {1..60}; do
    if curl -s "http://${HOST}:${APP_PORT}/actuator/health" > /dev/null 2>&1 \
        || curl -s "http://${HOST}:${APP_PORT}/health" > /dev/null 2>&1; then
        break
    fi
    sleep 1
done

if [ "$KEEP" = "--keep-running" ]; then
    echo "=== 业务流量触发 ==="
    echo "(请打开浏览器访问 http://localhost:${APP_PORT}/ 触发业务)"
    echo "完成后按 Ctrl+C 退出"
    trap "kill $APP_PID 2>/dev/null" INT TERM
    wait $APP_PID
    exit 0
fi

echo "=== 2. 验收 1:/metrics 含 JVM 指标 ==="
N=$(curl -s http://${HOST}:${AGENT_PORT}/metrics | grep -c "^sw_jvm_")
[ "$N" -gt 5 ] || { echo "FAIL ($N)"; exit 1; }
echo "OK ($N 行 sw_jvm_*)"

echo "=== 3. 验收 2:业务请求触发方法 + SQL 指标 ==="
curl -s http://${HOST}:${APP_PORT}/api/orders/find > /dev/null
curl -s http://${HOST}:${APP_PORT}/api/orders/count > /dev/null
sleep 1

echo "  sw_method_calls_total:"
curl -s http://${HOST}:${AGENT_PORT}/metrics | grep "^sw_method_calls_total" | head -3
echo "  sw_sql_calls_total (P1 JdbcTemplate):"
curl -s http://${HOST}:${AGENT_PORT}/metrics | grep "^sw_sql_calls_total" | head -3

N_METHOD=$(curl -s http://${HOST}:${AGENT_PORT}/metrics | grep -c "^sw_method_calls_total")
N_SQL=$(curl -s http://${HOST}:${AGENT_PORT}/metrics | grep -c "^sw_sql_calls_total")
[ "$N_METHOD" -gt 0 ] || { echo "FAIL: 无 sw_method_calls_total"; exit 1; }
[ "$N_SQL" -gt 0 ] || { echo "FAIL: 无 sw_sql_calls_total"; exit 1; }
echo "OK (method=$N_METHOD, sql=$N_SQL)"

echo "=== 4. 验收 3:/api/agent/logs 拉日志 ==="
N_LOG=$(curl -s "http://${HOST}:${AGENT_PORT}/api/agent/logs?since=2020-01-01T00:00:00Z&limit=100" | grep -c '"timestamp"')
[ "$N_LOG" -gt 0 ] || { echo "FAIL: 0 条日志"; exit 1; }
echo "OK ($N_LOG 条)"

echo "=== 5. 验收 4:响应头 X-SW-Log-Cursor ==="
HDR=$(curl -sI "http://${HOST}:${AGENT_PORT}/api/agent/logs?since=2020-01-01T00:00:00Z" | grep -i "X-SW-Log-Cursor")
[ -n "$HDR" ] || { echo "FAIL: 响应头缺少 X-SW-Log-Cursor"; exit 1; }
echo "OK ($HDR)"

echo "=== 6. 验收 5:capabilities ==="
JSON=$(curl -s http://${HOST}:${AGENT_PORT}/api/agent/capabilities)
echo "  $JSON"
echo "$JSON" | grep -q '"nativeJdbc":true' && echo "OK (nativeJdbc=true)" || echo "WARN: nativeJdbc=false(P2 不可用)"

echo "=== 7. 验收 6:健康检查 ==="
curl -fsS http://${HOST}:${AGENT_PORT}/health
echo "OK"

echo "=== 8. 关闭 mock-test ==="
kill $APP_PID 2>/dev/null || true
wait $APP_PID 2>/dev/null || true

echo "=== ALL PASS ==="
echo "完整日志: /tmp/mock-test-agent.log"
