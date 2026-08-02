# RocketMQ PushConsumer 生产环境 OOM/Full GC 安全配置报告

> 分析版本：RocketMQ 5.3.1  
> 分析文件：`DefaultMQPushConsumer.java` + `DefaultMQPushConsumerImpl.java` + `ProcessQueue.java`  
> 分析维度：客户端内存安全（OOM防护、Full GC频率控制）

---

## 目录

1. [消息拉取与本地存储机制](#1-消息拉取与本地存储机制)
2. [OOM风险全景分析](#2-oom风险全景分析)
3. [配置项完整清单与安全等级](#3-配置项完整清单与安全等级)
4. [计算公式模型](#4-计算公式模型)
5. [生产环境配置指南](#5-生产环境配置指南)
6. [典型场景配置模板](#6-典型场景配置模板)
7. [监控与告警建议](#7-监控与告警建议)

---

## 1. 消息拉取与本地存储机制

### 1.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      DefaultMQPushConsumer                        │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │              DefaultMQPushConsumerImpl                       │ │
│  │                                                              │ │
│  │  ┌──────────┐     ┌───────────────┐     ┌───────────────┐  │ │
│  │  │Rebalance │────→│PullMessageSvc │────→│pullMessage()  │  │ │
│  │  │ 分配队列  │     │ 调度循环       │     │ 流控检查+拉取  │  │ │
│  │  └──────────┘     └───────────────┘     └───────┬───────┘  │ │
│  │                                                  │           │ │
│  │  ┌───────────────────────────────────────────────▼────────┐ │ │
│  │  │               ProcessQueue (每个队列一个)                │ │ │
│  │  │  ┌──────────────────────────────────────────────────┐  │ │ │
│  │  │  │  TreeMap<Long, MessageExt> msgTreeMap             │  │ │ │
│  │  │  │  【按offset排序的消息缓存，就是这里占内存！】        │  │ │ │
│  │  │  │  msgCount (AtomicLong) → 当前缓存消息数            │  │ │ │
│  │  │  │  msgSize  (AtomicLong) → 当前缓存消息体总大小       │  │ │ │
│  │  │  └──────────────────────────────────────────────────┘  │ │ │
│  │  └────────────────────────────────────────────────────────┘ │ │
│  │                         │                                    │ │
│  │  ┌──────────────────────▼────────────────────────────────┐  │ │
│  │  │  ConsumeMessageService (消费线程池)                    │  │ │
│  │  │  → msgTreeMap中取出消息 → 调用MessageListener          │  │ │
│  │  │  → 消费成功 → removeMessage() → 释放内存               │  │ │
│  │  └───────────────────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 消息本地存储数据结构

**ProcessQueue** 是每个消息队列对应的本地缓存，是内存占用的核心：

```java
class ProcessQueue {
    TreeMap<Long, MessageExt> msgTreeMap;   // ← 吃内存的核心！
    AtomicLong msgCount;                     // 消息计数
    AtomicLong msgSize;                      // 消息体字节数
}
```

**MessageExt 对象内存占用**（估算）：
- 消息体(body)：N bytes
- Java对象头：16 bytes
- MessageExt 字段（topic, queueId, queueOffset, flag, tags, keys等）：~200 bytes
- 属性Map（Properties）：~200 bytes + 属性内容
- 合计：**约 500 bytes + N bytes（消息体）**

### 1.3 流控触发链

```
pullMessage() 被调用
    │
    ├─ cachedMessageCount > pullThresholdForQueue? → YES → 延迟50ms重试 🚫
    │
    ├─ cachedMessageSizeInMiB > pullThresholdSizeForQueue? → YES → 延迟50ms 🚫
    │
    ├─ (并发模式) maxSpan > consumeConcurrentlyMaxSpan? → YES → 延迟50ms 🚫
    │
    └─ 全部通过 → pullKernelImpl() 异步拉取 ✅
```

---

## 2. OOM风险全景分析

### 2.1 风险等级划分

| 风险等级 | 条件 | 后果 |
|---------|------|------|
| 🔴 **高危** | 生产速度 > 消费速度，且无有效流控 | 消息无限堆积 → OOM |
| 🟠 **中危** | 消费偶尔卡顿，积压后恢复 | 堆内存波动 → 频繁Full GC |
| 🟡 **低危** | 突发流量高峰，短暂超过阈值 | 内存压力大 → Young GC频繁 |
| 🟢 **安全** | 消费速度始终 > 生产速度，流控配置合理 | 稳态运行 |

### 2.2 OOM的7大罪魁祸首

| # | 原因 | 根本问题 | 涉及配置 |
|---|------|---------|---------|
| 1 | pullThresholdForTopic=-1 | 无主题级兜底，队列数多时总缓存无上限 | pullThresholdForTopic |
| 2 | pullThresholdForQueue=1000(默认) | 每条消息10KB时，10个队列=100MB+ | pullThresholdForQueue |
| 3 | consumeThreadMin/Max=20 | 消费线程不足，积压无法消化 | consumeThreadMin/Max |
| 4 | consumeMessageBatchMaxSize=1 | 批量消费太小，吞吐上不去 | consumeMessageBatchMaxSize |
| 5 | pullInterval=0 | 拉取太频繁，消费跟不上 | pullInterval |
| 6 | maxReconsumeTimes=-1 | 重试次数过多，消息滞留 | maxReconsumeTimes |
| 7 | 大消息场景未调整 | 一条消息几MB，队列级别无法限制 | pullThresholdSizeForQueue |

### 2.3 Full GC 触发原因

- **消息对象频繁创建与回收**：每次拉取创建 List<MessageExt>，消费完成后释放 → Young GC
- **TreeMap 频繁 put/remove**：消息进入和离开 ProcessQueue 导致红黑树结构调整 → 老年代引用变化
- **属性Map开销**：MessageExt 的 properties 是 HashMap，频繁创建 → 碎片化
- **消费线程池线程**：每个线程有独立栈 + 工作队列 → 线程数越多，GC Root 越多

---

## 3. 配置项完整清单与安全等级

### 3.1 🔴 直接OOM防护（必须配置）

| 配置项 | 默认值 | 安全范围 | 优先级 |
|-------|--------|---------|-------|
| `pullThresholdForQueue` | 1000 | 根据消息大小：小消息500~2000，大消息20~200 | 高 |
| `pullThresholdSizeForQueue` | 100 MiB | 1~1024，大消息场景为关键指标 | 高 |
| `pullThresholdForTopic` | -1(不限) | ⚠️ **强烈建议设置正值**，建议 5000~100000 | **最高** |
| `pullThresholdSizeForTopic` | -1(不限) | ⚠️ **强烈建议设置正值**，建议 500~8000 MiB | **最高** |

### 3.2 🟠 间接OOM防护（重要）

| 配置项 | 默认值 | 说明 |
|-------|--------|------|
| `consumeThreadMin` | 20 | 太少→积压，太多→GC压力，建议 8~64 |
| `consumeThreadMax` | 20 | 建议等于Min，避免动态调整 |
| `consumeConcurrentlyMaxSpan` | 2000 | 并发模式下offset跨度保护 |
| `consumeMessageBatchMaxSize` | 1 | 建议 1~32，根据业务处理速度 |
| `pullBatchSize` | 32 | 小消息可增大，大消息需减小 |

### 3.3 🟡 辅助配置

| 配置项 | 默认值 | 说明 |
|-------|--------|------|
| `pullInterval` | 0 | 积压场景可设置为 10~100ms |
| `pullBatchSizeInBytes` | 256KB | 大消息场景需调大 |
| `maxReconsumeTimes` | -1(16) | 建议 3~5 |
| `suspendCurrentQueueTimeMillis` | 1000 | 流控时的等待时间 |
| `consumeTimeout` | 15min | 根据业务处理耗时调整 |
| `popInvisibleTime` | 60000ms | Pop模式下消息不可见时间 |

---

## 4. 计算公式模型

### 4.1 基础参数定义

```
已知参数：
  T = 订阅的Topic数量
  Q = 每个Topic的队列数
  A = 单个消费者平均分配的队列数 = (T × Q) / 消费者实例数
  S = 平均消息大小（bytes），包含消息体+属性开销（通常为体大小的1.5~2倍）
  J = JVM最大堆内存（bytes）
  P = 安全系数（建议 0.3~0.5，即堆内存的30%~50%用于消息缓存）
```

### 4.2 核心计算公式

#### ① 单队列最大消息条数
```
pullThresholdForQueue = min(
    pullThresholdForTopic / A,           // topic级别均摊
    J × P / (A × S × 2)                 // 堆内存限制（×2是Java对象开销系数）
)
```

#### ② 主题级别消息条数上限
```
pullThresholdForTopic = J × P / S × 2
```

**示例：** J=4GB, P=0.4, S=10KB
```
pullThresholdForTopic = 4 × 1024 × 1024 × 1024 × 0.4 / (10 × 1024 × 2)
                    ≈ 4,294,967,296 × 0.4 / 20,480
                    ≈ 83,886
```

#### ③ 主题级别大小上限(MiB)
```
pullThresholdSizeForTopic = J × P / (1024 × 1024)   // 直接取堆内存的P%
```

**示例：** J=4GB, P=0.4
```
pullThresholdSizeForTopic = 4096 × 0.4 = 1638 MiB
```

#### ④ 消费线程数
```
consumeThreadMin = max(A, CPU核数 × 2)     // 至少覆盖分配的队列数
consumeThreadMax = consumeThreadMin         // 固定大小，避免动态调整
```

**示例：** A=12, CPU=4核8线程
```
consumeThreadMin = max(12, 8) = 12
```

#### ⑤ 批量消费大小
```
consumeMessageBatchMaxSize = min(pullBatchSize, 处理耗时建议值)
  - 处理耗时 < 10ms/条：建议 16~32
  - 处理耗时 10~100ms/条：建议 4~16
  - 处理耗时 > 100ms/条：建议 1~4
```

#### ⑥ 预期最大内存占用
```
预期最大内存 = A × pullThresholdForQueue × S × 2（Java对象开销）

示例：A=10, pullThresholdForQueue=500, S=10KB
预期最大内存 = 10 × 500 × 10KB × 2 = 100MB
```

#### ⑦ 安全验证公式
```
安全条件：预期最大内存 < J × P

如果 预期最大内存 > J × P，则需要：
  1. 降低 pullThresholdForQueue
  2. 或降低 pullThresholdForTopic
  3. 或增加消费线程数（提高消费速度）
  4. 或增加消费者实例数（降低A）
```

---

## 5. 生产环境配置指南

### 5.1 通用配置步骤

```java
DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("consumerGroup");

// ==================== 第一步：JVM参数（必须） ====================
// -Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
// 堆内存至少4GB，建议使用G1GC减少Full GC

// ==================== 第二步：OOM第一道防线 — 主题级兜底 ====================
// 计算：JVM 4GB, 安全系数0.4, 平均消息10KB
// pullThresholdForTopic = 4GB × 0.4 / (10KB × 2) ≈ 80000
consumer.setPullThresholdForTopic(80000);           // 主题最多缓存80000条
consumer.setPullThresholdSizeForTopic(1600);         // 主题最多缓存1600 MiB

// ==================== 第三步：OOM第二道防线 — 队列级控制 ====================
// 假设分配了10个队列：pullThresholdForQueue = 80000/10 = 8000
// 但太大，改为按堆内存算：4GB × 0.4 / (10 × 10KB × 2) ≈ 8000
consumer.setPullThresholdForQueue(500);              // 每队列500条
consumer.setPullThresholdSizeForQueue(50);           // 每队列50 MiB

// ==================== 第四步：消费线程配置 ====================
consumer.setConsumeThreadMin(16);                    // 至少覆盖分配的队列数
consumer.setConsumeThreadMax(16);                    // 固定大小

// ==================== 第五步：拉取参数优化 ====================
consumer.setPullBatchSize(32);                       // 每次拉取32条
consumer.setConsumeMessageBatchMaxSize(8);           // 每次批量消费8条

// ==================== 第六步：拉取间隔（积压场景） ====================
// consumer.setPullInterval(50);  // 积压时可设置50ms间隔

// ==================== 第七步：重试策略 ====================
consumer.setMaxReconsumeTimes(3);                    // 最多重试3次

// ==================== 第八步：Pop模式（如果使用） ====================
// consumer.setPopInvisibleTime(120000);              // 2分钟不可见
// consumer.setPopThresholdForQueue(48);              // 等待ACK最多48条
```

### 5.2 配置验证检查清单

- [ ] `pullThresholdForTopic` ≠ -1（必须有主题级兜底）
- [ ] `pullThresholdSizeForTopic` ≠ -1（必须有大小兜底）
- [ ] 预期最大内存 < JVM堆内存 × 安全系数(0.3~0.5)
- [ ] `consumeThreadMin` ≥ 分配的队列数
- [ ] `consumeThreadMin` = `consumeThreadMax`（固定线程池）
- [ ] `consumeMessageBatchMaxSize` 与业务处理速度匹配
- [ ] `maxReconsumeTimes` 设置为有限值（3~5）

---

## 6. 典型场景配置模板

### 场景1：小消息高频场景

**特征：** 消息体 < 1KB，TPS > 5000，延迟敏感

| 配置项 | 推荐值 | 理由 |
|-------|--------|------|
| JVM堆 | 8GB | 需要足够缓存 |
| pullThresholdForQueue | 2000 | 小消息占内存小 |
| pullThresholdForTopic | 100000 | 8GB堆可承受 |
| pullThresholdSizeForQueue | 200 MiB | 单队列200MB |
| pullThresholdSizeForTopic | 4000 MiB | 50%堆内存 |
| consumeThreadMin | 32 | 高吞吐需要多线程 |
| consumeThreadMax | 32 | 固定 |
| consumeMessageBatchMaxSize | 32 | 批量消费提吞吐 |
| pullBatchSize | 64 | 每次拉取64条 |
| pullInterval | 0 | 延迟敏感不等待 |

### 场景2：中等消息通用场景

**特征：** 消息体 1KB~50KB，TPS 500~5000

| 配置项 | 推荐值 | 理由 |
|-------|--------|------|
| JVM堆 | 4GB | 标准配置 |
| pullThresholdForQueue | 500 | 每队列500条 |
| pullThresholdForTopic | 50000 | 主题级上限 |
| pullThresholdSizeForQueue | 100 MiB | 每队列100MB |
| pullThresholdSizeForTopic | 2000 MiB | 50%堆内存 |
| consumeThreadMin | 16 | 适中 |
| consumeThreadMax | 16 | 固定 |
| consumeMessageBatchMaxSize | 8 | 批量消费 |
| pullBatchSize | 32 | 适中 |
| pullInterval | 0 | 即时拉取 |

### 场景3：大消息场景

**特征：** 消息体 > 100KB，TPS < 100

| 配置项 | 推荐值 | 理由 |
|-------|--------|------|
| JVM堆 | 8GB+ | 大消息需要大堆 |
| pullThresholdForQueue | 50 | 大消息，少缓存 |
| pullThresholdForTopic | 5000 | 主题级上限 |
| pullThresholdSizeForQueue | 500 MiB | 以大小为主限制 |
| pullThresholdSizeForTopic | 5000 MiB | 60%堆内存 |
| consumeThreadMin | 8 | 大消息处理慢，线程少些 |
| consumeThreadMax | 8 | 固定 |
| consumeMessageBatchMaxSize | 1 | 大消息一条条处理 |
| pullBatchSize | 8 | 每次少拉 |
| pullBatchSizeInBytes | 2MB | 调大防止截断 |
| pullInterval | 10ms | 适度间隔 |

### 场景4：高积压风险场景

**特征：** 消费速度 < 生产速度，需要强流控

| 配置项 | 推荐值 | 理由 |
|-------|--------|------|
| pullThresholdForQueue | 100 | 严格控制积压 |
| pullThresholdForTopic | 5000 | 总量控制 |
| pullThresholdSizeForQueue | 50 MiB | 大小控制 |
| pullThresholdSizeForTopic | 500 MiB | 全局大小控制 |
| consumeThreadMin | 32 | 尽量多线程消费 |
| consumeThreadMax | 32 | 固定 |
| consumeMessageBatchMaxSize | 1 | 减少批量处理时间 |
| pullInterval | 100ms | 主动降速 |
| suspendCurrentQueueTimeMillis | 2000 | 流控时等待更久 |

---

## 7. 监控与告警建议

### 7.1 关键监控指标

| 指标 | 告警阈值 | 含义 |
|------|---------|------|
| ProcessQueue.msgCount | > pullThresholdForQueue × 80% | 接近流控阈值 |
| 消费者积压总条数 | 持续增长 | 消费能力不足 |
| JVM老年代使用率 | > 70% | 可能Full GC |
| Full GC频率 | > 1次/小时 | 需要优化配置 |
| Young GC耗时 | > 500ms | 消息对象太多 |
| 消费RT | 超过SLA | 业务处理变慢 |

### 7.2 快速排障步骤

```
OOM或Full GC频繁？
    │
    ├─ 检查 JVM 堆使用率
    │   ├─ > 80% → 检查ProcessQueue消息积压数
    │   │   ├─ 积压大 → 检查消费速度/生产速度
    │   │   │   ├─ 消费慢 → 增加consumeThreadMin/消费者实例
    │   │   │   └─ 生产快 → 降低pullThresholdForQueue
    │   │   └─ 积压不大 → 检查是否有内存泄漏
    │   └─ < 80% → 检查GC配置
    │
    ├─ 检查 pullThresholdForTopic 是否设置
    │   └─ = -1 → 立即设置！
    │
    ├─ 检查 pullThresholdSizeForTopic 是否设置
    │   └─ = -1 → 立即设置！
    │
    └─ 检查 平均消息大小
        └─ 大消息场景 → 以pullThresholdSizeFor*为主要流控手段
```

### 7.3 RocketMQ 控制台查看命令

```bash
# 查看消费者积压
$ mqadmin consumerProgress -g consumerGroup

# 查看消费者连接状态
$ mqadmin consumerConnection -g consumerGroup

# 查看集群消费状态
$ mqadmin clusterList
```

---

## 附录：配置速查表

```java
// ===== 生产环境推荐配置速查 =====
// 根据你的场景选择对应值，不要直接复制

// 【必配】JVM：-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200

// 【必配】主题级流控（OOM第一道防线）
consumer.setPullThresholdForTopic(50000);        // 总条数上限
consumer.setPullThresholdSizeForTopic(2000);      // 总大小上限(MiB)

// 【必配】队列级流控（OOM第二道防线）
consumer.setPullThresholdForQueue(500);           // 每队列条数
consumer.setPullThresholdSizeForQueue(100);       // 每队列大小(MiB)

// 【必配】消费线程
consumer.setConsumeThreadMin(16);
consumer.setConsumeThreadMax(16);

// 【推荐配置】拉取参数
consumer.setPullBatchSize(32);
consumer.setConsumeMessageBatchMaxSize(8);

// 【推荐配置】重试限制
consumer.setMaxReconsumeTimes(3);

// 【可选】Pop模式
// consumer.setPopInvisibleTime(120000);
// consumer.setPopThresholdForQueue(48);
```

---

*报告生成日期：2026-08-02*  
*基于 RocketMQ 5.3.1 源码分析*