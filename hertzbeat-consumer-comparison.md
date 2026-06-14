# HertzBeat 消费层 vs mock-test 项目 对比报告

## 1. 调研结论

| 项目 | 消费层复杂度 | 消息中间件 | 业务线程池 | 跨节点通信 |
|---|---|---|---|---|
| **HertzBeat** | 生产级,自研多层抽象 | 4 种可插拔后端 (mem/kafka/redis/netty) | 5 个模块各双池 | Netty + Protobuf |
| **mock-test** | **无消费层** | 无 | 仅 Tomcat 默认 | 无 |

**核心结论**:`mock-test` 是一个纯同步 HTTP 请求/响应的 mock 后端,设计上用于测试 `spring-watch` AOP 切面框架,完全没有任何消息队列消费代码。两个项目定位不同,HertzBeat 是分布式监控告警系统,mock-test 是单进程测试目标。

---

## 2. HertzBeat 消费层架构详解

### 2.1 整体架构

HertzBeat **不使用** `@KafkaListener` / `@RocketMQMessageListener` 等注解式消费,而是采用**自研生产者-消费者模型**,基于统一的 `CommonDataQueue` 抽象,支持 4 种可插拔后端,通过 `common.queue.type` 配置切换。

```
[Collector]                                  [Manager]
TimerDispatch (HashedWheelTimer)             ManageServer (Netty Server)
    |                                              |
CommonDispatcher                                  |
    |                                              v
MetricsCollectorQueue                        NettyServerHandler
    |                                              |
WorkerPool.executeJob(MetricsCollect)             v
    |                                       processorTable
NettyDataQueue.sendMetricsData                    |
    |                                       CollectCyclicDataResponseProcessor
v                                              v
NettyDataQueue -> NettyRemotingClient        CommonDataQueue.sendMetricsData
    |                                              |
    +------ TCP + Protobuf + gzip ---------------+
                                                   |
                            +----------------------+----------------------+
                            v                      v                      v
                  MetricsRealTimeAlertCalc  DataStorageDispatch  ServiceDiscoveryWorker
                  (AlerterWorkerPool)       (WarehouseWorkerPool) (ManagerWorkerPool)
```

### 2.2 队列抽象层 (CommonDataQueue)

**接口定义** `hertzbeat-common-core/.../queue/CommonDataQueue.java`:

5 个逻辑通道共享一个队列实例:
- `sendMetricsData` / `pollMetricsDataToAlerter` / `pollMetricsDataToStorage` (指标通道)
- `pollServiceDiscoveryData` / `sendServiceDiscoveryData` (服务发现通道)
- `sendLogEntry*` / `pollLogEntry*` (日志通道)

**4 种后端实现** (在 `hertzbeat-common-spring/.../queue/impl/`):

| 后端 | 实现类 | 底层 | 激活配置 |
|---|---|---|---|
| 内存 (默认) | `InMemoryCommonDataQueue` | `LinkedBlockingQueue` | `common.queue.type=memory` |
| Kafka | `KafkaCommonDataQueue` | `KafkaProducer` / `KafkaConsumer` | `common.queue.type=kafka` |
| Redis | `RedisCommonDataQueue` | Redis list ops | `common.queue.type=redis` |
| Netty | `NettyDataQueue` (collector 端) | 委托给 `CollectJobService` | `common.queue.type=netty` |

**激活方式**:
```java
@Configuration
@ConditionalOnProperty(prefix="common.queue", name="type", havingValue="kafka")
public class KafkaCommonDataQueue implements CommonDataQueue, DisposableBean { ... }
```
`InMemoryCommonDataQueue` 额外加 `@Primary` + `matchIfMissing = true` 作为默认。

### 2.3 Kafka 后端消费模式 (手写 poll)

