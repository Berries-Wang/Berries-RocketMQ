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
package org.apache.rocketmq.store.config;

import java.io.File;

import org.apache.rocketmq.common.annotation.ImportantField;
import org.apache.rocketmq.store.ConsumeQueue;
import org.apache.rocketmq.store.StoreType;
import org.apache.rocketmq.store.queue.BatchConsumeQueue;

public class MessageStoreConfig {

    public static final String MULTI_PATH_SPLITTER = System.getProperty("rocketmq.broker.multiPathSplitter", ",");

    // ========================================================================
    // 1. 存储路径配置
    // ========================================================================

    /** 消息日志数据存储的根目录 */
    @ImportantField
    private String storePathRootDir = System.getProperty("user.home") + File.separator + "store";

    /** CommitLog 存储目录，为 null 时默认使用 storePathRootDir/commitlog */
    @ImportantField
    private String storePathCommitLog = null;

    /** DLedger CommitLog 存储目录，DLedger 模式下使用 */
    @ImportantField
    private String storePathDLedgerCommitLog = null;

    /** Epoch 文件存储目录，用于记录主从切换的 epoch 信息 */
    @ImportantField
    private String storePathEpochFile = null;

    /** Broker 身份标识文件存储目录 */
    @ImportantField
    private String storePathBrokerIdentity = null;

    /** 只读 CommitLog 存储路径，多级存储场景下使用 */
    private String readOnlyCommitLogStorePaths = null;

    // ========================================================================
    // 2. 文件大小配置
    // ========================================================================

    /** CommitLog 文件大小，默认 1GB */
    private int mappedFileSizeCommitLog = 1024 * 1024 * 1024;

    /** CompactionLog 文件大小，默认 100MB */
    private int compactionMappedFileSize = 100 * 1024 * 1024;

    /** CompactionLog ConsumeQueue 文件大小，默认 10MB */
    private int compactionCqMappedFileSize = 10 * 1024 * 1024;

    /** Compaction 调度间隔，默认 15 分钟（毫秒） */
    private int compactionScheduleInternal = 15 * 60 * 1000;

    /** 最大 OffsetMap 大小，默认 100MB */
    private int maxOffsetMapSize = 100 * 1024 * 1024;

    /** Compaction 线程数，默认 6 */
    private int compactionThreadNum = 6;

    /** 是否启用日志压缩（Compaction），默认 true */
    private boolean enableCompaction = true;

    /** TimerLog 文件大小，默认 100MB。定时消息（时间轮）使用 */
    private int mappedFileSizeTimerLog = 100 * 1024 * 1024;

    // ========================================================================
    // 3. 定时消息（Timer Message / 时间轮）配置
    // ========================================================================

    /** 时间轮精度（毫秒），可选值：1000/500/200/100，默认 1000ms */
    private int timerPrecisionMs = 1000;

    /**
     * 滚动窗口槽位数，默认 2 天（3600*24*2）。
     * 延迟时间超过此值的消息会被标记为 MAGIC_ROLL，分多次滚动投递
     */
    private int timerRollWindowSlot = 3600 * 24 * 2;

    /** TimerLog 刷盘间隔（毫秒），默认 1000ms */
    private int timerFlushIntervalMs = 1000;

    /** 出队获取消息线程数，默认 3 */
    private int timerGetMessageThreadNum = 3;

    /** 出队写入消息线程数，默认 3 */
    private int timerPutMessageThreadNum = 3;

    /** 是否使用 Disruptor 队列（提升入队/出队性能），默认 false */
    private boolean timerEnableDisruptor = false;

    /** 是否启用定时消息指标检查，默认 true */
    private boolean timerEnableCheckMetrics = true;

    /**
     * 是否拦截传统延迟级别消息，由时间轮接管。设为 true 时，
     * setDelayTimeLevel() 也会走时间轮路径，默认 false
     */
    private boolean timerInterceptDelayLevel = false;

    /** 定时消息最大延迟时间（秒），默认 3 天（259200秒） */
    private int timerMaxDelaySec = 3600 * 24 * 3;

    /** 是否启用时间轮（定时消息功能），默认 true */
    private boolean timerWheelEnable = true;

    /**
     * 启动后隐藏时间（毫秒），在这段时间内 Broker 不对外提供服务。
     * 1. 注册到 broker 在 (startTime + disappearTimeAfterStart) 之后
     * 2. 内部消息交换在 (startTime + disappearTimeAfterStart) 之后
     * A. PopReviveService
     * B. TimerDequeueGetService
     * -1 表示不启用
     */
    @ImportantField
    private int disappearTimeAfterStart = -1;

    /** 是否停止入队（调试用），true 时不再处理新的定时消息，默认 false */
    private boolean timerStopEnqueue = false;

    /** 定时消息指标检查时间（cron 分钟部分），如 "05" 表示每小时第5分钟 */
    private String timerCheckMetricsWhen = "05";

    /** 遇到未知错误是否跳过，true 可能导致消息丢失（慎用），默认 false */
    private boolean timerSkipUnknownError = false;

    /** 是否启用出队预热（预读取数据到 page cache），默认 false */
    private boolean timerWarmEnable = false;

    /** 是否停止出队（调试用），true 时不再投递到期的定时消息，默认 false */
    private boolean timerStopDequeue = false;

    /** 每个时间槽位的拥塞阈值，超过此值触发随机拒绝。默认 Integer.MAX_VALUE（不限制） */
    private int timerCongestNumEachSlot = Integer.MAX_VALUE;

    /** 指标小阈值，小于此值的 topic 指标会被重新计算，默认 1000000 */
    private int timerMetricSmallThreshold = 1000000;

    /** 进度日志输出间隔（毫秒），默认 10 秒 */
    private int timerProgressLogIntervalMs = 10 * 1000;

    // ========================================================================
    // 4. 存储类型配置
    // ========================================================================

    /** 存储类型：default（默认）或 defaultRocksDB */
    @ImportantField
    private String storeType = StoreType.DEFAULT.getStoreType();

    /** 是否将元数据 JSON 迁移到 RocksDB（仅 storeType=RocksDB 时有效），默认 false */
    private boolean transferMetadataJsonToRocksdb = false;

    // ========================================================================
    // 5. ConsumeQueue 配置
    // ========================================================================

    /** ConsumeQueue 文件大小，默认 30 万个条目（约 600万字节） */
    private int mappedFileSizeConsumeQueue = 300000 * ConsumeQueue.CQ_STORE_UNIT_SIZE;

    /** 是否启用 ConsumeQueue 扩展属性，默认 false */
    private boolean enableConsumeQueueExt = false;

    /** ConsumeQueue 扩展文件大小，默认 48MB */
    private int mappedFileSizeConsumeQueueExt = 48 * 1024 * 1024;

    /** 批量 ConsumeQueue 文件大小，默认 30 万个条目 */
    private int mapperFileSizeBatchConsumeQueue = 300000 * BatchConsumeQueue.CQ_STORE_UNIT_SIZE;

    /**
     * 过滤位图 Bit 长度，用于消息过滤。
     * 由计算过滤位图的管道设置
     */
    private int bitMapLengthConsumeQueueExt = 64;

    // ========================================================================
    // 6. 刷盘与提交策略
    // ========================================================================

    /** CommitLog 刷盘间隔（毫秒），将数据刷到磁盘 */
    @ImportantField
    private int flushIntervalCommitLog = 500;

    /**
     * 仅 TransientStorePool 启用时有效。
     * 将数据从堆外内存提交到 FileChannel 的间隔（毫秒）
     */
    @ImportantField
    private int commitIntervalCommitLog = 200;

    /** 最大可恢复的 CommitLog 文件数，默认 30 */
    private int maxRecoveryCommitlogFiles = 30;

    /** 磁盘空间使用率告警阈值（百分比），默认 90% */
    private int diskSpaceWarningLevelRatio = 90;

    /** 磁盘空间强制清理阈值（百分比），默认 85% */
    private int diskSpaceCleanForciblyRatio = 85;

    /**
     * 4.0.x 引入。决定 putMessage 时是否使用互斥 ReentrantLock，默认 true
     */
    private boolean useReentrantLockWhenPutMessage = true;

    /** 是否定时刷盘（true=按时间间隔，false=按页数），默认 true */
    @ImportantField
    private boolean flushCommitLogTimed = true;

    /** ConsumeQueue 刷盘间隔（毫秒），默认 1000ms */
    private int flushIntervalConsumeQueue = 1000;

    /** 资源回收间隔（毫秒），默认 10 秒 */
    private int cleanResourceInterval = 10000;

    /** CommitLog 文件删除间隔（毫秒），默认 100ms */
    private int deleteCommitLogFilesInterval = 100;

    /** ConsumeQueue 文件删除间隔（毫秒），默认 100ms */
    private int deleteConsumeQueueFilesInterval = 100;

    /** 强制销毁映射文件间隔（毫秒），默认 2 分钟 */
    private int destroyMapedFileIntervalForcibly = 1000 * 120;

    /** 重新删除挂起文件间隔（毫秒），默认 2 分钟 */
    private int redeleteHangedFileInterval = 1000 * 120;

    /** 文件删除时间点（24小时制），默认凌晨 4 点 */
    @ImportantField
    private String deleteWhen = "04";

    /** 磁盘最大使用率（百分比），超过此值禁止写入，默认 75% */
    private int diskMaxUsedSpaceRatio = 75;

    /** 文件保留时间（小时），超过此时间的文件会被删除，默认 72 小时 */
    @ImportantField
    private int fileReservedTime = 72;

