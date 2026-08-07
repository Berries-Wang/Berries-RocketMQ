# RocketMQ DefaultMQPushConsumer 代码深度分析报告

> 分析日期: 2026-08-06
> 源码版本: RocketMQ 5.3.1 Release
> 分析人: GLM-5.2

---

## 目录

1. [DefaultMQPushConsumer 启动流程](#1-defaultmqpushconsumer-启动流程)
2. [集群模式消息拉取与队列分配机制](#2-集群模式消息拉取与队列分配机制)
3. [messageRequestQueue 维护机制](#25-pullmessageservicerun-中-messagerequestqueue-的维护机制)
4. [如何查看消费者订阅的队列](#26-如何查看当前消费者订阅了哪些队列)
5. [重平衡策略详解](#27-重平衡策略详解)
6. [缓存和流控参数对消费者端及服务端行为的影响](#3-缓存和流控参数对消费者端及服务端行为的影响)
7. [消费者端积压不均排查指南](#4-消费者端积压不均排查指南)
8. [总结与架构图](#5-总结与架构图)

---

## 1. DefaultMQPushConsumer 启动流程

### 1.1 类体系

```
DefaultMQPushConsumer (用户入口类, 封装配置)
    └── 委托 → DefaultMQPushConsumerImpl (核心实现)
                    ├── RebalanceImpl / RebalancePushImpl (重平衡)
                    ├── PullMessageService (拉取服务线程)
                    ├── PullAPIWrapper (拉取API包装)
                    ├── ConsumeMessageService (消费服务)
                    ├── OffsetStore (偏移量存储)
                    └── MQClientInstance (客户端实例)
```

### 1.2 启动时序

```
DefaultMQPushConsumer.start()
    │
    ├── 1. NamespaceUtil.wrapNamespace() 包装consumerGroup
    │
    └── defaultMQPushConsumerImpl.start()
            │
            ├── 2. 状态检查: serviceState = CREATE_JUST → START_FAILED(保护态)
            │
            ├── 3. checkConfig() 配置校验
            │   ├── consumerGroup 非空, 非默认值(DEFAULT_CONSUMER)
            │   ├── messageModel 非空
            │   ├── consumeFromWhere 非空
            │   ├── allocateMessageQueueStrategy 非空 (默认: AllocateMessageQueueAveragely)
            │   ├── subscription 非空 (至少订阅一个Topic)
            │   ├── messageListener 非空 (必须是Orderly或Concurrently)
            │   ├── 各线程数/阈值参数范围合法
            │   └── ...
            │
            ├── 4. copySubscription() 拷贝订阅关系
            │   ├── 将用户订阅的 topic → subExpression 拷贝到 rebalanceImpl
            │   └── CLUSTERING 模式: 自动添加 %RETRY%{consumerGroup} 重试主题
            │
            ├── 5. 实例名处理: CLUSTERING → changeInstanceNameToPID()
            │
            ├── 6. 获取/创建 MQClientInstance
            │   └── MQClientManager.getInstance().getOrCreateMQClientInstance()
            │
            ├── 7. 初始化 RebalanceImpl
            │   ├── set consumerGroup, messageModel
            │   ├── set allocateMessageQueueStrategy
            │   └── set mQClientFactory
            │
            ├── 8. 初始化 PullAPIWrapper
            │   └── 注册 filterMessageHookList
            │
            ├── 9. 初始化 OffsetStore
            │   ├── 用户自定义 → 使用自定义
            │   ├── BROADCASTING → LocalFileOffsetStore
            │   └── CLUSTERING → RemoteBrokerOffsetStore
            │       └── offsetStore.load()
            │
            ├── 10. 初始化 ConsumeMessageService
            │   ├── MessageListenerConcurrently → ConsumeMessageConcurrentlyService
            │   ├── MessageListenerOrderly → ConsumeMessageOrderlyService
            │   └── consumeMessageService.start() 启动消费线程池
            │
            ├── 11. 注册消费者到 MQClientInstance
            │   └── 检查 consumerGroup 是否重复
            │
            ├── 12. mQClientFactory.start() 启动客户端工厂
            │   ├── 启动网络通信 (Netty)
            │   ├── 启动定时任务 (定时更新路由、发送心跳)
            │   └── 启动 PullMessageService 线程
            │
            ├── 13. serviceState = RUNNING
            │
            ├── 14. updateTopicSubscribeInfoWhenSubscriptionChanged()
            │   └── 从NameServer获取Topic路由信息
            │
            ├── 15. mQClientFactory.checkClientInBroker()
            │
            └── 16. sendHeartbeatToAllBrokerWithLock() 成功 → rebalanceImmediately()
                    └── 触发首次重平衡
```

### 1.3 关键设计要点

- **状态机保护**: `CREATE_JUST → START_FAILED(保护) → RUNNING → SHUTDOWN_ALREADY`
- **双重消费服务**: Pull模式下同时初始化 `consumeMessageService` 和 `consumeMessagePopService`，分别对应传统 Pull 和 Pop 两种消费模式
- **启动即触发重平衡**: `start()` 最后一步调用 `rebalanceImmediately()`，确保消费者启动后立即分配队列

---

## 2. 集群模式消息拉取与队列分配机制

### 2.1 队列与消费者的映射关系

#### 核心原则: 每个队列在同一消费组内只能被一个消费者消费

在 **CLUSTERING（集群）** 模式下，RocketMQ 通过 **重平衡（Rebalance）** 机制确保：
- **每个队列在同一消费组内，最多被一个消费者实例消费**
- **一个消费者可以消费多个队列**
- **不会出现多个消费者消费同一个队列的情况**（除非是广播模式）

#### 分配算法: 默认平均分配（AllocateMessageQueueAveragely）

源码: `AllocateMessageQueueAveragely.allocate()`

```
输入: consumerGroup, currentCID, mqAll(所有队列), cidAll(所有消费者ID)
算法:
    index = cidAll.indexOf(currentCID)         // 当前消费者在列表中的位置
    mod = mqAll.size() % cidAll.size()          // 余数
    // 前 mod 个消费者多分一个队列
    averageSize = (mod > 0 && index < mod) ? (mqAll.size() / cidAll.size() + 1) : (mqAll.size() / cidAll.size())
    startIndex = (mod > 0 && index < mod) ? index * averageSize : index * averageSize + mod
    range = min(averageSize, mqAll.size() - startIndex)
```

**示例：8个队列(Queue 0-7)，3个消费者(C1, C2, C3)**

| 消费者 | 平均分配(AVG) | 轮询分配(AVG_BY_CIRCLE) |
|--------|--------------|------------------------|
| C1 | [0,1,2] | [0,3,6] |
| C2 | [3,4,5] | [1,4,7] |
| C3 | [6,7] | [2,5] |

**结论：队列数 > 消费者数时**，每个消费者至少分到 `ceil(N/M)` 或 `floor(N/M)` 个队列，前几个消费者可能会多分一个。

#### 集群模式下的互斥保证

```
RebalanceImpl.rebalanceByTopic()  [CLUSTERING 分支]
    │
    ├── 1. 获取 mqSet: topicSubscribeInfoTable.get(topic)
    ├── 2. 获取 cidAll: mQClientFactory.findConsumerIdList(topic, consumerGroup)
    ├── 3. 排序: Collections.sort(mqAll), Collections.sort(cidAll)
    ├── 4. 分配: strategy.allocate(consumerGroup, clientId, mqAll, cidAll)
    │       └── 每个消费者得到不同的队列子集
    ├── 5. 更新: updateProcessQueueTableInRebalance(topic, allocateResultSet, isOrder)
    └── 6. 分发: dispatchPullRequest(pullRequestList, 500ms延迟)
```

### 2.2 消息拉取机制

#### PullRequest 数据结构

```java
public class PullRequest {
    private String consumerGroup;    // 消费组
    private MessageQueue messageQueue;  // 绑定的消息队列 (Topic + Broker + QueueId)
    private ProcessQueue processQueue;  // 该队列的处理快照
    private long nextOffset;           // 下次拉取的起始偏移量
    private boolean previouslyLocked;  // 是否已锁定(顺序消费用)
}
```

**关键：每个 PullRequest 绑定一个特定的 MessageQueue（即特定的 Broker + QueueId）**

#### 拉取流程

```
PullMessageService.run()  [线程主循环]
    │
    ├── messageRequestQueue.take()  [阻塞获取]
    │
    └── DefaultMQPushConsumerImpl.pullMessage(PullRequest)
            │
            ├── 流控检查 (见第3章)
            │
            ├── 构造 PullMessageRequestHeader
            │   ├── consumerGroup   → 消费组名
            │   ├── topic           → 主题
            │   ├── queueId         → 指定队列ID (关键!)
            │   ├── queueOffset     → 拉取起始偏移量
            │   ├── maxMsgNums      → pullBatchSize (默认32)
            │   ├── maxMsgBytes     → pullBatchSizeInBytes (默认256KB)
            │   ├── sysFlag         → 系统标志位
            │   ├── commitOffset    → 已消费偏移量
            │   ├── suspendTimeoutMillis → Broker最大挂起时间 (15秒)
            │   ├── subscription    → 订阅表达式
            │   ├── subVersion      → 订阅版本号
            │   ├── expressionType  → 表达式类型 (TAG/SQL92)
            │   └── brokerName      → Broker名称
            │
            ├── pullAPIWrapper.pullKernelImpl(...)  [发送网络请求]
            │       └── MQClientAPIImpl.pullMessage() → Netty 异步发送
            │
            └── PullCallback.onSuccess()  [异步回调]
                    ├── FOUND:
                    │   ├── processQueue.putMessage() 放入缓存
                    │   ├── consumeMessageService.submitConsumeRequest() 提交消费
                    │   └── executePullRequestImmediately/Later 继续拉取
                    ├── NO_NEW_MSG / NO_MATCHED_MSG:
                    │   └── 更新 offset → 立即拉取
                    └── OFFSET_ILLEGAL:
                        └── 冻结 offset → 持久化 → 删除 ProcessQueue → 重平衡
```

#### 拉取消息时传递的参数

客户端在 `pullKernelImpl` 中构造 `PullMessageRequestHeader`，传给 Broker 的请求参数包括：

| 参数 | 来源 | 说明 |
|------|------|------|
| **consumerGroup** | 消费组名 | 标识消费者所属组 |
| **topic** | 主题 | 拉取哪个主题的消息 |
| **queueId** | PullRequest.messageQueue.queueId | **指定拉取特定队列** |
| **queueOffset** | PullRequest.nextOffset | 从哪个偏移量开始拉取 |
| **maxMsgNums** | pullBatchSize (默认32) | 最多拉取多少条消息 |
| **maxMsgBytes** | pullBatchSizeInBytes (默认256KB) | 拉取消息的最大字节数 |
| **sysFlag** | 构建的标志位 | commitOffset/suspend/subscription/classFilter |
| **commitOffset** | offsetStore.readOffset() | 提交已消费的偏移量 |
| **suspendTimeoutMillis** | 15000ms | 长轮询挂起最大时间 |
| **subscription** | 订阅表达式 (如"TagA \|\| TagB") | 服务端过滤 |
| **subVersion** | 订阅版本号 | 用于判断订阅是否变更 |
| **expressionType** | TAG / SQL92 | 过滤表达式类型 |
| **brokerName** | MessageQueue.brokerName | 目标 Broker |

#### 是否会指定拉取某个队列的消息？

**是的，会指定。** 关键证据：

1. `PullRequest` 类中持有一个具体的 `MessageQueue messageQueue` 字段
2. 在 `pullKernelImpl` 中，`requestHeader.setQueueId(mq.getQueueId())` — 明确指定了队列ID
3. `PullRequest` 通过 `consumerGroup + messageQueue` 的 hashCode/equals 来唯一标识

也就是说，每个 `PullRequest` 从创建开始就绑定了一个特定的队列，每次拉取请求都明确告诉 Broker "我要拉取 Topic X 的 Queue Y 从 offset Z 开始的消息"。

### 2.3 会不会多个消费者消费同一个队列？

**在集群模式下：不会。**

- 重平衡阶段通过 `AllocateMessageQueueStrategy` 确保每个队列被唯一分配给一个消费者
- 如果消费者数量变化（增加或减少），会触发重平衡，重新分配队列
- 重平衡期间，旧的 `ProcessQueue` 会被标记为 `dropped=true`，不再拉取消息
- 新的 `ProcessQueue` 和 `PullRequest` 被创建，从新的起始 offset 开始拉取

**在广播模式下：会。**
- 广播模式下，每个消费者消费所有队列，不进行队列分配

### 2.4 消费者与队列的全生命周期绑定关系

```
Rebalance 触发 (doRebalance)
    │
    ├── rebalanceByTopic(topic, isOrder)
    │   ├── 获取所有队列 + 所有消费者
    │   ├── 分配策略 → 当前消费者应消费的队列集合
    │   └── updateProcessQueueTableInRebalance()
    │       ├── 遍历现有 processQueueTable
    │       │   ├── 不在新分配中的队列 → 标记 dropped=true
    │       │   └── 清理已丢弃的队列 (removeUnnecessaryMessageQueue)
    │       └── 新分配的队列
    │           ├── 创建 ProcessQueue (消息缓存)
    │           ├── 计算起始 offset (computePullFromWhere)
    │           ├── 创建 PullRequest (绑定 consumerGroup + messageQueue + processQueue)
    │           └── dispatchPullRequest → PullMessageService 开始拉取
    │
    └── 后续: PullMessageService 循环处理 PullRequest
            ├── 每次拉取指定 queueId + offset
            ├── 拉取成功 → 放入 ProcessQueue → 提交消费
            └── 消费完成 → 更新 offset → 继续拉取
```

### 2.5 PullMessageService#run 中 messageRequestQueue 的维护机制

`messageRequestQueue` 是 `PullMessageService` 内部的一个 **`LinkedBlockingQueue<MessageRequest>`**（无界阻塞队列），它是整个"推"模式拉取驱动引擎的核心。

#### 数据结构

```java
// PullMessageService.java:37
private final LinkedBlockingQueue<MessageRequest> messageRequestQueue = new LinkedBlockingQueue<>();
private final ScheduledExecutorService scheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(...);  // 延迟投递专用线程
```

- **无界阻塞队列**：`put()` 永不阻塞（容量无限），`take()` 在空时阻塞等待。
- 队列元素是统一的 `MessageRequest` 抽象，实际是两种子类：
  - `PullRequest`（传统 Pull 模式）
  - `PopRequest`（Pop 模式）
- 队列本身**不判重、不合并**——同一个 `PullRequest` 对象会反复进出队列，形成"拉取循环"。

#### 谁来读（消费者端）——`run()` 主循环

```java
public void run() {
    while (!this.isStopped()) {
        try {
            MessageRequest messageRequest = this.messageRequestQueue.take();  // 阻塞取
            if (messageRequest.getMessageRequestMode() == MessageRequestMode.POP) {
                this.popMessage((PopRequest) messageRequest);   // → impl.popMessage()
            } else {
                this.pullMessage((PullRequest) messageRequest); // → impl.pullMessage()
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) { ... }
    }
}
```

- 单线程 `while(true)` 循环，`take()` 阻塞等待。
- 取出后按模式分发到 `DefaultMQPushConsumerImpl.pullMessage/popMessage`，发起真正的网络拉取。

#### 谁来写（生产者端）——两类写入路径

**路径 A：重平衡初始灌入（一次性的）**

队列的**初始任务**来自重平衡。`RebalanceImpl` 为每个新分配的队列创建 `PullRequest` 后：

```java
// RebalancePushImpl.java:261  dispatchPullRequest()
for (PullRequest pullRequest : pullRequestList) {
    if (delay <= 0) {
        this.defaultMQPushConsumerImpl.executePullRequestImmediately(pullRequest);  // 立即入队
    } else {
        this.defaultMQPushConsumerImpl.executePullRequestLater(pullRequest, delay); // 延迟入队(默认500ms)
    }
}
```

→ 最终落到 `PullMessageService.executePullRequestImmediately()`，即 `messageRequestQueue.put(pullRequest)`。

> 也就是说：**消费者启动首次重平衡后，每个队列对应一个 PullRequest 被投入队列**，从此进入拉取循环。

**路径 B：每轮拉取完成后重新入队（持续的）**

这是队列被**持续维护**的关键。`pullMessage()` 的异步回调 `PullCallback.onSuccess()` 执行完后，会把**同一个 PullRequest 重新放回队列**，开始下一轮拉取：

```java
// DefaultMQPushConsumerImpl.java  pullCallback.onSuccess()
case FOUND:
    ...
    if (pullInterval > 0) {
        executePullRequestLater(pullRequest, pullInterval);   // 固定间隔下轮
    } else {
        executePullRequestImmediately(pullRequest);           // 立即下轮
    }
    break;
case NO_NEW_MSG:
case NO_MATCHED_MSG:
    executePullRequestImmediately(pullRequest);               // 立即继续
    break;
```

**关键点**：`PullRequest` 里的 `nextOffset` 在回调中已被更新到 `pullResult.getNextBeginOffset()`，所以重新入队的是"推进了 offset 的同一个请求"，从而不断拉取后续消息。

**延迟入队——`executePullRequestLater` 的两次投递**

```java
// PullMessageService.java:47
public void executePullRequestLater(final PullRequest pullRequest, final long timeDelay) {
    if (!isStopped()) {
        this.scheduledExecutorService.schedule(() -> {
            PullMessageService.this.executePullRequestImmediately(pullRequest);  // 到点后再 put
        }, timeDelay, TimeUnit.MILLISECONDS);
    }
}
```

注意这里有**两次入队发起的时点**：
1. 先通过 `scheduledExecutorService` 定时任务**延迟触发 put**
2. 定时任务到点后真正执行 `executePullRequestImmediately()` → `put`

`executePullRequestLater` 的调用场景（即流控/异常时的延迟重试）：

| 调用点 | 延迟量 | 含义 |
|--------|--------|------|
| 状态非 RUNNING | 3000ms | 消费者状态异常 |
| 消费者被暂停 | 1000ms | 暂停 |
| 缓存条数超阈值 | 50ms | 按条数流控 |
| 缓存大小超阈值 | 50ms | 按大小流控 |
| offset 跨度超限 | 50ms | 跨度流控 |
| 顺序消费未锁定 | 3000ms | 队列未加锁 |
| Broker 返回 FLOW_CONTROL | 20ms | 服务端流控 |
| 其他异常 | 3000ms | 通用重试 |

#### 完整流转图

```
重平衡 RebalanceImpl
  └─ 创建 PullRequest(每队列一个) ──┐
                                   ▼
                          ┌──────────────────┐   put()
  延迟路径 LateX ──调度──►│  scheduledExecutor│────┐
  立即路径 Immediate ────►│                  │    │
                          └──────────────────┘    ▼
                                        ┌────────────────────────┐
                                        │  messageRequestQueue   │   LinkedBlockingQueue
                                        │  (PullRequest/PopRequest)│
                                        └───────────┬────────────┘
                                                    │ take()  阻塞
                                                    ▼
                                        PullMessageService.run()  (单线程循环)
                                                    │
                                                    ▼
                                        DefaultMQPushConsumerImpl.pullMessage()
                                                    │  发送网络请求(异步)
                                                    ▼
                                        PullCallback.onSuccess()
                                                    │
                    ┌───────────────┬───────────────┼────────────────┐
                    ▼               ▼               ▼                ▼
               FOUND: 更新offset  NO_NEW/NO_MATCHED  流控(缓存满)    异常
                    │               │               │                │
                    ├─立即/Later ───┤─立即→          └─Later(50ms)─┐  └─Later(3000ms)
                    └───────────────┴───────────────┴──────────────┴──► 重新入队(循环)
```

#### 核心结论

1. **队列是"任务分发中心"**：`messageRequestQueue` 既是拉取任务的待办队列，也是拉取循环的"回旋镖"介质。每个队列一个 `PullRequest` 在其中反复进出。

2. **维护机制 = 生产者-消费者模型**：
   - **生产**：重平衡初始灌入 + 每轮拉取回调后重新 `put`
   - **消费**：`run()` 单线程 `take()` 阻塞取出
   - **延迟控制**：`scheduledExecutorService` 独立线程池负责"到点后再入队"，实现流控/重试的延迟

3. **无界队列不判重**：因为同一 `PullRequest` 反复入队正是"持续拉取"的机制；队列容量无上限，靠的是**流控逻辑（缓存满时延迟入队）**而非队列本身限流来防止堆积。

4. **统一抽象**：5.x 将 `PullRequest` 和 `PopRequest` 统一为 `MessageRequest`，通过 `getMessageRequestMode()` 分发，一套队列服务两种消费模式。

### 2.6 如何查看当前消费者订阅了哪些队列

#### 核心数据结构

当前消费者实际负责消费的队列存储在 `RebalanceImpl.processQueueTable` 中：

```java
// RebalanceImpl.java:52
protected final ConcurrentMap<MessageQueue, ProcessQueue> processQueueTable = new ConcurrentHashMap<>(64);
```

每个 `MessageQueue`（topic + brokerName + queueId）对应一个 `ProcessQueue`，key 集合就是当前消费者**实际拉取和消费的队列**。

访问链（均为 public 方法）：

```
DefaultMQPushConsumer.getDefaultMQPushConsumerImpl()
    └── .getRebalanceImpl()
            └── .getProcessQueueTable() → ConcurrentMap<MessageQueue, ProcessQueue>
```

#### 方式一：编程内嵌埋点（开发期/可改代码）

在消费者代码中任意时刻（start() 之后）：

```java
// 打印当前消费者分配到的所有队列
consumer.getDefaultMQPushConsumerImpl()
        .getRebalanceImpl()
        .getProcessQueueTable()
        .keySet()
        .forEach(System.out::println);

// 输出示例:
// MessageQueue [topic=TopicTest, brokerName=broker-a, queueId=0]
// MessageQueue [topic=TopicTest, brokerName=broker-a, queueId=1]
// MessageQueue [topic=TopicTest, brokerName=broker-b, queueId=0]
```

借助 `MessageQueueListener` 回调，在每次重平衡变更时自动感知分配结果：

```java
consumer.setMessageQueueListener((topic, mqAll, mqDivided) -> {
    System.out.println("Topic: " + topic);
    System.out.println("全部队列: " + mqAll);
    System.out.println("本消费者分配到的队列: " + mqDivided);
});
```

#### 方式二：mqadmin 命令行工具（运维/线上，Broker 可达时）

RocketMQ 内置的 tools 包可从 Broker 侧拉取消费者上报的运行时信息：

```bash
# 1. 查看消费组连接信息、客户端列表、订阅关系
sh mqadmin consumerConnection -n 127.0.0.1:9876 -g <consumerGroup>

# 输出示例:
# ClientId                     ClientAddr          Language  Version
# 10.0.0.1@12345              10.0.0.1:56789      JAVA      V4_9_0
#
# Below is subscription:
# Topic                  SubExpression
# TopicTest              *
#
# ConsumeType: CONSUME_PASSIVELY
# MessageModel: CLUSTERING
# ConsumeFromWhere: CONSUME_FROM_LAST_OFFSET

# 2. 查看消费者内部数据结构（含分配到哪些队列、offset、积压等）
sh mqadmin consumerStatus -n 127.0.0.1:9876 -g <consumerGroup> -i <clientId>

# 输出示例（ConsumerRunningInfo.formatString()）:
# #Consumer Offset#
# Topic                            Broker Name          QID   Consumer Offset
# TopicTest                        broker-a             0     1000
# TopicTest                        broker-a             1     985
#
# #Consumer MQ Detail#
# Topic                            Broker Name          QID   ProcessQueueInfo
# TopicTest                        broker-a             0     ProcessQueueInfo [cachedMsgCount=12, ...]
# TopicTest                        broker-a             1     ProcessQueueInfo [cachedMsgCount=0, ...]
```

`consumerStatus` 的核心数据来源是 `ConsumerRunningInfo`，其 `mqTable`（`TreeMap<MessageQueue, ProcessQueueInfo>`）就是消费者上报的 `processQueueTable` 的快照。

#### 方式三：RocketMQ Dashboard（图形化运维）

部署 [rocketmq-dashboard](https://github.com/apache/rocketmq-dashboard)（原 rocketmq-console）后：

1. 进入 **"消费者"** 页面
2. 选择目标消费组
3. 查看 **"消费者详情"** → 会显示该组下每个消费者实例
4. 点击实例 → 显示该实例绑定的队列列表（含 offset、积压消息数、拉取延迟等）

这是最直观的图形化方式，适合日常巡检。

#### 方式四：Arthas 在线诊断（不改代码、不重启，线上排查首选）

适合线上环境**无法改代码、不想重启**的场景，直接用 Arthas 从内存中读取对象。

**命令 1：`vmtool` 获取实例并取值（推荐）**

```bash
# 找到 DefaultMQPushConsumer 实例，通过表达式导航到 processQueueTable
[arthas@pid]$ vmtool -x 3 --action getInstances \
    --className org.apache.rocketmq.client.consumer.DefaultMQPushConsumer \
    --express 'instances[0].getDefaultMQPushConsumerImpl()\
        .getRebalanceImpl().getProcessQueueTable().keySet()'
```

`-x 3` 控制展开深度，`--express` 中的 getter 链全部是 public 方法，可以正常调用。

**命令 2：`ognl` 表达式（需知道对象引用）**

```bash
# 如果持有 bean 引用
[arthas@pid]$ ognl '#consumer=@springContext@getBean("myConsumer"), 
    #consumer.getDefaultMQPushConsumerImpl().getRebalanceImpl().getProcessQueueTable().keySet()'
```

**命令 3：`watch` 监控方法返回值**

```bash
# 监控 getProcessQueueTable 的返回值，观察队列变化
[arthas@pid]$ watch org.apache.rocketmq.client.impl.consumer.RebalanceImpl getProcessQueueTable returnObj
```

**命令 4：`trace` 跟踪重平衡过程**

```bash
# 跟踪重平衡调用，看队列分配的过程
[arthas@pid]$ trace org.apache.rocketmq.client.impl.consumer.RebalanceImpl rebalanceByTopic
```

#### 方式五：日志

RocketMQ 在重平衡时会打印关键日志，通过 grep 也能追溯分配结果：

```bash
# 消费者端日志
grep "client rebalanced result changed\|add a new mq\|remove unnecessary mq" ~/logs/rocketmq/rocketmq-client.log
```

#### 方式对比总结

| 方式 | 适用场景 | 是否需要改代码 | 是否影响线上 | 信息量 |
|------|---------|--------------|------------|-------|
| 代码内嵌 `getProcessQueueTable()` | 开发期调试 | ✅ 是 | 否 | 精确 |
| `MessageQueueListener` 回调 | 开发期/需要感知变更 | ✅ 是 | 否 | 精确 |
| mqadmin `consumerStatus` | 运维/线上排查 | ❌ 否 | 否 | 较全（含offset/积压） |
| mqadmin `consumerConnection` | 运维/快速查看 | ❌ 否 | 否 | 仅订阅和连接 |
| RocketMQ Dashboard | 可视化日常巡检 | ❌ 否 | 否 | 图形化，直观 |
| **Arthas vmtool** | **线上不改代码、不重启** | ❌ 否 | 否 | 精确，内存级 |
| Arthas watch/trace | 线上排查行为 | ❌ 否 | 有轻微性能开销 | 方法调用级 |
| 日志 | 事后追溯 | ❌ 否 | 否 | 依赖日志级别 |

### 2.7 重平衡策略详解（完整链路 + 代码分析）

#### 2.7.1 重平衡触发机制

**入口：RebalanceService 后台线程**

```java
// RebalanceService.java:40
public void run() {
    long realWaitInterval = waitInterval;  // 默认 20000ms
    while (!this.isStopped()) {
        this.waitForRunning(realWaitInterval);  // 周期性等待

        long interval = System.currentTimeMillis() - lastRebalanceTimestamp;
        if (interval < minInterval) {            // minInterval = 1000ms
            realWaitInterval = minInterval - interval;  // 距离上次不足1s，继续等待
        } else {
            boolean balanced = this.mqClientFactory.doRebalance();
            // 平衡了就按20s周期，没平衡(有变更)就1s后重试
            realWaitInterval = balanced ? waitInterval : minInterval;
            lastRebalanceTimestamp = System.currentTimeMillis();
        }
    }
}
```

**三种触发方式：**

| 触发方式 | 实现 | 时机 |
|---------|------|------|
| **周期性** | `RebalanceService` 每20秒循环 | 持续运行 |
| **立即触发** | `MQClientInstance.rebalanceImmediately()` | 心跳应答、消费者启动、offset异常时唤醒 |
| **延迟触发** | `MQClientInstance.rebalanceLater(500ms)` | 顺序消费锁冲突时 |

```java
// MQClientInstance.java:1060
public void rebalanceImmediately() {
    // 立即唤醒 RebalanceService 的 waitForRunning
    this.rebalanceService.wakeup();
}

// MQClientInstance.java:1052
public void rebalanceLater(long delayMillis) {
    // 延迟唤醒
    ThreadUtils.schedule(this.rebalanceService::wakeup, delayMillis, TimeUnit.MILLISECONDS);
}
```

#### 2.7.2 重平衡入口：doRebalance()

```java
// RebalanceImpl.java:237
public boolean doRebalance(final boolean isOrder) {
    boolean balanced = true;
    Map<String, SubscriptionData> subTable = this.getSubscriptionInner();
    if (subTable != null) {
        for (final Map.Entry<String, SubscriptionData> entry : subTable.entrySet()) {
            final String topic = entry.getKey();
            try {
                // 决策分支：客户端重平衡 vs Broker端重平衡
                if (!clientRebalance(topic) && tryQueryAssignment(topic)) {
                    // 走 Broker 端分配（Pop模式或 serverRebalance=true）
                    boolean result = this.getRebalanceResultFromBroker(topic, isOrder);
                    if (!result) { balanced = false; }
                } else {
                    // 走客户端分配（默认路径）
                    boolean result = this.rebalanceByTopic(topic, isOrder);
                    if (!result) { balanced = false; }
                }
            } catch (Throwable e) { ... }
        }
    }
    this.truncateMessageQueueNotMyTopic();  // 清理已取消订阅的 topic
    return balanced;
}
```

**决策逻辑** (`RebalancePushImpl.clientRebalance()`):

```java
// RebalancePushImpl.java:138
public boolean clientRebalance(String topic) {
    // 以下条件满足其一即走客户端重平衡：
    // 1. clientRebalance=true (默认)
    // 2. 顺序消费
    // 3. 广播模式
    return defaultMQPushConsumerImpl.getDefaultMQPushConsumer().isClientRebalance()
        || defaultMQPushConsumerImpl.isConsumeOrderly()
        || MessageModel.BROADCASTING.equals(messageModel);
}
```

**默认走客户端重平衡**（`clientRebalance=true`），只有 Pop 模式或显式配置 `clientRebalance=false` 时才走 Broker 分配。

#### 2.7.3 核心算法：rebalanceByTopic() — CLUSTERING 模式

```java
// RebalanceImpl.java:305
private boolean rebalanceByTopic(final String topic, final boolean isOrder) {
    ...
    case CLUSTERING: {
        Set<MessageQueue> mqSet = this.topicSubscribeInfoTable.get(topic);  // 路由信息
        List<String> cidAll = this.mQClientFactory.findConsumerIdList(topic, consumerGroup);  // 消费者列表

        if (mqSet != null && cidAll != null) {
            List<MessageQueue> mqAll = new ArrayList<>();
            mqAll.addAll(mqSet);
            Collections.sort(mqAll);    // 排序：保证所有消费者看到相同顺序
            Collections.sort(cidAll);   // 排序：保证所有消费者看到相同顺序

            AllocateMessageQueueStrategy strategy = this.allocateMessageQueueStrategy;

            // ★ 调用分配算法，返回本消费者应消费的队列
            List<MessageQueue> allocateResult = strategy.allocate(
                this.consumerGroup,
                this.mQClientFactory.getClientId(),  // 当前消费者 clientId
                mqAll,         // 所有已排序队列
                cidAll);       // 所有已排序消费者ID

            // 更新 processQueueTable
            boolean changed = this.updateProcessQueueTableInRebalance(topic, allocateResultSet, isOrder);
            if (changed) {
                this.messageQueueChanged(topic, mqSet, allocateResultSet);
            }
            // 对比当前分配是否与上次一致
            balanced = allocateResultSet.equals(getWorkingMessageQueue(topic));
        }
        break;
    }
}
```

**关键前提**：所有消费者必须看到**相同的 `mqAll` 和 `cidAll` 排序结果**，这是分配算法产生一致性的前提：
- `mqAll` 来自 `topicSubscribeInfoTable`（从 NameServer 获取的路由信息）
- `cidAll` 来自 Broker 的心跳管理（`findConsumerIdList()` 查询 Broker）
- 两次 `Collections.sort()` 保证确定性

#### 2.7.4 五种分配算法详解

##### 算法一：AllocateMessageQueueAveragely（默认 — 平均分配）

```java
// AllocateMessageQueueAveragely.java
public List<MessageQueue> allocate(String consumerGroup, String currentCID,
    List<MessageQueue> mqAll, List<String> cidAll) {

    int index = cidAll.indexOf(currentCID);  // 当前消费者在列表中的位置
    int mod = mqAll.size() % cidAll.size();  // 余数

    // 前 mod 个消费者: averageSize = floor(N/M) + 1
    // 后面的消费者:    averageSize = floor(N/M)
    int averageSize = mqAll.size() <= cidAll.size() ? 1 :
        (mod > 0 && index < mod ? mqAll.size() / cidAll.size() + 1
                                : mqAll.size() / cidAll.size());

    // 计算起始索引
    int startIndex = (mod > 0 && index < mod)
        ? index * averageSize
        : index * averageSize + mod;

    int range = Math.min(averageSize, mqAll.size() - startIndex);
    for (int i = 0; i < range; i++) {
        result.add(mqAll.get(startIndex + i));  // 连续取 averageSize 个
    }
    return result;
}
```

**示例：8个队列，3个消费者**

| 消费者 | index | mod=2, index<mod? | averageSize | startIndex | 分配结果 |
|--------|-------|-------------------|-------------|------------|---------|
| C1 | 0 | 是 | 8/3+1=3 | 0*3=0 | [0,1,2] |
| C2 | 1 | 是 | 8/3+1=3 | 1*3=3 | [3,4,5] |
| C3 | 2 | 否 | 8/3=2 | 2*2+2=6 | [6,7] |

**特点**：队列连续分组，前 `mod` 个消费者多分一个队列。**队列少时差异小**。

##### 算法二：AllocateMessageQueueAveragelyByCircle（轮询分配）

```java
// AllocateMessageQueueAveragelyByCircle.java
public List<MessageQueue> allocate(String consumerGroup, String currentCID,
    List<MessageQueue> mqAll, List<String> cidAll) {

    int index = cidAll.indexOf(currentCID);
    for (int i = index; i < mqAll.size(); i++) {
        if (i % cidAll.size() == index) {  // 步长为消费者数
            result.add(mqAll.get(i));
        }
    }
    return result;
}
```

**示例：8个队列，3个消费者**

| 消费者 | index | 分配结果 |
|--------|-------|---------|
| C1 | 0 | [0,3,6] |
| C2 | 1 | [1,4,7] |
| C3 | 2 | [2,5] |

**特点**：队列交错分配，每个消费者分散获取不同 Broker 的队列，负载更均衡。

##### 算法三：AllocateMessageQueueConsistentHash（一致性哈希）

```java
// AllocateMessageQueueConsistentHash.java
public List<MessageQueue> allocate(...) {
    // 1. 为每个消费者创建虚拟节点（默认10个）
    Collection<ClientNode> cidNodes = new ArrayList<>();
    for (String cid : cidAll) {
        cidNodes.add(new ClientNode(cid));
    }
    ConsistentHashRouter<ClientNode> router = new ConsistentHashRouter<>(cidNodes, virtualNodeCnt);

    // 2. 每个队列通过哈希环路由到消费者
    for (MessageQueue mq : mqAll) {
        ClientNode clientNode = router.routeNode(mq.toString());
        if (clientNode != null && currentCID.equals(clientNode.getKey())) {
            results.add(mq);
        }
    }
    return results;
}
```

**特点**：消费者增减时只影响少量队列，适合大规模动态伸缩场景。

##### 算法四：AllocateMessageQueueByMachineRoom（机房感知）

```java
// AllocateMessageQueueByMachineRoom.java
public List<MessageQueue> allocate(...) {
    // 1. 只筛选当前机房（consumeridcs）的队列
    List<MessageQueue> premqAll = new ArrayList<>();
    for (MessageQueue mq : mqAll) {
        String[] temp = mq.getBrokerName().split("@");
        if (temp.length == 2 && consumeridcs.contains(temp[0])) {
            premqAll.add(mq);
        }
    }
    // 2. 在筛选后的队列上做平均分配
    int mod = premqAll.size() / cidAll.size();
    int rem = premqAll.size() % cidAll.size();
    int startIndex = mod * currentIndex;
    ...
}
```

**特点**：Broker 命名约定 `机房@brokerName`，确保消费者只消费同机房队列。

##### 算法五：AllocateMessageQueueByConfig（手动配置）

```java
// AllocateMessageQueueByConfig.java
public List<MessageQueue> allocate(...) {
    return this.messageQueueList;  // 直接返回预先设置好的列表
}
```

**特点**：完全手动指定，不做任何计算。

#### 2.7.5 分配结果的应用：updateProcessQueueTableInRebalance()

这是分配结果真正生效的步骤：

```java
// RebalanceImpl.java:479
private boolean updateProcessQueueTableInRebalance(String topic, Set<MessageQueue> mqSet, boolean isOrder) {
    boolean changed = false;

    // 第一步：丢弃不再属于本消费者的队列
    // 遍历现有 processQueueTable，标记不再分配的队列为 dropped=true
    for (Entry<MessageQueue, ProcessQueue> entry : this.processQueueTable.entrySet()) {
        MessageQueue mq = entry.getKey();
        ProcessQueue pq = entry.getValue();
        if (mq.getTopic().equals(topic)) {
            if (!mqSet.contains(mq)) {
                pq.setDropped(true);                  // ★ 标记丢弃
                removeQueueMap.put(mq, pq);
            } else if (pq.isPullExpired() && ...) {  // 拉取超时也清理
                pq.setDropped(true);
                removeQueueMap.put(mq, pq);
            }
        }
    }
    // 执行实际移除（持久化 offset + 解锁 + 删除）
    for (Entry<MessageQueue, ProcessQueue> entry : removeQueueMap.entrySet()) {
        if (this.removeUnnecessaryMessageQueue(mq, pq)) {
            this.processQueueTable.remove(mq);        // ★ 从队列表中移除
            changed = true;
        }
    }

    // 第二步：添加新分配的队列
    for (MessageQueue mq : mqSet) {
        if (!this.processQueueTable.containsKey(mq)) {
            // 顺序消费需要先锁定队列
            if (isOrder && !this.lock(mq)) { continue; }

            this.removeDirtyOffset(mq);
            ProcessQueue pq = createProcessQueue();       // 创建新的 ProcessQueue
            long nextOffset = this.computePullFromWhere(mq);  // 计算起始 offset
            if (nextOffset >= 0) {
                this.processQueueTable.putIfAbsent(mq, pq);  // ★ 加入队列表
                PullRequest pullRequest = new PullRequest();
                pullRequest.setConsumerGroup(consumerGroup);
                pullRequest.setNextOffset(nextOffset);
                pullRequest.setMessageQueue(mq);
                pullRequest.setProcessQueue(pq);
                pullRequestList.add(pullRequest);
                changed = true;
            }
        }
    }
    // 延迟 500ms 分发 PullRequest 到 PullMessageService
    this.dispatchPullRequest(pullRequestList, 500);
    return changed;
}
```

**关键行为**：
- **丢弃的队列**：`ProcessQueue.setDropped(true)` → `pullMessage()` 中检测到 `dropped` 后直接返回，不再拉取
- **新增的队列**：创建 `ProcessQueue` + `PullRequest` → 通过 `dispatchPullRequest` 投入 `PullMessageService` 开始拉取
- **起始 offset**：通过 `computePullFromWhere()` 计算，集群模式从 `RemoteBrokerOffsetStore` 读取（Broker 持久化）

#### 2.7.6 队列数量与消费者数量的关系

**核心结论：分配算法只依赖队列数和消费者数，不依赖消息量。**

| 关系 | 分配结果 | 例子（8队列） |
|------|---------|-------------|
| 队列数 = 消费者数 | 每人1个队列 | 3消费者3队列 → 每人1个 |
| 队列数 > 消费者数 | 前 `mod` 个消费者多分1个 | 8队列3消费者 → 3,3,2 |
| 队列数 < 消费者数 | 部分消费者分不到队列 | 3队列5消费者 → 3人各1个，2人0个 |

**重要：分配算法不关心消息量**。即使某个队列积压了 100 万条消息，另一个队列只有 1 条，分配时也不会因此多分消费者给积压队列。这是**静态分配**，不是**动态负载均衡**。

如果需要根据消息量动态调整，需要：
- 使用 Pop 模式（Broker 端分配，消费者拉取时竞争）
- 或自行实现自定义 `AllocateMessageQueueStrategy`

#### 2.7.7 重平衡全链路流程图

```
RebalanceService (20s周期 / rebalanceImmediately 唤醒)
    │
    └── MQClientInstance.doRebalance()
            │
            └── 遍历所有 consumer
                    │
                    └── DefaultMQPushConsumerImpl.doRebalance()
                            │
                            └── RebalanceImpl.doRebalance(isOrder)
                                    │
                                    ├── 遍历所有订阅的 topic
                                    │       │
                                    │       ├── clientRebalance? → 客户端分配
                                    │       │       └── rebalanceByTopic(topic, isOrder)
                                    │       │               ├── 获取 mqAll (topicSubscribeInfoTable)
                                    │       │               ├── 获取 cidAll (findConsumerIdList)
                                    │       │               ├── Collections.sort(mqAll)
                                    │       │               ├── Collections.sort(cidAll)
                                    │       │               ├── strategy.allocate(mqAll, cidAll)
                                    │       │               │       ├── AVG: 连续分组
                                    │       │               │       ├── AVG_BY_CIRCLE: 轮询
                                    │       │               │       ├── CONSISTENT_HASH: 哈希环
                                    │       │               │       ├── MACHINE_ROOM: 机房过滤
                                    │       │               │       └── CONFIG: 手动指定
                                    │       │               │
                                    │       │               └── updateProcessQueueTableInRebalance()
                                    │       │                       ├── 丢弃不再分配的队列 (dropped)
                                    │       │                       ├── 创建新 ProcessQueue
                                    │       │                       ├── 计算起始 offset
                                    │       │                       └── dispatchPullRequest → PullMessageService
                                    │       │
                                    │       └── Broker 分配
                                    │               └── getRebalanceResultFromBroker()
                                    │
                                    └── truncateMessageQueueNotMyTopic() 清理已取消订阅
```

#### 2.7.8 关键设计要点

1. **去中心化**：每个消费者独立执行相同的分配算法，不依赖中心协调器。只要 `mqAll` 和 `cidAll` 一致，所有消费者会算出互补的结果，保证每个队列唯一分配给一个消费者。

2. **排序保证一致性**：`Collections.sort()` 确保所有消费者以相同的顺序处理队列和消费者列表，分配算法才能产生正确结果。

3. **最终一致性**：消费者上下线时，心跳传播到 Broker 需要时间（约 5-10 秒），因此 `cidAll` 在不同消费者视角可能短暂不一致，最终通过多轮重平衡收敛。

4. **分配结果是静态的**：与消息数量无关，只与队列数和消费者数有关。如果需要按消息量动态调整，需使用 Pop 模式或自定义策略。

5. **顺序消费的锁机制**：顺序消费时，新分配的队列需要先向 Broker 加锁（`lock(mq)`），确保同一时间只有一个消费者消费该队列。

---

## 3. 缓存和流控参数对消费者端及服务端行为的影响

### 3.1 核心参数总览

| 参数 | 默认值 | 作用域 | 作用 |
|------|--------|--------|------|
| **pullThresholdForQueue** | 1000 | 每个队列 | 本地缓存消息条数上限 |
| **pullThresholdSizeForQueue** | 100 MiB | 每个队列 | 本地缓存消息体大小上限 |
| **pullThresholdForTopic** | -1 (不限) | 主题级别 | 主题级别消息条数上限 |
| **pullThresholdSizeForTopic** | -1 (不限) | 主题级别 | 主题级别消息体大小上限 |
| **pullBatchSize** | 32 | 每次拉取 | 一次拉取请求最多获取的消息数 |
| **pullBatchSizeInBytes** | 256 KB | 每次拉取 | 一次拉取请求最多获取的字节数 |
| **consumeMessageBatchMaxSize** | 1 | 每次消费 | 一次回调MessageListener的最大消息数 |
| **consumeConcurrentlyMaxSpan** | 2000 | 每个队列 | offset最大跨度限制(并发消费) |
| **pullInterval** | 0 | 每次拉取 | 两次拉取之间最小间隔(毫秒) |
| **suspendCurrentQueueTimeMillis** | 1000 | 流控 | 流控时暂停拉取时间 |
| **adjustThreadPoolNumsThreshold** | 100000 | 全局 | 消费线程池动态调整阈值 |

### 3.2 消费者端流控机制（三层流控）

在 `DefaultMQPushConsumerImpl.pullMessage()` 中，每次拉取前执行三层检查：

#### 第一层：消息数量流控

```java
// 第272行
if (cachedMessageCount > this.defaultMQPushConsumer.getPullThresholdForQueue()) {
    this.executePullRequestLater(pullRequest, PULL_TIME_DELAY_MILLS_WHEN_CACHE_FLOW_CONTROL);
    // 延迟50ms后重试
    return;
}
```

- **触发条件**: ProcessQueue 中缓存的消息数量 > `pullThresholdForQueue` (默认1000)
- **行为**: 延迟50ms后重新将 PullRequest 放入队列，**暂停拉取**
- **目的**: 防止消费者端内存溢出，消费速度跟不上拉取速度时触发

#### 第二层：消息大小流控

```java
// 第282行
if (cachedMessageSizeInMiB > this.defaultMQPushConsumer.getPullThresholdSizeForQueue()) {
    this.executePullRequestLater(pullRequest, PULL_TIME_DELAY_MILLS_WHEN_CACHE_FLOW_CONTROL);
    // 延迟50ms后重试
    return;
}
```

- **触发条件**: ProcessQueue 中缓存消息体总大小 > `pullThresholdSizeForQueue` (默认100 MiB)
- **行为**: 同第一层，延迟50ms

#### 第三层：offset 跨度流控（并发消费模式）

```java
// 第293行
if (processQueue.getMaxSpan() > this.defaultMQPushConsumer.getConsumeConcurrentlyMaxSpan()) {
    this.executePullRequestLater(pullRequest, PULL_TIME_DELAY_MILLS_WHEN_CACHE_FLOW_CONTROL);
    // 延迟50ms后重试
    return;
}
```

- **触发条件**: ProcessQueue 中最大 offset - 最小 offset > `consumeConcurrentlyMaxSpan` (默认2000)
- **行为**: 延迟50ms
- **含义**: 如果消费速度远慢于拉取速度，导致拉取的消息 offset 跨度太大，说明消费端积压严重，需要流控

#### 流控流程总结

```
Broker                          Consumer
  │                                │
  │   ──── Pull(offset=1000) ──→   │
  │                                ├── ProcessQueue 现有消息 950 条
  │                                ├── pullThresholdForQueue = 1000
  │                                ├── 950 + 32 > 1000 → 触发流控!
  │                                └── 延迟50ms重试
  │                                │
  │   ←── (等待50ms) ────────      │
  │                                │
  │   ──── Pull(offset=1000) ──→   │
  │                                ├── ProcessQueue 现有消息 980 条 (消费慢)
  │                                └── 再次流控...
  │                                │
  │   ←── (消费了一批, 剩余 900) ── │
  │                                │
  │   ──── Pull(offset=1000) ──→   │
  │                                ├── 900 + 32 ≤ 1000 → 通过
  │                                └── 发送拉取请求到 Broker
```

### 3.3 主题级别阈值

在 `RebalancePushImpl.messageQueueChanged()` 中，当队列分配变化时：

```java
// 第67行
int pullThresholdForTopic = this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getPullThresholdForTopic();
if (pullThresholdForTopic != -1) {
    int newVal = Math.max(1, pullThresholdForTopic / currentQueueCount);
    this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().setPullThresholdForQueue(newVal);
}
```

- **作用**: 当设置了主题级别的上限时，自动按当前分配的队列数均分到每个队列的阈值
- **示例**: 设置 `pullThresholdForTopic = 3000`，当前分配了3个队列 → 每个队列 `pullThresholdForQueue = 1000`
- **动态调整**: 重平衡后队列数变化 → 自动重新计算

### 3.4 对服务端行为的影响

#### 拉取请求中的 maxMsgNums 参数

```java
// PullMessageRequestHeader
requestHeader.setMaxMsgNums(maxNums);  // pullBatchSize = 32
requestHeader.setMaxMsgBytes(maxSizeInBytes);  // pullBatchSizeInBytes = 256KB
```

Broker 端 `PullMessageProcessor` 处理时：
- 使用 `maxMsgNums` 作为 `GetMessageResult` 的消息数量上限
- 使用 `maxMsgBytes` 约束返回消息的总字节数
- Broker 实际返回的消息数量 = min(服务端可获取消息数, maxMsgNums)

#### 长轮询机制

```java
// 拉取请求中的挂起参数
requestHeader.setSuspendTimeoutMillis(brokerSuspendMaxTimeMillis);  // 15000ms
// Consumer 超时
CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND;  // 30000ms
```

- 当 Broker 没有新消息时，不会立即返回空结果，而是挂起连接最多15秒
- 挂起期间如果新消息到达，立即唤醒并返回
- 这是一种**长轮询（Long Polling）**机制，减少了空轮询的消耗

#### 服务端流控响应

```java
// PullCallback.onException
if (e instanceof MQBrokerException && ((MQBrokerException) e).getResponseCode() == ResponseCode.FLOW_CONTROL) {
    // Broker 主动流控 → 延迟20ms后重试
    executePullRequestLater(pullRequest, PULL_TIME_DELAY_MILLS_WHEN_BROKER_FLOW_CONTROL);  // 20ms
} else {
    // 其他异常 → 延迟3秒重试
    executePullRequestLater(pullRequest, pullTimeDelayMillsWhenException);  // 3000ms
}
```

- **Broker 流控**: 当 Broker 自身负载过高时，返回 `FLOW_CONTROL` 响应码
- **消费者行为**: 延迟20ms后重试，比普通异常(3秒)快得多
- 冷数据读取时 Broker 也会进行流控

### 3.5 消费线程池动态调整

```java
// 通过 adjustThreadPoolNumsThreshold 控制
```

- 当所有队列的累计积压消息数超过 `adjustThreadPoolNumsThreshold`（默认100000）时，增加消费线程
- 当积压数低于阈值的80%时，减少消费线程
- 线程数范围: `[consumeThreadMin, consumeThreadMax]`（默认均为20）

### 3.6 参数配置建议

| 场景 | 建议调整参数 |
|------|------------|
| 消息体较大(>100KB) | 调小 `pullThresholdSizeForQueue`，调大 `pullBatchSizeInBytes` |
| 消费速度慢，需要控制内存 | 调小 `pullThresholdForQueue` |
| 需要提升吞吐量 | 调大 `pullBatchSize`、`consumeMessageBatchMaxSize`、`consumeThreadMax` |
| 防止 Topic 级别 OOM | 设置 `pullThresholdForTopic` |
| 需要降低延迟 | 设置 `pullInterval = 0`（默认已经是0） |
| 服务端负载高 | 增大 `pullInterval` 减少拉取频率 |

---

## 4. 消费者端积压不均排查指南

### 4.1 问题现象

> 订单 id 哈希路由到队列，理论上各队列消息量均匀，但 Dashboard 显示"就绪消息"严重不均 —— 有的队列 14000 条，有的队列几十条。这是为什么？怎么排查？

### 4.2 核心认知：就绪消息 ≠ 存储分布，而是积压

**存储均匀 ≠ 积压均匀。**

```
某队列就绪消息数 = 生产到该队列的消息总量 − 该队列已消费的消息总量
```

它是一个**动态差值**（生产速率 × 消费落后时间），不是静态存储分布。订单 id 哈希只保证了**生产侧**消息均匀写入，但积压 = 进来 − 出去，消费速率不同会导致积压差异巨大。

### 4.3 为什么存储均匀积压却不均匀

#### 原因一：消费者实例能力差异（最常见）

如果消费组有多个消费者实例，分别绑定不同的队列，但实例间性能不同（机器配置、GC 频率、CPU 负载、依赖服务响应时间），消费速率能差好几倍。快实例的队列很快清空（就绪几十条），慢实例的队列越积越多（就绪上万）。

#### 原因二：热队列（Hot Partition）

即使订单 id 哈希均匀，不同订单的业务处理耗时天然不同。如果某些慢订单（如调外部接口、查库、发短信、大额校验）恰好哈希到同一个队列，该队列的消费速度就明显慢于其他队列，积压持续增长。RocketMQ 的分配是**静态**的，消费者一旦绑上这个"热队列"就一直被拖累。

#### 原因三：消费线程池被拖累

一个消费者负责多个队列时，**所有队列共享同一个消费线程池**。如果其中一个队列的消息处理特别慢，会占满线程池，导致该消费者负责的**所有队列**消费速度一起下降，全部积压。

#### 原因四：消费失败与重试

如果某队列的消息频繁消费失败（业务异常、反序列化失败），消息回到重试队列或反复重试，该队列的"有效消费速度"极低，积压暴涨。

### 4.4 排查步骤

#### 第一步：确认"谁在消费哪些队列"

**Arthas（线上不改代码，推荐）：**

```bash
# 1. 查看每个队列的缓存消息数和大小（反映积压程度）
vmtool -x 2 --action getInstances \
  --className org.apache.rocketmq.client.consumer.DefaultMQPushConsumer \
  --express 'instances[0].getDefaultMQPushConsumerImpl().getRebalanceImpl().getProcessQueueTable().entrySet().{ #this.getKey().getBrokerName() + "-" + #this.getKey().getQueueId() + "=" + #this.getValue().getMsgCount().get() + "/" + (#this.getValue().getMsgSize().get() / 1024) + "KB" }'
```

> 如果某个队列的缓存消息数持续接近 `pullThresholdForQueue(1000)`，说明该队列消费速度跟不上拉取速度。

**mqadmin（从 Broker 侧看全局）：**

```bash
# 查看消费组下所有消费者实例及其绑定的队列
sh mqadmin consumerStatus -n 127.0.0.1:9876 -g <consumerGroup>

# 只看某个特定 clientId
sh mqadmin consumerStatus -n 127.0.0.1:9876 -g <consumerGroup> -i <clientId>
```

`consumerStatus` 输出的 `ConsumerRunningInfo` 中包含：
- **`#Consumer Offset`**：每个队列的已消费 offset
- **`#Consumer MQ Detail`**：每个队列的 `ProcessQueueInfo`（`cachedMsgCount`、`cachedMsgSizeInMiB`）

**日志：**

```bash
grep "client rebalanced result changed\|add a new mq\|remove unnecessary mq" \
    ~/logs/rocketmq/rocketmq-client.log
```

#### 第二步：看"消费速度"——确认是不是某实例/某队列慢

**Arthas 监控消费监听器耗时（最直接）：**

```bash
# 监控你的 MessageListener 中每条消息的消费耗时
[arthas@pid]$ watch com.xxx.YourMessageListener consumeMessage \
    '{params[0].size(), #cost}' \
    -x 2 -n 100

# 如果耗时分布差异大，说明某些消息处理慢
# 输出示例: [1, 5ms] [1, 1200ms] [1, 3ms] [1, 3000ms] ...
```

**Arthas 按队列监控拉取回调耗时：**

```bash
[arthas@pid]$ watch org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl \
    pullMessage \
    '{params[0].messageQueue.brokerName+"-"+params[0].messageQueue.queueId, 
      params[0].nextOffset, 
      #cost}' \
    -x 2 -n 100
```

**看消费线程池状态：**

```bash
# 看消费线程池的队列积压
[arthas@pid]$ vmtool -x 1 --action getInstances \
    --className org.apache.rocketmq.client.impl.consumer.ConsumeMessageConcurrentlyService \
    --express 'instances[0].getConsumeExecutor().getQueue().size()'

# 如果线程池队列持续有积压，说明消费速度跟不上
```

#### 第三步：看"消费是否卡住"

**看线程堆栈：**

```bash
# 查看最忙的5个线程，看消费线程在干什么
[arthas@pid]$ thread -n 5

# 大量在执行业务代码 → 业务处理慢
# 大量在 wait/blocked → 线程池被占满
```

**看重试队列：**

```bash
# 查看重试主题的消息量（如果很大，说明消费失败率高）
sh mqadmin consumerProgress -n 127.0.0.1:9876 -g <consumerGroup>
```

**看日志中重试次数：**

```bash
grep "reconsumeTimes\|reconsume" ~/logs/rocketmq/rocketmq-client.log | head -20
```

#### 第四步：看"热队列"特征

如果前三步确认**某个特定队列的积压持续增长**而其他队列正常，基本是"热队列"问题。

在消费监听器中临时加日志，看积压大的队列里是什么消息：

```java
public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
    ConsumeConcurrentlyContext context) {
    for (MessageExt msg : msgs) {
        String orderId = msg.getProperty("orderId"); // 或 msg.getKeys()
        int queueId = msg.getQueueId();
        if (queueId == 2) {  // 积压最大的队列 ID
            log.warn("热队列 queueId={}, orderId={}, storeTime={}",
                queueId, orderId, msg.getStoreTimestamp());
        }
    }
    // ... 正常消费逻辑
}
```

跑几分钟，看该队列的订单有没有共性：
- 同一类订单（大额、跨境、某些店铺）
- 是否调用了慢的外部服务
- 消息体是否特别大

### 4.5 排查路线图

```
Step 1: 确认分配
    → consumerStatus 看每个实例绑了哪些队列
    → 确认是不是某个消费者绑了太多队列

Step 2: 看积压分布
    → 对比各队列的 consumer offset 和 max offset
    → 确认积压是"所有队列都涨"还是"某个队列涨"

Step 3: 看消费速度
    → Arthas watch 消费监听器耗时
    → 看线程池状态

Step 4: 定位根因
    → 慢队列 → 打印该队列的订单特征（热队列）
    → 慢实例 → 看该实例的线程堆栈、GC
    → 多失败 → 看重试队列
```

### 4.6 快速排查命令汇总

```bash
# 1. 看当前消费者绑了哪些队列，各缓存多少消息
arthas vmtool ... getProcessQueueTable().entrySet()...

# 2. 看消费监听器每次调用的耗时分布
arthas watch YourListener consumeMessage '{params[0].size(), #cost}' -n 100

# 3. 看消费线程是否在干活
arthas thread -n 5

# 4. 看 Broker 侧各队列的积压
mqadmin consumerStatus -n 127.0.0.1:9876 -g <group>
```

### 4.7 一句话结论

**订单 id 哈希保证了"写入"均匀，但"就绪/积压"是动态差值，由消费速率主导。14000 vs 几十的极端不均，几乎可以断定是某个消费者实例或某个队列消费过慢（性能差、热队列、重试、线程池被拖累）造成的滚雪球式积压，而不是队列存储不均衡。**

---

## 5. 总结与架构图

### 5.1 完整架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                    DefaultMQPushConsumer                        │
│  (配置入口: pullThresholdForQueue, pullBatchSize, ...)           │
└─────────────────────────┬───────────────────────────────────────┘
                          │ 委托
                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     DefaultMQPushConsumerImpl                        │
│  ┌──────────────────┐  ┌─────────────────┐  ┌───────────────────┐   │
│  │ RebalancePushImpl │  │  PullAPIWrapper  │  │ ConsumeMessageService│
│  │ (队列分配)        │  │  (拉取API封装)   │  │ (消费调度)          │
│  └────────┬─────────┘  └────────┬────────┘  └─────────┬─────────┘   │
│           │                     │                      │             │
│           分配结果               │ 发送请求              │ 提交消费     │
│           ▼                     ▼                      ▼             │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │             PullMessageService (线程循环)                     │    │
│  │  messageRequestQueue.take() → pullMessage() → callback      │    │
│  └──────────────────────────────────────────────────────────────┘    │
│           │                                                          │
│           │ PullRequest(consumerGroup, messageQueue, nextOffset)     │
│           ▼                                                          │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │  ProcessQueue (每个队列一个)                                   │    │
│  │  msgTreeMap: TreeMap<Long, MessageExt>  ← 缓存拉取到的消息     │    │
│  │  msgCount: 当前缓存消息数                                       │    │
│  │  msgSize: 当前缓存消息总大小                                     │    │
│  └──────────────────────────────────────────────────────────────┘    │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ 网络 (Netty)
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Broker 端                                     │
│  PullMessageProcessor.processRequest()                          │
│  ├── 权限/Topic/订阅检查                                         │
│  ├── 长轮询(suspendTimeoutMillis=15s)                            │
│  └── MessageStore.getMessageAsync() → 返回消息                    │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 核心结论

1. **启动流程**: DefaultMQPushConsumer 启动是一个分层初始化过程，从配置校验 → 订阅关系拷贝 → 网络层初始化 → 消费服务启动 → 最后触发重平衡。

2. **队列分配**: 集群模式下，通过重平衡算法确保 **每个队列在同一消费组内唯一分配给一个消费者**，不会出现多消费者消费同一队列的情况。队列数 > 消费者数时，队列会被平均分配。

3. **消息拉取**: 每次拉取请求都明确指定了 `topic` + `queueId` + `queueOffset`，即拉取特定队列的特定偏移量范围的消息。PullRequest 与 MessageQueue 是一对一绑定的。

4. **流控机制**: 消费者端通过三层缓存检查（数量、大小、offset跨度）在拉取前进行本地流控，Broker 端通过响应码 `FLOW_CONTROL` 进行服务端流控。参数 `pullThresholdForQueue` 是防止本地内存溢出的关键防线。

5. **长轮询**: 采用长轮询（Long Polling）机制，Broker 无消息时挂起最多15秒，有新消息到达才返回，减少空轮询消耗。

### 5.3 关键文件索引

| 文件 | 路径 | 核心内容 |
|------|------|---------|
| DefaultMQPushConsumer.java | client/consumer/ | 消费者入口，配置定义 |
| DefaultMQPushConsumerImpl.java | client/impl/consumer/ | 启动逻辑，pullMessage() |
| RebalanceImpl.java | client/impl/consumer/ | 重平衡核心逻辑 |
| RebalancePushImpl.java | client/impl/consumer/ | Push模式重平衡实现 |
| PullMessageService.java | client/impl/consumer/ | 拉取服务线程 |
| PullMessageRequestHeader.java | remoting/protocol/header/ | 拉取请求协议头 |
| PullAPIWrapper.java | client/impl/consumer/ | 拉取API封装 |
| ProcessQueue.java | client/impl/consumer/ | 队列处理快照 |
| AllocateMessageQueueAveragely.java | client/consumer/rebalance/ | 平均分配算法 |
| PullMessageProcessor.java | broker/processor/ | Broker端拉取处理 |
| ConsumeMessageConcurrentlyService.java | client/impl/consumer/ | 并发消费服务 |
