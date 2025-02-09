package com.berries.wang.producer;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

/**
 * Producer如何发送消息
 */
public class HowProducerMsg {
    public static void main(String[] args) throws MQClientException, RemotingException, InterruptedException {
        // 创建生产者实例，并指定生产者组名
        DefaultMQProducer defaultMQProducer = new DefaultMQProducer("Default-Producer-Group");

        // 设置nameserver
        defaultMQProducer.setNamesrvAddr("localhost:9876");

        // 设置发送超时时间
        defaultMQProducer.setSendMsgTimeout(3000);

        // 设置重试次数
        defaultMQProducer.setRetryTimesWhenSendAsyncFailed(3); // 异步发送重试次数设置
        defaultMQProducer.setRetryTimesWhenSendFailed(3); // 同步发送重试次数设置

        // 开启producer
        defaultMQProducer.start();

        // 消息发送逻辑
        {
            // 创建消息
            String topic = "Test-Topic";
            String tags = "Tag-A";
            String keys = "OrderId_100";
            String message_body = "Hello RocketMQ";

            Message msg = new Message(topic, tags, keys, message_body.getBytes());

            // 发送消息,多种发送模式: 同步发送 、异步发送、单向发送
            { // 同步发送: 等待服务器响应
                try {
                    SendResult sendResult = defaultMQProducer.send(msg);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            { // 异步发送: 适用对响应时间敏感的业务场景
                try {
                    defaultMQProducer.send(msg, new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            System.out.println("发送成功");
                        }

                        @Override
                        public void onException(Throwable throwable) {
                            System.out.println("发送失败");
                        }
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            { // 单向发送: 不关心发送结果，适用于不太重要的日志收集场景
                defaultMQProducer.sendOneway(msg);
            }
        }


        // 使用完成后关闭Producer
        defaultMQProducer.shutdown();
    }
}
