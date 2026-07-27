---
kind: design
name: 将通用 sandbox 客户端逻辑抽取为独立 Maven 模块 agentscope-extensions-agent-sandbox
source: session
category: adr
---

# 将通用 sandbox 客户端逻辑抽取为独立 Maven 模块 agentscope-extensions-agent-sandbox

_来源：1bd783d → 50622b1 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
原 `agentscope-extensions-sandbox-kubernetes` 中混杂了通用的 HTTP 连接器、ConnectionStrategy、CommandExecutor、Filesystem 等与 Kubernetes 无关的逻辑，导致代码重复且难以被其他运行时复用。

## 决策驱动
- 消除跨模块代码重复
- 让非 K8s 运行时也能复用 sandbox 客户端
- 降低 kubernetes 模块依赖复杂度（移除 Fabric8）

## 备选方案
- **在现有 kubernetes 模块内按包拆分** _（已否决）_ — 优点：无需新建模块，Maven 配置简单；缺点：其他运行时仍需依赖整个 kubernetes 模块及其 Fabric8 依赖
- **新建独立的 agentscope-extensions-agent-sandbox 模块** — 优点：纯 Java 标准库 + Jackson，无 Fabric8 依赖；任何运行时均可引用；与 Python SDK 结构对齐；缺点：新增一个 Maven 模块，需维护父子 pom 注册

## 决策
新建 `agentscope-extensions-agent-sandbox` 模块，包含 model、connector、command、filesystem、exception 等通用层；`agentscope-extensions-sandbox-kubernetes` 仅保留 K8s 特定适配（CRD 模型、PortForwardStrategy），并通过该模块的 SDK 完成上层封装。

## 影响
SDK 模块不依赖 Fabric8，可被 JVM 上任意运行时（如 Docker、本地进程）复用；kubernetes 模块体积减小，编译更快。后续新增运行时只需实现 ConnectionStrategy 即可接入。