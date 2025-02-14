# RocketMQ

## RocketMQ主要特性
1. 高吞吐量: RocketMQ可以处理高流量、低延迟的数据流，非常适合金融、电子商务等高并发场景。
2. 高可靠性: 通过消息持久化、多副本机制，RocketMQ确保消息的高度可靠性。
3. 灵活的消息模型: 支持多种模型，包括点对点和发布/订阅。
    > 具体参考:[]()
4. 支持顺序消息: 支持在同一个主题内，消息的消费顺序
    >  具体参考:[顺序消息](./000.STU-DOCS/008.RocketMQ顺序消息/RocketMQ如何保证消息顺序.md)
5. 支持事务消息： 支持分布式事务，确保消息和数据库操作的一致性.
    > 具体参考:[事务消息](./000.STU-DOCS/009.RocketMQ事务消息/RocketMQ事务消息是如何实现的.md)
6. 丰富的消息过滤机制： 支持[基于Tag和属性的消息过滤](./000.STU-DOCS/007.RocketMQ消息过滤机制/RocketMQ消息过滤机制.md).

## 使用场景
1. 流量削峰：高并发情况下，消息队列可以缓冲流量，防止流量激增导致系统崩溃
2. 事件驱动架构: 实现复杂业务流程的事件驱动，推动业务中各个阶段的事件流转
3. 日志处理: 收集和处理日志、监控数据等场景，适合大规模数据的实时处理
4. 系统解耦: 微服务结构中，服务间通过消息队列进行解耦，降低服务间的相互依赖
5. 异步处理: 非实时性任务通过消息队列处理，提高系统响应速度.

---

## 参考
1. [RocketMQ中文社区](https://rocketmq.io/?spm=5176.29160081.0.0.30a2666bMSVrr1)