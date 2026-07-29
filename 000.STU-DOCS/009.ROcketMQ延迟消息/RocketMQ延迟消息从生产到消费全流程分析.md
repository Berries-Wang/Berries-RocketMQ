# RocketMQ 延迟消息从生产到消费全流程分析

> 前置阅读: [RocketMQ 消息集成：多类型业务消息——定时消息.pdf](./RocketMQ%20消息集成：多类型业务消息——定时消息.pdf)、[定时_延时消息 _ RocketMQ.pdf](./定时_延时消息%20_%20RocketMQ.pdf)

---

## 1. 概述

RocketMQ 5.3.1 中存在**两套独立的延迟消息机制**，两者并行运行，通过配置开关控制：

| 特性 | 传统延迟级别消息 | 定时消息（Timer Message） |
|------|-----------------|--------------------------|
| **核心类** | `ScheduleMessageService` | `TimerMessageStore` |
| **系统主题** | `SCHEDULE_TOPIC_XXXX` | `%wheel_timer` |
| **精度** | 秒级（18个固定级别） | 毫秒级（可配置100ms/200ms/500ms/1000ms） |
| **存储结构** | 基于 ConsumeQueue + CommitLog | 基于时间轮（TimerWheel）+ TimerLog + CommitLog |
| **配置开关** | 默认开启 | `timerWheelEnable=true`（默认开启） |
| **延迟范围** | 1s ~ 2h（18个级别） | 最大3天（可配置`timerMaxDelaySec`） |

---

## 2. 生产者发送延迟消息

### 2.1 客户端 API

**传统延迟级别：**
```java
// 设置延迟级别，level=3 对应 10s（1s 5s 10s 30s 1m 2m ...）
message.setDelayTimeLevel(3);
```

**定时消息（5.0+）：**
```java
// 方式1：延迟10秒后投递
message.setDelayTimeSec(10);

// 方式2：延迟10000毫秒后投递
message.setDelayTimeMs(10_000L);

// 方式3：指定绝对投递时间戳
message.setDeliverTimeMs(System.currentTimeMillis() + 10_000L);
```

### 2.2 Broker 端消息处理入口

消息到达 Broker 后，`SendMessageProcessor` 处理消息写入请求。Broker 在 `putMessage` 之前注册了 `PutMessageHook` 列表，其中 `handleScheduleMessage` 负责拦截并转换延迟消息。

**源码：BrokerController.java**（第962-975行）
```java
putMessageHookList.add(new PutMessageHook() {
    @Override
    public String hookName() {
        return "handleScheduleMessage";
    }

    @Override
    public PutMessageResult executeBeforePutMessage(MessageExt msg) {
        if (msg instanceof MessageExtBrokerInner) {
            return HookUtils.handleScheduleMessage(BrokerController.this, 
                (MessageExtBrokerInner) msg);
        }
        return null;
    }
});
```

### 2.3 HookUtils 调度逻辑

**源码：HookUtils.java**（第129-151行）—— `handleScheduleMessage` 方法
```java
public static PutMessageResult handleScheduleMessage(BrokerController brokerController,
    final MessageExtBrokerInner msg) {
    final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());
    if (tranType == MessageSysFlag.TRANSACTION_NOT_TYPE
        || tranType == MessageSysFlag.TRANSACTION_COMMIT_TYPE) {
        if (!isRolledTimerMessage(msg)) {
            if (checkIfTimerMessage(msg)) {
                if (!brokerController.getMessageStoreConfig().isTimerWheelEnable()) {
                    //wheel timer is not enabled, reject the message
                    return new PutMessageResult(PutMessageStatus.WHEEL_TIMER_NOT_ENABLE, null);
                }
                PutMessageResult transformRes = transformTimerMessage(brokerController, msg);
                if (null != transformRes) {
                    return transformRes;
                }
            }
        }
        // Delay Delivery
        if (msg.getDelayTimeLevel() > 0) {
            transformDelayLevelMessage(brokerController, msg);
        }
    }
    return null;
}
```

**处理优先级：**
1. 先检查是否为**定时消息**（设置了 `PROPERTY_TIMER_DELIVER_MS` / `PROPERTY_TIMER_DELAY_MS` / `PROPERTY_TIMER_DELAY_SEC`）
2. 再检查是否为**传统延迟级别消息**（`delayTimeLevel > 0`）
3. **注意**：如果同时设置了延迟级别和定时属性，`checkIfTimerMessage` 会清除定时属性，优先使用延迟级别（见第159-168行）

---

## 3. 传统延迟级别消息（ScheduleMessageService）全流程

### 3.1 延迟级别定义

默认18个延迟级别，在 `MessageStoreConfig` 中定义：

```java
private String messageDelayLevel = "1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h";
```

| Level | 延迟时间 | Level | 延迟时间 | Level | 延迟时间 |
|-------|---------|-------|---------|-------|---------|
| 1 | 1s | 7 | 3m | 13 | 9m |
| 2 | 5s | 8 | 4m | 14 | 10m |
| 3 | 10s | 9 | 5m | 15 | 20m |
| 4 | 30s | 10 | 6m | 16 | 30m |
| 5 | 1m | 11 | 7m | 17 | 1h |
| 6 | 2m | 12 | 8m | 18 | 2h |

### 3.2 消息转换（transformDelayLevelMessage）

**源码：HookUtils.java**（第222-235行）
```java
public static void transformDelayLevelMessage(BrokerController brokerController, 
    MessageExtBrokerInner msg) {
    if (msg.getDelayTimeLevel() > brokerController.getScheduleMessageService().getMaxDelayLevel()) {
        msg.setDelayTimeLevel(brokerController.getScheduleMessageService().getMaxDelayLevel());
    }
    // 备份真实 topic、queueId
    MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_TOPIC, msg.getTopic());
    MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_QUEUE_ID, 
        String.valueOf(msg.getQueueId()));
    msg.setPropertiesString(MessageDecoder.messageProperties2String(msg.getProperties()));

    // 将 topic 改为 SCHEDULE_TOPIC_XXXX，queueId 改为 delayLevel - 1
    msg.setTopic(TopicValidator.RMQ_SYS_SCHEDULE_TOPIC);
    msg.setQueueId(ScheduleMessageService.delayLevel2QueueId(msg.getDelayTimeLevel()));
}
```

**关键逻辑：**
1. 将原始 topic 备份到 `PROPERTY_REAL_TOPIC` 属性中
2. 将原始 queueId 备份到 `PROPERTY_REAL_QUEUE_ID` 属性中
3. 将消息的 topic 改为 `SCHEDULE_TOPIC_XXXX`
4. 将消息的 queueId 改为 `delayLevel - 1`（level 3 → queueId 2）

这样消息被写入 CommitLog 后，会被分发到 `SCHEDULE_TOPIC_XXXX` 主题的特定队列中，等待延迟时间到达后被投递。

### 3.3 ScheduleMessageService 启动

