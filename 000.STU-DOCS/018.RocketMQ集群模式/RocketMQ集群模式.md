# RocketMQ 集群模式
> 阅读:[Dledger](./Dledger%20_%20RocketMQ.pdf) 、 [RocketMQ 部署方式](./部署方式%20_%20RocketMQ.pdf)
1. 单Master模式
2. Master-Slave模式(经典双集群模式)
   > 一个master节点对应多个slave节点,master和slave都是独立的NameServer。master节点负责响应客户请求并存储消息，slave节点只同步master节点消息，也会响应部分客户端读请求
3. Dledger模式(高可用集群部署)
   > 在Master-Slave模式增加Raft协议，实现了自动脑裂后的数据高可靠性。