/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.client.consumer;

import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.client.consumer.listener.MessageListener;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import org.apache.rocketmq.client.consumer.store.OffsetStore;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.trace.AsyncTraceDispatcher;
import org.apache.rocketmq.client.trace.TraceDispatcher;
import org.apache.rocketmq.client.trace.hook.ConsumeMessageTraceHookImpl;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.remoting.protocol.NamespaceUtil;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * In most scenarios, this is the mostly recommended class to consume messages.（在大多数场景下，这是消费消息最推荐的类。）
 * </p>
 * Technically speaking, this push client is virtually a wrapper of the underlying pull service. Specifically, on
 * arrival of messages pulled from brokers, it roughly invokes the registered callback handler to feed the messages.（从技术上讲，这个推模式客户端实际上是底层拉取服务的封装。具体来说，当从Broker拉取到消息时，它会调用注册的回调处理器来投递消息。）
 * </p>
 * See quickstart/Consumer in the example module for a typical usage.（典型用法请参见example模块中的quickstart/Consumer。）
 * </p>
 *
 * <p>
 * <strong>Thread Safety:</strong> After initialization, the instance can be regarded as thread-safe.（线程安全：初始化后，该实例可视为线程安全。）
 * </p>
 */
public class DefaultMQPushConsumer extends ClientConfig implements MQPushConsumer {

    private final Logger log = LoggerFactory.getLogger(DefaultMQPushConsumer.class);

    /**
     * Internal implementation. Most of the functions herein are delegated to it.（内部实现。大部分功能都委托给该实现类。）
     */
    protected final transient DefaultMQPushConsumerImpl defaultMQPushConsumerImpl;

    /**
     * Consumers of the same role is required to have exactly same subscriptions and consumerGroup to correctly achieve
     * load balance. It's required and needs to be globally unique.（同一角色的消费者必须具有完全相同的订阅和consumerGroup才能正确实现负载均衡。该字段是必需的，并且需要全局唯一。）
     * </p>
     * See <a href="https://rocketmq.apache.org/docs/introduction/02concepts">here</a> for further discussion.（更多讨论请参见此处。）
     */
    private String consumerGroup;

    /**
     * Message model defines the way how messages are delivered to each consumer clients.（消息模型定义了消息如何投递给每个消费者客户端。）
     * </p>
     * RocketMQ supports two message models: clustering and broadcasting. If clustering is set, consumer clients with
     * the same {@link #consumerGroup} would only consume shards of the messages subscribed, which achieves load
     * balances; Conversely, if the broadcasting is set, each consumer client will consume all subscribed messages
     * separately.（RocketMQ支持两种消息模型：集群和广播。如果设置为集群，具有相同consumerGroup的消费者客户端只会消费所订阅消息的一部分，从而实现负载均衡；相反，如果设置为广播，每个消费者客户端将单独消费所有订阅的消息。）
     * </p>
     * This field defaults to clustering.（该字段默认为集群模式。）
     */
    private MessageModel messageModel = MessageModel.CLUSTERING;

    /**
     * Consuming point on consumer booting.（消费者启动时的消费点位。）
     * </p>
     * There are three consuming points:（有三种消费点位：）
     * <ul>
     * <li>
     * <code>CONSUME_FROM_LAST_OFFSET</code>: consumer clients pick up where it stopped previously.（消费者客户端从上次停止的位置继续消费。）
     * If it were a newly booting up consumer client, according aging of the consumer group, there are two
     * cases:（如果是新启动的消费者客户端，根据消费者组的时效，有两种情况：）
     * <ol>
     * <li>
     * if the consumer group is created so recently that the earliest message being subscribed has yet
     * expired, which means the consumer group represents a lately launched business, consuming will
     * start from the very beginning;（如果消费者组创建时间较近，所订阅的最早消息尚未过期，意味着消费者组代表一个最近上线的业务，消费将从最开始的位置开始。）
     * </li>
     * <li>
     * if the earliest message being subscribed has expired, consuming will start from the latest
     * messages, meaning messages born prior to the booting timestamp would be ignored.（如果所订阅的最早消息已过期，消费将从最新消息开始，意味着启动时间戳之前产生的消息将被忽略。）
     * </li>
     * </ol>
     * </li>
     * <li>
     * <code>CONSUME_FROM_FIRST_OFFSET</code>: Consumer client will start from earliest messages available.（消费者客户端将从最早可用的消息开始消费。）
     * </li>
     * <li>
     * <code>CONSUME_FROM_TIMESTAMP</code>: Consumer client will start from specified timestamp, which means
     * messages born prior to {@link #consumeTimestamp} will be ignored（消费者客户端将从指定的时间戳开始消费，这意味着在consumeTimestamp之前产生的消息将被忽略。）
     * </li>
     * </ul>
     */
    private ConsumeFromWhere consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET;

    /**
     * Backtracking consumption time with second precision. Time format is
     * 20131223171201<br>（回溯消费时间，精度为秒。时间格式为20131223171201）
     * Implying Seventeen twelve and 01 seconds on December 23, 2013 year<br>（表示2013年12月23日17时12分01秒）
     * Default backtracking consumption time Half an hour ago.（默认回溯消费时间为半小时前。）
     */
    private String consumeTimestamp = UtilAll.timeMillisToHumanString3(System.currentTimeMillis() - (1000 * 60 * 30));