`KafkaCommonDataQueue.genericPollDataFunction`:
```java
public <T> T genericPollDataFunction(LinkedBlockingQueue<T> dataQueue,
                                     KafkaConsumer<Long, T> dataConsumer,
                                     ReentrantLock lock) throws InterruptedException {
    T pollData = dataQueue.poll();
    if (pollData != null) return pollData;
    lock.lockInterruptibly();
    try {
        ConsumerRecords<Long, T> records = dataConsumer.poll(Duration.ofSeconds(1));
        int index = 0;
        for (ConsumerRecord<Long, T> record : records) {
            if (index == 0) pollData = record.value();
            else dataQueue.offer(record.value());   // 多余数据先缓存到本地队列
            index++;
        }
        dataConsumer.commitAsync();
    } finally { lock.unlock(); }
    return pollData;
}
```

**关键点**:
- 5 个独立的 `KafkaConsumer` 实例,每个订阅一个 topic: `metrics-alert-consumer` / `metrics-persistent-consumer` / `service-discovery-data-consumer` / `log-entry-consumer` / `log-entry-storage-consumer`
- 手动 `commitAsync()`,无注解驱动
- `ReentrantLock` 保证多通道消费互斥

### 2.4 Netty 后端 (跨节点传输)

`NettyDataQueue` (collector 端):
```java
public void sendMetricsData(MetricsData metricsData) {
    collectJobService.sendAsyncCollectData(metricsData);
    // -> 构建 ClusterMsg.Message (RESPONSE_CYCLIC_TASK_DATA)
    // -> NettyRemotingClient.sendMsg 推送到 manager
}
public MetricsData pollMetricsDataToAlerter() {
    return null;  // collector 端 poll 都是 no-op
}
```

**Netty 分发** (`NettyRemotingAbstract.processReceiveMsg`):
```java
protected void processReceiveMsg(ChannelHandlerContext ctx, ClusterMsg.Message message) {
    if (ClusterMsg.Direction.REQUEST.equals(message.getDirection())) {
        this.processRequestMsg(ctx, message);     // -> processor.handle() 在 Netty IO 线程直接处理
    } else {
        this.processResponseMsg(ctx, message);    // -> processor.handle() OR ResponseFuture
    }
}
```

**关键设计**: **没有独立的消费者线程池**,消息在 Netty `EventLoopGroup` IO 线程上直接分发给 `NettyRemotingProcessor` 处理,要求处理器快速移交到队列/工作池。

**注册方式**: `ManageServer.init()` / `CollectServer.init()` 中手动 `registerProcessor(MessageType, NettyRemotingProcessor)`,存入 `ConcurrentHashMap<MessageType, NettyRemotingProcessor> processorTable`。

### 2.5 业务线程池 (每模块双池)

| 模块 | 文件 | 短任务池 | long-running 池 |
|---|---|---|---|
| Collector | `WorkerPool.java` | core=max(2,cpu), max=cpu*16, `SynchronousQueue` | `collect-long-running` |
| Alerter | `AlerterWorkerPool.java` | 10 fixed | `notify-worker`(6) + `log-worker`(10) |
| Warehouse | `WarehouseWorkerPool.java` | 2 / Integer.MAX_VALUE | `warehouse-long-running` |
| Manager | `ManagerWorkerPool.java` | 6/10 | `manager-long-running` |
| Common | `CommonThreadPool.java` | 1/Integer.MAX_VALUE | `common-long-running` |

**统一接口**:
```java
public interface WorkerPool {
    void executeJob(Runnable r);          // 短任务,有界,可能拒绝
    void executeLongRunning(Runnable r);  // 长循环,无限,用于消费循环
}
```

**设计意图**: 长循环消费逻辑走 `executeLongRunning` 通道,避免阻塞短任务的有界准入。

### 2.6 三种消费循环实现