**源码：ScheduleMessageService.java**（第134-165行）—— `start` 方法
```java
public void start() {
    if (started.compareAndSet(false, true)) {
        this.load();  // 加载延迟配置和偏移量
        this.deliverExecutorService = ThreadUtils.newScheduledThreadPool(
            this.maxDelayLevel, ...);
        // 为每个延迟级别启动一个 DeliverDelayedMessageTimerTask
        for (Map.Entry<Integer, Long> entry : this.delayLevelTable.entrySet()) {
            Integer level = entry.getKey();
            Long timeDelay = entry.getValue();
            Long offset = this.offsetTable.get(level);
            if (null == offset) {
                offset = 0L;
            }
            if (timeDelay != null) {
                this.deliverExecutorService.schedule(
                    new DeliverDelayedMessageTimerTask(level, offset), 
                    FIRST_DELAY_TIME, TimeUnit.MILLISECONDS);
            }
        }
        // 定期持久化偏移量
        scheduledPersistService.scheduleAtFixedRate(() -> {
            ScheduleMessageService.this.persist();
        }, 10000, flushDelayOffsetInterval, TimeUnit.MILLISECONDS);
    }
}
```

每个延迟级别都有一个独立的 `DeliverDelayedMessageTimerTask` 线程，负责从该级别的 ConsumeQueue 中读取消息并投递。

### 3.4 延迟投递核心逻辑

**源码：ScheduleMessageService.java**（第399-487行）—— `DeliverDelayedMessageTimerTask.executeOnTimeUp`
```java
public void executeOnTimeUp() {
    ConsumeQueueInterface cq = ...getConsumeQueue(
        TopicValidator.RMQ_SYS_SCHEDULE_TOPIC, delayLevel2QueueId(delayLevel));
    // ... 从 offset 开始迭代 ConsumeQueue
    while (bufferCQ.hasNext() && isStarted()) {
        CqUnit cqUnit = bufferCQ.next();
        long tagsCode = cqUnit.getTagsCode();  // tagsCode 存储的是投递时间戳
        long now = System.currentTimeMillis();
        long deliverTimestamp = this.correctDeliverTimestamp(now, tagsCode);
        long countdown = deliverTimestamp - now;
        if (countdown > 0) {
            // 时间未到，调度下一次检查（100ms后）
            this.scheduleNextTimerTask(currOffset, DELAY_FOR_A_WHILE);
            return;
        }
        // 时间已到，从 CommitLog 读取完整消息
        MessageExt msgExt = ...lookMessageByOffset(offsetPy, sizePy);
        // 还原真实 topic 和 queueId
        MessageExtBrokerInner msgInner = messageTimeUp(msgExt);
        // 投递到真实 topic
        deliverSuc = syncDeliver(msgInner, ...);
    }
}
```

**延迟的核心机制：**

`tagsCode` 字段在 ConsumeQueue 中存储的是**投递时间戳**（绝对时间），计算公式为：

```java
public long computeDeliverTimestamp(final int delayLevel, final long storeTimestamp) {
    Long time = this.delayLevelTable.get(delayLevel);
    if (time != null) {
        return time + storeTimestamp;  // 存储时间 + 延迟级别对应的时间
    }
    return storeTimestamp + 1000;
}
```

`DeliverDelayedMessageTimerTask` 不断循环检查：
- 如果 `countdown > 0`（当前时间 < 投递时间），则休眠 100ms 后再次检查
- 如果 `countdown <= 0`（当前时间 >= 投递时间），则执行投递

### 3.5 消息还原与投递

**源码：ScheduleMessageService.java**（第333-363行）—— `messageTimeUp` 方法
```java
private MessageExtBrokerInner messageTimeUp(MessageExt msgExt) {
    MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
    // ... 复制消息体、flag、属性等
    // 清除延迟相关属性
    MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_DELAY_TIME_LEVEL);
    // 将 topic 还原为真实 topic
    msgInner.setTopic(msgInner.getProperty(MessageConst.PROPERTY_REAL_TOPIC));
    // 将 queueId 还原为真实 queueId
    String queueIdStr = msgInner.getProperty(MessageConst.PROPERTY_REAL_QUEUE_ID);
    int queueId = Integer.parseInt(queueIdStr);
    msgInner.setQueueId(queueId);
    return msgInner;
}
```

### 3.6 传统延迟消息全流程总结

```
Producer.send(message.setDelayTimeLevel(3))
  │
  ▼
Broker SendMessageProcessor
  │
  ▼
HookUtils.handleScheduleMessage()
  │
  ├─ checkIfTimerMessage() → 检查是否定时消息（否，因为有 delayTimeLevel）
  │
  ▼
transformDelayLevelMessage()
  │  ├─ 备份 PROPERTY_REAL_TOPIC = 原始Topic
  │  ├─ 备份 PROPERTY_REAL_QUEUE_ID = 原始QueueId
  │  └─ 设置 topic = SCHEDULE_TOPIC_XXXX, queueId = delayLevel - 1
  │
  ▼
写入 CommitLog → 分发到 SCHEDULE_TOPIC_XXXX 的 ConsumeQueue
  │
  ▼  (等待延迟时间到达)
ScheduleMessageService.DeliverDelayedMessageTimerTask
  │  ├─ 读取 ConsumeQueue，获取 tagsCode（投递时间戳）
  │  └─ compare: deliverTimestamp > now ? 等待 : 投递
  │
  ▼
messageTimeUp() → 还原真实 topic 和 queueId
  │
  ▼
写入 CommitLog（真实 Topic）→ 消费者消费（同普通消息）
```

---

## 4. 定时消息（TimerMessageStore）全流程

### 4.1 时间轮架构

定时消息基于**时间轮（TimerWheel）** 实现，其核心设计如下：

**TimerWheel 数据结构：**
- 固定 `7天 × 86400秒 = 604800` 个槽位（slot）
- 每个槽位 Slot 32 字节：`timeMs(8) + firstPos(8) + lastPos(8) + num(4) + magic(4)`
- 槽位数组大小为 `slotsTotal × 2 × SIZE`，支持双倍索引范围
- 基于 mmap 文件映射（`timerwheel` 文件），持久化到磁盘

**源码：Slot.java** —— 时间槽数据结构
```java
/**
 * ┌────────────┬───────────┬───────────┬───────────┬───────────┐
 * │delayed time│ first pos │ last pos  │    num    │   magic   │
 * ├────────────┼───────────┼───────────┼───────────┼───────────┤
 * │   8bytes   │   8bytes  │  8bytes   │   4bytes  │   4bytes  │
 * └────────────┴───────────┴───────────┴───────────┴───────────┘
 */
public class Slot {
    public static final short SIZE = 32;
    public final long timeMs;   // 延迟时间
    public final long firstPos; // 链表中第一个 TimerLog 条目的位置
    public final long lastPos;  // 链表中最后一个 TimerLog 条目的位置
    public final int num;       // 该槽位中的消息数量
    public final int magic;     // 保留字段
}
```

**TimerLog 数据结构：**
每条记录固定 44 字节（`UNIT_SIZE`）：

