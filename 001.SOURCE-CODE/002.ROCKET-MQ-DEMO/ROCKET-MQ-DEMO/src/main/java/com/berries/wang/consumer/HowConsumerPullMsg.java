package com.berries.wang.consumer;

import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 拉模式消费
 **/
public class HowConsumerPullMsg {
    public static void main(String[] args)
        throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        // 创建消费者实例: 类都被废弃了,从代码注释来看，推荐使用： DefaultLitePullConsumer
        DefaultMQPullConsumer pullConsumer = new DefaultMQPullConsumer("PullConsumerGroup");

        pullConsumer.setNamesrvAddr("localhost:9876");

        pullConsumer.start();

        // 获取主题的消息队列
        Set<MessageQueue> messageQueues = pullConsumer.fetchSubscribeMessageQueues("Test-Topic");

        for (MessageQueue messageQueue : messageQueues) {
            long offset = pullConsumer.fetchConsumeOffset(messageQueue, true);
            while (true) {
                PullResult pullResult = pullConsumer.pull(messageQueue, "*", offset, 32);

                offset = pullResult.getNextBeginOffset();

                switch (pullResult.getPullStatus()) {
                    case FOUND: {
                        for (MessageExt msg : pullResult.getMsgFoundList()) {
                            System.out.println("收到消息:" + new String(msg.getBody()));
                        }
                    }
                    break;
                    case NO_NEW_MSG:
                    case NO_MATCHED_MSG:
                    case OFFSET_ILLEGAL:
                    default:
                        break;
                }

                // 更新消费者偏移量
                pullConsumer.updateConsumeOffset(messageQueue, offset);

                TimeUnit.MILLISECONDS.sleep(1000);
            }
        }

    }
}
