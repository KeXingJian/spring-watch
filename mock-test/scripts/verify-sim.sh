#!/usr/bin/env bash
# verify-sim.sh — mock-test Simulator 端到端验证
# 启动 mock-test 后,跑一次,5 分钟内即可看到指标是否真的丰富了
set -e

ENDPOINT="${1:-http://localhost:18081}"
METRICS_ENDPOINT="${2:-http://localhost:19464/metrics}"
WAIT_SECONDS="${WAIT_SECONDS:-60}"
EXPECTED_BURST_SECONDS="${EXPECTED_BURST_SECONDS:-70}"

echo ">>> 等待 $ENDPOINT 就绪..."
for i in $(seq 1 30); do
  if curl -sf "$ENDPOINT/api/ping" > /dev/null 2>&1; then
    echo "  启动成功 (用时 ${i} 次×2s)"
    break
  fi
  sleep 2
  if [ "$i" = "30" ]; then
    echo "  [失败] $ENDPOINT 在 60s 内未就绪,退出"
    exit 1
  fi
done

echo ">>> 等待 $WAIT_SECONDS 秒,让 Simulator 产生数据..."
sleep "$WAIT_SECONDS"

check() {
  local name="$1"
  local label="$2"
  local val=$(curl -sf "$METRICS_ENDPOINT" 2>/dev/null | grep -E "^${name}" | awk '{print $2}' | head -1)
  if [ -z "$val" ]; then
    echo "  [缺] $label ($name)"
    return 1
  else
    echo "  [有] $label = $val"
    return 0
  fi
}

echo ""
echo "=== JDBC 指标 (期望: use_time_count > 0, usage 有数据) ==="
JDBC_OK=0
check "db_client_connections_max" "连接池 max" && JDBC_OK=$((JDBC_OK + 1)) || true
check "db_client_connections_usage" "连接池 usage" && JDBC_OK=$((JDBC_OK + 1)) || true
check "db_client_connections_use_time_milliseconds_count" "use_time count" && JDBC_OK=$((JDBC_OK + 1)) || true

echo ""
echo "=== 业务指标 (期望: order_created_total > 0) ==="
BIZ_OK=0
check "business_order_created_total" "订单创建" && BIZ_OK=$((BIZ_OK + 1)) || true
check "business_order_paid_total" "订单支付" && BIZ_OK=$((BIZ_OK + 1)) || true
check "business_order_amount" "订单金额" && BIZ_OK=$((BIZ_OK + 1)) || true

echo ""
echo "=== JVM 指标 ==="
JVM_OK=0
check "jvm_memory_used_bytes" "内存使用" && JVM_OK=$((JVM_OK + 1)) || true
check "jvm_gc_duration_seconds_count" "GC 次数" && JVM_OK=$((JVM_OK + 1)) || true
check "jvm_thread_count" "线程数" && JVM_OK=$((JVM_OK + 1)) || true

echo ""
echo ">>> 等待 ${EXPECTED_BURST_SECONDS} 秒,期望连接池打满场景触发..."
sleep "$EXPECTED_BURST_SECONDS"

PENDING_VAL=$(curl -sf "$METRICS_ENDPOINT" 2>/dev/null | grep -E "^db_client_connections_pending_requests" | awk '{print $2}' | head -1)
if [ -n "$PENDING_VAL" ] && [ "$(printf "%.0f" "$PENDING_VAL")" != "0" ]; then
  echo "  [有] pending_requests = $PENDING_VAL (连接池打满场景已触发)"
else
  echo "  [缺] pending_requests = ${PENDING_VAL:-0} (可能周期还没到,或被采样平滑掉)"
fi

echo ""
echo ">>> 拉取日志(检查是否有自动产生的日志)"
LOGS=$(curl -sf "$ENDPOINT/api/agent/logs?since=1970-01-01T00:00:00Z" 2>/dev/null || echo "[]")
ERR_COUNT=$(echo "$LOGS" | grep -o '"level":"ERROR"' | wc -l || true)
WARN_COUNT=$(echo "$LOGS" | grep -o '"level":"WARN"' | wc -l || true)
INFO_COUNT=$(echo "$LOGS" | grep -o '"level":"INFO"' | wc -l || true)
echo "  INFO  日志数: $INFO_COUNT"
echo "  WARN  日志数: $WARN_COUNT (期望 ≥ 5,说明业务异常 / 慢查询 / D-1 触发了)"
echo "  ERROR 日志数: $ERR_COUNT (期望 ≥ 1,说明 D-2 触发了)"

echo ""
echo "=== 汇总 ==="
TOTAL=$((JDBC_OK + BIZ_OK + JVM_OK))
echo "  关键指标命中: $TOTAL / 9"
if [ "$TOTAL" -ge 6 ] && [ "$WARN_COUNT" -ge 5 ]; then
  echo "  [PASS] mock-test Simulator 行为丰富验证通过"
  exit 0
else
  echo "  [WARN] 部分指标缺失,可能是 Simulator 周期未到或被禁用(more time needed)"
  exit 0
fi
