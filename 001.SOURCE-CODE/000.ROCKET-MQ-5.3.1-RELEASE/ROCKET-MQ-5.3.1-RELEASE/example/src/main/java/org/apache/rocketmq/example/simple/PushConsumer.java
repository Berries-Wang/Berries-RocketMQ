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

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.channel.DefaultChannelId;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;

public class PushConsumer {
    public static final String TOPIC = "TopicTest";
    public static final String CONSUMER_GROUP = "CID_JODIE_1";
    public static final String NAMESRV_ADDR = "localhost:9876";

    public static void main(String[] args) throws InterruptedException, MQClientException {

        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(CONSUMER_GROUP);

        // Uncomment the following line while debugging, namesrvAddr should be set to your local address
        consumer.setNamesrvAddr(NAMESRV_ADDR);

        consumer.subscribe(TOPIC, "*");
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_MAX_OFFSET);
        //wrong time format 2017_0422_221800
        // consumer.setConsumeTimestamp("20181109221800");
        consumer.setConsumeTimeout(30);
        consumer.setConsumeThreadMin(20);
        consumer.setConsumeThreadMax(20);
        consumer.registerMessageListener(new MessageListenerConcurrently() {

            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
                System.out.printf("%s Receive New Messages: %d %n", Thread.currentThread().getName(), msgs.size());
                try {
                    RequestConfig requestConfig = RequestConfig.custom()
                            .setConnectionRequestTimeout(3000000)
                            .setConnectTimeout(3000000)
                            .setSocketTimeout(3000000).build();

                    CloseableHttpClient httpClient = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build();

                    HttpGet request = new HttpGet("http://127.0.0.1:8081/sentinel/flow/codeFactor");

                    CloseableHttpResponse response = httpClient.execute(request);

                    if (response.getStatusLine().getStatusCode() == 200) {
                        //请求体内容
                        String content = EntityUtils.toString(response.getEntity(), "UTF-8");
                        System.out.println("响应内容: " + content);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        DefaultChannelId.newInstance();

        consumer.start();
        System.out.printf("Consumer Started.%n");
    }
}