```java
public final static int UNIT_SIZE = 4   // size（本条记录大小）
    + 8   // prev pos（链表前驱指针）
    + 4   // magic value（魔法值：1=正常, 2=滚动, 4=删除）
    + 8   // curr write time（写入时间）
    + 4   // delayed time（延迟相对时间 = 投递时间 - 写入时间）
    + 8   // offsetPy（CommitLog 中的物理偏移量）
    + 4   // sizePy（消息体大小）
    + 4   // hash code of real topic（真实 topic 的 hash 值）
    + 8;  // reserved（保留字段）
```

### 4.2 定时消息转换（transformTimerMessage）

**源码：HookUtils.java**（第179-220行）—— `transformTimerMessage` 方法
```java
private static PutMessageResult transformTimerMessage(BrokerController brokerController,
    MessageExtBrokerInner msg) {
    // 1. 计算投递时间戳（绝对时间）
    long deliverMs;
    if (msg.getProperty(MessageConst.PROPERTY_TIMER_DELAY_SEC) != null) {
        // 相对延迟秒数：当前时间 + 延迟秒数 * 1000
        deliverMs = System.currentTimeMillis() + 
            Long.parseLong(msg.getProperty(MessageConst.PROPERTY_TIMER_DELAY_SEC)) * 1000;
    } else if (msg.getProperty(MessageConst.PROPERTY_TIMER_DELAY_MS) != null) {
        // 相对延迟毫秒数：当前时间 + 延迟毫秒数
        deliverMs = System.currentTimeMillis() + 
            Long.parseLong(msg.getProperty(MessageConst.PROPERTY_TIMER_DELAY_MS));
    } else {
        // 绝对投递时间戳
        deliverMs = Long.parseLong(msg.getProperty(MessageConst.PROPERTY_TIMER_DELIVER_MS));
    }
    
    // 2. 检查是否超过最大延迟时间（默认3天）
    if (deliverMs - System.currentTimeMillis() > timerMaxDelaySec * 1000L) {
        return PutMessageStatus.WHEEL_TIMER_MSG_ILLEGAL;
    }
    
    // 3. 按精度对齐时间（timerPrecisionMs=1000ms 时，对齐到秒）
    int timerPrecisionMs = brokerController.getMessageStoreConfig().getTimerPrecisionMs();
    deliverMs = deliverMs / timerPrecisionMs * timerPrecisionMs;
    
    // 4. 存储属性转换
    MessageAccessor.putProperty(msg, PROPERTY_TIMER_OUT_MS, deliverMs + "");
    MessageAccessor.putProperty(msg, PROPERTY_REAL_TOPIC, msg.getTopic());
    MessageAccessor.putProperty(msg, PROPERTY_REAL_QUEUE_ID, String.valueOf(msg.getQueueId()));
    msg.setTopic(TimerMessageStore.TIMER_TOPIC);  // 改为 %wheel_timer
    msg.setQueueId(0);
}
```

### 4.3 TimerMessageStore 内部流水线架构

TimerMessageStore 内部定义了**7个服务线程**，构成一条完整的处理流水线：

```
                    ┌──────────────────────────────────┐
                    │           CommitLog               │
                    │  topic=%wheel_timer, queueId=0    │
                    └──────────┬───────────────────────┘
                               │
                    ┌──────────▼───────────────────────┐
                    │ ① TimerEnqueueGetService         │
                    │ 从 ConsumeQueue 读取%wheel_timer  │
                    │ 放入 enqueuePutQueue              │
                    └──────────┬───────────────────────┘
                               │
                    ┌──────────▼───────────────────────┐
                    │ ② TimerEnqueuePutService         │
                    │ 写入 TimerLog + TimerWheel       │
                    │ ※ 核心：将延迟信息写入时间轮      │
                    └──────────┬───────────────────────┘
                               │
                    ┌──────────▼───────────────────────┐
                    │ ③ TimerDequeueWarmService        │
                    │ 预读取数据到 page cache           │
                    └──────────┬───────────────────────┘
                               │
                    ┌──────────▼───────────────────────┐
                    │ ④ TimerDequeueGetService         │
                    │ 扫描当前时间槽位，读取 TimerLog   │
                    │ 放入 dequeueGetQueue              │
                    └──────────┬───────────────────────┘
                               │
                    ┌──────────▼───────────────────────┐
                    │ ⑤ TimerDequeueGetMessageService  │
                    │ 从 CommitLog 读取完整消息体       │
                    │ 放入 dequeuePutQueue              │
                    └──────────┬───────────────────────┘
                               │
                    ┌──────────▼───────────────────────┐
                    │ ⑥ TimerDequeuePutMessageService  │
                    │ 将消息还原并写回 CommitLog        │
                    │ ※ 投递到真实 topic               │
                    └──────────┬───────────────────────┘
                               │
                    ┌──────────▼───────────────────────┐
                    │ ⑦ TimerFlushService              │
                    │ 定期刷盘：TimerLog + TimerWheel   │
                    │ + TimerCheckpoint                 │
                    └──────────────────────────────────┘
```

### 4.4 入队流程详解

#### 4.4.1 TimerEnqueueGetService：从 ConsumeQueue 获取消息

```java
// TimerMessageStore.java 第1291-1312行
public class TimerEnqueueGetService extends ServiceThread {
    @Override
    public void run() {
        while (!this.isStopped()) {
            if (!TimerMessageStore.this.enqueue(0)) {
                waitForRunning(100L * precisionMs / 1000);
            }
        }
    }
}
```

`enqueue(0)` 方法（第642-722行）从 `%wheel_timer` 主题的 ConsumeQueue 读取消息，提取 `PROPERTY_TIMER_OUT_MS`（投递时间戳），封装为 `TimerRequest` 放入 `enqueuePutQueue`。

#### 4.4.2 TimerEnqueuePutService：写入 TimerLog 和 TimerWheel

```java
// TimerMessageStore.java 第1355-1376行
protected void putMessageToTimerWheel(TimerRequest req) {
    if (shouldRunningDequeue && req.getDelayTime() < currWriteTimeMs) {
        // 已过期，直接放入出队队列（无需入队）
        req.setEnqueueTime(Long.MAX_VALUE);
        dequeuePutQueue.put(req);
    } else {
        // 写入 TimerLog 和 TimerWheel
        doEnqueue(req.getOffsetPy(), req.getSizePy(), req.getDelayTime(), req.getMsg());
    }
}
```

`doEnqueue` 方法（第724-765行）是**延迟的核心**：

