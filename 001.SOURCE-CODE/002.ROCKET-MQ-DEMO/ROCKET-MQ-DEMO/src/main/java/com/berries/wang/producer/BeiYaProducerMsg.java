package com.berries.wang.producer;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

import java.util.concurrent.TimeUnit;

/**
 * 背压
 **/
public class BeiYaProducerMsg {
    public static void main(String[] args)
        throws InterruptedException, MQBrokerException, RemotingException, MQClientException {
        String groupName = "Producer-Group-BeiYa";
        DefaultMQProducer defaultMQProducer = new DefaultMQProducer(groupName);

        defaultMQProducer.setNamesrvAddr("localhost:9876");

        // 设置消息发送超时时间，若超过，则说明可能存在积压，可以降级发送频率
        defaultMQProducer.setSendMsgTimeout(3000);

        // 设置异步发送失败重试次数
        defaultMQProducer.setRetryTimesWhenSendAsyncFailed(0);

        // 示例： 根据积压情况调整发送频率
        while (true) {
            if (checkMessageAccumulation(groupName)) {
                // 降低发送频率
                TimeUnit.MILLISECONDS.sleep(1000);
            }

            Message msg = new Message("Topic-Test", "TagA", "Hello RocketMQ".getBytes());
            defaultMQProducer.send(msg);
        }
    }

    public static boolean checkMessageAccumulation(String groupName) {
        long ACCUMULATION_THRESHOLD = 10000;

        // DefaultMQAdminExt RocketMQ提供的管理类，用于管理和监控RocketMQ集群.
        DefaultMQAdminExt defaultMQAdminExt = new DefaultMQAdminExt();
        defaultMQAdminExt.setInstanceName("checkMessageAccumulation");

        try {
            defaultMQAdminExt.start();
            ConsumeStats consumeStats = defaultMQAdminExt.examineConsumeStats(groupName);
            // 计算积压总数
            long accumulateCount = consumeStats.computeTotalDiff();
            // 发生积压
            if (accumulateCount > ACCUMULATION_THRESHOLD) {
                System.out.println("发生消息积压");
                return true;
            }
        } catch (Exception ex) {

        } finally {
            defaultMQAdminExt.shutdown();
        }
        return false;
    }
}
