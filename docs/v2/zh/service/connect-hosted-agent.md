---
title: "Hosted：连接并创建 Agent"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

连接装有 Coding Agent 的电脑或服务器，让 Service 把工作派发到该主机。先在主机安装并登录要使用的 provider，再按[Runtime Host 安装指南](/v2/zh/service/runtime-host)准备 `agentscope` CLI。

## 连接主机

将下面的 URL 替换成主机可以访问的 Service 地址：

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

按连接流程完成身份认证。确认 Host 在线并成功探测 provider；再在主机上验证 provider 自己能够完成一次请求。支持类型与能力差异见[Provider 参考](/v2/zh/service/hosted-agent-providers)。

## 创建 Hosted Agent

1. 打开 **DESIGN → Agents → New agent**，填写名称和职责。
2. Runtime 选择已发现的 Coding Agent provider。
3. Model 可留空使用默认配置；只关联 provider 支持的 Workspace 能力。
4. 保存后检查 Runtime 就绪状态。

## 派发并核对结果

创建一个只读 [Issue](/v2/zh/service/issues)，例如“根据提供的 README 内容给出三条文档改进建议”，选择该 Agent。查看 Execution、结果评论与交付物，确认工作由预期主机执行。

任务目录由 Host 管理；本机已经打开的 Git 仓库不会自动成为任务输入。准备好实际资料后再要求读写文件，将需要共享的结果上传为 Artifact。

没有可选 Runtime 时检查 Host 和 provider 探测结果；领取后失败时检查登录、参数和依赖。完整参数、定义映射及恢复行为见[Hosted Agent 参考手册](/v2/zh/service/hosted-agent)。

单任务接入通过后，继续[全 Hosted 研发闭环](/v2/zh/service/cases/sdlc-team)，验证需求分析、代码修改、PR、Review、CI 与审批协作。
