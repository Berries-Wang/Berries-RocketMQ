# 消费者分类
RocketMQ支持的消费者类型;
1. PushConsumer
2. SimpleConsumer
3. pullConsumer 

## PushConsumer
PushConsumer是一种高度封装的消费者类型，消费消息仅通过消费监听器处理业务并返回消费结果。消息的获取、消费状态提交以及消费重试都通过RocketMQ的客户端SDK完成。

### 内部原理
[消费者分类](./消费者分类%20_%20RocketMQ.pdf)

SDK 内置了一个长轮询线程，现将消息异步拉取到SDK内置的缓存队列中，再分别提交到消费线程中，触发监听器执行本地消费逻辑。


## SimpleConsumer
SimpleConsumer是一种接口原子型的消费者类型，消息的获取、消费状态以及消费重试都是通过消费者业务逻辑主动发起调用完成。

```java
     // 消费示例：使用 SimpleConsumer 消费普通消息，主动获取消息处理并提交。 
     ClientServiceProvider provider = ClientServiceProvider.loadService();
     String topic = "YourTopic";
     FilterExpression filterExpression = new FilterExpression("YourFilterTag", FilterExpressionType.TAG);
     SimpleConsumer simpleConsumer = provider.newSimpleConsumerBuilder()
             // 设置消费者分组。
             .setConsumerGroup("YourConsumerGroup")
             // 设置接入点。
             .setClientConfiguration(ClientConfiguration.newBuilder().setEndpoints("YourEndpoint").build())
             // 设置预绑定的订阅关系。
             .setSubscriptionExpressions(Collections.singletonMap(topic, filterExpression))
             // 设置从服务端接受消息的最大等待时间
             .setAwaitDuration(Duration.ofSeconds(1))
             .build();
     try {
         // 主动获取消息: SimpleConsumer 需要主动获取消息，并处理。
         List<MessageView> messageViewList = simpleConsumer.receive(10, Duration.ofSeconds(30));
         messageViewList.forEach(messageView -> {
             System.out.println(messageView);
             // 提交处理结果: 消费处理完成后，需要主动调用 ACK 提交消费结果。
             try {
                 simpleConsumer.ack(messageView);
             } catch (ClientException e) {
                 logger.error("Failed to ack message, messageId={}", messageView.getMessageId(), e);
             }
         });
     } catch (ClientException e) {
         // 如果遇到系统流控等原因造成拉取失败，需要重新发起获取消息请求。
         logger.error("Failed to receive message", e);
     }
```
