INSERT INTO alert_rule
(appId, rule_name, rule_type, expression, duration_seconds, status, created_at)
VALUES
     (650794751521025, 'TEST_ORDER_CALL_COUNT_HIGH', 'metric',
     'metric == ''http_server_request_duration_seconds_count'' && http_route == ''/api/orders'' && value > 10',
     0, 'enabled', NOW());