```java
public boolean doEnqueue(long offsetPy, int sizePy, long delayedTime, MessageExt messageExt) {
    long tmpWriteTimeMs = currWriteTimeMs;
    // 判断是否需要滚动（延迟时间超过滚动窗口）
    boolean needRoll = delayedTime - tmpWriteTimeMs >= (long) timerRollWindowSlots * precisionMs;
    int magic = MAGIC_DEFAULT;
    if (needRoll) {
        magic = magic | MAGIC_ROLL;
        // 调整延迟时间到滚动窗口内
        delayedTime = tmpWriteTimeMs + (long) timerRollWindowSlots * precisionMs;
    }
    
    // 获取时间槽
    Slot slot = timerWheel.getSlot(delayedTime);
    
    // 写入 TimerLog（44字节固定记录）
    ByteBuffer tmpBuffer = timerLogBuffer;
    tmpBuffer.putInt(TimerLog.UNIT_SIZE);   // size
    tmpBuffer.putLong(slot.lastPos);         // prev pos ← 链表前驱指针
    tmpBuffer.putInt(magic);                 // magic
    tmpBuffer.putLong(tmpWriteTimeMs);       // currWriteTime
    tmpBuffer.putInt((int)(delayedTime - tmpWriteTimeMs)); // delayTime
    tmpBuffer.putLong(offsetPy);             // offset（CommitLog 物理偏移）
    tmpBuffer.putInt(sizePy);                // sizePy
    tmpBuffer.putInt(hashTopicForMetrics(realTopic)); // topic hash
    tmpBuffer.putLong(0);                    // reserved
    long ret = timerLog.append(tmpBuffer.array(), 0, TimerLog.UNIT_SIZE);
    
    if (-1 != ret) {
        // 更新时间槽（链表头插法）
        timerWheel.putSlot(delayedTime, 
            slot.firstPos == -1 ? ret : slot.firstPos, ret, 
            isDelete ? slot.num - 1 : slot.num + 1, slot.magic);
    }
    return -1 != ret;
}
```

**时间轮入队示意图：**

```
TimerLog (文件)                            TimerWheel (mmap)
┌──────────────────┐                       ┌─────────────────────────┐
│ Record N         │ ◄── prevPos ──┐        │ Slot[0] : timeMs=...    │
│  offsetPy        │              │        │  firstPos ──┐           │
│  delayTime       │              │        │  lastPos ───┤           │
│  prevPos ────────┼──────┐       │        │  num=3      │           │
├──────────────────┤      │       │        ├─────────────────────────┤
│ Record N-1       │      │       │        │ Slot[1] : timeMs=...    │
│  prevPos ────────┼──┐   │       │        │  firstPos ──────────────┤
├──────────────────┤  │   │       │        │  lastPos ──────────────┤
│ Record N-2       │  │   │       │        │  num=4                  │
│  prevPos = -1    │  │   │       │        └─────────────────────────┘
└──────────────────┘  │   │       │                 ...
                      │   │       │
                      ▼   ▼       ▼
                    (链表：前驱指针串联)
```

**核心机制：**
- TimerLog 中的记录通过 `prevPos` 字段构成**单向链表**
- 时间槽的 `firstPos` 指向链表头，`lastPos` 指向链表尾
- 新记录插入时，`prevPos` 指向原 `lastPos`，然后更新槽的 `lastPos` 为新记录位置
- `delayedTime` 存储的是**相对时间**（`投递时间 - 写入时间`），加上 `currWriteTime` 得到绝对投递时间

### 4.5 出队流程详解

#### 4.5.1 TimerDequeueGetService：扫描时间轮并出队

```java
// TimerMessageStore.java 第1419-1445行
public class TimerDequeueGetService extends ServiceThread {
    @Override
    public void run() {
        while (!this.isStopped()) {
            if (-1 == TimerMessageStore.this.dequeue()) {
                waitForRunning(100L * precisionMs / 1000);
            }
        }
    }
}
```

`dequeue()` 方法（第893-1002行）核心逻辑：

```java
public int dequeue() throws Exception {
    // 1. 当前读取时间已追上写入时间，停止
    if (currReadTimeMs >= currWriteTimeMs) return -1;
    
    // 2. 获取当前时间对应的槽位
    Slot slot = timerWheel.getSlot(currReadTimeMs);
    if (-1 == slot.timeMs) {
        moveReadTime();  // 空槽位，直接推进时间
        return 0;
    }
    
    // 3. 从槽位的 lastPos 开始，沿着 prevPos 链表遍历 TimerLog
    long currOffsetPy = slot.lastPos;
    while (currOffsetPy != -1) {
        // 读取 TimerLog 记录，获取 offsetPy/sizePy/delayedTime 等
        TimerRequest timerRequest = new TimerRequest(offsetPy, sizePy, delayedTime, ...);
        if (needDelete(magic) && !needRoll(magic)) {
            deleteMsgStack.add(timerRequest);  // 删除消息
        } else {
            normalMsgStack.addFirst(timerRequest);  // 正常消息
        }
        currOffsetPy = prevPos;  // 沿链表前驱指针遍历
    }
    
    // 4. 将 TimerRequest 放入 dequeueGetQueue
    dequeueGetQueue.put(deleteList);
    dequeueGetQueue.put(normalList);
    
    // 5. 推进读取时间
    moveReadTime();
}
```

**出队时间推进机制：**
```java
private void moveReadTime() {
    currReadTimeMs = currReadTimeMs + precisionMs;  // 每次推进一个精度单位
    commitReadTimeMs = currReadTimeMs;
}
```

#### 4.5.2 TimerDequeueGetMessageService：从 CommitLog 读取消息

从 `dequeueGetQueue` 获取 `TimerRequest`，根据 `offsetPy` 和 `sizePy` 从 CommitLog 读取完整的 `MessageExt` 消息体，放入 `dequeuePutQueue`。

**特殊处理：**
- **删除消息**：如果 `magic` 包含 `MAGIC_DELETE`，将消息的 `uniqKey` 加入删除列表，后续相同 key 的消息会被跳过
- **滚动消息**：如果 `magic` 包含 `MAGIC_ROLL`，消息会重新入队到下一个时间窗口

#### 4.5.3 TimerDequeuePutMessageService：还原并投递

**源码：TimerMessageStore.java**（第1073-1127行）—— `doPut` 方法
```java
public int doPut(MessageExtBrokerInner message, boolean roll) throws Exception {
    PutMessageResult putMessageResult = messageStore.putMessage(message);
    // 重试机制（最多3次）
    while (retryNum < 3) {
        switch (putMessageResult.getPutMessageStatus()) {
            case PUT_OK: return PUT_OK;
            case SERVICE_NOT_AVAILABLE: return PUT_NEED_RETRY;
            // ...
        }
        Thread.sleep(50);
        putMessageResult = messageStore.putMessage(message);
    }
}
```

**消息还原（convertMessage）：**
```java
// TimerMessageStore.java 第1129-1158行
public MessageExtBrokerInner convertMessage(MessageExt msgExt, boolean needRoll) {
    // ... 复制消息属性
    if (needRoll) {
        // 滚动消息：topic 保持 %wheel_timer，继续在时间轮中等待
        msgInner.setTopic(msgExt.getTopic());
        msgInner.setQueueId(msgExt.getQueueId());
    } else {
        // 正常到期：还原为真实 topic 和 queueId
        msgInner.setTopic(msgInner.getProperty(MessageConst.PROPERTY_REAL_TOPIC));
        msgInner.setQueueId(Integer.parseInt(
            msgInner.getProperty(MessageConst.PROPERTY_REAL_QUEUE_ID)));
    }
    return msgInner;
}
```

---

## 5. 时间轮转算法详解（核心）