#### a) `MetricsRealTimeAlertCalculator` (构造函数启动)
```java
@Component
public class MetricsRealTimeAlertCalculator {
    public void startCalculate() {
        Runnable runnable = () -> {
            ExponentialBackoff backoff = new ExponentialBackoff(50L, 1000L);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    CollectRep.MetricsData metricsData = dataQueue.pollMetricsDataToAlerter();
                    if (metricsData == null) continue;
                    backoff.reset();
                    calculate(metricsData);                                 // JEXL 告警规则
                    dataQueue.sendMetricsDataToStorage(metricsData);        // 转发到存储
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                  catch (CommonDataQueueUnknownException ue) { ...backoff... }
                  catch (Exception e) { log.error(...); }
            }
        };
        for (int i = 0; i < CALCULATE_THREADS; i++) {     // 3 个消费者
            workerPool.executeJob(runnable);
        }
    }
}
```
构造函数中自动 `startCalculate()`。

#### b) `WindowedLogRealTimeAlertCalculator` (`@PostConstruct` 启动)
```java
@Component
public class WindowedLogRealTimeAlertCalculator implements Runnable {
    @PostConstruct public void start() {
        this.dispatcherExecutor = new ThreadPoolExecutor(CALCULATE_THREADS, ...);
        for (int i = 0; i < CALCULATE_THREADS; i++) dispatcherExecutor.execute(this);
    }
    public void run() {
        while (...) {
            LogEntry e = dataQueue.pollLogEntry();
            logWorker.reduceAndSendLogTask(e);   // 转发到 AlerterWorkerPool.log-worker 通道
        }
    }
}
```

#### c) `ServiceDiscoveryWorker` (`InitializingBean` 启动)
```java
@Component public class ServiceDiscoveryWorker implements InitializingBean {
    @Override public void afterPropertiesSet() {
        workerPool.executeLongRunning(new SdUpdateTask());   // 1 个长循环线程
    }
    private class SdUpdateTask implements Runnable {
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try (CollectRep.MetricsData md = dataQueue.pollServiceDiscoveryData()) {
                    monitorService.addMonitor(...);   // 自动创建监控项
                } catch (...) { ... }
            }
        }
    }
}
```

#### d) `DataStorageDispatch` (构造 + long-running 池)
```java
public DataStorageDispatch(...) { ...; startPersistentDataStorage(); startLogDataStorage(); }

protected void startPersistentDataStorage() {
    Runnable runnable = () -> {
        Thread.currentThread().setName("warehouse-persistent-data-storage");
        while (!Thread.currentThread().isInterrupted()) {
            CollectRep.MetricsData metricsData = commonDataQueue.pollMetricsDataToStorage();
            if (metricsData == null) continue;
            calculateMonitorStatus(metricsData);                                  // 更新 hzb_monitor 状态
            historyDataWriter.ifPresent(dw -> dw.saveData(metricsData));         // TSDB 写入
            pluginRunner.pluginExecute(PostCollectPlugin.class, ...);
            realTimeDataWriter.saveData(metricsData);                            // 内存环形缓冲
        }
    };
    workerPool.executeLongRunning(runnable);   // 1 个 long-running 线程
}
```

### 2.7 线程全景

| 通道 | 位置 | 线程数 | 用途 |
|---|---|---|---|
| Netty IO | `NettyRemotingServer/Client` | `EventLoopGroup` | 编解码 + 分发到 Processor (无线程切换) |
| Common 背景 | `CommonThreadPool` | `common-worker-N` / `common-long-running-N` | Netty start(), 临时任务 |
| Collector 工作 | `WorkerPool` | `collect-worker-N` (cpu*16) / `collect-long-running-N` | MetricsCollect + 调度循环 + 超时监控 |
| Alerter | `AlerterWorkerPool` | `alerter-worker-N`(10) / `notify-worker-N`(6) / `log-worker-N`(10) | 规则计算、通知发送、日志评估 |
| Warehouse | `WarehouseWorkerPool` | `warehouse-worker-N` / `warehouse-long-running-N` | TSDB 写入 + 日志批处理 |
| Manager | `ManagerWorkerPool` | `manager-worker-N`(6/10) / `manager-long-running-N` | SD worker 等 |
| Timer | `TimerDispatcher` | `wheelTimer` (HashedWheelTimer, 1s tick, 512 slots) | Cron/interval 任务调度 |
| Scheduler | `CommonDispatcher` | `metrics-task-dispatcher`(1) + `metrics-task-timeout-monitor-N`(1 scheduled) | 从 `MetricsCollectorQueue` 取任务,超时看门狗 |
| Log 调度 | `WindowedLogRealTimeAlertCalculator` | `log-dispatcher-N`(3) | 轮询日志队列,转发到 LogWorker |
| Heartbeat | `CollectServer` | `heartbeat-worker-N`(1 scheduled + 1 executor) | 心跳到 manager |
| 通道检查 | `ManageServer` | `manager-channel-check-N` | 健康检查 collector 通道 |