    /** 每批最大删除文件数，默认 10 */
    @ImportantField
    private int deleteFileBatchMax = 10;

    /** ConsumeQueue 流控水位线，默认 600000 */
    private int putMsgIndexHightWater = 600000;

    /** 消息体最大大小（字节），默认 4MB，仅指 body 长度，不包括其他字段 */
    private int maxMessageSize = 1024 * 1024 * 4;

    /** 消息过滤最大大小（字节），与 maxMsgNums * CQ_STORE_UNIT_SIZE(20/46) 相关，默认 16000 */
    private int maxFilterMessageSize = 16000;

    /** 恢复时是否校验 CRC32，确保消息在网络或磁盘上没有损坏，默认 true */
    private boolean checkCRCOnRecover = true;

    /** 刷盘 CommitLog 时的最少页数（4KB/页），默认 4 页 */
    private int flushCommitLogLeastPages = 4;

    /** 提交数据到文件时的最少页数，默认 4 页 */
    private int commitCommitLogLeastPages = 4;

    /** 磁盘预热时的刷盘页数，默认 1024 字节 */
    private int flushLeastPagesWhenWarmMapedFile = 1024 / 4 * 16;

    /** 刷盘 ConsumeQueue 时的最少页数，默认 2 页 */
    private int flushConsumeQueueLeastPages = 2;

    /** 强制刷盘 CommitLog 间隔（毫秒），超过此间隔即使未达最少页数也刷盘，默认 10 秒 */
    private int flushCommitLogThoroughInterval = 1000 * 10;

    /** 强制提交间隔（毫秒），默认 200ms */
    private int commitCommitLogThoroughInterval = 200;

    /** 强制刷盘 ConsumeQueue 间隔（毫秒），默认 60 秒 */
    private int flushConsumeQueueThoroughInterval = 1000 * 60;

    // ========================================================================
    // 7. 消息传输与内存访问配置
    // ========================================================================

    /** 内存中消息最大传输字节数，默认 256KB */
    @ImportantField
    private int maxTransferBytesOnMessageInMemory = 1024 * 256;

    /** 内存中消息最大传输条数，默认 32 条 */
    @ImportantField
    private int maxTransferCountOnMessageInMemory = 32;

    /** 磁盘中消息最大传输字节数，默认 64KB */
    @ImportantField
    private int maxTransferBytesOnMessageInDisk = 1024 * 64;

    /** 磁盘中消息最大传输条数，默认 8 条 */
    @ImportantField
    private int maxTransferCountOnMessageInDisk = 8;

    /** 内存中访问消息的最大比例（百分比），超过此值从磁盘读取，默认 40% */
    @ImportantField
    private int accessMessageInMemoryMaxRatio = 40;

    // ========================================================================
    // 8. 消息索引配置
    // ========================================================================

    /** 是否启用消息索引，默认 true */
    @ImportantField
    private boolean messageIndexEnable = true;

    /** 索引 Hash 槽数量，默认 500 万 */
    private int maxHashSlotNum = 5000000;

    /** 索引最大数量，默认 2000 万 */
    private int maxIndexNum = 5000000 * 4;

    /** 批量查询最大消息数，默认 64 */
    private int maxMsgsNumBatch = 64;

    /** 索引是否安全模式（刷盘后再返回），默认 false */
    @ImportantField
    private boolean messageIndexSafe = false;

    // ========================================================================
    // 9. 主从同步（HA）配置
    // ========================================================================

    /** HA 监听端口，默认 10912 */
    private int haListenPort = 10912;

    /** HA 发送心跳间隔（毫秒），默认 5 秒 */
    private int haSendHeartbeatInterval = 1000 * 5;

    /** HA 清理过期连接间隔（毫秒），默认 20 秒 */
    private int haHousekeepingInterval = 1000 * 20;

    /**
     * 传输到 Slave 的最大数据大小。
     * 注意：不能大于 HAClient.READ_MAX_BUFFER_SIZE，默认 32KB
     */
    private int haTransferBatchSize = 1024 * 32;

    /** Master 地址，Slave 需要配置 */
    @ImportantField
    private String haMasterAddress = null;

    /** 主从同步最大差距（字节），超过此值认为 Slave 落后，默认 256MB */
    private int haMaxGapNotInSync = 1024 * 1024 * 256;

    // ========================================================================
    // 10. Broker 角色与刷盘类型
    // ========================================================================

    /** Broker 角色：ASYNC_MASTER（异步主）/ SYNC_MASTER（同步主）/ SLAVE（从） */
    @ImportantField
    private volatile BrokerRole brokerRole = BrokerRole.ASYNC_MASTER;

    /** 刷盘类型：ASYNC_FLUSH（异步刷盘）/ SYNC_FLUSH（同步刷盘） */
    @ImportantField
    private FlushDiskType flushDiskType = FlushDiskType.ASYNC_FLUSH;

    /**
     * 同步刷盘超时时间（毫秒），由 GroupTransferService 使用，
     * 用于主从同步确认，默认 5 秒
     */
    private int syncFlushTimeout = 1000 * 5;

    /**
     * PutMessage 等待消息刷盘和同步的超时时间（毫秒），
     * 在当前 Broker 成员组中等待，默认 8 秒
     */
    private int putMessageTimeout = 1000 * 8;

    /** Slave 超时时间（毫秒），默认 3 秒 */
    private int slaveTimeout = 3000;

    // ========================================================================
    // 11. 延迟消息配置
    // ========================================================================

    /**
     * 延迟级别定义，格式：空格分隔的"数字+单位"列表。
     * 单位：s=秒，m=分，h=时
     * 共 18 个级别：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     */
    private String messageDelayLevel = "1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h";

    /** 延迟偏移量持久化间隔（毫秒），默认 10 秒 */
    private long flushDelayOffsetInterval = 1000 * 10;

    // ========================================================================
    // 12. 文件清理与恢复配置
    // ========================================================================

    /** 是否启用强制清理文件，默认 true */
    @ImportantField
    private boolean cleanFileForciblyEnable = true;

    /** 是否启用映射文件预热，默认 false */
    private boolean warmMapedFileEnable = false;

    /** 是否在 Slave 中检查偏移量，默认 false */
    private boolean offsetCheckInSlave = false;

    /** 是否启用调试锁，默认 false */
    private boolean debugLockEnable = false;

    /** 是否启用消息去重，默认 false */
    private boolean duplicationEnable = false;

    /** 是否记录磁盘故障，默认 true */
    private boolean diskFallRecorded = true;

    /** OS Page Cache 繁忙超时时间（毫秒），默认 1 秒 */
    private long osPageCacheBusyTimeOutMills = 1000;

    /** 默认最大查询数量，默认 32 */
    private int defaultQueryMaxNum = 32;

    // ========================================================================
    // 13. 瞬态存储池配置（TransientStorePool，堆外内存）
    // ========================================================================

    /** 是否启用瞬态存储池（使用堆外内存提升写入性能），默认 false */
    @ImportantField
    private boolean transientStorePoolEnable = false;

    /** 瞬态存储池大小（文件数），默认 5 */
    private int transientStorePoolSize = 5;

    /** 存储池中无可用缓冲区时是否快速失败，默认 false */
    private boolean fastFailIfNoBufferInStorePool = false;

    // ========================================================================
    // 14. DLedger 配置（Raft 协议）
    // ========================================================================

    /** 是否启用 DLedger CommitLog，默认 false */
    private boolean enableDLegerCommitLog = false;

    /** DLedger 组名称 */
    private String dLegerGroup;

    /** DLedger 节点列表，格式：n0-address:port,n1-address:port,... */
    private String dLegerPeers;

    /** DLedger 自身节点 ID，如 n0 */
    private String dLegerSelfId;

    /** 首选 Leader 节点 ID */
    private String preferredLeaderId;

    /** 是否启用批量推送，默认 false */
    private boolean enableBatchPush = false;

    // ========================================================================
    // 15. 调度消息统计与异步投递
    // ========================================================================

    /** 是否启用调度消息统计，默认 true */
    private boolean enableScheduleMessageStats = true;

    /** 是否启用 LMQ（轻量消息队列），默认 false */
    private boolean enableLmq = false;

    /** 是否启用多级分发，默认 false */
    private boolean enableMultiDispatch = false;

    /** 最大 LMQ ConsumeQueue 数量，默认 20000 */
    private int maxLmqConsumeQueueNum = 20000;

    /** 是否启用延迟消息异步投递，默认 false */
    private boolean enableScheduleAsyncDeliver = false;

    /** 异步投递最大待处理数，超过此值触发流控，默认 2000 */
    private int scheduleAsyncDeliverMaxPendingLimit = 2000;

    /** 异步投递最大重试次数，超过则阻塞，默认 3 */
    private int scheduleAsyncDeliverMaxResendNum2Blocked = 3;

    // ========================================================================
    // 16. 批量操作与分发配置
    // ========================================================================

    /** 每批最大删除文件数，默认 50 */
    private int maxBatchDeleteFilesNum = 50;

    /** 分发 ConsumeQueue 的线程数，默认 10 */
    private int dispatchCqThreads = 10;

    /** 分发 ConsumeQueue 的缓存数量，默认 4096 */
    private int dispatchCqCacheNum = 1024 * 4;

    /** 是否启用异步构建 ConsumeQueue（reput），默认 true */
    private boolean enableAsyncReput = true;

    /** 是否重新检查 reput 偏移量（从 ConsumeQueue 检查），默认 false */
    private boolean recheckReputOffsetFromCq = false;