这是理解定时消息**"如何延迟"**的关键所在。时间轮并不是一个像时钟那样"自己转动的物理轮子"，而是一种**基于时间索引计算的算法**。

### 5.1 两个关键指针

TimerMessageStore 中维护了两个核心时间指针，它们共同驱动时间轮的运转：

```java
// 写入指针：当前时间轮可以写入的最大时间
// 由 TimerEnqueuePutService 在每次处理完后更新
protected volatile long currWriteTimeMs;

// 读取指针：当前出队扫描到的时间
// 由 TimerDequeueGetService 在每次出队后推进
protected volatile long currReadTimeMs;
```

**关键关系：`currReadTimeMs` 永远追赶 `currWriteTimeMs`。**

- `currWriteTimeMs` 代表"已经入队的最新时间"——它紧跟系统时间，由 `maybeMoveWriteTime()` 驱动
- `currReadTimeMs` 代表"已经出队到的时间"——它由 `TimerDequeueGetService` 以 `precisionMs` 为步长推进

### 5.2 写入指针推进逻辑

```java
// TimerMessageStore.java 第553-557行
protected void maybeMoveWriteTime() {
    if (currWriteTimeMs < formatTimeMs(System.currentTimeMillis())) {
        currWriteTimeMs = formatTimeMs(System.currentTimeMillis());
    }
}
```

`currWriteTimeMs` 在 `TimerEnqueuePutService.fetchAndPutTimerRequest()` 每次处理完一批入队请求后被调用，与系统时间对齐（按精度取整）。

### 5.3 读取指针推进逻辑

```java
// TimerMessageStore.java 第559-562行
private void moveReadTime() {
    currReadTimeMs = currReadTimeMs + precisionMs;  // 每次推进一个精度单位
    commitReadTimeMs = currReadTimeMs;
}
```

`currReadTimeMs` 在 `TimerDequeueGetService.dequeue()` 每次成功处理后推进。如果当前槽位为空，也正常推进。

### 5.4 时间槽索引计算

```java
// TimerWheel.java 第130-131行
public int getSlotIndex(long timeMs) {
    return (int) (timeMs / precisionMs % (slotsTotal * 2));
}
```

**这就是时间轮转的数学本质！** 它并不是一个物理轮子，而是一个**环形数组索引计算**：

```
slotIndex = floor(timeMs / precisionMs) % (slotsTotal * 2)
```

其中：
- `timeMs` = 要查找的绝对时间戳
- `precisionMs` = 精度（如 1000ms）
- `slotsTotal` = 7天 × 86400秒 = 604800
- `slotsTotal * 2` = 1209600（双倍容量，确保环形覆盖）

**举例：**
- 时间戳 `1696000000000ms`，`precisionMs=1000`，`slotsTotal=604800`
- `slotIndex = floor(1696000000000 / 1000) % 1209600 = 1696000000 % 1209600`
- 因为 `1696000000 / 1209600 ≈ 1402` 余数落在某个槽位

### 5.5 时间轮的"旋转"本质

```
时间轴（毫秒）：
──┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼──▶
  T0   T1   T2   T3   T4   T5   T6   T7   T8   T9   T10

时间轮槽位索引（环形数组）：
┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
│ 0  │ 1  │ 2  │ 3  │ 4  │ 5  │ 6  │ 7  │ 8  │ 9  │ 10 │...│
└────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
  ▲                                       ▲
  │                                       │
  currReadTimeMs                          currWriteTimeMs
  (正在出队的位置)                          (已入队的最新位置)

currReadTimeMs 以 precisionMs 步长向右推进，追赶 currWriteTimeMs
```

**关键理解：时间轮并不"自动旋转"，而是由算法驱动：**

1. **入队方向**：`TimerEnqueuePutService` 调用 `doEnqueue()`，计算 `delayedTime` 对应的槽位索引，写入 TimerLog 并更新槽位信息。`currWriteTimeMs` 随系统时间推进。

2. **出队方向**：`TimerDequeueGetService` 调用 `dequeue()`，用 `currReadTimeMs` 计算当前槽位索引，读取该槽位链表中的所有 TimerLog 记录，逐个处理，然后 `moveReadTime()` 推进到下一个槽位。

3. **追赶关系**：出队始终落后于或等于入队。当 `currReadTimeMs >= currWriteTimeMs` 时，说明所有消息都已出队，`dequeue()` 返回 -1，服务线程短暂休眠。

### 5.6 完整的一次"滴答"（Tick）

```
TimerDequeueGetService 线程循环：
  │
  ├─ dequeue() 被调用
  │   │
  │   ├─ currReadTimeMs >= currWriteTimeMs? → 返回 -1，线程休眠
  │   │
  │   ├─ 计算 slotIndex = (currReadTimeMs / precisionMs) % (slotsTotal * 2)
  │   │
  │   ├─ 读取 TimerWheel 中该索引的 Slot
  │   │   │
  │   │   ├─ slot.timeMs == -1? → 空槽位，moveReadTime()，返回 0
  │   │   │
  │   │   └─ 非空槽位：
  │   │       ├─ 从 slot.lastPos 开始，沿 prevPos 链表遍历 TimerLog
  │   │       ├─ 每条记录：
  │   │       │   ├─ 计算绝对投递时间 = enqueueTime + delayedTime
  │   │       │   ├─ 检查 magic 标记（MAGIC_ROLL / MAGIC_DELETE）
  │   │       │   ├─ 分类：删除消息 / 正常消息 / 滚动消息
  │   │       │   └─ 放入对应的处理队列
  │   │       ├─ 等待所有消息处理完成（CountDownLatch）
  │   │       └─ moveReadTime() → currReadTimeMs += precisionMs
  │   │
  │   └─ 返回处理结果
  │
  └─ 如果返回 -1，休眠 100ms 后重试；否则立即继续
```

### 5.7 环形数组的双倍容量设计

注意 `slotsTotal * 2` 这个设计。为什么是双倍？

因为时间轴是无限延伸的，而槽位数组是有限的。7天 = 604800 秒 = 604800 个槽位（precisionMs=1000 时）。但实际数组大小为 `604800 * 2 = 1209600`。

这意味着：
- 时间戳 `T` 和 `T + 7天` 计算出的槽位索引**不同**（因为取模 1209600，而不是 604800）
- 这避免了新旧消息的槽位冲突
- 时间轮的"有效范围"是 7天，但数组可以容纳 14 天的范围，确保不会出现索引重叠

```
时间轴：   T0          T0+7天         T0+14天
           │             │              │
槽位索引：  0 ... 604799  604800 ... 1209599  0 ... 604799（重新开始）
           │             │              │
          [---- 7天 ----][---- 7天 ----][---- 7天 ----]
           ^                          ^
           相同索引，但差了 14 天，不会冲突
```

---

## 6. 滚动机制（MAGIC_ROLL）—— 长时间延迟消息的完整流转

这是用户最关心的部分：**如果延迟时间很长（比如 7 天），消息是如何在时间轮中流转的？**

### 6.1 滚动条件

