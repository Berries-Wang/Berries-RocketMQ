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
        DefaultMQPushConsumer defaultMQPushConsumer = new DefaultMQPushConsumer("Default-Consumer-Group");

        defaultMQPushConsumer.setNamesrvAddr("localhost:9876");

        defaultMQPushConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);

        // 订阅一个主题以进行消费订阅: 参考: 001.SOURCE-CODE/000.ROCKET-MQ-5.3.1-RELEASE/ROCKET-MQ-5.3.1-RELEASE/client/src/main/java/org/apache/rocketmq/client/consumer/DefaultMQPushConsumer.java
        defaultMQPushConsumer.subscribe("Test-Topic", "*");

        // 参考: org.apache.rocketmq.client.consumer.DefaultMQPushConsumer.setPullThresholdForTopic 代码注释
        // 所以，RocketMQ Client 是一次性拉取多个消息到本地，当本地缓存的消息数量低于阈值，则继续拉取
        defaultMQPushConsumer.setPullThresholdForTopic(1000);

        // 注册回调函数处理消息
        defaultMQPushConsumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messageExts,
                ConsumeConcurrentlyContext consumeConcurrentlyContext) {
                Optional.ofNullable(messageExts).orElse(new LinkedList<>()).forEach(eleMsg -> {
                    System.out.println("收到消息: " + new String(eleMsg.getBody()));
                });
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        // 启动consumer实例
        defaultMQPushConsumer.start();
    }
}