    // ========================================================================
    // 17. Topic 长度与消息版本
    // ========================================================================

    /**
     * Topic 最大长度（字节），已废弃，将在未来版本移除。
     * @deprecated
     */
    @Deprecated
    private int maxTopicLength = Byte.MAX_VALUE;

    /**
     * 当 Topic 长度超过 Byte.MAX_VALUE 时，自动使用 MESSAGE_VERSION_V2。
     * 否则使用 MESSAGE_VERSION_V1。
     * 注意：客户端无法解码 MESSAGE_VERSION_V2 版本消息。
     * 启用此配置解决 https://github.com/apache/rocketmq/issues/5568
     */
    private boolean autoMessageVersionOnTopicLen = true;

    // ========================================================================
    // 18. 属性校验与 CRC 配置
    // ========================================================================

    /**
     * 启动后不可更改，修改需要重启生效。
     * 是否启用消息属性 CRC 追加，默认 false
     */
    private boolean enabledAppendPropCRC = false;

    /** 是否强制校验消息属性 CRC，默认 false */
    private boolean forceVerifyPropCRC = false;

    // ========================================================================
    // 19. ConsumeQueue 偏移校正与查询
    // ========================================================================

    /** 获取消息时遍历的 CQ 文件数，默认 1 */
    private int travelCqFileNumWhenGetMessage = 1;

    /** 两次校正之间的休眠间隔（秒），默认 1 */
    private int correctLogicMinOffsetSleepInterval = 1;

    /** 强制校正最小偏移量的间隔（毫秒），默认 5 分钟 */
    private int correctLogicMinOffsetForceInterval = 5 * 60 * 1000;

    // ========================================================================
    // 20. 映射文件交换（Swap）配置
    // ========================================================================

    /** 是否启用映射文件交换，默认 true */
    private boolean mappedFileSwapEnable = true;

    /** CommitLog 强制交换映射间隔（毫秒），默认 12 小时 */
    private long commitLogForceSwapMapInterval = 12L * 60 * 60 * 1000;

    /** CommitLog 交换映射间隔（毫秒），默认 1 小时 */
    private long commitLogSwapMapInterval = 1L * 60 * 60 * 1000;

    /** CommitLog 交换映射保留文件数，默认 100 */
    private int commitLogSwapMapReserveFileNum = 100;

    /** 逻辑队列（ConsumeQueue）强制交换映射间隔（毫秒），默认 12 小时 */
    private long logicQueueForceSwapMapInterval = 12L * 60 * 60 * 1000;

    /** 逻辑队列（ConsumeQueue）交换映射间隔（毫秒），默认 1 小时 */
    private long logicQueueSwapMapInterval = 1L * 60 * 60 * 1000;

    /** 清理已交换映射的间隔（毫秒），默认 5 分钟 */
    private long cleanSwapedMapInterval = 5L * 60 * 1000;

    /** 逻辑队列交换映射保留文件数，默认 20 */
    private int logicQueueSwapMapReserveFileNum = 20;

    // ========================================================================
    // 21. 缓存与分发线程配置
    // ========================================================================

    /** 是否启用 BCQ（批量 ConsumeQueue）缓存查找，默认 true */
    private boolean searchBcqByCacheEnable = true;

    /** 是否在发送线程中直接分发 ConsumeQueue，默认 false */
    @ImportantField
    private boolean dispatchFromSenderThread = false;

    /** putMessage 时是否唤醒提交线程，默认 true */
    @ImportantField
    private boolean wakeCommitWhenPutMessage = true;

    /** putMessage 时是否唤醒刷盘线程，默认 false */
    @ImportantField
    private boolean wakeFlushWhenPutMessage = false;

    /** 是否启用清理过期偏移量，默认 false */
    @ImportantField
    private boolean enableCleanExpiredOffset = false;

    // ========================================================================
    // 22. 异步 Put 与拉取配置
    // ========================================================================

    /** 最大异步 PutMessage 请求数，默认 5000 */
    private int maxAsyncPutMessageRequests = 5000;

    /** 拉取消息批量最大条数，默认 160 */
    private int pullBatchMaxMessageCount = 160;

    // ========================================================================
    // 23. 副本与一致性配置
    // ========================================================================

    /** 总副本数，默认 1 */
    @ImportantField
    private int totalReplicas = 1;

    /**
     * 每条消息必须成功写入至少 inSyncReplicas 个同步副本。
     * Master 算作一个同步副本，计入总数。
     * 如果 Master 是 ASYNC_MASTER，inSyncReplicas 将被忽略。
     * 如果启用了 controllerMode 且 allAckInSyncStateSet=true，inSyncReplicas 将被忽略。
     */
    @ImportantField
    private int inSyncReplicas = 1;

    /**
     * 在自动多副本模式下工作，提供最小同步副本数。
     * 在 controller 模式下仍然有效。
     */
    @ImportantField
    private int minInSyncReplicas = 1;

    /**
     * 每条消息必须成功写入 SyncStateSet 中的所有副本。
     */
    @ImportantField
    private boolean allAckInSyncStateSet = false;

    /**
     * 动态调整同步副本数以提高可用性，
     * 实时的同步副本数可能小于 inSyncReplicas 配置值。
     */
    @ImportantField
    private boolean enableAutoInSyncReplicas = false;

    // ========================================================================
    // 24. HA 流控配置
    // ========================================================================

    /** 是否启用 HA 流控，默认 false */
    @ImportantField
    private boolean haFlowControlEnable = false;

    /** 单个 Slave 传输数据的最大速度（字节/秒），默认 100MB/s */
    private long maxHaTransferByteInSecond = 100 * 1024 * 1024;

    /** Slave 落后 Master 的最大时间间隔（毫秒），默认 15 秒 */
    private long haMaxTimeSlaveNotCatchup = 1000 * 15;

    /**
     * Broker 启动时是否从 Master 同步刷盘偏移量，
     * 用于旧版本 Broker 升级场景，默认 false
     */
    private boolean syncMasterFlushOffsetWhenStartup = false;

    /** 最大校验和范围（字节），默认 1GB */
    private long maxChecksumRange = 1024 * 1024 * 1024;

    // ========================================================================
    // 25. 磁盘分区与 Slave 配置
    // ========================================================================

    /** 每个磁盘分区的副本数，默认 1 */
    private int replicasPerDiskPartition = 1;

    /** 逻辑磁盘空间强制清理阈值（百分比），默认 0.8（80%） */
    private double logicalDiskSpaceCleanForciblyThreshold = 0.8;

    /** Slave 最大重新发送长度（字节），默认 256MB */
    private long maxSlaveResendLength = 256 * 1024 * 1024;

    /**
     * 当新的 Broker 副本（无数据）加入 Master 时，
     * 是否从最后一个文件开始同步，默认 false
     */
    private boolean syncFromLastFile = false;

    /** 是否为异步 Learner 节点，默认 false */
    private boolean asyncLearner = false;

    // ========================================================================
    // 26. 冷数据与流控配置
    // ========================================================================

    /** 开始估算前扫描的记录数，默认 20000 */
    private int maxConsumeQueueScan = 20_000;

    /** 开始估算前的匹配记录数阈值，默认 5000 */
    private int sampleCountThreshold = 5000;

    /** 是否启用冷数据流控，默认 false */
    private boolean coldDataFlowControlEnable = false;

    /** 是否启用冷数据扫描，默认 false */
    private boolean coldDataScanEnable = false;

    /** 是否启用数据预读，默认 true */
    private boolean dataReadAheadEnable = true;

    /** 定时消息冷数据检查间隔（毫秒），默认 60 秒 */
    private int timerColdDataCheckIntervalMs = 60 * 1000;

    /** 采样步长，默认 32 */
    private int sampleSteps = 32;

    /** 热数据内存访问比例，默认 26% */
    private int accessMessageInMemoryHotRatio = 26;

    /** 是否并发构建 ConsumeQueue（多线程），默认 false */
    private boolean enableBuildConsumeQueueConcurrently = false;

    /** 批量分发请求线程池大小，默认 16 */
    private int batchDispatchRequestThreadPoolNums = 16;

    // ========================================================================
    // 27. RocksDB 模式配置
    // ========================================================================

    /** RocksDB 脏 CQ 清理间隔（分钟），默认 60 分钟 */
    private long cleanRocksDBDirtyCQIntervalMin = 60;

    /** RocksDB CQ 统计间隔（秒），默认 10 秒 */
    private long statRocksDBCQIntervalSec = 10;

    /** RocksDB MemTable 刷写间隔（毫秒），默认 1 小时 */
    private long memTableFlushIntervalMs = 60 * 60 * 1000L;

    /** 是否实时持久化 RocksDB 配置，默认 true */
    private boolean realTimePersistRocksDBConfig = true;

    /** 是否启用 RocksDB 日志，默认 false */
    private boolean enableRocksDBLog = false;

    // ========================================================================
    // 28. 锁与可见性配置
    // ========================================================================

    /** Topic 队列锁数量，默认 32 */
    private int topicQueueLockNum = 32;

    /**
     * 如果 readUnCommitted 为 true，ConsumeQueue 的分发将超过 confirmOffset，
     * 可能导致客户端读取到未提交的消息。
     * 例如：同步刷盘时 reput 偏移量超过 flush 偏移量。
     */
    private boolean readUnCommitted = false;

    /** 是否通过 FileChannel 写入 ConsumeQueue 数据，默认 true */
    private boolean putConsumeQueueDataByFileChannel = true;