```java
// TimerMessageStore.java 第738行
boolean needRoll = delayedTime - tmpWriteTimeMs >= (long) timerRollWindowSlots * precisionMs;
```

**默认配置下：**
- `timerRollWindowSlot = 3600 * 24 * 2 = 172800`（2天）
- `precisionMs = 1000`
- 滚动窗口 = `172800 * 1000 = 172800000ms = 2天`

**也就是说：** 如果消息的投递时间减去当前写入时间 >= 2天，就触发滚动。

### 6.2 滚动时间调整算法

```java
// TimerMessageStore.java 第740-747行
if (needRoll) {
    magic = magic | MAGIC_ROLL;
    if (delayedTime - tmpWriteTimeMs - (long) timerRollWindowSlots * precisionMs 
        < (long) timerRollWindowSlots / 3 * precisionMs) {
        // 剩余时间不到滚动窗口的 1/3，给足够时间下次滚动
        delayedTime = tmpWriteTimeMs + (long) (timerRollWindowSlots / 2) * precisionMs;
    } else {
        // 剩余时间充足，调整到滚动窗口满
        delayedTime = tmpWriteTimeMs + (long) timerRollWindowSlots * precisionMs;
    }
}
```

**这条算法的含义：**
- 如果延迟时间超过滚动窗口（2天），将消息的投递时间**调整到当前写入时间 + 滚动窗口大小**
- 但预留了**缓冲**：如果超出部分不到滚动窗口的 1/3，则只调整到滚动窗口的 1/2，确保下次滚动有足够时间

### 6.3 举例：延迟 7 天的消息（默认配置）

假设：
- 当前系统时间：`T0 = 2024-01-01 00:00:00`
- 消息投递时间：`T_deliver = T0 + 7天 = 2024-01-08 00:00:00`
- 滚动窗口：`2天`
- 精度：`1000ms`

#### 第 1 次入队（T0）

```
生产者发送 setDeliverTimeMs(T0 + 7天)
  → HookUtils.transformTimerMessage()
    → PROPERTY_TIMER_OUT_MS = T0 + 7天
    → topic = %wheel_timer, queueId = 0
  → 写入 CommitLog
  → TimerEnqueueGetService 读取到该消息
  → TimerEnqueuePutService.doEnqueue()：

检查：delayedTime - currWriteTimeMs = 7天 - 0 = 7天 >= 2天 ✓ → 需要滚动

magic = MAGIC_DEFAULT | MAGIC_ROLL
delayedTime 调整 = T0 + 2天（滚动窗口满）

写入 TimerLog：magic=MAGIC_ROLL, offsetPy, sizePy, 
              currWriteTime=T0, delayedTime=2天
写入 TimerWheel：Slot[索引=(T0+2天)/1000 % 1209600]
               lastPos = 当前记录位置
```

**此时：**
- TimerLog 记录：`{magic=ROLL, delayedTime=2天, offsetPy=指向CommitLog中%wheel_timer的消息}`
- 时间轮槽位：`Slot[2天后] → lastPos → 该记录`

#### 第 1 次出队（T0 + 2天）

```
经过 2 天，currReadTimeMs 推进到 T0 + 2天

TimerDequeueGetService.dequeue()：
  → slotIndex = (T0 + 2天) / 1000 % 1209600
  → 读取 Slot，非空
  → 沿 prevPos 链表遍历 TimerLog
  → 读取到该记录：magic=ROLL, enqueueTime=T0, delayedTime=2天
  → 计算绝对投递时间 = T0 + 2天
  
  → 判断：needRoll(magic) = true → 放入 normalMsgStack
  → dequeueGetQueue → TimerDequeueGetMessageService
     → 从 CommitLog 读取完整消息体
  → dequeuePutQueue → TimerDequeuePutMessageService

TimerDequeuePutMessageService：
  → convert(msgExt, enqueueTime=T0, needRoll=true)
     → PROPERTY_TIMER_ROLL_TIMES = 1（第一次滚动）
     → PROPERTY_TIMER_ENQUEUE_MS = T0
     → convertMessage(needRoll=true)
       → topic = %wheel_timer（保持！）
       → queueId = 0（保持！）
  → doPut(msg, roll=true)
     → messageStore.putMessage(msg) → 写入 CommitLog！！！
```

**到这里，原始消息被重新写入了 CommitLog！** 新的 CommitLog 条目包含了更新后的属性（`TIMER_ROLL_TIMES=1`）。

#### 第 2 次入队（T0 + 2天）

```
新 CommitLog 条目被分发到 %wheel_timer 的 ConsumeQueue

TimerEnqueueGetService 再次读取到该消息
  → 提取 PROPERTY_TIMER_OUT_MS = T0 + 7天（原始投递时间未变）
  → 封装 TimerRequest 放入 enqueuePutQueue

TimerEnqueuePutService.doEnqueue()：
  currWriteTimeMs ≈ T0 + 2天（已随系统时间推进）
  delayedTime = T0 + 7天
  检查：7天 - 2天 = 5天 >= 2天 ✓ → 继续滚动

  magic = MAGIC_ROLL
  delayedTime 调整 = (T0 + 2天) + 2天 = T0 + 4天

  写入 TimerLog 新记录
  写入 TimerWheel 新槽位
```

#### 继续滚动...

| 次数 | 出队时间 | 滚动后投递时间 | 操作 |
|------|---------|---------------|------|
| 第1次入队 | T0 | T0+2天（调整后） | 写入 TimerLog `magic=ROLL` |
| 第1次出队 | T0+2天 | 重新写入 CommitLog | `rollTimes=1` |
| 第2次入队 | T0+2天 | T0+4天（调整后） | 写入 TimerLog |
| 第2次出队 | T0+4天 | 重新写入 CommitLog | `rollTimes=2` |
| 第3次入队 | T0+4天 | T0+6天（调整后） | 写入 TimerLog |
| 第3次出队 | T0+6天 | 重新写入 CommitLog | `rollTimes=3` |
| 第4次入队 | T0+6天 | T0+7天（原始投递） | ✅ 不再滚动！ |
| 第4次出队 | T0+7天 | 还原真实 topic | 投递给消费者！ |

### 6.4 最后一次：不再滚动，投递到真实 Topic

在第4次入队时，`delayedTime - currWriteTimeMs = 7天 - 6天 = 1天 < 2天`，不再滚动。

第4次出队时：
```java
TimerDequeuePutMessageService：
  → convert(msgExt, enqueueTime, needRoll=false)
     → convertMessage(needRoll=false)
       → msgInner.setTopic(PROPERTY_REAL_TOPIC)  // 还原真实 topic
       → msgInner.setQueueId(PROPERTY_REAL_QUEUE_ID)  // 还原真实 queueId
  → doPut(msg, roll=false)
     → messageStore.putMessage(msg) → 写入 CommitLog（真实 Topic）
     → 消费者消费！
```

### 6.5 完整滚动流程图

