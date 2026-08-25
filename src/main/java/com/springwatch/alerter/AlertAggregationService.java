package com.springwatch.alerter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.springwatch.model.entity.AlertRule;
import com.springwatch.model.event.MetricEvent;
import com.springwatch.util.SnowFlakeIdGenerator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P1 告警收敛 - 相似告警聚类抑制(告警风暴治理)。
 *
 * 思路:按 应用(appid) + 规则类型(ruleType) + 指标维度(metric / 日志指纹) 生成相似度指纹。
 * 同一相似指纹在"静默窗口"(默认5min)内的 FIRING 事件聚为同一收敛组:
 *   - 首报(leader):立即通知 + 发布诊断事件(首报即达)
 *   - 后续同类(member):静默抑制,仅累加计数(同类静默)
 * 风暴摘要由调度任务周期性推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertAggregationService {

    /** 收敛组缓存 key:相似度指纹 */
    private Cache<String, AggGroup> groupCache;

    @Value("${spring-watch.alert.aggregation.enabled:true}")
    private boolean aggregationEnabled;

    @Value("${spring-watch.alert.aggregation.silence-minutes:5}")
    private long silenceMinutes;

    @Value("${spring-watch.alert.aggregation.min-storm-count:3}")
    private int minStormCount;

    @PostConstruct
    void init() {
        this.groupCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(silenceMinutes))
                .maximumSize(20000)
                .build();
        log.info("[kxj: 告警收敛服务启动 - enabled={}, silence={}min, minStormCount={}]",
                aggregationEnabled, silenceMinutes, minStormCount);
    }

    /**
     * 相似度指纹:应用 + 规则类型 + 指标维度。
     * 日志类规则(合成指标)取 fingerprint tag 作为维度,更精确。
     */
    public String similarityKey(AlertRule rule, MetricEvent event) {
        Long appid = event.getAppid();
        String ruleType = rule != null ? rule.getRuleType() : "unknown";
        String dimension;
        if (event.getTags() != null && event.getTags().containsKey("fingerprint")) {
            dimension = event.getTags().get("fingerprint");
        } else {
            dimension = event.getMetricName() != null ? event.getMetricName() : ruleType;
        }
        return appid + "|" + ruleType + "|" + dimension;
    }

    /**
     * 对一次 FIRING 做收敛决策。
     * 返回决策:该告警属于哪个收敛组,是否作为首报(应通知),收敛组当前累计次数。
     */
    public Decision decide(AlertRule rule, MetricEvent event) {
        if (!aggregationEnabled) {
            return Decision.ofNewLeader(generateGroupId());
        }
        String key = similarityKey(rule, event);
        AggGroup[] holder = new AggGroup[1];
        groupCache.asMap().compute(key, (k, existing) -> {
            AggGroup group = existing;
            if (group == null) {
                group = new AggGroup(generateGroupId(), Instant.now(), new AtomicInteger(0));
            }
            group.count().incrementAndGet();
            holder[0] = group;
            return group;
        });
        AggGroup group = holder[0];
        boolean leader = group.count().get() == 1;
        log.debug("[kxj: 告警收敛决策 - key={}, groupId={}, count={}, leader={}]",
                key, group.groupId(), group.count().get(), leader);
        return new Decision(group.groupId(), leader, group.count().get());
    }

    /** 当前所有活跃收敛组(风暴摘要用),仅统计被抑制数 >= minStormCount 的组 */
    public List<StormGroup> activeStormGroups() {
        List<StormGroup> out = new ArrayList<>();
        for (Map.Entry<String, AggGroup> e : groupCache.asMap().entrySet()) {
            AggGroup g = e.getValue();
            int suppressed = g.count().get() - 1;
            if (suppressed < minStormCount) {
                continue;
            }
            out.add(new StormGroup(g.groupId(), e.getKey(), g.firstAt(), g.count().get(), suppressed));
        }
        return out;
    }

    private String generateGroupId() {
        return Long.toString(SnowFlakeIdGenerator.generateId());
    }

    /** 决策结果 */
    public record Decision(String groupId, boolean leader, int groupCount) {
        static Decision ofNewLeader(String groupId) {
            return new Decision(groupId, true, 1);
        }
    }

    /** 风暴摘要组 */
    public record StormGroup(String groupId, String similarityKey, Instant firstAt,
                             int groupCount, int suppressedCount) {
    }

    private record AggGroup(String groupId, Instant firstAt, AtomicInteger count) {
    }
}