**虚拟线程支持**: 所有池都可通过 `VirtualThreadProperties` 切换到 `Executors.newVirtualThreadPerTaskExecutor` 模式,Java 21+ 适用。

### 2.8 关键注解模式总结

| 组件 | 注解模式 |
|---|---|
| 队列实现 | `@Configuration` + `@ConditionalOnProperty` + `DisposableBean`,默认实现加 `@Primary` + `matchIfMissing=true` |
| 线程池 | `@Component` + `DisposableBean` |
| 消费类 | `@Component`,三种启动方式: 构造函数 / `@PostConstruct` / `InitializingBean.afterPropertiesSet()` |
| Netty 服务 | `@Component` + `@ConditionalOnProperty` + `@Order` + `CommandLineRunner`,手动 `new NettyRemotingServer/Client` |
| Processor | 普通 `@Slf4j` 类,**无 `@Component`**,手动 `registerProcessor()` 注册 |

---

## 3. mock-test 项目消费层分析

### 3.1 全局搜索结果

穷尽搜索 16 个 Java 文件,以下模式**全部 0 匹配**:

| 搜索模式 | 结果 |
|---|---|
| `@KafkaListener` | 无 |
| `@RocketMQMessageListener` | 无 |
| `@RabbitListener` / `@JmsListener` / `@StreamListener` | 无 |
| `MessageListener` / `consume` / `Queue` | 无 |
| `ThreadPool` / `Executor` / `ExecutorService` / `taskExecutor` | 无 |
| `@Async` / `@EnableAsync` | 无 |
| `dispatcher` / `workerPool` | 无 |
| `kafka` / `rocketmq` / `rabbitmq` / `activemq` | 无 |
| `org.apache.kafka` / `org.apache.rocketmq` / `org.springframework.amqp` / `org.springframework.jms` 导入 | 无 |

### 3.2 项目定位

`mock-test` 是一个**纯同步 HTTP 请求/响应的 mock 后端**,专门为 `spring-watch` 可观测性框架提供测试目标。

**模块结构**:
```
src/main/java/
├── com/mock/test/
│   ├── MockTestApplication.java         # @SpringBootApplication 入口
│   ├── controller/                      # 纯 REST 控制器 (同步)
│   │   ├── BusinessApiController.java   # /api/users, /api/orders, /api/products, /api/cart
│   │   ├── WebsiteMockController.java   # 主页
│   │   └── AgentLogController.java      # 内存日志缓冲
│   ├── service/                         # @Service 业务逻辑 (同步)
│   │   └── UserService, ProductService, OrderService, CartService, UserStatsService
│   ├── dao/                             # @Repository (H2 内存库)
│   │   └── UserDao, ProductDao, OrderDao
│   ├── metrics/BusinessMetrics.java     # OpenTelemetry counter/histogram
│   ├── logging/                         # 自定义 logback 内存 appender
│   │   └── LogEvent, InMemoryLogBufferAppender
│   └── web/RestApiAccessLogFilter.java  # @Component Servlet 过滤器
└── com/springwatch/                     # spring-watch 框架
    ├── SpringWatch.java                 # @Target(METHOD) 自定义注解
    └── SpringWatchAspect.java           # @Aspect + @Component, OpenTelemetry AOP
```

### 3.3 消息流 (无)