    /** 是否将偏移量 JSON 迁移到 RocksDB，默认 false */
    private boolean transferOffsetJsonToRocksdb = false;

    /** RocksDB CQ 是否启用双写，默认 false */
    private boolean rocksdbCQDoubleWriteEnable = false;

    /** 批量写入 KV CQ 的大小，默认 16 */
    private int batchWriteKvCqSize = 16;


    public int getBatchWriteKvCqSize() {
        return batchWriteKvCqSize;
    }

    public void setBatchWriteKvCqSize(int batchWriteKvCqSize) {
        this.batchWriteKvCqSize = batchWriteKvCqSize;
    }

    public boolean isRocksdbCQDoubleWriteEnable() {
        return rocksdbCQDoubleWriteEnable;
    }

    public void setRocksdbCQDoubleWriteEnable(boolean rocksdbWriteEnable) {
        this.rocksdbCQDoubleWriteEnable = rocksdbWriteEnable;
    }

    public boolean isTransferOffsetJsonToRocksdb() {
        return transferOffsetJsonToRocksdb;
    }

    public void setTransferOffsetJsonToRocksdb(boolean transferOffsetJsonToRocksdb) {
        this.transferOffsetJsonToRocksdb = transferOffsetJsonToRocksdb;
    }

    public boolean isEnabledAppendPropCRC() {
        return enabledAppendPropCRC;
    }

    public void setEnabledAppendPropCRC(boolean enabledAppendPropCRC) {
        this.enabledAppendPropCRC = enabledAppendPropCRC;
    }

    public boolean isDebugLockEnable() {
        return debugLockEnable;
    }

    public void setDebugLockEnable(final boolean debugLockEnable) {
        this.debugLockEnable = debugLockEnable;
    }

    public boolean isDuplicationEnable() {
        return duplicationEnable;
    }

    public void setDuplicationEnable(final boolean duplicationEnable) {
        this.duplicationEnable = duplicationEnable;
    }

    public long getOsPageCacheBusyTimeOutMills() {
        return osPageCacheBusyTimeOutMills;
    }

    public void setOsPageCacheBusyTimeOutMills(final long osPageCacheBusyTimeOutMills) {
        this.osPageCacheBusyTimeOutMills = osPageCacheBusyTimeOutMills;
    }

    public boolean isDiskFallRecorded() {
        return diskFallRecorded;
    }

    public void setDiskFallRecorded(final boolean diskFallRecorded) {
        this.diskFallRecorded = diskFallRecorded;
    }

    public boolean isWarmMapedFileEnable() {
        return warmMapedFileEnable;
    }

    public void setWarmMapedFileEnable(boolean warmMapedFileEnable) {
        this.warmMapedFileEnable = warmMapedFileEnable;
    }

    public int getCompactionMappedFileSize() {
        return compactionMappedFileSize;
    }

    public int getCompactionCqMappedFileSize() {
        return compactionCqMappedFileSize;
    }

    public void setCompactionMappedFileSize(int compactionMappedFileSize) {
        this.compactionMappedFileSize = compactionMappedFileSize;
    }

    public void setCompactionCqMappedFileSize(int compactionCqMappedFileSize) {
        this.compactionCqMappedFileSize = compactionCqMappedFileSize;
    }

    public int getCompactionScheduleInternal() {
        return compactionScheduleInternal;
    }

    public void setCompactionScheduleInternal(int compactionScheduleInternal) {
        this.compactionScheduleInternal = compactionScheduleInternal;
    }

    public int getMaxOffsetMapSize() {
        return maxOffsetMapSize;
    }

    public void setMaxOffsetMapSize(int maxOffsetMapSize) {
        this.maxOffsetMapSize = maxOffsetMapSize;
    }

    public int getCompactionThreadNum() {
        return compactionThreadNum;
    }

    public void setCompactionThreadNum(int compactionThreadNum) {
        this.compactionThreadNum = compactionThreadNum;
    }

    public boolean isEnableCompaction() {
        return enableCompaction;
    }

    public void setEnableCompaction(boolean enableCompaction) {
        this.enableCompaction = enableCompaction;
    }

    public int getMappedFileSizeCommitLog() {
        return mappedFileSizeCommitLog;
    }

    public void setMappedFileSizeCommitLog(int mappedFileSizeCommitLog) {
        this.mappedFileSizeCommitLog = mappedFileSizeCommitLog;
    }

    public boolean isEnableRocksDBStore() {
        return StoreType.DEFAULT_ROCKSDB.getStoreType().equalsIgnoreCase(this.storeType);
    }

    public String getStoreType() {
        return storeType;
    }

    public void setStoreType(String storeType) {
        this.storeType = storeType;
    }

    public int getMappedFileSizeConsumeQueue() {
        int factor = (int) Math.ceil(this.mappedFileSizeConsumeQueue / (ConsumeQueue.CQ_STORE_UNIT_SIZE * 1.0));
        return (int) (factor * ConsumeQueue.CQ_STORE_UNIT_SIZE);
    }

    public void setMappedFileSizeConsumeQueue(int mappedFileSizeConsumeQueue) {
        this.mappedFileSizeConsumeQueue = mappedFileSizeConsumeQueue;
    }

    public boolean isEnableConsumeQueueExt() {
        return enableConsumeQueueExt;
    }

    public void setEnableConsumeQueueExt(boolean enableConsumeQueueExt) {
        this.enableConsumeQueueExt = enableConsumeQueueExt;
    }

    public int getMappedFileSizeConsumeQueueExt() {
        return mappedFileSizeConsumeQueueExt;
    }

    public void setMappedFileSizeConsumeQueueExt(int mappedFileSizeConsumeQueueExt) {
        this.mappedFileSizeConsumeQueueExt = mappedFileSizeConsumeQueueExt;
    }

    public int getBitMapLengthConsumeQueueExt() {
        return bitMapLengthConsumeQueueExt;
    }

    public void setBitMapLengthConsumeQueueExt(int bitMapLengthConsumeQueueExt) {
        this.bitMapLengthConsumeQueueExt = bitMapLengthConsumeQueueExt;
    }

    public int getFlushIntervalCommitLog() {
        return flushIntervalCommitLog;
    }

    public void setFlushIntervalCommitLog(int flushIntervalCommitLog) {
        this.flushIntervalCommitLog = flushIntervalCommitLog;
    }

    public int getFlushIntervalConsumeQueue() {
        return flushIntervalConsumeQueue;
    }

    public void setFlushIntervalConsumeQueue(int flushIntervalConsumeQueue) {
        this.flushIntervalConsumeQueue = flushIntervalConsumeQueue;
    }

    public int getPutMsgIndexHightWater() {
        return putMsgIndexHightWater;
    }

    public void setPutMsgIndexHightWater(int putMsgIndexHightWater) {
        this.putMsgIndexHightWater = putMsgIndexHightWater;
    }

    public int getCleanResourceInterval() {
        return cleanResourceInterval;
    }

    public void setCleanResourceInterval(int cleanResourceInterval) {
        this.cleanResourceInterval = cleanResourceInterval;
    }

    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    public int getMaxFilterMessageSize() {
        return maxFilterMessageSize;
    }

    public void setMaxFilterMessageSize(int maxFilterMessageSize) {
        this.maxFilterMessageSize = maxFilterMessageSize;
    }

    @Deprecated
    public int getMaxTopicLength() {
        return maxTopicLength;
    }

    @Deprecated
    public void setMaxTopicLength(int maxTopicLength) {
        this.maxTopicLength = maxTopicLength;
    }

    public boolean isAutoMessageVersionOnTopicLen() {
        return autoMessageVersionOnTopicLen;
    }

    public void setAutoMessageVersionOnTopicLen(boolean autoMessageVersionOnTopicLen) {
        this.autoMessageVersionOnTopicLen = autoMessageVersionOnTopicLen;
    }

    public int getTravelCqFileNumWhenGetMessage() {
        return travelCqFileNumWhenGetMessage;
    }

    public void setTravelCqFileNumWhenGetMessage(int travelCqFileNumWhenGetMessage) {
        this.travelCqFileNumWhenGetMessage = travelCqFileNumWhenGetMessage;
    }

    public int getCorrectLogicMinOffsetSleepInterval() {
        return correctLogicMinOffsetSleepInterval;
    }

    public void setCorrectLogicMinOffsetSleepInterval(int correctLogicMinOffsetSleepInterval) {
        this.correctLogicMinOffsetSleepInterval = correctLogicMinOffsetSleepInterval;
    }

    public int getCorrectLogicMinOffsetForceInterval() {
        return correctLogicMinOffsetForceInterval;
    }

    public void setCorrectLogicMinOffsetForceInterval(int correctLogicMinOffsetForceInterval) {
        this.correctLogicMinOffsetForceInterval = correctLogicMinOffsetForceInterval;
    }

    public boolean isCheckCRCOnRecover() {
        return checkCRCOnRecover;
    }

    public boolean getCheckCRCOnRecover() {
        return checkCRCOnRecover;
    }

    public void setCheckCRCOnRecover(boolean checkCRCOnRecover) {
        this.checkCRCOnRecover = checkCRCOnRecover;
    }

    public boolean isForceVerifyPropCRC() {
        return forceVerifyPropCRC;
    }

    public void setForceVerifyPropCRC(boolean forceVerifyPropCRC) {
        this.forceVerifyPropCRC = forceVerifyPropCRC;
    }

    public String getStorePathCommitLog() {
        if (storePathCommitLog == null) {
            return storePathRootDir + File.separator + "commitlog";
        }
        return storePathCommitLog;
    }

