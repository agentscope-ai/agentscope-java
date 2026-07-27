---
kind: design
name: 从 Fabric8 Pod 直管迁移到 agent-sandbox CRD + HTTP API 模式
source: session
category: adr
---

# 从 Fabric8 Pod 直管迁移到 agent-sandbox CRD + HTTP API 模式

_来源：1bd783d → 50622b1 提交周期内记录的编码计划——内容为规划时意图，实现可能滞后或有出入。_

**状态：** accepted

## 背景
原有 `Fabric8KubernetesPodRuntime` 直接通过 Fabric8 客户端创建/管理 Pod，使用 K8s ExecWatch 执行命令、tar+exec 操作文件。需要改为由 agent-sandbox operator 管理 Pod 生命周期，Java 侧仅通过 HTTP API 与 sandbox-router 通信，以对齐 Python SDK 的架构并解耦 Pod 管理逻辑。

## 决策驱动
- 与 Python SDK 架构对齐
- 解耦 Pod 生命周期管理
- 统一跨语言连接策略

## 备选方案
- **继续使用 Fabric8 直接管理 Pod** _（已否决）_ — 优点：改动最小，无需引入新组件；缺点：无法复用 agent-sandbox operator 能力；Python/Java 实现差异大；难以支持多连接策略
- **通过 agent-sandbox CRD + HTTP API 间接管理** — 优点：Pod 生命周期交由 operator 管理；HTTP API 可被任意语言调用；天然支持 port-forward/Gateway/直连多种策略；缺点：需新增 Java HTTP 客户端和 CRD 模型；工作空间持久化需组合 tar 命令或扩展 API

## 决策
采用 SandboxClaim/SandboxWarmPoolRef CRD 引用预配置 WarmPool，通过 AgentSandboxClient 调用 sandbox-router 的 /execute、/upload、/download、/list、/exists HTTP 端点完成所有操作；连接策略抽象为 ConnectionStrategy 接口，支持 PortForward/Gateway/Direct/InCluster 四种方式。

## 影响
模块职责清晰分离：agent-sandbox operator 负责 Pod 调度与网络暴露，Java 侧专注业务编排。工作空间打包/解压需通过 run("tar ...") 组合实现，若频繁操作则应考虑扩展 sandbox-router 的 archive 端点。向后兼容保持 Jackson type name 为 "kubernetes"。