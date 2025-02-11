# RocketMQ 消息过滤机制
过滤机制包括:
1. Tag过滤：简单高效，适合多订阅者需要接受不同子类型消息的场景
2. SQL语法过滤： 灵活强大，适合需要复杂条件过滤场景，但是需要对性能加以考虑，并且需要在Broker端配置以支持属性过滤
   ```conf
     # 需要配置 enablePropertyFilter=true启用SQL9语法过滤功能
   ```