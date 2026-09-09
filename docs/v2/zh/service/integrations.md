# SDK 与应用接入

已有 Agent 应用可以继续管理自己的进程，通过接入层向控制面提供身份、在线状态、会话信息和执行能力。

## 选择接入组件

| 组件 | 源码位置 | 发布形式 |
| --- | --- | --- |
| Java 扩展 | `agentscope-extensions/agentscope-extensions-aistio` | Maven 制品 |
| Python SDK | `agentscope-service/aistio/sdk/python` | `aistio-sdk` wheel / sdist |
| DSH 插件 | `agentscope-service/aistio/sdk/dsh` | `@agentscope/dsh-aistio` npm 包 |
| Coding Agent Host | `agentscope-service/aistio/cmd` | CLI / daemon 二进制包 |

SDK 各自有版本，不能仅根据 Service 镜像标签推断包版本。按 Release manifest 选择对应制品。尚未发布到公共包仓库的候选包，可以从 Release 下载后安装。

```bash
python -m pip install ./aistio_sdk-0.1.0-py3-none-any.whl
npm install ./agentscope-dsh-aistio-0.1.0.tgz
```

文件名中的版本以下载的制品为准。Java 应用按对应版本使用 `io.agentscope:agentscope-extensions-aistio` 及其依赖。

## 网络与协议

先确认适配器采用的协议。完整 Service 的 Compose / Helm 默认运行独立 HTTP 模式，没有启动 ASDP gRPC。需要 ASDP 的适配器必须使用已配置 Kubernetes-native Aistio 和相应 gRPC 连接的部署，不能把 HTTP 端口填入 gRPC 地址。

控制面需要能够访问应用声明的回调/合约地址。容器里的 `localhost` 指向容器自身；跨主机部署必须填写对方能访问的地址。共享内部令牌只用于受信任私网服务调用，外部 Host 使用专用身份凭据。

## 接入验收

确认注册与心跳、创建一次会话、读取历史，再测试一次支持的任务派发和结果回报。最后重启应用，检查身份连续性与状态恢复。模型执行、工具和应用自身的生命周期仍由所选运行时负责。

具体 API 以对应包的源码示例和版本说明为准；服务端运行成功不代表每一种第三方框架适配路径都已验证。