    /**
     * Queue allocation algorithm specifying how message queues are allocated to each consumer clients.（队列分配算法，指定消息队列如何分配给每个消费者客户端。）
     */
    private AllocateMessageQueueStrategy allocateMessageQueueStrategy;

    /**
     * Subscription relationship（订阅关系）
     */
    private Map<String /* topic */, String /* sub expression */> subscription = new HashMap<>();

    /**
     * Message listener（消息监听器）
     */
    private MessageListener messageListener;

    /**
     * Listener to call if message queue assignment is changed.（消息队列分配变更时调用的监听器。）
     */
    private MessageQueueListener messageQueueListener;

    /**
     * Offset Storage（偏移量存储）
     */
    private OffsetStore offsetStore;

    // ========================================================================
    // 【OOM/Full GC 安全配置区域】以下配置项直接决定客户端内存占用
    // 配置不当会导致：消息积压 → 堆内存暴涨 → Full GC频繁 → OOM
    // 配置原则：consumer的消费能力 >= 拉取速度，避免消息在本地堆积
    // ========================================================================

    /**
     * 【OOM关键】最小消费线程数
     * 控制消费线程池的corePoolSize。线程数太少→消费速度跟不上→消息堆积→OOM。
     * 线程数太多→线程上下文切换开销→CPU飙高→间接影响GC。
     * 生产建议：根据CPU核数 × 2来设置，至少能覆盖分配的队列数。
     * 例如：4核8线程机器，分配了12个队列，建议设置 16~24。
     */
    private int consumeThreadMin = 20;

    /**
     * 【OOM关键】最大消费线程数
     * consumeThreadMin和consumeThreadMax相等时，线程池大小固定，避免动态调整带来的性能抖动。
     * 默认值20是经验值，但对高负载场景可能不够。
     * 设为-1会使用Integer.MAX_VALUE（危险！可能创建过多线程导致OOM）。
     * 检查范围：[1, 1000]
     */
    private int consumeThreadMax = 20;

    /**
     * 【中等影响】线程池动态调整阈值
     * 当所有队列累计积压消息数达到此值时，自动增加消费线程(每次+1)；
     * 低于80%阈值时，自动减少消费线程。
     * 默认100000条，对于高吞吐场景可能触发太频繁，可适当调高。
     * 注意：仅在consumeThreadMin < consumeThreadMax时生效。
     */
    private long adjustThreadPoolNumsThreshold = 100000;

    /**
     * 【OOM关联】并发消费模式下offset最大跨度
     * 仅对并发消费(MessageListenerConcurrently)有效。
     * 当ProcessQueue中最大offset与最小offset的差值超过此值时触发流控。
     * 防止消费线程卡住导致队列中消息offset跨度太大，间接防止内存泄漏式堆积。
     * 默认2000，范围[1, 65535]。
     * 大消息场景建议调小(如500)，小消息场景可适当调大(如5000)。
     */
    private int consumeConcurrentlyMaxSpan = 2000;

    /**
     * 【OOM最关键】每个队列本地缓存的消息条数上限
     * 这是防止OOM的第一道防线！每个MessageQueue对应一个ProcessQueue，
     * 达到此阈值后暂停拉取，直到消费掉一部分才继续。
     * 默认1000条，范围[1, 65535]。
     *
     * 内存计算公式：每个队列最大内存 = pullThresholdForQueue × 平均消息大小
     * 总内存 = 分配的队列数 × pullThresholdForQueue × 平均消息大小 × 2（消息对象开销）
     *
     * 典型值参考：
     * - 小消息(<1KB)：500~2000
     * - 中等消息(1KB~10KB)：200~500
     * - 大消息(>100KB)：20~50
     * - 超大消息(>1MB)：5~10
     */
    private int pullThresholdForQueue = 1000;

    /**
     * 【Pop模式流控】等待ACK的最大消息数
     * Pop模式下，消息被pop后进入"等待ack"状态，不计入pullThresholdForQueue。
     * 达到此阈值后暂停pop，防止未确认消息堆积过多。
     * 默认96，范围隐含受popBatchNums控制。
     * 注意：如果业务处理慢且不及时ack，此值需要调小。
     */
    private int popThresholdForQueue = 96;

    /**
     * 【OOM关键】每个队列本地缓存的消息体总大小上限(MiB)
     * 以消息体(body)大小计算，不包含消息属性、tag等开销。
     * 默认100 MiB，范围[1, 1024]。
     * 注意：实际内存占用 ≈ 消息体大小 × 2~3（因为还有Java对象头、属性Map等）。
     *
     * 建议与pullThresholdForQueue配合使用，哪个先达到就触发流控。
     * 大消息场景：应当将此值作为主要流控手段，pullThresholdForQueue设置小一些。
     * 小消息场景：pullThresholdForQueue是主要流控手段，此值可以设置大一些。
     */
    private int pullThresholdSizeForQueue = 100;

    /**
     * 【OOM最重要兜底】主题级别消息条数上限
     * 默认-1（不限制）。一旦设置，会覆盖pullThresholdForQueue的值：
     *   pullThresholdForQueue = pullThresholdForTopic / 分配的队列数
     * 这是防止OOM的全局兜底策略！即使某个topic的队列非常多，总消息数也不会超过此值。
     * 范围[1, 6553500]，-1表示不限制。
     *
     * 强烈建议生产环境开启！
     * 推荐值：topic总消息数上限 = JVM堆内存 / 平均消息大小 × 安全系数(0.3~0.5)
     * 例如：4GB堆，平均10KB消息，安全系数0.4
     *   pullThresholdForTopic = 4GB × 0.4 / 10KB ≈ 160000
     */
    private int pullThresholdForTopic = -1;