    public void setStorePathCommitLog(String storePathCommitLog) {
        this.storePathCommitLog = storePathCommitLog;
    }

    public String getStorePathDLedgerCommitLog() {
        return storePathDLedgerCommitLog;
    }

    public void setStorePathDLedgerCommitLog(String storePathDLedgerCommitLog) {
        this.storePathDLedgerCommitLog = storePathDLedgerCommitLog;
    }

    public String getStorePathEpochFile() {
        if (storePathEpochFile == null) {
            return storePathRootDir + File.separator + "epochFileCheckpoint";
        }
        return storePathEpochFile;
    }

    public void setStorePathEpochFile(String storePathEpochFile) {
        this.storePathEpochFile = storePathEpochFile;
    }

    public String getStorePathBrokerIdentity() {
        if (storePathBrokerIdentity == null) {
            return storePathRootDir + File.separator + "brokerIdentity";
        }
        return storePathBrokerIdentity;
    }

    public void setStorePathBrokerIdentity(String storePathBrokerIdentity) {
        this.storePathBrokerIdentity = storePathBrokerIdentity;
    }

    public String getDeleteWhen() {
        return deleteWhen;
    }

    public void setDeleteWhen(String deleteWhen) {
        this.deleteWhen = deleteWhen;
    }

    public int getDiskMaxUsedSpaceRatio() {
        if (this.diskMaxUsedSpaceRatio < 10)
            return 10;

        if (this.diskMaxUsedSpaceRatio > 95)
            return 95;

        return diskMaxUsedSpaceRatio;
    }

    public void setDiskMaxUsedSpaceRatio(int diskMaxUsedSpaceRatio) {
        this.diskMaxUsedSpaceRatio = diskMaxUsedSpaceRatio;
    }

    public int getDeleteCommitLogFilesInterval() {
        return deleteCommitLogFilesInterval;
    }

    public void setDeleteCommitLogFilesInterval(int deleteCommitLogFilesInterval) {
        this.deleteCommitLogFilesInterval = deleteCommitLogFilesInterval;
    }

    public int getDeleteConsumeQueueFilesInterval() {
        return deleteConsumeQueueFilesInterval;
    }

    public void setDeleteConsumeQueueFilesInterval(int deleteConsumeQueueFilesInterval) {
        this.deleteConsumeQueueFilesInterval = deleteConsumeQueueFilesInterval;
    }

    public int getMaxTransferBytesOnMessageInMemory() {
        return maxTransferBytesOnMessageInMemory;
    }

    public void setMaxTransferBytesOnMessageInMemory(int maxTransferBytesOnMessageInMemory) {
        this.maxTransferBytesOnMessageInMemory = maxTransferBytesOnMessageInMemory;
    }

    public int getMaxTransferCountOnMessageInMemory() {
        return maxTransferCountOnMessageInMemory;
    }

    public void setMaxTransferCountOnMessageInMemory(int maxTransferCountOnMessageInMemory) {
        this.maxTransferCountOnMessageInMemory = maxTransferCountOnMessageInMemory;
    }

    public int getMaxTransferBytesOnMessageInDisk() {
        return maxTransferBytesOnMessageInDisk;
    }

    public void setMaxTransferBytesOnMessageInDisk(int maxTransferBytesOnMessageInDisk) {
        this.maxTransferBytesOnMessageInDisk = maxTransferBytesOnMessageInDisk;
    }

    public int getMaxTransferCountOnMessageInDisk() {
        return maxTransferCountOnMessageInDisk;
    }

    public void setMaxTransferCountOnMessageInDisk(int maxTransferCountOnMessageInDisk) {
        this.maxTransferCountOnMessageInDisk = maxTransferCountOnMessageInDisk;
    }

    public int getFlushCommitLogLeastPages() {
        return flushCommitLogLeastPages;
    }

    public void setFlushCommitLogLeastPages(int flushCommitLogLeastPages) {
        this.flushCommitLogLeastPages = flushCommitLogLeastPages;
    }

    public int getFlushConsumeQueueLeastPages() {
        return flushConsumeQueueLeastPages;
    }

    public void setFlushConsumeQueueLeastPages(int flushConsumeQueueLeastPages) {
        this.flushConsumeQueueLeastPages = flushConsumeQueueLeastPages;
    }

    public int getFlushCommitLogThoroughInterval() {
        return flushCommitLogThoroughInterval;
    }

    public void setFlushCommitLogThoroughInterval(int flushCommitLogThoroughInterval) {
        this.flushCommitLogThoroughInterval = flushCommitLogThoroughInterval;
    }

    public int getFlushConsumeQueueThoroughInterval() {
        return flushConsumeQueueThoroughInterval;
    }

    public void setFlushConsumeQueueThoroughInterval(int flushConsumeQueueThoroughInterval) {
        this.flushConsumeQueueThoroughInterval = flushConsumeQueueThoroughInterval;
    }

    public int getDestroyMapedFileIntervalForcibly() {
        return destroyMapedFileIntervalForcibly;
    }

    public void setDestroyMapedFileIntervalForcibly(int destroyMapedFileIntervalForcibly) {
        this.destroyMapedFileIntervalForcibly = destroyMapedFileIntervalForcibly;
    }

    public int getFileReservedTime() {
        return fileReservedTime;
    }

    public void setFileReservedTime(int fileReservedTime) {
        this.fileReservedTime = fileReservedTime;
    }

    public int getRedeleteHangedFileInterval() {
        return redeleteHangedFileInterval;
    }

    public void setRedeleteHangedFileInterval(int redeleteHangedFileInterval) {
        this.redeleteHangedFileInterval = redeleteHangedFileInterval;
    }

    public int getAccessMessageInMemoryMaxRatio() {
        return accessMessageInMemoryMaxRatio;
    }

    public void setAccessMessageInMemoryMaxRatio(int accessMessageInMemoryMaxRatio) {
        this.accessMessageInMemoryMaxRatio = accessMessageInMemoryMaxRatio;
    }

    public boolean isMessageIndexEnable() {
        return messageIndexEnable;
    }

    public void setMessageIndexEnable(boolean messageIndexEnable) {
        this.messageIndexEnable = messageIndexEnable;
    }

    public int getMaxHashSlotNum() {
        return maxHashSlotNum;
    }

    public void setMaxHashSlotNum(int maxHashSlotNum) {
        this.maxHashSlotNum = maxHashSlotNum;
    }

    public int getMaxIndexNum() {
        return maxIndexNum;
    }

    public void setMaxIndexNum(int maxIndexNum) {
        this.maxIndexNum = maxIndexNum;
    }

    public int getMaxMsgsNumBatch() {
        return maxMsgsNumBatch;
    }

    public void setMaxMsgsNumBatch(int maxMsgsNumBatch) {
        this.maxMsgsNumBatch = maxMsgsNumBatch;
    }

    public int getHaListenPort() {
        return haListenPort;
    }

    public void setHaListenPort(int haListenPort) {
        if (haListenPort < 0) {
            this.haListenPort = 0;
            return;
        }
        this.haListenPort = haListenPort;
    }

    public int getHaSendHeartbeatInterval() {
        return haSendHeartbeatInterval;
    }

    public void setHaSendHeartbeatInterval(int haSendHeartbeatInterval) {
        this.haSendHeartbeatInterval = haSendHeartbeatInterval;
    }

    public int getHaHousekeepingInterval() {
        return haHousekeepingInterval;
    }

    public void setHaHousekeepingInterval(int haHousekeepingInterval) {
        this.haHousekeepingInterval = haHousekeepingInterval;
    }

    public BrokerRole getBrokerRole() {
        return brokerRole;
    }

    public void setBrokerRole(BrokerRole brokerRole) {
        this.brokerRole = brokerRole;
    }

    public void setBrokerRole(String brokerRole) {
        this.brokerRole = BrokerRole.valueOf(brokerRole);
    }

    public int getHaTransferBatchSize() {
        return haTransferBatchSize;
    }

    public void setHaTransferBatchSize(int haTransferBatchSize) {
        this.haTransferBatchSize = haTransferBatchSize;
    }

    public int getHaMaxGapNotInSync() {
        return haMaxGapNotInSync;
    }

    public void setHaMaxGapNotInSync(int haMaxGapNotInSync) {
        this.haMaxGapNotInSync = haMaxGapNotInSync;
    }

    public FlushDiskType getFlushDiskType() {
        return flushDiskType;
    }

    public void setFlushDiskType(FlushDiskType flushDiskType) {
        this.flushDiskType = flushDiskType;
    }

    public void setFlushDiskType(String type) {
        this.flushDiskType = FlushDiskType.valueOf(type);
    }

    public int getSyncFlushTimeout() {
        return syncFlushTimeout;
    }

    public void setSyncFlushTimeout(int syncFlushTimeout) {
        this.syncFlushTimeout = syncFlushTimeout;
    }

    public int getPutMessageTimeout() {
        return putMessageTimeout;
    }

    public void setPutMessageTimeout(int putMessageTimeout) {
        this.putMessageTimeout = putMessageTimeout;
    }

    public int getSlaveTimeout() {
        return slaveTimeout;
    }

    public void setSlaveTimeout(int slaveTimeout) {
        this.slaveTimeout = slaveTimeout;
    }

    public String getHaMasterAddress() {
        return haMasterAddress;
    }

    public void setHaMasterAddress(String haMasterAddress) {
        this.haMasterAddress = haMasterAddress;
    }

