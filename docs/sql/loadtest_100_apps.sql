
DO $$
DECLARE
    v_total INT := 1000;
    v_hosts INT := 10;
    v_base_appid BIGINT := 700000000000000;  -- appid 起始,避免和真实 appid 冲突
BEGIN
    DELETE FROM monitor_app WHERE app_name LIKE 'loadtest_%';
    RAISE NOTICE '[kxj: 崩溃测试 - 清空旧 loadtest 应用]';

    INSERT INTO monitor_app (
        appid, app_name, endpoint, metrics_port, app_type,
        scrape_interval, schedule_type,
        status, last_heartbeat, created_at, updated_at, last_log_pull_time
    )
    SELECT
        v_base_appid + gs                                            AS appid,
        'loadtest_' || gs::text                        AS app_name,
        'http://localhost:8081'                                  AS endpoint,
        9464                                                         AS metrics_port,
        'springboot'                                                 AS app_type,
        30 AS scrape_interval,
        'INTERVAL'        AS schedule_type,
        'active'                                                     AS status,
        NOW()                                                        AS last_heartbeat,
        NOW()                                                        AS created_at,
        NOW()                                                        AS updated_at,
        NOW() - INTERVAL '1 hour'                                    AS last_log_pull_time
    FROM generate_series(1, v_total) AS gs;

    RAISE NOTICE '[kxj: 崩溃测试 - 插入 % 个 loadtest 应用, host 数=%, 周期=5/10/15/30/60s 混合, 比例=95%% INTERVAL + 5%% CRON]',
        v_total, v_hosts;
END $$;