    /**
     * 【OOM重要兜底】主题级别消息体大小上限(MiB)
     * 默认-1（不限制）。一旦设置，会覆盖pullThresholdSizeForQueue的值：
     *   pullThresholdSizeForQueue = pullThresholdSizeForTopic / 分配的队列数
     * 范围[1, 102400]，-1表示不限制。
     *
     * 推荐值：约为JVM堆内存的30%~50%
     * 例如：4GB堆 → pullThresholdSizeForTopic ≈ 1200~2000 MiB
     */
    private int pullThresholdSizeForTopic = -1;

    /**
     * 【中等影响】拉取间隔(毫秒)
     * 每次拉取完成后等待多久再发起下一次拉取。
     * 默认0（无间隔，立即拉取），范围[0, 65535]。
     * 设置为>0可以降低拉取频率，减少网络开销和CPU，但会增加消费延迟。
     * 对OOM影响：间接。如果消费速度跟不上，适当增加拉取间隔可以给消费线程喘息机会。
     * 非顺序消费场景建议保持0，顺序消费场景可考虑设置10~50ms。
     */
    private long pullInterval = 0;

    /**
     * 【中等影响】每次批量消费的最大消息数
     * 消费线程一次从ProcessQueue取出并提交给MessageListener的消息数量。
     * 默认1，范围[1, 1024]。
     * 增大此值可以提升吞吐量（减少消费函数调用次数），但需要注意：
     * 1. 如果业务处理慢，增大此值会加剧消息堆积（因为一批消息处理时间变长）
     * 2. 如果业务处理快，增大此值可以显著提升吞吐
     * 3. 对OOM的间接影响：积压时此值越大，单次消费占用的临时内存越多
     * 推荐：业务处理快的场景设置8~32，处理慢的场景保持1~4
     */
    private int consumeMessageBatchMaxSize = 1;

    /**
     * 【中等影响】每次拉取的最大消息条数
     * 一次Pull请求从Broker拉取的最大消息数量。
     * 默认32，范围[1, 1024]。
     * 对OOM影响：单次拉取的数据量越大，突发的内存压力越大。
     * 如果平均消息很大(>100KB)，建议调小(8~16)；
     * 如果消息很小(<1KB)，可以调大(32~64)。
     * 注意：实际拉取数量还受pullBatchSizeInBytes限制。
     */
    private int pullBatchSize = 32;

    /**
     * 【中等影响】每次拉取的最大字节数
     * 一次Pull请求从Broker拉取的最大字节数，与pullBatchSize共同作用，
     * 哪个先达到就按哪个。
     * 默认256KB，即一次拉取最大256KB数据。
     * 大消息场景：需要适当调大此值，否则每次只能拉取很少几条消息。
     * 注意：此值不是绝对限制，Broker可能返回超过此值的数据。
     */
    private int pullBatchSizeInBytes = 256 * 1024;

    /**
     * 是否在每次拉取时更新订阅关系
     */
    private boolean postSubscriptionWhenPull = false;

    /**
     * 是否为单位模式
     */
    private boolean unitMode = false;

    /**
     * 【间接影响】最大重试消费次数
     * 并发模式下-1表示16次，顺序模式下-1表示Integer.MAX_VALUE。
     * 重试次数过多会导致消息在重试队列中滞留，间接影响内存。
     * 建议：根据业务容忍度设置，一般3~5次即可，过多重试没有意义。
     */
    private int maxReconsumeTimes = -1;

    /**
     * 【流控辅助】流控时暂停拉取的时间(毫秒)
     * 当触发流控（消息积压）时，对于当前正在处理的队列，暂停多长时间再重新尝试。
     * 默认1000ms。此值太小会导致流控期间频繁重试（浪费CPU），
     * 太大会导致消费延迟增加。
     */
    private long suspendCurrentQueueTimeMillis = 1000;

    /**
     * 【间接影响】消费超时时间(分钟)
     * 消息阻塞消费线程的最大时间，超过此时间会被认为消费超时。
     * 默认15分钟。
     * 如果业务处理耗时较长，需要适当调大此值，否则消息会被认为消费失败而重试，
     * 导致消息在ProcessQueue中滞留更久，增加内存压力。
     */
    private long consumeTimeout = 15;

    /**
     * 【Pop模式】消息不可见时间(毫秒)
     * Pop模式下，消息pop后对其他消费者不可见的时间。
     * 范围[5000, 300000]，默认60000(1分钟)。
     * 如果业务处理时间超过此值，需要调大，否则消息会被其他消费者重复消费。
     * 对OOM的间接影响：此值太小会导致重复消费，增加系统整体负载。
     */
    private long popInvisibleTime = 60000;

    /**
     * 【Pop模式】批量pop数量
     * 一次pop请求最多返回的消息数量，范围[1, 32]，默认32。
     */
    private int popBatchNums = 32;

    /**
     * 优雅关闭时等待消费完成的最大时间(毫秒)
     * 0表示不等待，直接关闭。建议生产环境设置5000~30000ms。
     */
    private long awaitTerminationMillisWhenShutdown = 0;

    /**
     * Interface of asynchronous transfer data（异步传输数据接口）
     */
    private TraceDispatcher traceDispatcher = null;

    // force to use client rebalance（强制使用客户端重平衡）
    private boolean clientRebalance = true;

