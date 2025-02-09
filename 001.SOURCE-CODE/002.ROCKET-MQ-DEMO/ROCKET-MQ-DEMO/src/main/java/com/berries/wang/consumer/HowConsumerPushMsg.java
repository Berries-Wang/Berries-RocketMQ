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

        // 注册回调函数处理消息
        defaultMQPushConsumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> list, ConsumeConcurrentlyContext consumeConcurrentlyContext) {
                Optional.ofNullable(list).orElse(new LinkedList<>()).forEach(eleMsg -> {
                    System.out.println("收到消息: " + new String(eleMsg.getBody()));
                });
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        // 启动consumer实例
        defaultMQPushConsumer.start();
    }
}
