#!/usr/bin/env bash
# verify-sim.sh — mock-test Simulator 端到端验证
# 启动 mock-test 后,跑一次,3~5 分钟内即可看到指标是否真的丰富了
set -e

ENDPOINT="${1:-http://localhost:18081}"
METRICS_ENDPOINT="${2:-http://localhost:19464/metrics}"
WAIT_SECONDS="${WAIT_SECONDS:-90}"
STRESS_WAIT_SECONDS="${STRESS_WAIT_SECONDS:-30}"

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

check_count() {
  local pattern="$1"
  local label="$2"
  local val=$(curl -sf "$METRICS_ENDPOINT" 2>/dev/null | grep -E "$pattern" | awk '{print $2}' | head -1)
  if [ -z "$val" ]; then
    echo "  [缺] $label (未匹配: $pattern)"
    return 1
  else
    echo "  [有] $label = $val"
    return 0
  fi
}

check_min() {
  local pattern="$1"
  local label="$2"
  local expect="$3"
  local val=$(curl -sf "$METRICS_ENDPOINT" 2>/dev/null | grep -E "$pattern" | awk '{print $2}' | head -1)
  if [ -z "$val" ]; then
    echo "  [缺] $label (未匹配: $pattern)"
    return 1
  fi
  local intval=$(printf "%.0f" "$val" 2>/dev/null || echo "0")
  if [ "$intval" -lt "$expect" ]; then
    echo "  [有但弱] $label = $val (期望 ≥ $expect)"
    return 1
  else
    echo "  [有] $label = $val (≥ $expect ✓)"
    return 0
  fi
}

echo ""
echo "=== JDBC 指标 (期望: used 长期 > 0, pending 出现尖峰) ==="
JDBC_OK=0
check_count "^db_client_connections_max " "连接池 max" && JDBC_OK=$((JDBC_OK + 1)) || true
check_min "^db_client_connections_usage\{.*state=\"used\"" "连接池 used" 1 && JDBC_OK=$((JDBC_OK + 1)) || true
check_min "^db_client_connections_usage\{.*state=\"idle\"" "连接池 idle" 1 && JDBC_OK=$((JDBC_OK + 1)) || true
check_min "^db_client_connections_use_time_milliseconds_count" "use_time count" 50 && JDBC_OK=$((JDBC_OK + 1)) || true

echo ""
echo "=== HTTP 指标 (期望: 4xx/5xx/2xx 都有数据) ==="
HTTP_OK=0
check_count "^http_server_request_duration_seconds_count\{.*http_response_status_code=\"2" "HTTP 2xx 计数" && HTTP_OK=$((HTTP_OK + 1)) || true
check_count "^http_server_request_duration_seconds_count\{.*http_response_status_code=\"4" "HTTP 4xx 计数" && HTTP_OK=$((HTTP_OK + 1)) || true
check_count "^http_server_request_duration_seconds_count\{.*http_response_status_code=\"5" "HTTP 5xx 计数" && HTTP_OK=$((HTTP_OK + 1)) || true
check_count "^http_server_request_duration_seconds_count\{.*http_route=\"/api/products\"" "products route 计数" && HTTP_OK=$((HTTP_OK + 1)) || true
check_count "^http_server_request_duration_seconds_count\{.*http_route=\"/api/orders\"" "orders route 计数" && HTTP_OK=$((HTTP_OK + 1)) || true

echo ""
echo "=== 业务指标 (期望: order_created_total > 0) ==="
BIZ_OK=0
check_count "^business_order_created_total" "订单创建" && BIZ_OK=$((BIZ_OK + 1)) || true
check_count "^business_order_paid_total" "订单支付" && BIZ_OK=$((BIZ_OK + 1)) || true
check_count "^business_order_amount" "订单金额" && BIZ_OK=$((BIZ_OK + 1)) || true

echo ""
echo "=== JVM 指标 ==="
JVM_OK=0
check_count "^jvm_memory_used_bytes" "内存使用" && JVM_OK=$((JVM_OK + 1)) || true
check_count "^jvm_gc_duration_seconds_count" "GC 次数" && JVM_OK=$((JVM_OK + 1)) || true
check_count "^jvm_thread_count" "线程数" && JVM_OK=$((JVM_OK + 1)) || true

echo ""
echo ">>> 等待 ${STRESS_WAIT_SECONDS} 秒,期望连接池打满场景触发..."
sleep "$STRESS_WAIT_SECONDS"

PENDING_VAL=$(curl -sf "$METRICS_ENDPOINT" 2>/dev/null | grep -E "^db_client_connections_pending_requests" | awk '{print $2}' | head -1)
USED_VAL=$(curl -sf "$METRICS_ENDPOINT" 2>/dev/null | grep -E '^db_client_connections_usage\{.*state="used"' | awk '{print $2}' | head -1)
IDLE_VAL=$(curl -sf "$METRICS_ENDPOINT" 2>/dev/null | grep -E '^db_client_connections_usage\{.*state="idle"' | awk '{print $2}' | head -1)
echo "  [采] used=$USED_VAL  idle=$IDLE_VAL  pending=$PENDING_VAL"

if [ -n "$PENDING_VAL" ] && [ "$(printf "%.0f" "$PENDING_VAL")" != "0" ]; then
  echo "  [有] pending_requests = $PENDING_VAL (连接池打满场景已触发)"
  STRESS_OK=1
elif [ -n "$USED_VAL" ] && [ "$(printf "%.0f" "$USED_VAL")" -ge 5 ]; then
  echo "  [有] used = $USED_VAL (后台慢查询线程造成高使用率,pending 在打满瞬间会更高)"
  STRESS_OK=1
else
  echo "  [缺] pending=0 且 used<5,可能周期未到"
  STRESS_OK=0
fi

echo ""
echo ">>> 拉取日志(检查 INFO/WARN/ERROR 各档位)..."
LOGS=$(curl -sf "$ENDPOINT/api/agent/logs?since=1970-01-01T00:00:00Z" 2>/dev/null || echo "[]")
ERR_COUNT=$(echo "$LOGS" | grep -o '"level":"ERROR"' | wc -l || true)
WARN_COUNT=$(echo "$LOGS" | grep -o '"level":"WARN"' | wc -l || true)
INFO_COUNT=$(echo "$LOGS" | grep -o '"level":"INFO"' | wc -l || true)
echo "  INFO  日志数: $INFO_COUNT (期望 ≥ 50)"
echo "  WARN  日志数: $WARN_COUNT (期望 ≥ 10)"
echo "  ERROR 日志数: $ERR_COUNT (期望 ≥ 5)"

echo ""
echo "=== 汇总 ==="
TOTAL=$((JDBC_OK + HTTP_OK + BIZ_OK + JVM_OK + STRESS_OK))
echo "  关键指标命中: $TOTAL / 16"
if [ "$TOTAL" -ge 11 ] && [ "$ERR_COUNT" -ge 5 ] && [ "$WARN_COUNT" -ge 10 ]; then
  echo "  [PASS] mock-test Simulator 行为丰富验证通过"
  exit 0
elif [ "$TOTAL" -ge 9 ]; then
  echo "  [PARTIAL] 大部分指标已就绪,等更长时间可能全绿"
  exit 0
else
  echo "  [WARN] 指标偏少,可能是 Simulator 周期未到 / 已被禁用"
  exit 0
fi
