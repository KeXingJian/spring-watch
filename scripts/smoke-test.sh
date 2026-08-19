#!/bin/bash
# smoke-test.sh - 5 秒烟测,挂载完成后快速验证 Agent 存活与基础接口
# 用法: ./smoke-test.sh [HOST] [PORT]
set -e

HOST=${1:-localhost}
PORT=${2:-9464}

echo "=== 1. 存活(/health) ==="
curl -fsS http://${HOST}:${PORT}/health && echo " OK" || { echo "FAIL"; exit 1; }

echo "=== 2. /metrics 含 sw_* 指标 ==="
N=$(curl -s http://${HOST}:${PORT}/metrics | grep -c "^sw_" || true)
[ "$N" -gt 5 ] || { echo "FAIL: sw_* 指标仅 $N 行,期望 >5"; exit 1; }
echo "OK ($N 行 sw_*)"

echo "=== 3. /api/agent/capabilities ==="
JSON=$(curl -fsS http://${HOST}:${PORT}/api/agent/capabilities)
echo "  $JSON"
echo "$JSON" | grep -q '"version"' || { echo "FAIL: 缺少 version 字段"; exit 1; }
echo "OK"

echo "=== 4. /api/agent/logs since ISO ==="
N_LOG=$(curl -s "http://${HOST}:${PORT}/api/agent/logs?since=2020-01-01T00:00:00Z" | grep -c '"timestamp"' || true)
echo "接受: $N_LOG 条日志"
[ "$N_LOG" -gt 0 ] || { echo "WARN: 0 条日志(可能 mock-test 还没产生日志)"; }

echo "=== 5. 响应头 check ==="
curl -sI "http://${HOST}:${PORT}/api/agent/logs?since=2020-01-01T00:00:00Z" 2>/dev/null | grep -i "X-SW-Log-Cursor\|X-SW-Log-Dropped" || {
    echo "FAIL: 响应头缺少 X-SW-Log-Cursor 或 X-SW-Log-Dropped"; exit 1;
}

echo "=== 6. 鉴权(/api/agent/logs 配 token 时) ==="
if [ -n "$SW_TOKEN" ]; then
    H=$(curl -s -o /dev/null -w "%{http_code}" "http://${HOST}:${PORT}/api/agent/logs?since=2020-01-01T00:00:00Z")
    [ "$H" = "401" ] || { echo "FAIL: 无 token 应 401,实际 $H"; exit 1; }
    H=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $SW_TOKEN" "http://${HOST}:${PORT}/api/agent/logs?since=2020-01-01T00:00:00Z")
    [ "$H" = "200" ] || { echo "FAIL: 有 token 应 200,实际 $H"; exit 1; }
    echo "OK (401 未授权 / 200 已授权)"
else
    echo "SKIP (未配 SW_TOKEN 环境变量)"
fi

echo "=== 7. /metrics 含 # HELP / # TYPE ==="
N_HELP=$(curl -s http://${HOST}:${PORT}/metrics | grep -c "^# HELP")
N_TYPE=$(curl -s http://${HOST}:${PORT}/metrics | grep -c "^# TYPE")
[ "$N_HELP" -gt 5 ] || { echo "FAIL: # HELP 仅 $N_HELP 行"; exit 1; }
[ "$N_TYPE" -gt 5 ] || { echo "FAIL: # TYPE 仅 $N_TYPE 行"; exit 1; }
echo "OK (# HELP=$N_HELP, # TYPE=$N_TYPE)"

echo "=== 8. 限流(快速打 100 次 /metrics) ==="
START=$(date +%s%N)
for i in {1..100}; do
    curl -s http://${HOST}:${PORT}/metrics > /dev/null
done
END=$(date +%s%N)
DUR_MS=$(( (END - START) / 1000000 ))
echo "OK (100 次 /metrics 耗时 ${DUR_MS}ms)"

echo "=== ALL PASS ==="
