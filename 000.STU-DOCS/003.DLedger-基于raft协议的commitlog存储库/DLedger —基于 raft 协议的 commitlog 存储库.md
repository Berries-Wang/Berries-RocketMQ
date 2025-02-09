# DLedger —基于 raft 协议的 commitlog 存储库
> 先阅读:[DLedger —基于 raft 协议的 commitlog 存储库](./DLedger%20—基于%20raft%20协议的%20commitlog%20存储库.pdf) , 再了解 Raft算法是如何运行的。 

## 着重注意
### 1. 消息提交成功的标识
Raft 协议复制过程可以分为四步，先是发送消息给 leader，leader 除了本地存储之外，会把消息复制给 follower，然后等待follower 确认，如果`得到多数节点确认`，`该消息就可以被提交，并向客户端返回发送成功的确认`。DLedger 中如何去优化这一复制过程？
> 即： 大多数follower确认，否则一直是未提交的状态。 可以参照 [Raft 算法是如何运行的](https://thesecretlivesofdata.com/raft/?spm=5176.29160081.0.0.30a2666bMSVrr1) 算法流程



## 参考资料
1. [Raft 算法是如何运行的](https://thesecretlivesofdata.com/raft/?spm=5176.29160081.0.0.30a2666bMSVrr1)