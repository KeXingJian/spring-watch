package com.springwatch.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 告警规则表 - alert_rule
 */
@Entity
@Table(name = "alert_rule")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule {

    /** 主键 ID,数据库自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的监控应用
     * DB 列 appid → monitor_app.appid(雪花 ID,53 bit)
     * 必填,告警评估按 app.getAppid() 查规则集
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appid", referencedColumnName = "appid")
    private MonitorApp app;

    /** 规则名,告警列表 / 邮件主题 / 日志中展示 */
    @Column(length = 256)
    private String ruleName;

    /**
     * 规则类型 - 决定评估路径
     *   metric          : 指标类,JEXL 表达式评估(value / metric / tags 平铺到 ctx)
     *   log_error_rate  : 日志错误率,用 thresholdValue 阈值(走 LogAlertScheduler 定时任务)
     *   log_keyword     : 日志关键字,单点触发
     *   log_new_pattern : 日志新模式,单点触发
     */
    @Column(length = 32)
    private String ruleType;

    /**
     * JEXL 表达式 - 仅 metric 类型用
     * 例: metric == 'jvm_memory_used_bytes' && value > 800000000
     * 长度 512 足够,复杂表达式建议拆分多规则
     */
    @Column(length = 512)
    private String expression;

    /**
     * 阈值 - 仅 log_error_rate 类型用
     * 含义:错误率百分比(0~100),value > thresholdValue 即触发
     * metric / log_keyword / log_new_pattern 不用此字段
     */
    private Double thresholdValue;

    /**
     * PENDING 状态持续多少秒才升级到 FIRING
     * 0 = 立即升级(无防抖),默认 60
     * metric / log_error_rate 走 IDLE→PENDING→FIRING 路径会用到
     */
    @Builder.Default
    private Integer durationSeconds = 60;

    /**
     * 通知渠道 JSON,例: {"email":"a@x.com,b@y.com"}
     * 为空时回退到 alert_notification_config 表(按 appid 查)
     * JSON 解析失败时不静默回退,记 WARN 并跳过通知
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String notifyChannels;

    /**
     * 启用状态: enabled / disabled
     * AlertRuleCache.refresh() 只加载 enabled,disabled 规则不参与评估
     */
    @Column(length = 16)
    @Builder.Default
    private String status = "enabled";

    /**
     * 告警级别 - 标注告警信息的严重程度
     * 取值: INFO / WARNING / CRITICAL
     * 用途: AlertNotifier.buildSubject / buildBody / buildBody 中作为前缀
     *   例: [WARNING] 告警触发 / [RESOLVED][WARNING] 告警恢复 / [CRITICAL] 告警触发
     * 空或 null 时 AlertEngine.determineLevel 回退到 "warning"
     */
    @Column(length = 16)
    @Builder.Default
    private String level = "warning";

    /**
     * PENDING 状态下需要连续命中多少次才升级到 FIRING
     * 1 = 单次命中立即升级,默认 1
     * 仅在 durationSeconds > 0 且 times > 1 时真正参与累计
     */
    @Column
    @Builder.Default
    private Integer times = 1;

    /**
     * 自定义通知模板,支持占位符:
     *   {{metric}} {{value}} {{threshold}} {{rule}} {{app}} {{appid}} {{time}} {{level}} {{expression}}
     * 为空时使用 AlertNotifier.defaultBody
     */
    @Column(length = 1024)
    private String template;

    /** 创建时间,DB 默认 NOW(),不允许更新 */
    @Column(updatable = false)
    private Instant createdAt;
}