```
时间线：T0 ──────────────── T0+7天
         │                   │
         │                   └── 到期投递到真实 Topic
         │
         ├── T0：第1次入队
         │   delayedTime = T0+7天 → 超过滚动窗口(2天)
         │   magic = ROLL, 调整到 T0+2天
         │   写入 TimerLog → TimerWheel
         │
         ├── T0+2天：第1次出队 → 第1次滚动
         │   TimerDequeueGetService 扫描到 ROLL 消息
         │   → 从 CommitLog 读取原始消息
         │   → convert(needRoll=true)
         │     topic=%wheel_timer（保持）
         │   → doPut → 写入 CommitLog（新条目）
         │   → TimerEnqueueGetService 再次读取
         │   → 第2次入队
         │
         ├── T0+4天：第2次滚动（同上）
         │
         ├── T0+6天：第3次滚动
         │
         └── T0+7天：第4次出队
             → 不再滚动
             → convert(needRoll=false)
               → topic=真实Topic
             → doPut → 写入 CommitLog（真实 Topic）
             → 消费者消费
```

**关键结论：** 一条延迟 7 天的消息，在期间会**重新写入 CommitLog 3 次**（每次滚动一次），产生 3 条额外的 CommitLog 条目，以及对应的 TimerLog 记录和 TimerWheel 槽位更新。

---

## 7. 后台进程架构（7个服务线程详解）

TimerMessageStore 内部定义了 7 个后台服务线程，它们协同工作，构成了完整的定时消息处理流水线：

### 7.1 线程一览

| 编号 | 线程名 | 类名 | 核心职责 | 驱动方式 |
|------|-------|------|---------|---------|
| ① | TimerEnqueueGetService | `TimerEnqueueGetService` | 从 ConsumeQueue 读取 %wheel_timer 消息 | 循环轮询，无数据时休眠 |
| ② | TimerEnqueuePutService | `TimerEnqueuePutService` | 将消息写入 TimerLog + TimerWheel | 从 enqueuePutQueue 消费 |
| ③ | TimerDequeueWarmService | `TimerDequeueWarmService` | 预读取到 page cache | 循环轮询（当前基本为空实现） |
| ④ | TimerDequeueGetService | `TimerDequeueGetService` | 扫描时间轮，到期出队 | 循环轮询，无数据时休眠 |
| ⑤ | TimerDequeueGetMessageService | `TimerDequeueGetMessageService` | 从 CommitLog 读取完整消息体 | 从 dequeueGetQueue 消费（多线程） |
| ⑥ | TimerDequeuePutMessageService | `TimerDequeuePutMessageService` | 将消息写回 CommitLog | 从 dequeuePutQueue 消费（多线程） |
| ⑦ | TimerFlushService | `TimerFlushService` | 定期刷盘 | 定时器驱动 |

### 7.2 线程协作关系

```
                    ┌─────────────────────────────────────┐
                    │           CommitLog                  │
                    │  (topic=%wheel_timer, queueId=0)     │
                    └────────────┬────────────────────────┘
                                 │
                    ┌────────────▼────────────────────────┐
                    │ ① TimerEnqueueGetService (1线程)     │
                    │ 循环：enqueue(0) → 读取 ConsumeQueue │
                    │ 从 currQueueOffset 开始迭代          │
                    │ 结果：封装 TimerRequest              │
                    │ 输出：enqueuePutQueue                │
                    └────────────┬────────────────────────┘
                                 │ 阻塞队列
                    ┌────────────▼────────────────────────┐
                    │ ② TimerEnqueuePutService (1线程)     │
                    │ 循环：fetchAndPutTimerRequest()      │
                    │ 结果：doEnqueue() → TimerLog + Wheel │
                    │ 推进：commitQueueOffset              │
                    │ 推进：currWriteTimeMs（maybeMove）   │
                    └─────────────────────────────────────┘
                                 │
                    ┌────────────▼────────────────────────┐
                    │ ④ TimerDequeueGetService (1线程)     │
                    │ 循环：dequeue()                      │
                    │ 用 currReadTimeMs 查槽位             │
                    │ 遍历 TimerLog 链表                   │
                    │ 分类：正常/删除/滚动                  │
                    │ 输出：dequeueGetQueue                │
                    │ 推进：currReadTimeMs（moveReadTime） │
                    └────────────┬────────────────────────┘
                                 │ 阻塞队列
                    ┌────────────▼────────────────────────┐
                    │ ⑤ TimerDequeueGetMessageService     │
                    │ (多线程，默认3个)                     │
                    │ 从 dequeueGetQueue 获取 TimerRequest │
                    │ getMessageByCommitOffset()           │
                    │ 检查删除标记                         │
                    │ 输出：dequeuePutQueue                │
                    └────────────┬────────────────────────┘
                                 │ 阻塞队列
                    ┌────────────▼────────────────────────┐
                    │ ⑥ TimerDequeuePutMessageService     │
                    │ (多线程，默认3个)                     │
                    │ 从 dequeuePutQueue 获取 TimerRequest │
                    │ convert() → convertMessage()        │
                    │ doPut() → messageStore.putMessage() │
                    │ ※ 滚动：保持 %wheel_timer           │
                    │ ※ 到期：还原真实 topic              │
                    └────────────┬────────────────────────┘
                                 │
                    ┌────────────▼────────────────────────┐
                    │         CommitLog                    │
                    │ (真实 topic 或 再次 %wheel_timer)     │
                    └─────────────────────────────────────┘

                    ┌────────────▼────────────────────────┐
                    │ ⑦ TimerFlushService (1线程)          │
                    │ 定时器驱动：                          │
                    │ 1. prepareTimerCheckPoint()          │
                    │ 2. timerLog.flush()                  │
                    │ 3. timerWheel.flush()                │
                    │ 4. timerCheckpoint.flush()           │
                    │ 5. timerMetrics.persist()            │
                    │ 间隔：timerFlushIntervalMs(默认1秒)  │
                    └─────────────────────────────────────┘
```

### 7.3 线程间队列

三个阻塞队列衔接各线程：

| 队列 | 类型 | 容量 | 生产者 | 消费者 |
|------|------|------|-------|-------|
| `enqueuePutQueue` | `LinkedBlockingDeque` 或 `DisruptorBlockingQueue` | 1024 | ① TimerEnqueueGetService | ② TimerEnqueuePutService |
| `dequeueGetQueue` | `LinkedBlockingDeque` 或 `DisruptorBlockingQueue` | 1024 | ④ TimerDequeueGetService | ⑤ TimerDequeueGetMessageService |
| `dequeuePutQueue` | `LinkedBlockingDeque` 或 `DisruptorBlockingQueue` | 1024 | ⑤ TimerDequeueGetMessageService | ⑥ TimerDequeuePutMessageService |

### 7.4 TimerDequeuePutMessageService 核心逻辑（关键！）

这是决定消息是"滚动"还是"投递"的关键环节：

