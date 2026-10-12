---
title: Capability Grant (能力裁剪)
description: 按用户/请求的实际权限，把 agent 无权的能力彻底切除
en_link: /v2/en/docs/building-blocks/capability-grant
---

## 概述

企业级系统里，agent 通常以**多租户、多角色**形态运行：同一个 agent 面向大量权限不同的用户。Capability grant layer（授权层）让你能**按用户 / 请求的实际权限，把无权能力彻底切除**——被切除的能力对模型**不存在**：不进 tool schema、不可枚举、不可被 `reset_equipped_tools` 激活、不可被 `load_skill_through_path` 加载、不可执行。

它与其他既有层正交：

| 层                              | 关系                                                                               |
| ------------------------------- | ---------------------------------------------------------------------------------- |
| **grant（本页）**               | 设置了白名单则只放行白名单内能力；未配置 resolver 或 resolver 返回 null 时为 ALLOW_ALL（不裁剪） |
| activation（`activatedGroups`） | 会话级软开关，决定已存在的能力「当前是否激活」；与 grant 独立叠加                  |
| Permission system               | 执行期审批（ALLOW / DENY / ASK）；grant 是事前切除，两者互补                       |
| 外部工具（`ToolRequestConfig`） | 由调用方经请求传入、外部工具在客户端执行无服务端安全问题、调用方自控，不在裁剪范围 |

## 快速接入

用户只需两件事：实现 `CapabilityResolver`、注册到 `ReActAgent`。

```java
CapabilityResolver resolver = (ctx, agent) -> {
    String userId = ctx.getUserId();
    // 粒度自选：按 (userId, agentId) 做 per-agent 差异，
    // 或忽略 agentId 按用户出一份数据（框架一视同仁）
    return new CapabilitySurface(
            authzService.allowedSkills(userId),      // 允许的 skillId；null = 未设置
            authzService.allowedToolGroups(userId),  // 允许的 ToolGroup 名；null = 未设置
            authzService.allowedTools(userId));      // 允许的工具名；null = 未设置
};

ReActAgent agent = ReActAgent.builder()
        .name("enterprise-agent").model(model)
        .toolkit(toolkit)
        .skillRepositories(repos)       // 多租户用动态路径；勿用静态 skillBox()（见下文）
        .capabilityResolver(resolver)   // 框架在每次 call 入口调用
        .build();
```

`CapabilitySurface` 是不可变的授权全集。框架在每次 `call()` 入口（服务端）解析一次——resolver 返回 `null` 等同不裁剪——随 `RuntimeContext` 走完整条调用链（请求内冻结、不持久化）；未自配 resolver 的子 agent 直接复用父授权面（零重算）。

**缓存建议**：resolver 在每次调用入口都会执行（调用内只此一次）。若其后端是 DB / RPC 等高成本查询，可按业务实际需要自行加缓存（如按 `userId` + TTL），并接受相应的权限变更生效延迟；框架不代管缓存。

返回超集是安全的：surface 内容可超出任何 agent 的实际能力范围，本 agent 没有的名字不会被任何读点命中。

## 三维语义（必读）

`CapabilitySurface` 只有三种形态，含义明确：

| 形态                                      | 语义                                                                                                                                     |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| 三个维度**全部为 `null`**（全部未设置）   | **不做任何裁剪**，全部放行（`ALLOW_ALL`）                                                                                                |
| 三个维度**全部为 `Set.of()`**（明确为空） | **全部禁止**（system 能力除外，即 `DENY_ALL`）                                                                                           |
| 任意 1~3 个维度已设置                     | surface 即一份**完整授权清单**：工具只要命中**任一已设置维度**（逐名 / 所属组 / 技能派生组）即通过；未设置（`null`）的维度不产生任何授权 |

```java
// 仅授权 reporting 组内的工具；未设置的技能/工具维度不产生额外授权
new CapabilitySurface(null, Set.of("reporting"), null);

// 完全不裁剪（唯一无条件放行的形态）
new CapabilitySurface(null, null, null);

// 全部禁止（除 system 能力）
new CapabilitySurface(Set.of(), Set.of(), Set.of());
```

`Set.of()`（明确为空）与 `null`（未设置）语义不同：前者是显式声明“该维度零授权”，后者是“未参与设置”。一旦 surface 不是三维全 `null`，两者在授权判定上效果一致（都不放行该维度）；访问器（`allowedSkills()` 等）仍保留这一区分。

## 授权判定规则

正向授权层次包含，命中**任一已设置维度**即放行：

- 授权一个 **skill** 即隐含授权其手册、脚本与关联工具（技能组 `skillId + "_skill_tools"`）；
- 授权一个 **ToolGroup** 即隐含授权组内全部工具；
- 逐名授权一个**工具**即授权该工具本身（不问分组，也不放大到组内其他工具）。

按 skillId 授权的精度依赖 skillId 唯一：`skillId = name + "_" + source` 对 `(name, source)` 非单射，name 含 `_` 时可能碰撞——多租户部署请保证 skill 命名全局唯一。

只提供白名单，不提供 deny（反向禁用）；默认拒绝——不在授权清单内的能力即「不存在」。

## 系统能力标记（`system`）

一般而言，标记 `system` 的能力是**保证 agent 正常运行的基本核心能力**（框架元工具即属此类）；该标记不是业务形态的裁剪控制手段，不应拿它充当业务能力的开关。

框架自身的元工具（`reset_equipped_tools`、`load_skill_through_path`）标记为 system，在任何授权面下（包括 DENY_ALL）保持可用。装配方也可将自己的工具/组/skill 标记 `system = true`（`@Tool(system = true)` 或对应 Builder）——这等同于装配方显式声明该能力免裁剪，注册时会输出 INFO 留痕。

**标记只作用于被标记的对象，不传染**：工具的 `system` 豁免该工具的可见与执行；**组的 `system` 仅豁免该组自身的枚举与激活**，组内成员工具仍受授权面控制（成员需要豁免时逐一标记 `system`）；skill 的 `system` 豁免其提示词可见与加载。

> **信任边界**：`system` 标记对装配方（agent 开发者）开放且可信。该授权层的威胁模型是「终端用户 vs agent 能力面」，不设防装配方自身。

## fail-closed 行为

resolver 抛异常时**中止本次调用**：异常传播给调用方并记录 ERROR（授权体系故障 ≠「无授权」）；需要降级继续服务请在 resolver 内部捕获并返回 `DENY_ALL`。

**直连执行**：`Toolkit.callTool` / `callTools` 等不经 agent `call()` 入口直接执行工具的路径不经过授权解析——无 surface 即视为不裁剪；需要按权限裁剪时，在 `RuntimeContext` 中显式放入 surface 即可（`rc.put(CapabilitySurface.class, surface)`）。

## 静态 skillBox 路径限制

`skillBox(...)` 已随 hook 系统于 2.0.0 标记为过时，**新代码不要使用**——skill 接入一律使用 `skillRepositories(...)` 动态路径。存量代码若仍停留在该路径，其限制如下：`skillBox(...)` 构建期注册（或 `dynamicSkillsEnabled(false)`）没有 per-call 可见面过滤——配置 resolver 时构建期会输出 ERROR 并使该 agent 的 resolver 失效——该 agent 直调时不裁剪；作为子代理运行时继承父授权面，skill 加载与工具执行仍被硬门拦截，但静态**提示词会枚举全量 skill 目录**（残留暴露；静态 load 工具 schema 内嵌的 `skillId` enum 同样全量）。