```
HTTP Request
    -> RestApiAccessLogFilter (访问日志)
    -> @RestController (BusinessApiController)
    -> @Service (UserService / OrderService / ...,带 @WithSpan / @SpringWatch AOP)
    -> @Repository (UserDao / OrderDao / ..., H2 内存库)
    -> HTTP Response
```

### 3.4 唯一"异步"点

`BusinessApiController.simulateDelay` 中调用了 `Thread.sleep`,**仅用于测试延迟观测**,不构成消费循环。

### 3.5 线程模型

只有 3 个线程来源:
1. **Tomcat 默认 Servlet 线程池** 处理 HTTP 请求
2. **`@WithSpan` / `@SpringWatch` AOP 切面** 在同一请求线程上记录指标
3. **`Thread.sleep`** 模拟延迟(非独立线程)

`SpringWatchAspect` 核心逻辑:
```java
@Slf4j @Aspect @Component
public class SpringWatchAspect {
    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint pjp, SpringWatch annotation) throws Throwable {
        String name = pjp.getSignature().getDeclaringTypeName() + "." + pjp.getSignature().getName();
        DoubleHistogram hist = HISTS.computeIfAbsent(name, k -> BusinessMetrics.METHOD_LATENCY_MS.get());
        long start = System.nanoTime();
        try { return pjp.proceed(); }
        finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            hist.record(costMs, Attributes.of(METHOD_KEY, name));
        }
    }
}
```

---

## 4. 关键差异对比

| 维度 | HertzBeat | mock-test |
|---|---|---|
| 消费后端 | 4 种可插拔 (mem/kafka/redis/netty) | 无 |
| 线程池 | 5 个模块各双池 (短任务 + long-running) | 仅 Tomcat |
| 消费循环 | 5 个手写 `while(!interrupted) poll()` 循环 | 无 |
| 跨节点 | Netty + Protobuf + gzip,手写 RPC | 无 |
| 启动方式 | 3 种 (构造器 / `@PostConstruct` / `InitializingBean`) | 无 |
| 优雅关闭 | `DisposableBean` 统一实现 | 无 |
| 虚拟线程 | `VirtualThreadProperties` 已支持 | 不需要 |
| 背压处理 | `LinkedBlockingQueue` 有界 + `SynchronousQueue` 短任务 | Tomcat 默认 |
| 重试/退避 | `ExponentialBackoff(50L, 1000L)` | 无 |
| 锁 | `ReentrantLock` 保护 Kafka 消费 | 无 |
| 业务规模 | 分布式监控告警系统 (10+ 子模块) | 单进程 mock 后端 |

---

## 5. 可借鉴的方案 (适合 mock-test 落地)

虽然 mock-test 当前不需要消费层,但如果要测试 `spring-watch` 在高并发消息驱动场景下的表现,有 3 个轻量级方案可借鉴:

### 5.1 HTTP 请求异步化
模仿 `InMemoryCommonDataQueue` 模式:
- `LinkedBlockingQueue<RequestTask>` + `WorkerPool.executeLongRunning` 把同步 HTTP 请求丢队列
- 后台消费线程处理后再 `CompletableFuture` 回调
- 验证 `SpringWatchAspect` 在异步链路下的指标采集是否准确

### 5.2 AOP + 虚拟线程
将 `SpringWatchAspect` 的 `proceed()` 包装到 `Executors.newVirtualThreadPerTaskExecutor()`:
- 模拟 HertzBeat 的 `VirtualThreadProperties` 切换
- 验证高并发下 OpenTelemetry 指标采集的正确性和开销

### 5.3 轻量队列适配器
参考 `CommonDataQueue` 接口 + `InMemoryCommonDataQueue` 实现:
- 写一个 `MockDataQueue` 接口 + `InMemoryMockDataQueue` 实现
- 为后续接入 Kafka/RabbitMQ 留口子,但保持默认内存实现
- 在 `mock-test` 内模拟"下单 -> 支付 -> 物流" 异步链路

---

## 6. 关键文件路径汇总

### HertzBeat