```java
// TimerMessageStore.java 第1470-1539行
public class TimerDequeuePutMessageService extends AbstractStateService {
    @Override
    public void run() {
        while (!this.isStopped() || dequeuePutQueue.size() != 0) {
            TimerRequest tr = dequeuePutQueue.poll(10, TimeUnit.MILLISECONDS);
            if (null == tr) continue;
            
            // 核心：从 TimerRequest 中取出消息体
            MessageExt msgExt = tr.getMsg();
            
            // 调用 convert() 方法
            // 参数 needRoll(tr.getMagic()) 决定消息是滚动还是投递
            MessageExtBrokerInner msg = convert(msgExt, tr.getEnqueueTime(), 
                needRoll(tr.getMagic()));
            
            // 调用 doPut() 写回 CommitLog
            // 参数 needRoll(tr.getMagic()) 传递给 convertMessage()
            doRes = PUT_NEED_RETRY != doPut(msg, needRoll(tr.getMagic()));
        }
    }
}
```

**`needRoll(tr.getMagic())` 是关键判断：**
```java
// TimerMessageStore.java 第1641-1643行
public boolean needRoll(int magic) {
    return (magic & MAGIC_ROLL) != 0;
}
```

- 如果 magic 包含 `MAGIC_ROLL`（值为 2）→ `needRoll=true` → 消息保持 `%wheel_timer`，继续在时间轮中等待
- 如果 magic 不包含 `MAGIC_ROLL` → `needRoll=false` → 消息还原到真实 topic，投递给消费者

---

## 8. 延迟消息的"延迟"本质

### 8.1 传统延迟级别：基于 timed wait 的轮询

```java
// ScheduleMessageService.DeliverDelayedMessageTimerTask
long countdown = deliverTimestamp - now;
if (countdown > 0) {
    // 核心：通过 ScheduledExecutorService.schedule() 延时等待
    this.scheduleNextTimerTask(currOffset, DELAY_FOR_A_WHILE);
    return;
}
```

**本质**：延迟时间编码在 ConsumeQueue 的 `tagsCode` 字段中，通过 `ScheduledExecutorService` 的 `schedule()` 方法实现定时检查。每次检查发现时间未到，就重新调度下一次检查（间隔100ms）。

### 8.2 定时消息：基于时间轮索引 + 链表遍历的精确投递

**本质**：延迟时间通过**时间槽索引**和**TimerLog 中的相对时间**共同决定：

1. **时间槽定位**：`slotIndex = (deliverTimeMs / precisionMs) % (slotsTotal * 2)`，将投递时间映射到固定槽位
2. **链表遍历**：每个槽位维护一个 TimerLog 条目的单向链表，到期时遍历链表获取所有消息
3. **时间推进**：`currReadTimeMs` 以 `precisionMs` 为单位递增，每次推进扫描一个槽位

**精度控制：**
```java
// 精度配置（1000/500/200/100ms）
private int timerPrecisionMs = 1000;  // 默认1000ms

// 时间对齐
deliverMs = deliverMs / timerPrecisionMs * timerPrecisionMs;
```

---

## 9. 消费者消费延迟消息

消费者**不需要特殊处理**延迟消息。当消息到达投递时间后，Broker 自动将消息还原到真实 topic 和 queueId，消费者以普通消息方式消费即可。

```java
// 消费者示例（ScheduledMessageConsumer.java / TimerMessageConsumer.java）
public static void main(String[] args) throws Exception {
    DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("ConsumerGroup");
    consumer.subscribe("TestTopic", "*");
    consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
        System.out.printf("%s Receive New Messages: %s %n", 
            Thread.currentThread().getName(), msgs);
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    });
    consumer.start();
}
```

---

## 10. 配置项说明

### 10.1 传统延迟级别消息配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `messageDelayLevel` | `1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h` | 18个延迟级别，空格分隔 |
| `flushDelayOffsetInterval` | 10000 | 延迟偏移量持久化间隔（毫秒） |
| `enableScheduleMessageStats` | true | 是否启用调度消息统计 |
| `enableScheduleAsyncDeliver` | false | 是否启用异步投递 |
| `scheduleAsyncDeliverMaxPendingLimit` | 2000 | 异步投递最大待处理数 |
| `scheduleAsyncDeliverMaxResendNum2Blocked` | 3 | 异步投递最大重试次数，超过则阻塞 |

### 10.2 定时消息（Timer Message）配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `timerWheelEnable` | true | 是否启用时间轮（定时消息） |
| `timerPrecisionMs` | 1000 | 时间轮精度（毫秒），可选 1000/500/200/100ms |
| `timerMaxDelaySec` | 259200（3天） | 最大延迟时间（秒） |
| `timerRollWindowSlot` | 172800（2天） | 滚动窗口槽位数。延迟超过此值的消息会被分多次滚动投递 |
| `timerFlushIntervalMs` | 1000 | 刷盘间隔（毫秒） |
| `timerGetMessageThreadNum` | 3 | 出队获取消息线程数（⑤号线程） |
| `timerPutMessageThreadNum` | 3 | 出队写入消息线程数（⑥号线程） |
| `mappedFileSizeTimerLog` | 104857600（100M） | TimerLog 文件大小 |
| `timerEnableDisruptor` | false | 是否使用 Disruptor 队列 |
| `timerEnableCheckMetrics` | true | 是否启用指标检查 |
| `timerCheckMetricsWhen` | `05` | 指标检查时间 |
| `timerMetricSmallThreshold` | 1000000 | 指标小阈值 |
| `timerProgressLogIntervalMs` | 10000 | 进度日志间隔（毫秒） |
| `timerStopEnqueue` | false | 停止入队（调试用） |
| `timerStopDequeue` | false | 停止出队（调试用） |
| `timerSkipUnknownError` | false | 遇到未知错误是否跳过 |
| `timerWarmEnable` | false | 是否启用预热 |
| `timerCongestNumEachSlot` | Integer.MAX_VALUE | 每个槽位的拥塞阈值 |
| `timerInterceptDelayLevel` | false | 是否拦截延迟级别消息（由时间轮接管） |

---

## 11. 两套机制的对比与选型

| 维度 | 传统延迟级别 | 定时消息（时间轮） |
|------|------------|------------------|
| 精度 | 秒级（固定级别） | 毫秒级（可配置） |
| 延迟范围 | 1s ~ 2h | 最大3天（可配置） |
| 存储开销 | 低（复用 ConsumeQueue） | 中（额外 TimerLog + TimerWheel 文件） |
| 实现复杂度 | 简单 | 较复杂 |
| 是否支持任意时间 | ❌（仅18个级别） | ✅（任意时间） |
| 是否支持取消 | ❌ | ✅（通过 `PROPERTY_TIMER_DEL_UNIQKEY`） |
| 是否支持滚动 | ❌ | ✅（超过滚动窗口的消息自动分片） |
| 配置方式 | `setDelayTimeLevel(n)` | `setDeliverTimeMs(ts)` / `setDelayTimeSec(s)` |
| 版本要求 | 所有版本 | 5.0+ |

---

## 参考

1. [RocketMQ 消息集成：多类型业务消息——定时消息.pdf](./RocketMQ%20消息集成：多类型业务消息——定时消息.pdf)
2. [定时_延时消息 _ RocketMQ.pdf](./定时_延时消息%20_%20RocketMQ.pdf)
3. [RocketMQ 消息处理流程](../010.RocketMQ消息处理流程.md)