    public String getMessageDelayLevel() {
        return messageDelayLevel;
    }

    public void setMessageDelayLevel(String messageDelayLevel) {
        this.messageDelayLevel = messageDelayLevel;
    }

    public long getFlushDelayOffsetInterval() {
        return flushDelayOffsetInterval;
    }

    public void setFlushDelayOffsetInterval(long flushDelayOffsetInterval) {
        this.flushDelayOffsetInterval = flushDelayOffsetInterval;
    }

    public boolean isCleanFileForciblyEnable() {
        return cleanFileForciblyEnable;
    }

    public void setCleanFileForciblyEnable(boolean cleanFileForciblyEnable) {
        this.cleanFileForciblyEnable = cleanFileForciblyEnable;
    }

    public boolean isMessageIndexSafe() {
        return messageIndexSafe;
    }

    public void setMessageIndexSafe(boolean messageIndexSafe) {
        this.messageIndexSafe = messageIndexSafe;
    }

    public boolean isFlushCommitLogTimed() {
        return flushCommitLogTimed;
    }

    public void setFlushCommitLogTimed(boolean flushCommitLogTimed) {
        this.flushCommitLogTimed = flushCommitLogTimed;
    }

    public String getStorePathRootDir() {
        return storePathRootDir;
    }

    public void setStorePathRootDir(String storePathRootDir) {
        this.storePathRootDir = storePathRootDir;
    }

    public int getFlushLeastPagesWhenWarmMapedFile() {
        return flushLeastPagesWhenWarmMapedFile;
    }

    public void setFlushLeastPagesWhenWarmMapedFile(int flushLeastPagesWhenWarmMapedFile) {
        this.flushLeastPagesWhenWarmMapedFile = flushLeastPagesWhenWarmMapedFile;
    }

    public boolean isOffsetCheckInSlave() {
        return offsetCheckInSlave;
    }

    public void setOffsetCheckInSlave(boolean offsetCheckInSlave) {
        this.offsetCheckInSlave = offsetCheckInSlave;
    }

    public int getDefaultQueryMaxNum() {
        return defaultQueryMaxNum;
    }

    public void setDefaultQueryMaxNum(int defaultQueryMaxNum) {
        this.defaultQueryMaxNum = defaultQueryMaxNum;
    }

    public boolean isTransientStorePoolEnable() {
        return transientStorePoolEnable;
    }

    public void setTransientStorePoolEnable(final boolean transientStorePoolEnable) {
        this.transientStorePoolEnable = transientStorePoolEnable;
    }

    public int getTransientStorePoolSize() {
        return transientStorePoolSize;
    }

    public void setTransientStorePoolSize(final int transientStorePoolSize) {
        this.transientStorePoolSize = transientStorePoolSize;
    }

    public int getCommitIntervalCommitLog() {
        return commitIntervalCommitLog;
    }

    public void setCommitIntervalCommitLog(final int commitIntervalCommitLog) {
        this.commitIntervalCommitLog = commitIntervalCommitLog;
    }

    public boolean isFastFailIfNoBufferInStorePool() {
        return fastFailIfNoBufferInStorePool;
    }

    public void setFastFailIfNoBufferInStorePool(final boolean fastFailIfNoBufferInStorePool) {
        this.fastFailIfNoBufferInStorePool = fastFailIfNoBufferInStorePool;
    }

    public boolean isUseReentrantLockWhenPutMessage() {
        return useReentrantLockWhenPutMessage;
    }

    public void setUseReentrantLockWhenPutMessage(final boolean useReentrantLockWhenPutMessage) {
        this.useReentrantLockWhenPutMessage = useReentrantLockWhenPutMessage;
    }

    public int getCommitCommitLogLeastPages() {
        return commitCommitLogLeastPages;
    }

    public void setCommitCommitLogLeastPages(final int commitCommitLogLeastPages) {
        this.commitCommitLogLeastPages = commitCommitLogLeastPages;
    }

    public int getCommitCommitLogThoroughInterval() {
        return commitCommitLogThoroughInterval;
    }

    public void setCommitCommitLogThoroughInterval(final int commitCommitLogThoroughInterval) {
        this.commitCommitLogThoroughInterval = commitCommitLogThoroughInterval;
    }

    public boolean isWakeCommitWhenPutMessage() {
        return wakeCommitWhenPutMessage;
    }

    public void setWakeCommitWhenPutMessage(boolean wakeCommitWhenPutMessage) {
        this.wakeCommitWhenPutMessage = wakeCommitWhenPutMessage;
    }

    public boolean isWakeFlushWhenPutMessage() {
        return wakeFlushWhenPutMessage;
    }

    public void setWakeFlushWhenPutMessage(boolean wakeFlushWhenPutMessage) {
        this.wakeFlushWhenPutMessage = wakeFlushWhenPutMessage;
    }

    public int getMapperFileSizeBatchConsumeQueue() {
        return mapperFileSizeBatchConsumeQueue;
    }

    public void setMapperFileSizeBatchConsumeQueue(int mapperFileSizeBatchConsumeQueue) {
        this.mapperFileSizeBatchConsumeQueue = mapperFileSizeBatchConsumeQueue;
    }

    public boolean isEnableCleanExpiredOffset() {
        return enableCleanExpiredOffset;
    }

    public void setEnableCleanExpiredOffset(boolean enableCleanExpiredOffset) {
        this.enableCleanExpiredOffset = enableCleanExpiredOffset;
    }

    public String getReadOnlyCommitLogStorePaths() {
        return readOnlyCommitLogStorePaths;
    }

    public void setReadOnlyCommitLogStorePaths(String readOnlyCommitLogStorePaths) {
        this.readOnlyCommitLogStorePaths = readOnlyCommitLogStorePaths;
    }

    public String getdLegerGroup() {
        return dLegerGroup;
    }

    public void setdLegerGroup(String dLegerGroup) {
        this.dLegerGroup = dLegerGroup;
    }

    public String getdLegerPeers() {
        return dLegerPeers;
    }

    public void setdLegerPeers(String dLegerPeers) {
        this.dLegerPeers = dLegerPeers;
    }

    public String getdLegerSelfId() {
        return dLegerSelfId;
    }

    public void setdLegerSelfId(String dLegerSelfId) {
        this.dLegerSelfId = dLegerSelfId;
    }

    public boolean isEnableDLegerCommitLog() {
        return enableDLegerCommitLog;
    }

    public void setEnableDLegerCommitLog(boolean enableDLegerCommitLog) {
        this.enableDLegerCommitLog = enableDLegerCommitLog;
    }

    public String getPreferredLeaderId() {
        return preferredLeaderId;
    }

    public void setPreferredLeaderId(String preferredLeaderId) {
        this.preferredLeaderId = preferredLeaderId;
    }

    public boolean isEnableBatchPush() {
        return enableBatchPush;
    }

    public void setEnableBatchPush(boolean enableBatchPush) {
        this.enableBatchPush = enableBatchPush;
    }

    public boolean isEnableScheduleMessageStats() {
        return enableScheduleMessageStats;
    }

    public void setEnableScheduleMessageStats(boolean enableScheduleMessageStats) {
        this.enableScheduleMessageStats = enableScheduleMessageStats;
    }

    public int getMaxAsyncPutMessageRequests() {
        return maxAsyncPutMessageRequests;
    }

    public void setMaxAsyncPutMessageRequests(int maxAsyncPutMessageRequests) {
        this.maxAsyncPutMessageRequests = maxAsyncPutMessageRequests;
    }

    public int getMaxRecoveryCommitlogFiles() {
        return maxRecoveryCommitlogFiles;
    }

    public void setMaxRecoveryCommitlogFiles(final int maxRecoveryCommitlogFiles) {
        this.maxRecoveryCommitlogFiles = maxRecoveryCommitlogFiles;
    }

    public boolean isDispatchFromSenderThread() {
        return dispatchFromSenderThread;
    }

    public void setDispatchFromSenderThread(boolean dispatchFromSenderThread) {
        this.dispatchFromSenderThread = dispatchFromSenderThread;
    }

    public int getDispatchCqThreads() {
        return dispatchCqThreads;
    }

    public void setDispatchCqThreads(final int dispatchCqThreads) {
        this.dispatchCqThreads = dispatchCqThreads;
    }

    public int getDispatchCqCacheNum() {
        return dispatchCqCacheNum;
    }

    public void setDispatchCqCacheNum(final int dispatchCqCacheNum) {
        this.dispatchCqCacheNum = dispatchCqCacheNum;
    }

    public boolean isEnableAsyncReput() {
        return enableAsyncReput;
    }

    public void setEnableAsyncReput(final boolean enableAsyncReput) {
        this.enableAsyncReput = enableAsyncReput;
    }

    public boolean isRecheckReputOffsetFromCq() {
        return recheckReputOffsetFromCq;
    }

    public void setRecheckReputOffsetFromCq(final boolean recheckReputOffsetFromCq) {
        this.recheckReputOffsetFromCq = recheckReputOffsetFromCq;
    }

    public long getCommitLogForceSwapMapInterval() {
        return commitLogForceSwapMapInterval;
    }

    public void setCommitLogForceSwapMapInterval(long commitLogForceSwapMapInterval) {
        this.commitLogForceSwapMapInterval = commitLogForceSwapMapInterval;
    }

    public int getCommitLogSwapMapReserveFileNum() {
        return commitLogSwapMapReserveFileNum;
    }

    public void setCommitLogSwapMapReserveFileNum(int commitLogSwapMapReserveFileNum) {
        this.commitLogSwapMapReserveFileNum = commitLogSwapMapReserveFileNum;
    }