    private RPCHook rpcHook = null;

    /**
     * Default constructor.（默认构造函数。）
     */
    public DefaultMQPushConsumer() {
        this(MixAll.DEFAULT_CONSUMER_GROUP, null, new AllocateMessageQueueAveragely());
    }

    /**
     * Constructor specifying consumer group.（指定消费者组的构造函数。）
     *
     * @param consumerGroup Consumer group.（消费者组。）
     */
    public DefaultMQPushConsumer(final String consumerGroup) {
        this(consumerGroup, null, new AllocateMessageQueueAveragely());
    }

    /**
     * Constructor specifying RPC hook.（指定RPC钩子的构造函数。）
     *
     * @param rpcHook RPC hook to execute before each remoting command.（在每个远程命令之前执行的RPC钩子。）
     */
    public DefaultMQPushConsumer(RPCHook rpcHook) {
        this(MixAll.DEFAULT_CONSUMER_GROUP, rpcHook, new AllocateMessageQueueAveragely());
    }

    /**
     * Constructor specifying consumer group, RPC hook.（指定消费者组和RPC钩子的构造函数。）
     *
     * @param consumerGroup Consumer group.（消费者组。）
     * @param rpcHook       RPC hook to execute before each remoting command.（在每个远程命令之前执行的RPC钩子。）
     */
    public DefaultMQPushConsumer(final String consumerGroup, RPCHook rpcHook) {
        this(consumerGroup, rpcHook, new AllocateMessageQueueAveragely());
    }

    /**
     * Constructor specifying consumer group, enabled msg trace flag and customized trace topic name.（指定消费者组、消息追踪开关和自定义追踪主题名的构造函数。）
     *
     * @param consumerGroup        Consumer group.（消费者组。）
     * @param enableMsgTrace       Switch flag instance for message trace.（消息追踪开关标志。）
     * @param customizedTraceTopic The name value of message trace topic.If you don't config,you can use the default trace topic name.（消息追踪主题的名称。如果不配置，可以使用默认的追踪主题名。）
     */
    public DefaultMQPushConsumer(final String consumerGroup, boolean enableMsgTrace, final String customizedTraceTopic) {
        this(consumerGroup, null, new AllocateMessageQueueAveragely(), enableMsgTrace, customizedTraceTopic);
    }

    /**
     * Constructor specifying consumer group, RPC hook and message queue allocating algorithm.（指定消费者组、RPC钩子和消息队列分配算法的构造函数。）
     *
     * @param consumerGroup                Consumer group.（消费者组。）
     * @param rpcHook                      RPC hook to execute before each remoting command.（在每个远程命令之前执行的RPC钩子。）
     * @param allocateMessageQueueStrategy Message queue allocating algorithm.（消息队列分配算法。）
     */
    public DefaultMQPushConsumer(final String consumerGroup, RPCHook rpcHook, AllocateMessageQueueStrategy allocateMessageQueueStrategy) {
        this(consumerGroup, rpcHook, allocateMessageQueueStrategy, false, null);
    }

    /**
     * Constructor specifying consumer group, RPC hook, message queue allocating algorithm, enabled msg trace flag and customized trace topic name.（指定消费者组、RPC钩子、消息队列分配算法、消息追踪开关和自定义追踪主题名的构造函数。）
     *
     * @param consumerGroup                Consumer group.（消费者组。）
     * @param rpcHook                      RPC hook to execute before each remoting command.（在每个远程命令之前执行的RPC钩子。）
     * @param allocateMessageQueueStrategy message queue allocating algorithm.（消息队列分配算法。）
     * @param enableMsgTrace               Switch flag instance for message trace.（消息追踪开关标志。）
     * @param customizedTraceTopic         The name value of message trace topic.If you don't config,you can use the default trace topic name.（消息追踪主题的名称。如果不配置，可以使用默认的追踪主题名。）
     */
    public DefaultMQPushConsumer(final String consumerGroup, RPCHook rpcHook, AllocateMessageQueueStrategy allocateMessageQueueStrategy, boolean enableMsgTrace, final String customizedTraceTopic) {
        this.consumerGroup = consumerGroup;
        this.rpcHook = rpcHook;
        this.allocateMessageQueueStrategy = allocateMessageQueueStrategy;
        // 注意
        defaultMQPushConsumerImpl = new DefaultMQPushConsumerImpl(this, rpcHook);
        this.enableTrace = enableMsgTrace;
        this.traceTopic = customizedTraceTopic;
    }

    /**
     * Constructor specifying namespace and consumer group.（指定命名空间和消费者组的构造函数。）
     *
     * @param namespace     Namespace for this MQ Producer instance.（此MQ生产者实例的命名空间。）
     * @param consumerGroup Consumer group.（消费者组。）
     */
    @Deprecated
    public DefaultMQPushConsumer(final String namespace, final String consumerGroup) {
        this(namespace, consumerGroup, null, new AllocateMessageQueueAveragely());
    }

    /**
     * Constructor specifying namespace, consumer group and RPC hook .（指定命名空间、消费者组和RPC钩子的构造函数。）
     *
     * @param namespace     Namespace for this MQ Producer instance.（此MQ生产者实例的命名空间。）
     * @param consumerGroup Consumer group.（消费者组。）
     * @param rpcHook       RPC hook to execute before each remoting command.（在每个远程命令之前执行的RPC钩子。）
     */
    @Deprecated
    public DefaultMQPushConsumer(final String namespace, final String consumerGroup, RPCHook rpcHook) {
        this(namespace, consumerGroup, rpcHook, new AllocateMessageQueueAveragely());
    }

