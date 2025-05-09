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
package org.apache.rocketmq.example.simple;

import io.netty.channel.DefaultChannelId;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class Producer {

    public static final String PRODUCER_GROUP = "ProducerGroupName";
    public static final String DEFAULT_NAMESRVADDR = "127.0.0.1:9876";
    public static final String TOPIC = "TopicTest";
    public static final String TAG = "TagA";

    public static void main(String[] args) throws MQClientException, InterruptedException {

        DefaultMQProducer producer = new DefaultMQProducer(PRODUCER_GROUP);
        producer.setNamesrvAddr(DEFAULT_NAMESRVADDR);

        // Uncomment the following line while debugging, namesrvAddr should be set to your local address
        // producer.setNamesrvAddr(DEFAULT_NAMESRVADDR);
        DefaultChannelId.newInstance();
        producer.start();
        for (long i = 0; i < 9999999999L; i++) {
            try {
                Message msg = new Message(TOPIC, TAG, "OrderID188", (" Hello world_"+i).getBytes(StandardCharsets.UTF_8));
                SendResult sendResult = producer.send(msg,Integer.MAX_VALUE);
                System.out.printf("%s%n", sendResult);

                TimeUnit.MILLISECONDS.sleep(3);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        producer.shutdown();
    }
}
