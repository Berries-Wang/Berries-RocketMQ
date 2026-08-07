# RocketMQ DefaultMQProducer 与消息存储深度分析报告

> 分析日期: 2026-08-06
> 源码版本: RocketMQ 5.3.1 Release
> 分析人: GLM-5.2

本文档是 `022.20260806代码分析` 目录下的第二份分析报告，聚焦于**生产者发送**与**消息存储**两条链路。
消费者链路见同目录 `README.md`。

---

## 目录

1. [DefaultMQProducer 发送流程](#1-defaultmqproducer-发送流程)
2. [消息存储: 存在哪里, 如何存储](#2-消息存储-存在哪里-如何存储)
3. [多队列如何选择实际队列](#3-多队列如何选择实际队列)
4. [定时/延迟消息的存储与消费](#4-定时延迟消息的存储与消费)
5. [总结与架构图](#5-总结与架构图)

---

## 1. DefaultMQProducer 发送流程

### 1.1 类体系

```
DefaultMQProducer (用户入口类, 封装配置)
    └── 委托 → DefaultMQProducerImpl (核心实现)
                    ├── TopicPublishInfo (路由信息)
                    ├── MQFaultStrategy (故障容错/队列选择)
                    ├── MQClientInstance (客户端实例/网络)
                    ├── SendMessageHook (发送钩子)
                    └── ProduceAccumulator (自动批量)
```

### 1.2 发送时序

```
DefaultMQProducer.send(msg)
    │
    ├── 1. 设置 topic 的 namespace
    │
    ├── 2. autoBatch ? sendByAccumulator() : sendDirect()
    │
    └── DefaultMQProducerImpl.send(msg, timeout)
            │
            └── sendDefaultImpl(msg, CommunicationMode.SYNC, null, timeout)
                    │
                    ├── 1. makeSureStateOK() 状态检查
                    │
                    ├── 2. tryToFindTopicPublishInfo(topic)
                    │       ├── 本地缓存 topicPublishInfoTable 命中?
                    │       └── 未命中 → 从 NameServer 更新路由
                    │
                    ├── 3. 重试循环 (1 + retryTimesWhenSendFailed = 3次)
                    │       │
                    │       ├── 4. mqFaultStrategy.selectOneMessageQueue()
                    │       │       └── 选择目标队列 (见第3章)
                    │       │
                    │       ├── 5. sendKernelImpl(msg, mq) 实际网络发送
                    │       │       ├── 解析 broker 地址
                    │       │       ├── 设置消息唯一ID (UNIQ_KEY)
                    │       │       ├── 消息体压缩 (>4KB 时)
                    │       │       ├── 执行 SendMessageHook(before)
                    │       │       ├── 构造 SendMessageRequestHeader
                    │       │       ├── MQClientAPIImpl.sendMessage() (Netty)
                    │       │       └── 执行 SendMessageHook(after)
                    │       │
                    │       └── 6. updateFaultItem() 记录延迟/故障
                    │
                    └── 7. 返回 SendResult / 抛出异常
```

### 1.3 关键配置

| 配置 | 默认值 | 含义 |
|------|--------|------|
| sendMsgTimeout | 3000ms | 发送超时 |
| retryTimesWhenSendFailed | 2 | 同步发送失败重试次数 |
| retryTimesWhenSendAsyncFailed | 2 | 异步发送失败重试次数 |
| compressMsgBodyOverHowmuch | 4KB | 消息体超过此大小则压缩 |
| maxMessageSize | 4MB | 最大消息体 |
| defaultTopicQueueNums | 4 | 自动创建 topic 的默认队列数 |
| sendLatencyFaultEnable | false | 是否启用延迟故障容错 |
| retryAnotherBrokerWhenNotStoreOK | false | 存储非OK时是否重试其他Broker |

---

## 2. 消息存储: 存在哪里, 如何存储

### 2.1 存储架构总览

RocketMQ 采用 **"一写多读"** 的存储架构：所有消息**顺序追加写入 CommitLog**，再异步构建多个逻辑索引（ConsumeQueue、IndexFile）。

```
                    ┌─────────────────────────────────────────┐
                    │           存储目录 (默认 ~/store)         │
                    │                                         │
                    │  commitlog/                             │
                    │    └── 00000000000000000000 (1GB/个)    │  ← 消息实体
                    │                                         │
                    │  consumequeue/                          │
                    │    └── {topic}/{queueId}/               │  ← 逻辑队列索引
                    │         └── 00000000000000000000        │    (20字节/条)
                    │                                         │
                    │  index/                                 │  ← 消息索引(按key查询)
                    │  config/                                │  ← 配置/offset
                    │  abort                                  │
                    │  checkpoint                             │
                    └─────────────────────────────────────────┘
```

| 存储文件 | 作用 | 特点 |
|---------|------|------|
| **CommitLog** | 消息实体 | 所有消息顺序追加，文件固定1GB，整个Broker共享 |
| **ConsumeQueue** | 逻辑队列 | 每个 topic 每个 queueId 一个目录，固定20字节/条 |
| **IndexFile** | 二级索引 | 按消息key/时间查询，固定500万条/文件 |

### 2.2 CommitLog 写入流程

**核心原则：顺序追加写，刷盘批量写。**

```
DefaultMessageStore.asyncPutMessage(msg)
    │
    └── CommitLog.asyncPutMessage(msg)
            │
            ├── 1. 消息预处理
            │   ├── 设置 storeTimestamp 存储时间戳
            │   ├── 计算消息体 CRC32
            │   └── 根据topic长度选择消息版本 V1/V2
            │
            ├── 2. 主从副本数检查 (needAckNums / inSyncReplicas)
            │
            ├── 3. 获取队列偏移量
            │   ├── topicQueueLock.lock()  (topic+queueId 细粒度锁)
            │   └── assignOffset(msg) 分配 ConsumeQueue 偏移量
            │
            ├── 4. 写入 CommitLog
            │   ├── putMessageLock.lock() (写入锁)
            │   ├── 获取/创建 MappedFile
            │   ├── mappedFile.appendMessage() → doAppend()
            │   │       ├── 计算物理偏移量 (fileFromOffset + position)
            │   │       ├── 事务消息 queueOffset置0 (不进CQ)
            │   │       ├── 写入消息体到 MappedByteBuffer
            │   │       └── 处理 END_OF_FILE (文件满则新建)
            │   └── putMessageLock.unlock()
            │
            ├── 5. increaseOffset() 更新队列偏移量
            │
            └── 6. handleDiskFlushAndHA()
                ├── handleDiskFlush():
                │   ├── 同步刷盘: GroupCommitService 等待落盘
                │   └── 异步刷盘: 唤醒刷盘线程 (默认500ms间隔)
                └── handleHA():
                    ├── 同步复制: Master 等待 Slave 确认
                    └── 异步复制: 不等待
```

### 2.3 ConsumeQueue 构建 (异步分发)

CommitLog 写入成功后，**ReputMessageService** 后台线程异步构建 ConsumeQueue：

```
ReputMessageService (后台线程)
    │
    ├── 从 CommitLog 读取新追加的消息
    ├── 解析为 DispatchRequest
    └── doDispatch(req)
        └── 遍历 CommitLogDispatcher 列表
            ├── BuildConsumeQueueThread → ConsumeQueue.putMessagePositionInfoWrapper()
            │       └── putMessagePositionInfo()
            │           └── 写入 20 字节条目:
            │               [CommitLog物理偏移量(8B)]
            │               + [消息体大小(4B)]
            │               + [Tag Hash码(8B)]
            └── BuildIndexThread → 构建 IndexFile (按消息key)
```

**ConsumeQueue 条目格式 (20字节):**

```
┌──────────────────────┬──────────────┬──────────────────┐
│ CommitLog物理偏移量(8B)│ 消息体大小(4B) │ Tag HashCode(8B) │
└──────────────────────┴──────────────┴──────────────────┘
```

### 2.4 消费者如何读取

```
消费者 Pull (topic=X, queueId=0, offset=N)
    │
    ├── 1. 访问 ConsumeQueue: consumequeue/X/0/ 文件
    │       └── 定位到第 N 条记录 (N * 20 字节偏移)
    │
    ├── 2. 取出 CommitLog物理偏移量 + 消息体大小
    │
    └── 3. 到 CommitLog 对应物理偏移量读取消息实体
```

> **关键**：消息实体只存一份在 CommitLog，ConsumeQueue 只是"指针"。这就是为什么"一写多读"——一个主题多个队列都是指向同一份 CommitLog 数据的索引。

### 2.5 刷盘策略

| 策略 | 配置 | 说明 |
|------|------|------|
| **异步刷盘** | ASYNC_FLUSH (默认) | 写入内存 MappedByteBuffer 即返回，后台每500ms刷盘 |
| **同步刷盘** | SYNC_FLUSH | 写入后等待 GroupCommitService 确认落盘才返回 |

| 复制策略 | 配置 | 说明 |
|---------|------|------|
| **异步复制** | ASYNC_MASTER (默认) | Master 写入后不等待 Slave，吞吐高，可能丢消息 |
| **同步复制** | SYNC_MASTER | Master 写入后等待 inSyncReplicas 个 Slave 确认才返回 |

---

## 3. 多队列如何选择实际队列

### 3.1 核心疑问

> 发送端拿到 `TopicPublishInfo`（包含该 topic 的所有队列），如何决定一条消息落到**哪个队列**？

### 3.2 默认策略: 轮询（Round Robin）+ 故障容错

**入口**: `DefaultMQProducerImpl.sendDefaultImpl()`

```java
// 每次重试循环中选择队列
MessageQueue mq = mqFaultStrategy.selectOneMessageQueue(tpInfo, lastBrokerName, resetIndex);
```

**`MQFaultStrategy.selectOneMessageQueue()`:**

```java
public MessageQueue selectOneMessageQueue(TopicPublishInfo tpInfo, String lastBrokerName, boolean resetIndex) {
    if (this.sendLatencyFaultEnable) {
        // 启用延迟故障容错
        if (resetIndex) tpInfo.resetIndex();
        // 1. 优先: 可用且非上次Broker
        MessageQueue mq = tpInfo.selectOneMessageQueue(availableFilter, brokerFilter);
        if (mq == null) {
            // 2. 其次: 可达且非上次Broker
            mq = tpInfo.selectOneMessageQueue(reachableFilter, brokerFilter);
            if (mq == null) {
                // 3. 兜底: 纯轮询
                mq = tpInfo.selectOneMessageQueue();
            }
        }
        return mq;
    } else {
        // 未启用故障容错: 轮询 + 排除上次Broker
        MessageQueue mq = tpInfo.selectOneMessageQueue(brokerFilter);
        if (mq == null) {
            mq = tpInfo.selectOneMessageQueue();
        }
        return mq;
    }
}
```

**`TopicPublishInfo.selectOneMessageQueue(QueueFilter...)`:**

```java
public MessageQueue selectOneMessageQueue(QueueFilter... filter) {
    for (int i = 0; i < this.messageQueueList.size(); i++) {
        // 递增 sendWhichQueue (每线程独立的轮询计数器)
        int index = this.sendWhichQueue.incrementAndGet();
        int pos = Math.abs(index) % this.messageQueueList.size();
        MessageQueue mq = this.messageQueueList.get(pos);
        // 通过所有过滤器则选中
        if (filter == null || filterOK(mq, filter)) {
            return mq;
        }
    }
    return null;
}
```

### 3.3 关键点: 轮询计数器是线程级独立的

`TopicPublishInfo.sendWhichQueue` 类型为 **`ThreadLocalIndex`**：

```java
// 每个发送线程有独立的计数器
private final ThreadLocalIndex sendWhichQueue = new ThreadLocalIndex();
```

- 每个线程独立维护自己的轮询索引
- 不同线程并发发送时，各自轮询，互不干扰
- 这样在多线程生产者下，消息会均匀分布到所有队列

### 3.4 队列选择流程图

```
TopicPublishInfo (所有队列: [Q0, Q1, Q2, Q3] 分布在 B1, B2 两个Broker)
    │
    ├── 线程级轮询计数器自增
    │   └── index++ → pos = index % size
    │
    ├── [启用延迟故障容错时]
    │   ├── 优先选"可用"队列 (未隔离, 非上次Broker)
    │   ├── 其次选"可达"队列 (非上次Broker)
    │   └── 最后纯轮询
    │
    └── 选中一个 MessageQueue「topic + brokerName + queueId」
            │
            └── sendKernelImpl() 发送到该队列
```

### 3.5 延迟故障容错 (sendLatencyFaultEnable)

当某 Broker 发送延迟高时，自动隔离一段时间：

```java
// 延迟阈值 → 不可用时长映射
latencyMax          = {50, 100, 550, 1800, 3000, 5000, 15000}  (ms)
notAvailableDuration= {0,   0,  2000, 5000, 6000, 10000, 30000} (ms)

// 例如: 某次发送延迟 600ms
// 落在 [550, 1800) → 该 Broker 被隔离 5000ms
```

- 隔离期内不再向该 Broker 的队列发送
- 通过 `ServiceDetector` 定期探测恢复情况
- 平衡了负载均衡与故障规避

### 3.6 有序消息的队列选择

如果是**顺序消息**（`MessageQueueSelector`），则不走轮询：

```java
// 用户自定义 selector
MessageQueue mq = producer.selectOneMessageQueue(tpInfo, msg, selector, arg);
// 例如: 按订单ID取模 → 同一订单进入同一队列
MessageQueue mq = selector.select(mqs, msg, arg);
```

用户可指定 `HashMessageQueueSelector`（按 key 哈希到固定队列）等，保证同一业务 key 的消息进入同一队列以保序。

---

## 4. 定时/延迟消息的存储与消费

### 4.1 两种定时消息机制

RocketMQ 5.x 提供**两套**定时消息方案：

| 方案 | 精度 | 实现 | 触发方式 |
|------|------|------|---------|
| **传统延迟级别** | 固定18档 | ScheduleMessageService + SCHEDULE_TOPIC_XXXX | 设置 `delayTimeLevel` |
| **定时消息(时间轮)** | 秒级/毫秒级 | TimerMessageStore + TimerWheel | 设置 `TIME_DELIVER_MS` 等属性 |

### 4.2 传统延迟级别方案（延迟消息）

#### 发送端

```java
Message msg = new Message("TopicTest", "TagA", "Hello".getBytes());
// 设置延迟级别 3 → 10秒后投递
msg.setDelayTimeLevel(3);
producer.send(msg);
```

#### Broker 端存储重定向

消息到达 Broker 后，`HookUtils.handleScheduleMessage()` 在持久化前改写消息：

```java
// HookUtils.transformDelayLevelMessage()
// 1. 将 topic 改写为 SCHEDULE_TOPIC_XXXX
// 2. 将 queueId 改写为 (delayLevel - 1)
// 3. 备份原 topic → PROPERTY_REAL_TOPIC
// 4. 备份原 queueId → PROPERTY_REAL_QUEUE_ID
```

**因此延迟消息实际上不进入原 topic 的队列，而是进入 `SCHEDULE_TOPIC_XXXX` 的 `(delayLevel-1)` 队列。**

```
原消息: Topic=MyTopic, QueueId=? delayLevel=3(10s)
    │
    ↓ HookUtils.transformDelayLevelMessage()
    │
改写后: Topic=SCHEDULE_TOPIC_XXXX, QueueId=2
    │    + 属性: DELAY=3, REAL_TOPIC=MyTopic, REAL_QID=?
    │
    ↓ 写入 CommitLog + ConsumeQueue(SCHEDULE_TOPIC_XXXX/2)
```

#### 延迟级别表

```java
// MessageStoreConfig.messageDelayLevel
private String messageDelayLevel = "1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h";
// 共18档: 级别1=1s ... 级别18=2h
```

#### 到期投递 (ScheduleMessageService)

```
ScheduleMessageService
    │
    ├── 为每个延迟级别启动一个 DeliverDelayedMessageTimerTask
    │
    └── DeliverDelayedMessageTimerTask.run()
        │
        ├── 遍历 SCHEDULE_TOPIC_XXXX/{level-1} 的 ConsumeQueue
        │
        ├── 读取消息的 tagsCode (编码了交付时间戳)
        │
        ├── messageTimeUp()? → 到期
        │       └── 通过 EscapeBridge / messageStore.putMessage()
        │           将消息重新写回原 topic (REAL_TOPIC, REAL_QID)
        │           并清除 DELAY 属性
        │
        └── 未到期 → 重新调度 (推迟到交付时间)
```

**消费端体会**: 消费端完全无感知延迟逻辑，到期后消息被重新写回原队列，消费者正常拉取即可。

### 4.3 定时消息方案（时间轮, 高精度）

#### 发送端

```java
Message msg = new Message("TopicTest", "TagA", "Hello".getBytes());
// 方式1: 绝对交付时间
msg.setDeliveryTimestamp(System.currentTimeMillis() + 10000);
// 或 方式2: 延迟秒数
msg.setDelayTimeSec(10);
// 或 方式3: 延迟毫秒数
msg.setDelayTimeMs(10000);
```

#### 存储重定向

```java
// HookUtils.transformTimerMessage()
// 1. 改写 topic → %wheel_timer (TimerMessageStore.TIMER_TOPIC)
// 2. 备份原 topic → PROPERTY_REAL_TOPIC
// 3. 备份原 queueId → PROPERTY_REAL_QUEUE_ID
// 4. 设置 PROPERTY_TIMER_OUT_MS = 交付时间
```

#### 时间轮存储 (TimerMessageStore + TimerWheel + TimerLog)

```
Producer 发送定时消息
    │
    ↓ (topic 被改写为 %wheel_timer)
    │
CommitLog + ConsumeQueue(%wheel_timer)
    │
    ├── TimerEnqueueGetService 从 ConsumeQueue 读取
    │
    ├── TimerEnqueuePutService → doEnqueue()
    │       ├── TimerLog: 追加 52 字节记录(链表结构)
    │       │    [size|prevPos|magic|currWriteTime|delayedTime|offsetPy|sizePy|topicHash|reserved]
    │       └── TimerWheel: 更新对应时间槽
    │            [delayedTime|firstPos|lastPos|num|magic]  (32字节/槽, 7天环形)
    │
    │   ⏳ 时间推进...
    │
    ├── TimerDequeueGetService 按 currReadTimeMs 推进
    │       └── 从 TimerWheel 定位到期的槽
    │           └── 从 TimerLog 链表中取出消息
    │
    ├── TimerDequeueGetMessageService 从 CommitLog 还原消息体
    │
    └── TimerDequeuePutMessageService → doPut()
            ├── convert() 恢复原 topic/queueId
            ├── 通过 EscapeBridge (跨Broker) 或本地 MessageStore
            └── 写回原 topic → 消费者正常消费
```

**时间轮结构:**

```
TimerWheel: 7天 × 24小时 × 3600秒 = 604800 个槽 (精度1秒)
每个槽: 32字节 [delayedTime|firstPos|lastPos|num|magic]
TimerLog: 52字节/条 链表, 串联同一槽内的消息
```

**三个核心服务线程:**

| 服务 | 职责 |
|------|------|
| TimerEnqueueGetService/PutService | 将定时消息从 CommitLog 灌入 TimerLog + TimerWheel |
| TimerDequeueGetService | 按时间推进，扫描到期槽位 |
| TimerDequeuePutMessageService | 到期消息写回原 topic |

### 4.4 两种方案的对比总结

| 维度 | 传统延迟级别 | 定时消息(时间轮) |
|------|------------|----------------|
| 精度 | 固定18档(1s~2h) | 秒级/毫秒级 |
| 存储 | SCHEDULE_TOPIC_XXXX 队列 | TimerLog + TimerWheel |
| 投递 | ScheduleMessageService 定时扫描 | TimerMessageStore 时间轮推进 |
| 适用 | 大多数延迟场景 | 高精度定时 |
| 配置开关 | 默认启用 | timerWheelEnable=true(默认) |

> 注意：若同时设置了 `delayTimeLevel` 和定时器属性，**传统延迟级别优先**，定时器属性被清除。

---

## 5. 总结与架构图

### 5.1 完整端到端链路

```
┌─────────────────────────────────────────────────────────────────┐
│  DefaultMQProducer                                              │
│    send() → sendDefaultImpl() → MQFaultStrategy.selectOneMQ()   │
│    → sendKernelImpl() → Netty 发送                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 消息 (topic, queueId, body)
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Broker: SendMessageProcessor                                  │
│    → HookUtils.handleScheduleMessage()  ← 定时/延迟改写          │
│    → DefaultMessageStore.putMessage()                          │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  MessageStore (存储层)                                          │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ CommitLog: 所有消息顺序追加写入 (文件1GB)                 │    │
│  │   └── 刷盘(异步/同步) + 主从复制(异步/同步)               │    │
│  │                                                         │    │
│  │ ReputMessageService (异步) → 构建逻辑索引                 │    │
│  │   ├── ConsumeQueue: {topic}/{queueId}/ 20字节/条         │    │
│  │   └── IndexFile: 按消息key索引                           │    │
│  │                                                         │    │
│  │ [定时消息] ScheduleMessageService / TimerMessageStore    │    │
│  │   └── 到期后写回原topic                                  │    │
│  └─────────────────────────────────────────────────────────┘    │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  消费者 (见 README.md)                                          │
│    Pull(topic, queueId, offset) → ConsumeQueue 定位              │
│    → CommitLog 读取消息实体 → 消费                               │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 三个核心疑问的答案

**1. 消息存到哪里，如何存储？**
- 所有消息**顺序追加**写入共享的 **CommitLog** 文件（1GB/个）
- 异步构建 **ConsumeQueue**（逻辑队列索引，20字节/条）和 **IndexFile**（二级索引）
- 采用"一写多读"：实体只存一份，队列只是指针
- 支持异步/同步刷盘，异步/同步主从复制

**2. 多队列如何选择实际队列？**
- 默认**轮询**（Round Robin），轮询计数器是**线程级独立**的（ThreadLocalIndex）
- 可选**延迟故障容错**（sendLatencyFaultEnable=true）：隔离高延迟 Broker，优先选可用队列
- **顺序消息**走用户自定义 `MessageQueueSelector`（如按 key 哈希）

**3. 定时/延迟消息如何存储和消费？**
- **传统延迟级别**：消息改写进 `SCHEDULE_TOPIC_XXXX/{level-1}` 队列，`ScheduleMessageService` 定时扫描到期后写回原 topic
- **定时消息(时间轮)**：消息改写进 `%wheel_timer`，`TimerMessageStore` 用 TimerWheel + TimerLog 实现秒级/毫秒级精确调度，到期写回原 topic
- **消费端完全无感知**：到期后消息被重新写回原队列，消费者正常拉取

### 5.3 关键文件索引

| 文件 | 路径 | 核心内容 |
|------|------|---------|
| DefaultMQProducer.java | client/producer/ | 生产者入口，配置 |
| DefaultMQProducerImpl.java | client/impl/producer/ | sendDefaultImpl, sendKernelImpl |
| MQFaultStrategy.java | client/latency/ | 队列选择 + 故障容错 |
| TopicPublishInfo.java | client/impl/producer/ | 路由信息 + 轮询 |
| CommitLog.java | store/ | 消息顺序追加写入 |
| ConsumeQueue.java | store/ | 逻辑队列索引 |
| DefaultMessageStore.java | store/ | 存储总控 |
| MessageStoreConfig.java | store/config/ | 存储配置(延迟级别等) |
| ScheduleMessageService.java | broker/schedule/ | 传统延迟调度 |
| TimerMessageStore.java | store/timer/ | 定时消息时间轮 |
| TimerWheel.java | store/timer/ | 时间轮 |
| TimerLog.java | store/timer/ | 定时日志 |
| HookUtils.java | broker/util/ | 延迟/定时消息改写 |
| EscapeBridge.java | broker/failover/ | 跨Broker投递 |