    public long getLogicQueueForceSwapMapInterval() {
        return logicQueueForceSwapMapInterval;
    }

    public void setLogicQueueForceSwapMapInterval(long logicQueueForceSwapMapInterval) {
        this.logicQueueForceSwapMapInterval = logicQueueForceSwapMapInterval;
    }

    public int getLogicQueueSwapMapReserveFileNum() {
        return logicQueueSwapMapReserveFileNum;
    }

    public void setLogicQueueSwapMapReserveFileNum(int logicQueueSwapMapReserveFileNum) {
        this.logicQueueSwapMapReserveFileNum = logicQueueSwapMapReserveFileNum;
    }

    public long getCleanSwapedMapInterval() {
        return cleanSwapedMapInterval;
    }

    public void setCleanSwapedMapInterval(long cleanSwapedMapInterval) {
        this.cleanSwapedMapInterval = cleanSwapedMapInterval;
    }

    public long getCommitLogSwapMapInterval() {
        return commitLogSwapMapInterval;
    }

    public void setCommitLogSwapMapInterval(long commitLogSwapMapInterval) {
        this.commitLogSwapMapInterval = commitLogSwapMapInterval;
    }

    public long getLogicQueueSwapMapInterval() {
        return logicQueueSwapMapInterval;
    }

    public void setLogicQueueSwapMapInterval(long logicQueueSwapMapInterval) {
        this.logicQueueSwapMapInterval = logicQueueSwapMapInterval;
    }

    public int getMaxBatchDeleteFilesNum() {
        return maxBatchDeleteFilesNum;
    }

    public void setMaxBatchDeleteFilesNum(int maxBatchDeleteFilesNum) {
        this.maxBatchDeleteFilesNum = maxBatchDeleteFilesNum;
    }

    public boolean isSearchBcqByCacheEnable() {
        return searchBcqByCacheEnable;
    }

    public void setSearchBcqByCacheEnable(boolean searchBcqByCacheEnable) {
        this.searchBcqByCacheEnable = searchBcqByCacheEnable;
    }

    public int getDiskSpaceWarningLevelRatio() {
        return diskSpaceWarningLevelRatio;
    }

    public void setDiskSpaceWarningLevelRatio(int diskSpaceWarningLevelRatio) {
        this.diskSpaceWarningLevelRatio = diskSpaceWarningLevelRatio;
    }

    public int getDiskSpaceCleanForciblyRatio() {
        return diskSpaceCleanForciblyRatio;
    }

    public void setDiskSpaceCleanForciblyRatio(int diskSpaceCleanForciblyRatio) {
        this.diskSpaceCleanForciblyRatio = diskSpaceCleanForciblyRatio;
    }

    public boolean isMappedFileSwapEnable() {
        return mappedFileSwapEnable;
    }

    public void setMappedFileSwapEnable(boolean mappedFileSwapEnable) {
        this.mappedFileSwapEnable = mappedFileSwapEnable;
    }

    public int getPullBatchMaxMessageCount() {
        return pullBatchMaxMessageCount;
    }

    public void setPullBatchMaxMessageCount(int pullBatchMaxMessageCount) {
        this.pullBatchMaxMessageCount = pullBatchMaxMessageCount;
    }

    public int getDeleteFileBatchMax() {
        return deleteFileBatchMax;
    }

    public void setDeleteFileBatchMax(int deleteFileBatchMax) {
        this.deleteFileBatchMax = deleteFileBatchMax;
    }

    public int getTotalReplicas() {
        return totalReplicas;
    }

    public void setTotalReplicas(int totalReplicas) {
        this.totalReplicas = totalReplicas;
    }

    public int getInSyncReplicas() {
        return inSyncReplicas;
    }

    public void setInSyncReplicas(int inSyncReplicas) {
        this.inSyncReplicas = inSyncReplicas;
    }

    public int getMinInSyncReplicas() {
        return minInSyncReplicas;
    }

    public void setMinInSyncReplicas(int minInSyncReplicas) {
        this.minInSyncReplicas = minInSyncReplicas;
    }

    public boolean isAllAckInSyncStateSet() {
        return allAckInSyncStateSet;
    }

    public void setAllAckInSyncStateSet(boolean allAckInSyncStateSet) {
        this.allAckInSyncStateSet = allAckInSyncStateSet;
    }

    public boolean isEnableAutoInSyncReplicas() {
        return enableAutoInSyncReplicas;
    }

    public void setEnableAutoInSyncReplicas(boolean enableAutoInSyncReplicas) {
        this.enableAutoInSyncReplicas = enableAutoInSyncReplicas;
    }

    public boolean isHaFlowControlEnable() {
        return haFlowControlEnable;
    }

    public void setHaFlowControlEnable(boolean haFlowControlEnable) {
        this.haFlowControlEnable = haFlowControlEnable;
    }

    public long getMaxHaTransferByteInSecond() {
        return maxHaTransferByteInSecond;
    }

    public void setMaxHaTransferByteInSecond(long maxHaTransferByteInSecond) {
        this.maxHaTransferByteInSecond = maxHaTransferByteInSecond;
    }

    public long getHaMaxTimeSlaveNotCatchup() {
        return haMaxTimeSlaveNotCatchup;
    }

    public void setHaMaxTimeSlaveNotCatchup(long haMaxTimeSlaveNotCatchup) {
        this.haMaxTimeSlaveNotCatchup = haMaxTimeSlaveNotCatchup;
    }

    public boolean isSyncMasterFlushOffsetWhenStartup() {
        return syncMasterFlushOffsetWhenStartup;
    }

    public void setSyncMasterFlushOffsetWhenStartup(boolean syncMasterFlushOffsetWhenStartup) {
        this.syncMasterFlushOffsetWhenStartup = syncMasterFlushOffsetWhenStartup;
    }

    public long getMaxChecksumRange() {
        return maxChecksumRange;
    }

    public void setMaxChecksumRange(long maxChecksumRange) {
        this.maxChecksumRange = maxChecksumRange;
    }

    public int getReplicasPerDiskPartition() {
        return replicasPerDiskPartition;
    }

    public void setReplicasPerDiskPartition(int replicasPerDiskPartition) {
        this.replicasPerDiskPartition = replicasPerDiskPartition;
    }

    public double getLogicalDiskSpaceCleanForciblyThreshold() {
        return logicalDiskSpaceCleanForciblyThreshold;
    }

    public void setLogicalDiskSpaceCleanForciblyThreshold(double logicalDiskSpaceCleanForciblyThreshold) {
        this.logicalDiskSpaceCleanForciblyThreshold = logicalDiskSpaceCleanForciblyThreshold;
    }

    public int getDisappearTimeAfterStart() {
        return disappearTimeAfterStart;
    }

    public void setDisappearTimeAfterStart(int disappearTimeAfterStart) {
        this.disappearTimeAfterStart = disappearTimeAfterStart;
    }

    public long getMaxSlaveResendLength() {
        return maxSlaveResendLength;
    }

    public void setMaxSlaveResendLength(long maxSlaveResendLength) {
        this.maxSlaveResendLength = maxSlaveResendLength;
    }

    public boolean isSyncFromLastFile() {
        return syncFromLastFile;
    }

    public void setSyncFromLastFile(boolean syncFromLastFile) {
        this.syncFromLastFile = syncFromLastFile;
    }

    public boolean isEnableLmq() {
        return enableLmq;
    }

    public void setEnableLmq(boolean enableLmq) {
        this.enableLmq = enableLmq;
    }

    public boolean isEnableMultiDispatch() {
        return enableMultiDispatch;
    }

    public void setEnableMultiDispatch(boolean enableMultiDispatch) {
        this.enableMultiDispatch = enableMultiDispatch;
    }

    public int getMaxLmqConsumeQueueNum() {
        return maxLmqConsumeQueueNum;
    }

    public void setMaxLmqConsumeQueueNum(int maxLmqConsumeQueueNum) {
        this.maxLmqConsumeQueueNum = maxLmqConsumeQueueNum;
    }

    public boolean isEnableScheduleAsyncDeliver() {
        return enableScheduleAsyncDeliver;
    }

    public void setEnableScheduleAsyncDeliver(boolean enableScheduleAsyncDeliver) {
        this.enableScheduleAsyncDeliver = enableScheduleAsyncDeliver;
    }

    public int getScheduleAsyncDeliverMaxPendingLimit() {
        return scheduleAsyncDeliverMaxPendingLimit;
    }

    public void setScheduleAsyncDeliverMaxPendingLimit(int scheduleAsyncDeliverMaxPendingLimit) {
        this.scheduleAsyncDeliverMaxPendingLimit = scheduleAsyncDeliverMaxPendingLimit;
    }

    public int getScheduleAsyncDeliverMaxResendNum2Blocked() {
        return scheduleAsyncDeliverMaxResendNum2Blocked;
    }

    public void setScheduleAsyncDeliverMaxResendNum2Blocked(int scheduleAsyncDeliverMaxResendNum2Blocked) {
        this.scheduleAsyncDeliverMaxResendNum2Blocked = scheduleAsyncDeliverMaxResendNum2Blocked;
    }

    public boolean isAsyncLearner() {
        return asyncLearner;
    }

    public void setAsyncLearner(boolean asyncLearner) {
        this.asyncLearner = asyncLearner;
    }

    public int getMappedFileSizeTimerLog() {
        return mappedFileSizeTimerLog;
    }