**队列抽象与后端**
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-common-core\src\main\java\org\apache\hertzbeat\common\queue\CommonDataQueue.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-common-core\src\main\java\org\apache\hertzbeat\common\constants\DataQueueConstants.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-common-spring\src\main\java\org\apache\hertzbeat\common\queue\impl\InMemoryCommonDataQueue.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-common-spring\src\main\java\org\apache\hertzbeat\common\queue\impl\KafkaCommonDataQueue.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-common-spring\src\main\java\org\apache\hertzbeat\common\queue\impl\RedisCommonDataQueue.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-collector\hertzbeat-collector-common\src\main\java\org\apache\hertzbeat\collector\dispatch\export\NettyDataQueue.java`

**消费循环**
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-alerter\src\main\java\org\apache\hertzbeat\alert\calculate\realtime\MetricsRealTimeAlertCalculator.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-alerter\src\main\java\org\apache\hertzbeat\alert\calculate\realtime\WindowedLogRealTimeAlertCalculator.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-alerter\src\main\java\org\apache\hertzbeat\alert\calculate\realtime\window\LogWorker.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-warehouse\src\main\java\org\apache\hertzbeat\warehouse\store\DataStorageDispatch.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-manager\src\main\java\org\apache\hertzbeat\manager\component\sd\ServiceDiscoveryWorker.java`

**线程池**
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-collector\hertzbeat-collector-common\src\main\java\org\apache\hertzbeat\collector\dispatch\WorkerPool.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-alerter\src\main\java\org\apache\hertzbeat\alert\AlerterWorkerPool.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-warehouse\src\main\java\org\apache\hertzbeat\warehouse\WarehouseWorkerPool.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-manager\src\main\java\org\apache\hertzbeat\manager\scheduler\ManagerWorkerPool.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-common-spring\src\main\java\org\apache\hertzbeat\common\support\CommonThreadPool.java`

**Netty 远程通信**
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-remoting\src\main\java\org\apache\hertzbeat\remoting\netty\NettyRemotingAbstract.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-remoting\src\main\java\org\apache\hertzbeat\remoting\netty\NettyRemotingServer.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-remoting\src\main\java\org\apache\hertzbeat\remoting\netty\NettyRemotingClient.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-remoting\src\main\java\org\apache\hertzbeat\remoting\netty\NettyRemotingProcessor.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-manager\src\main\java\org\apache\hertzbeat\manager\scheduler\netty\ManageServer.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-collector\hertzbeat-collector-common\src\main\java\org\apache\hertzbeat\collector\dispatch\entrance\CollectServer.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-collector\hertzbeat-collector-common\src\main\java\org\apache\hertzbeat\collector\dispatch\entrance\internal\CollectJobService.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-manager\src\main\java\org\apache\hertzbeat\manager\scheduler\netty\process\CollectCyclicDataResponseProcessor.java`

**Collector 内部调度**
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-collector\hertzbeat-collector-common\src\main\java\org\apache\hertzbeat\collector\timer\TimerDispatcher.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-collector\hertzbeat-collector-collector\src\main\java\org\apache\hertzbeat\collector\dispatch\CommonDispatcher.java`
- `D:\codespace\ideaProject\hertzbeat\hertzbeat-collector\hertzbeat-collector-collector\src\main\java\org\apache\hertzbeat\collector\dispatch\MetricsCollectorQueue.java`

### mock-test

**入口与配置**
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\mock\test\MockTestApplication.java`
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\resources\application.yml`

**Web 层**
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\mock\test\controller\BusinessApiController.java`
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\mock\test\controller\WebsiteMockController.java`
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\mock\test\controller\AgentLogController.java`
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\mock\test\web\RestApiAccessLogFilter.java`

**spring-watch 框架**
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\springwatch\SpringWatch.java`
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\springwatch\SpringWatchAspect.java`
- `D:\codespace\ideaProject\spring-watch\mock-test\src\main\java\com\mock\test\metrics\BusinessMetrics.java`
