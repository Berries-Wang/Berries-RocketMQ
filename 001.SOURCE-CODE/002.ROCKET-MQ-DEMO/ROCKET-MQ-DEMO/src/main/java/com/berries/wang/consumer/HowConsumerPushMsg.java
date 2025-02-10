package com.berries.wang.consumer;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public class HowConsumerPushMsg {
    public static void main(String[] args) throws MQClientException {
        // 实例化Consumer , 创建并配置一个Consumer实例
        DefaultMQPushConsumer defaultMQPushConsumer = new DefaultMQPushConsumer("Default-Consumer-Group");

        // 设置NamneServer地址
        defaultMQPushConsumer.setNamesrvAddr("localhost:9876");

        // 设置消费起始位置：ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET 表示从最早的可用消息开始消费
        defaultMQPushConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);

        // 订阅一个主题以进行消费订阅: 参考: 001.SOURCE-CODE/000.ROCKET-MQ-5.3.1-RELEASE/ROCKET-MQ-5.3.1-RELEASE/client/src/main/java/org/apache/rocketmq/client/consumer/DefaultMQPushConsumer.java
        // 指定需要订阅的主题和标签
        defaultMQPushConsumer.subscribe("Test-Topic", "*");

        // 参考: org.apache.rocketmq.client.consumer.DefaultMQPushConsumer.setPullThresholdForTopic 代码注释
        // 所以，RocketMQ Client 是一次性拉取多个消息到本地，当本地缓存的消息数量低于阈值，则继续拉取
        defaultMQPushConsumer.setPullThresholdForTopic(1000);

        // 注册回调函数处理消息: 注册消息监听器
        defaultMQPushConsumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messageExts,
                ConsumeConcurrentlyContext consumeConcurrentlyContext) {
                Optional.ofNullable(messageExts).orElse(new LinkedList<>()).forEach(eleMsg -> {
                    System.out.println("收到消息: " + new String(eleMsg.getBody()));
                });
                // 消费成功,手动提交位移
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                // 消费失败，稍后重试
                // return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });

        // 启动consumer实例
        defaultMQPushConsumer.start();
    }
}