    public void setMappedFileSizeTimerLog(final int mappedFileSizeTimerLog) {
        this.mappedFileSizeTimerLog = mappedFileSizeTimerLog;
    }

    public int getTimerPrecisionMs() {
        return timerPrecisionMs;
    }

    public void setTimerPrecisionMs(int timerPrecisionMs) {
        int[] candidates = {100, 200, 500, 1000};
        for (int i = 1; i < candidates.length; i++) {
            if (timerPrecisionMs < candidates[i]) {
                this.timerPrecisionMs = candidates[i - 1];
                return;
            }
        }
        this.timerPrecisionMs = candidates[candidates.length - 1];
    }

    public int getTimerRollWindowSlot() {
        return timerRollWindowSlot;
    }

    public int getTimerGetMessageThreadNum() {
        return timerGetMessageThreadNum;
    }

    public void setTimerGetMessageThreadNum(int timerGetMessageThreadNum) {
        this.timerGetMessageThreadNum = timerGetMessageThreadNum;
    }

    public int getTimerPutMessageThreadNum() {
        return timerPutMessageThreadNum;
    }

    public void setTimerPutMessageThreadNum(int timerPutMessageThreadNum) {
        this.timerPutMessageThreadNum = timerPutMessageThreadNum;
    }

    public boolean isTimerEnableDisruptor() {
        return timerEnableDisruptor;
    }

    public boolean isTimerEnableCheckMetrics() {
        return timerEnableCheckMetrics;
    }

    public void setTimerEnableCheckMetrics(boolean timerEnableCheckMetrics) {
        this.timerEnableCheckMetrics = timerEnableCheckMetrics;
    }

    public boolean isTimerStopEnqueue() {
        return timerStopEnqueue;
    }

    public void setTimerStopEnqueue(boolean timerStopEnqueue) {
        this.timerStopEnqueue = timerStopEnqueue;
    }

    public String getTimerCheckMetricsWhen() {
        return timerCheckMetricsWhen;
    }

    public boolean isTimerSkipUnknownError() {
        return timerSkipUnknownError;
    }

    public void setTimerSkipUnknownError(boolean timerSkipUnknownError) {
        this.timerSkipUnknownError = timerSkipUnknownError;
    }

    public boolean isTimerWarmEnable() {
        return timerWarmEnable;
    }

    public boolean isTimerWheelEnable() {
        return timerWheelEnable;
    }

    public void setTimerWheelEnable(boolean timerWheelEnable) {
        this.timerWheelEnable = timerWheelEnable;
    }

    public boolean isTimerStopDequeue() {
        return timerStopDequeue;
    }

    public int getTimerMetricSmallThreshold() {
        return timerMetricSmallThreshold;
    }

    public void setTimerMetricSmallThreshold(int timerMetricSmallThreshold) {
        this.timerMetricSmallThreshold = timerMetricSmallThreshold;
    }

    public int getTimerCongestNumEachSlot() {
        return timerCongestNumEachSlot;
    }

    public void setTimerCongestNumEachSlot(int timerCongestNumEachSlot) {
        // In order to get this value from messageStoreConfig properties file created before v4.4.1.
        this.timerCongestNumEachSlot = timerCongestNumEachSlot;
    }

    public int getTimerFlushIntervalMs() {
        return timerFlushIntervalMs;
    }

    public void setTimerFlushIntervalMs(final int timerFlushIntervalMs) {
        this.timerFlushIntervalMs = timerFlushIntervalMs;
    }

    public void setTimerRollWindowSlot(final int timerRollWindowSlot) {
        this.timerRollWindowSlot = timerRollWindowSlot;
    }

    public int getTimerProgressLogIntervalMs() {
        return timerProgressLogIntervalMs;
    }

    public void setTimerProgressLogIntervalMs(final int timerProgressLogIntervalMs) {
        this.timerProgressLogIntervalMs = timerProgressLogIntervalMs;
    }

    public boolean isTimerInterceptDelayLevel() {
        return timerInterceptDelayLevel;
    }

    public void setTimerInterceptDelayLevel(boolean timerInterceptDelayLevel) {
        this.timerInterceptDelayLevel = timerInterceptDelayLevel;
    }

    public int getTimerMaxDelaySec() {
        return timerMaxDelaySec;
    }

    public void setTimerMaxDelaySec(final int timerMaxDelaySec) {
        this.timerMaxDelaySec = timerMaxDelaySec;
    }

    public int getMaxConsumeQueueScan() {
        return maxConsumeQueueScan;
    }

    public void setMaxConsumeQueueScan(int maxConsumeQueueScan) {
        this.maxConsumeQueueScan = maxConsumeQueueScan;
    }

    public int getSampleCountThreshold() {
        return sampleCountThreshold;
    }

    public void setSampleCountThreshold(int sampleCountThreshold) {
        this.sampleCountThreshold = sampleCountThreshold;
    }

    public boolean isColdDataFlowControlEnable() {
        return coldDataFlowControlEnable;
    }

    public void setColdDataFlowControlEnable(boolean coldDataFlowControlEnable) {
        this.coldDataFlowControlEnable = coldDataFlowControlEnable;
    }

    public boolean isColdDataScanEnable() {
        return coldDataScanEnable;
    }

    public void setColdDataScanEnable(boolean coldDataScanEnable) {
        this.coldDataScanEnable = coldDataScanEnable;
    }

    public int getTimerColdDataCheckIntervalMs() {
        return timerColdDataCheckIntervalMs;
    }

    public void setTimerColdDataCheckIntervalMs(int timerColdDataCheckIntervalMs) {
        this.timerColdDataCheckIntervalMs = timerColdDataCheckIntervalMs;
    }

    public int getSampleSteps() {
        return sampleSteps;
    }

    public void setSampleSteps(int sampleSteps) {
        this.sampleSteps = sampleSteps;
    }

    public int getAccessMessageInMemoryHotRatio() {
        return accessMessageInMemoryHotRatio;
    }

    public void setAccessMessageInMemoryHotRatio(int accessMessageInMemoryHotRatio) {
        this.accessMessageInMemoryHotRatio = accessMessageInMemoryHotRatio;
    }

    public boolean isDataReadAheadEnable() {
        return dataReadAheadEnable;
    }

    public void setDataReadAheadEnable(boolean dataReadAheadEnable) {
        this.dataReadAheadEnable = dataReadAheadEnable;
    }

    public boolean isEnableBuildConsumeQueueConcurrently() {
        return enableBuildConsumeQueueConcurrently;
    }

    public void setEnableBuildConsumeQueueConcurrently(boolean enableBuildConsumeQueueConcurrently) {
        this.enableBuildConsumeQueueConcurrently = enableBuildConsumeQueueConcurrently;
    }

    public int getBatchDispatchRequestThreadPoolNums() {
        return batchDispatchRequestThreadPoolNums;
    }

    public void setBatchDispatchRequestThreadPoolNums(int batchDispatchRequestThreadPoolNums) {
        this.batchDispatchRequestThreadPoolNums = batchDispatchRequestThreadPoolNums;
    }

    public boolean isRealTimePersistRocksDBConfig() {
        return realTimePersistRocksDBConfig;
    }

    public void setRealTimePersistRocksDBConfig(boolean realTimePersistRocksDBConfig) {
        this.realTimePersistRocksDBConfig = realTimePersistRocksDBConfig;
    }

    public long getStatRocksDBCQIntervalSec() {
        return statRocksDBCQIntervalSec;
    }

    public void setStatRocksDBCQIntervalSec(long statRocksDBCQIntervalSec) {
        this.statRocksDBCQIntervalSec = statRocksDBCQIntervalSec;
    }

    public long getCleanRocksDBDirtyCQIntervalMin() {
        return cleanRocksDBDirtyCQIntervalMin;
    }

    public void setCleanRocksDBDirtyCQIntervalMin(long cleanRocksDBDirtyCQIntervalMin) {
        this.cleanRocksDBDirtyCQIntervalMin = cleanRocksDBDirtyCQIntervalMin;
    }

    public long getMemTableFlushIntervalMs() {
        return memTableFlushIntervalMs;
    }

    public void setMemTableFlushIntervalMs(long memTableFlushIntervalMs) {
        this.memTableFlushIntervalMs = memTableFlushIntervalMs;
    }

    public boolean isEnableRocksDBLog() {
        return enableRocksDBLog;
    }

    public void setEnableRocksDBLog(boolean enableRocksDBLog) {
        this.enableRocksDBLog = enableRocksDBLog;
    }

    public int getTopicQueueLockNum() {
        return topicQueueLockNum;
    }

    public void setTopicQueueLockNum(int topicQueueLockNum) {
        this.topicQueueLockNum = topicQueueLockNum;
    }

    public boolean isReadUnCommitted() {
        return readUnCommitted;
    }

    public void setReadUnCommitted(boolean readUnCommitted) {
        this.readUnCommitted = readUnCommitted;
    }

    public boolean isPutConsumeQueueDataByFileChannel() {
        return putConsumeQueueDataByFileChannel;
    }

    public void setPutConsumeQueueDataByFileChannel(boolean putConsumeQueueDataByFileChannel) {
        this.putConsumeQueueDataByFileChannel = putConsumeQueueDataByFileChannel;
    }

    public boolean isTransferMetadataJsonToRocksdb() {
        return transferMetadataJsonToRocksdb;
    }

    public void setTransferMetadataJsonToRocksdb(boolean transferMetadataJsonToRocksdb) {
        this.transferMetadataJsonToRocksdb = transferMetadataJsonToRocksdb;
    }

}