    /**
     * Constructor specifying namespace, consumer group, RPC hook and message queue allocating algorithm.（指定命名空间、消费者组、RPC钩子和消息队列分配算法的构造函数。）
     *
     * @param namespace                    Namespace for this MQ Producer instance.（此MQ生产者实例的命名空间。）
     * @param consumerGroup                Consumer group.（消费者组。）
     * @param rpcHook                      RPC hook to execute before each remoting command.（在每个远程命令之前执行的RPC钩子。）
     * @param allocateMessageQueueStrategy Message queue allocating algorithm.（消息队列分配算法。）
     */
    @Deprecated
    public DefaultMQPushConsumer(final String namespace, final String consumerGroup, RPCHook rpcHook, AllocateMessageQueueStrategy allocateMessageQueueStrategy) {
        this.consumerGroup = consumerGroup;
        this.namespace = namespace;
        this.rpcHook = rpcHook;
        this.allocateMessageQueueStrategy = allocateMessageQueueStrategy;
        defaultMQPushConsumerImpl = new DefaultMQPushConsumerImpl(this, rpcHook);
    }

    /**
     * Constructor specifying namespace, consumer group, RPC hook, message queue allocating algorithm, enabled msg trace flag and customized trace topic name.（指定命名空间、消费者组、RPC钩子、消息队列分配算法、消息追踪开关和自定义追踪主题名的构造函数。）
     *
     * @param namespace                    Namespace for this MQ Producer instance.（此MQ生产者实例的命名空间。）
     * @param consumerGroup                Consumer group.（消费者组。）
     * @param rpcHook                      RPC hook to execute before each remoting command.（在每个远程命令之前执行的RPC钩子。）
     * @param allocateMessageQueueStrategy message queue allocating algorithm.（消息队列分配算法。）
     * @param enableMsgTrace               Switch flag instance for message trace.（消息追踪开关标志。）
     * @param customizedTraceTopic         The name value of message trace topic.If you don't config,you can use the default trace topic name.（消息追踪主题的名称。如果不配置，可以使用默认的追踪主题名。）
     */
    @Deprecated
    public DefaultMQPushConsumer(final String namespace, final String consumerGroup, RPCHook rpcHook, AllocateMessageQueueStrategy allocateMessageQueueStrategy, boolean enableMsgTrace, final String customizedTraceTopic) {
        this.consumerGroup = consumerGroup;
        this.namespace = namespace;
        this.rpcHook = rpcHook;
        this.allocateMessageQueueStrategy = allocateMessageQueueStrategy;
        defaultMQPushConsumerImpl = new DefaultMQPushConsumerImpl(this, rpcHook);
        this.enableTrace = enableMsgTrace;
        this.traceTopic = customizedTraceTopic;
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public void createTopic(String key, String newTopic, int queueNum, Map<String, String> attributes) throws MQClientException {
        createTopic(key, withNamespace(newTopic), queueNum, 0, null);
    }

    @Override
    public void setUseTLS(boolean useTLS) {
        super.setUseTLS(useTLS);
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public void createTopic(String key, String newTopic, int queueNum, int topicSysFlag, Map<String, String> attributes) throws MQClientException {
        this.defaultMQPushConsumerImpl.createTopic(key, withNamespace(newTopic), queueNum, topicSysFlag);
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public long searchOffset(MessageQueue mq, long timestamp) throws MQClientException {
        return this.defaultMQPushConsumerImpl.searchOffset(queueWithNamespace(mq), timestamp);
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public long maxOffset(MessageQueue mq) throws MQClientException {
        return this.defaultMQPushConsumerImpl.maxOffset(queueWithNamespace(mq));
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public long minOffset(MessageQueue mq) throws MQClientException {
        return this.defaultMQPushConsumerImpl.minOffset(queueWithNamespace(mq));
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public long earliestMsgStoreTime(MessageQueue mq) throws MQClientException {
        return this.defaultMQPushConsumerImpl.earliestMsgStoreTime(queueWithNamespace(mq));
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public QueryResult queryMessage(String topic, String key, int maxNum, long begin, long end) throws MQClientException, InterruptedException {
        return this.defaultMQPushConsumerImpl.queryMessage(withNamespace(topic), key, maxNum, begin, end);
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    @Override
    public MessageExt viewMessage(String topic, String msgId) throws RemotingException, MQBrokerException, InterruptedException, MQClientException {
        try {
            MessageDecoder.decodeMessageId(msgId);
            return this.defaultMQPushConsumerImpl.viewMessage(withNamespace(topic), msgId);
        } catch (Exception e) {
            // Ignore（忽略）
        }
        return this.defaultMQPushConsumerImpl.queryMessageByUniqKey(withNamespace(topic), msgId);
    }

    public AllocateMessageQueueStrategy getAllocateMessageQueueStrategy() {
        return allocateMessageQueueStrategy;
    }

    public void setAllocateMessageQueueStrategy(AllocateMessageQueueStrategy allocateMessageQueueStrategy) {
        this.allocateMessageQueueStrategy = allocateMessageQueueStrategy;
    }

    public int getConsumeConcurrentlyMaxSpan() {
        return consumeConcurrentlyMaxSpan;
    }

    public void setConsumeConcurrentlyMaxSpan(int consumeConcurrentlyMaxSpan) {
        this.consumeConcurrentlyMaxSpan = consumeConcurrentlyMaxSpan;
    }

    public ConsumeFromWhere getConsumeFromWhere() {
        return consumeFromWhere;
    }

    public void setConsumeFromWhere(ConsumeFromWhere consumeFromWhere) {
        this.consumeFromWhere = consumeFromWhere;
    }

    public int getConsumeMessageBatchMaxSize() {
        return consumeMessageBatchMaxSize;
    }

    public void setConsumeMessageBatchMaxSize(int consumeMessageBatchMaxSize) {
        this.consumeMessageBatchMaxSize = consumeMessageBatchMaxSize;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public int getConsumeThreadMax() {
        return consumeThreadMax;
    }

    public void setConsumeThreadMax(int consumeThreadMax) {
        this.consumeThreadMax = consumeThreadMax;
    }

    public int getConsumeThreadMin() {
        return consumeThreadMin;
    }

    public void setConsumeThreadMin(int consumeThreadMin) {
        this.consumeThreadMin = consumeThreadMin;
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    public DefaultMQPushConsumerImpl getDefaultMQPushConsumerImpl() {
        return defaultMQPushConsumerImpl;
    }

    public MessageListener getMessageListener() {
        return messageListener;
    }

    public void setMessageListener(MessageListener messageListener) {
        this.messageListener = messageListener;
    }

    public MessageModel getMessageModel() {
        return messageModel;
    }

    public void setMessageModel(MessageModel messageModel) {
        this.messageModel = messageModel;
    }

    public int getPullBatchSize() {
        return pullBatchSize;
    }

    public void setPullBatchSize(int pullBatchSize) {
        this.pullBatchSize = pullBatchSize;
    }

    public long getPullInterval() {
        return pullInterval;
    }

    public void setPullInterval(long pullInterval) {
        this.pullInterval = pullInterval;
    }

    public int getPullThresholdForQueue() {
        return pullThresholdForQueue;
    }

    public void setPullThresholdForQueue(int pullThresholdForQueue) {
        this.pullThresholdForQueue = pullThresholdForQueue;
    }

    public int getPopThresholdForQueue() {
        return popThresholdForQueue;
    }

    public void setPopThresholdForQueue(int popThresholdForQueue) {
        this.popThresholdForQueue = popThresholdForQueue;
    }

    public int getPullThresholdForTopic() {
        return pullThresholdForTopic;
    }

    public void setPullThresholdForTopic(final int pullThresholdForTopic) {
        this.pullThresholdForTopic = pullThresholdForTopic;
    }

    public int getPullThresholdSizeForQueue() {
        return pullThresholdSizeForQueue;
    }

    public void setPullThresholdSizeForQueue(final int pullThresholdSizeForQueue) {
        this.pullThresholdSizeForQueue = pullThresholdSizeForQueue;
    }

    public int getPullThresholdSizeForTopic() {
        return pullThresholdSizeForTopic;
    }

    public void setPullThresholdSizeForTopic(final int pullThresholdSizeForTopic) {
        this.pullThresholdSizeForTopic = pullThresholdSizeForTopic;
    }

    public Map<String, String> getSubscription() {
        return subscription;
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    public void setSubscription(Map<String, String> subscription) {
        Map<String, String> subscriptionWithNamespace = new HashMap<>(subscription.size(), 1);
        for (Entry<String, String> topicEntry : subscription.entrySet()) {
            subscriptionWithNamespace.put(withNamespace(topicEntry.getKey()), topicEntry.getValue());
        }
        this.subscription = subscriptionWithNamespace;
    }

    /**
     * Send message back to broker which will be re-delivered in future.（将消息发送回Broker，该消息将在未来重新投递。）
     * <p>
     * This method will be removed or it's visibility will be changed in a certain version after April 5, 2020, so
     * please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除或其可见性将被更改，请不要使用此方法。）
     *
     * @param msg        Message to send back.（要发送回的消息。）
     * @param delayLevel delay level.（延迟级别。）
     * @throws RemotingException    if there is any network-tier error.（如果有任何网络层错误。）
     * @throws MQBrokerException    if there is any broker error.（如果有任何Broker错误。）
     * @throws InterruptedException if the thread is interrupted.（如果线程被中断。）
     * @throws MQClientException    if there is any client error.（如果有任何客户端错误。）
     */
    @Deprecated
    @Override
    public void sendMessageBack(MessageExt msg, int delayLevel) throws RemotingException, MQBrokerException, InterruptedException, MQClientException {
        msg.setTopic(withNamespace(msg.getTopic()));
        this.defaultMQPushConsumerImpl.sendMessageBack(msg, delayLevel, msg.getBrokerName());
    }

    /**
     * Send message back to the broker whose name is <code>brokerName</code> and the message will be re-delivered in
     * future.（将消息发送回指定brokerName的Broker，该消息将在未来重新投递。）
     * <p>
     * This method will be removed or it's visibility will be changed in a certain version after April 5, 2020, so
     * please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除或其可见性将被更改，请不要使用此方法。）
     *
     * @param msg        Message to send back.（要发送回的消息。）
     * @param delayLevel delay level.（延迟级别。）
     * @param brokerName broker name.（Broker名称。）
     * @throws RemotingException    if there is any network-tier error.（如果有任何网络层错误。）
     * @throws MQBrokerException    if there is any broker error.（如果有任何Broker错误。）
     * @throws InterruptedException if the thread is interrupted.（如果线程被中断。）
     * @throws MQClientException    if there is any client error.（如果有任何客户端错误。）
     */
    @Deprecated
    @Override
    public void sendMessageBack(MessageExt msg, int delayLevel, String brokerName) throws RemotingException, MQBrokerException, InterruptedException, MQClientException {
        msg.setTopic(withNamespace(msg.getTopic()));
        this.defaultMQPushConsumerImpl.sendMessageBack(msg, delayLevel, brokerName);
    }

    @Override
    public Set<MessageQueue> fetchSubscribeMessageQueues(String topic) throws MQClientException {
        return this.defaultMQPushConsumerImpl.fetchSubscribeMessageQueues(withNamespace(topic));
    }

    /**
     * This method gets internal infrastructure readily to serve. Instances must call this method after configuration.（此方法使内部基础设施就绪以提供服务。实例必须在配置完成后调用此方法。）
     *
     * @throws MQClientException if there is any client error.（如果有任何客户端错误。）
     */
    @Override
    public void start() throws MQClientException {
        setConsumerGroup(NamespaceUtil.wrapNamespace(this.getNamespace(), this.consumerGroup));
        this.defaultMQPushConsumerImpl.start();
        if (enableTrace) {
            try {
                AsyncTraceDispatcher dispatcher = new AsyncTraceDispatcher(consumerGroup, TraceDispatcher.Type.CONSUME, getTraceMsgBatchNum(), traceTopic, rpcHook);
                dispatcher.setHostConsumer(this.defaultMQPushConsumerImpl);
                dispatcher.setNamespaceV2(namespaceV2);
                traceDispatcher = dispatcher;
                this.defaultMQPushConsumerImpl.registerConsumeMessageHook(new ConsumeMessageTraceHookImpl(traceDispatcher));
            } catch (Throwable e) {
                log.error("system mqtrace hook init failed ,maybe can't send msg trace data");
            }
        }
        if (null != traceDispatcher) {
            if (traceDispatcher instanceof AsyncTraceDispatcher) {
                ((AsyncTraceDispatcher) traceDispatcher).getTraceProducer().setUseTLS(isUseTLS());
            }
            try {
                traceDispatcher.start(this.getNamesrvAddr(), this.getAccessChannel());
            } catch (MQClientException e) {
                log.warn("trace dispatcher start failed ", e);
            }
        }
    }

    /**
     * Shut down this client and releasing underlying resources.（关闭此客户端并释放底层资源。）
     */
    @Override
    public void shutdown() {
        this.defaultMQPushConsumerImpl.shutdown(awaitTerminationMillisWhenShutdown);
        if (null != traceDispatcher) {
            traceDispatcher.shutdown();
        }
    }

    @Override
    @Deprecated
    public void registerMessageListener(MessageListener messageListener) {
        this.messageListener = messageListener;
        this.defaultMQPushConsumerImpl.registerMessageListener(messageListener);
    }

    /**
     * Register a callback to execute on message arrival for concurrent consuming.（注册一个在消息到达时执行的回调，用于并发消费。）
     *
     * @param messageListener message handling callback.（消息处理回调。）
     */
    @Override
    public void registerMessageListener(MessageListenerConcurrently messageListener) {
        this.messageListener = messageListener;
        this.defaultMQPushConsumerImpl.registerMessageListener(messageListener);
    }

    /**
     * Register a callback to execute on message arrival for orderly consuming.（注册一个在消息到达时执行的回调，用于顺序消费。）
     *
     * @param messageListener message handling callback.（消息处理回调。）
     */
    @Override
    public void registerMessageListener(MessageListenerOrderly messageListener) {
        this.messageListener = messageListener;
        this.defaultMQPushConsumerImpl.registerMessageListener(messageListener);
    }

    /**
     * Subscribe a topic to consuming subscription.（订阅一个主题以进行消费订阅。）
     *
     * @param topic         topic to subscribe.（要订阅的主题。）
     * @param subExpression subscription expression.it only support or operation such as "tag1 || tag2 || tag3" <br>（订阅表达式，仅支持或操作，如"tag1 || tag2 || tag3"）
     *                      if null or * expression,meaning subscribe all（如果为null或*表达式，表示订阅所有。）
     * @throws MQClientException if there is any client error.（如果有任何客户端错误。）
     */
    @Override
    public void subscribe(String topic, String subExpression) throws MQClientException {
        this.defaultMQPushConsumerImpl.subscribe(withNamespace(topic), subExpression);
    }

    /**
     * Subscribe a topic to consuming subscription.（订阅一个主题以进行消费订阅。）
     *
     * @param topic             topic to consume.（要消费的主题。）
     * @param fullClassName     full class name,must extend org.apache.rocketmq.common.filter.MessageFilter（完整类名，必须继承org.apache.rocketmq.common.filter.MessageFilter）
     * @param filterClassSource class source code,used UTF-8 file encoding,must be responsible for your code safety（类源代码，使用UTF-8文件编码，必须对自己的代码安全负责）
     */
    @Override
    public void subscribe(String topic, String fullClassName, String filterClassSource) throws MQClientException {
        this.defaultMQPushConsumerImpl.subscribe(withNamespace(topic), fullClassName, filterClassSource);
    }

    /**
     * Subscribe a topic by message selector.（通过消息选择器订阅一个主题。）
     *
     * @param topic           topic to consume.（要消费的主题。）
     * @param messageSelector {@link org.apache.rocketmq.client.consumer.MessageSelector}
     * @see org.apache.rocketmq.client.consumer.MessageSelector#bySql
     * @see org.apache.rocketmq.client.consumer.MessageSelector#byTag
     */
    @Override
    public void subscribe(final String topic, final MessageSelector messageSelector) throws MQClientException {
        this.defaultMQPushConsumerImpl.subscribe(withNamespace(topic), messageSelector);
    }

    /**
     * Un-subscribe the specified topic from subscription.（取消订阅指定的主题。）
     *
     * @param topic message topic（消息主题）
     */
    @Override
    public void unsubscribe(String topic) {
        this.defaultMQPushConsumerImpl.unsubscribe(topic);
    }

    /**
     * Update the message consuming thread core pool size.（更新消息消费线程的核心池大小。）
     *
     * @param corePoolSize new core pool size.（新的核心池大小。）
     */
    @Override
    public void updateCorePoolSize(int corePoolSize) {
        this.defaultMQPushConsumerImpl.updateCorePoolSize(corePoolSize);
    }

    /**
     * Suspend pulling new messages.（暂停拉取新消息。）
     */
    @Override
    public void suspend() {
        this.defaultMQPushConsumerImpl.suspend();
    }

    /**
     * Resume pulling.（恢复拉取。）
     */
    @Override
    public void resume() {
        this.defaultMQPushConsumerImpl.resume();
    }

    public boolean isPause() {
        return this.defaultMQPushConsumerImpl.isPause();
    }

    public boolean isConsumeOrderly() {
        return this.defaultMQPushConsumerImpl.isConsumeOrderly();
    }

    public void registerConsumeMessageHook(final ConsumeMessageHook hook) {
        this.defaultMQPushConsumerImpl.registerConsumeMessageHook(hook);
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    public OffsetStore getOffsetStore() {
        return offsetStore;
    }

    /**
     * This method will be removed in a certain version after April 5, 2020, so please do not use this method.（此方法将在2020年4月5日之后的某个版本中移除，请不要使用此方法。）
     */
    @Deprecated
    public void setOffsetStore(OffsetStore offsetStore) {
        this.offsetStore = offsetStore;
    }

    public String getConsumeTimestamp() {
        return consumeTimestamp;
    }

    public void setConsumeTimestamp(String consumeTimestamp) {
        this.consumeTimestamp = consumeTimestamp;
    }

    public boolean isPostSubscriptionWhenPull() {
        return postSubscriptionWhenPull;
    }

    public void setPostSubscriptionWhenPull(boolean postSubscriptionWhenPull) {
        this.postSubscriptionWhenPull = postSubscriptionWhenPull;
    }

    @Override
    public boolean isUnitMode() {
        return unitMode;
    }

    @Override
    public void setUnitMode(boolean isUnitMode) {
        this.unitMode = isUnitMode;
    }

    public long getAdjustThreadPoolNumsThreshold() {
        return adjustThreadPoolNumsThreshold;
    }

    public void setAdjustThreadPoolNumsThreshold(long adjustThreadPoolNumsThreshold) {
        this.adjustThreadPoolNumsThreshold = adjustThreadPoolNumsThreshold;
    }

    public int getMaxReconsumeTimes() {
        return maxReconsumeTimes;
    }

    public void setMaxReconsumeTimes(final int maxReconsumeTimes) {
        this.maxReconsumeTimes = maxReconsumeTimes;
    }

    public long getSuspendCurrentQueueTimeMillis() {
        return suspendCurrentQueueTimeMillis;
    }

    public void setSuspendCurrentQueueTimeMillis(final long suspendCurrentQueueTimeMillis) {
        this.suspendCurrentQueueTimeMillis = suspendCurrentQueueTimeMillis;
    }

    public long getConsumeTimeout() {
        return consumeTimeout;
    }

    public void setConsumeTimeout(final long consumeTimeout) {
        this.consumeTimeout = consumeTimeout;
    }

    public long getPopInvisibleTime() {
        return popInvisibleTime;
    }

    public void setPopInvisibleTime(long popInvisibleTime) {
        this.popInvisibleTime = popInvisibleTime;
    }

    public long getAwaitTerminationMillisWhenShutdown() {
        return awaitTerminationMillisWhenShutdown;
    }

    public void setAwaitTerminationMillisWhenShutdown(long awaitTerminationMillisWhenShutdown) {
        this.awaitTerminationMillisWhenShutdown = awaitTerminationMillisWhenShutdown;
    }

    public int getPullBatchSizeInBytes() {
        return pullBatchSizeInBytes;
    }

    public void setPullBatchSizeInBytes(int pullBatchSizeInBytes) {
        this.pullBatchSizeInBytes = pullBatchSizeInBytes;
    }

    public TraceDispatcher getTraceDispatcher() {
        return traceDispatcher;
    }

    public int getPopBatchNums() {
        return popBatchNums;
    }

    public void setPopBatchNums(int popBatchNums) {
        this.popBatchNums = popBatchNums;
    }

    public boolean isClientRebalance() {
        return clientRebalance;
    }

    public void setClientRebalance(boolean clientRebalance) {
        this.clientRebalance = clientRebalance;
    }

    public MessageQueueListener getMessageQueueListener() {
        return messageQueueListener;
    }

    public void setMessageQueueListener(MessageQueueListener messageQueueListener) {
        this.messageQueueListener = messageQueueListener;
    }
}
