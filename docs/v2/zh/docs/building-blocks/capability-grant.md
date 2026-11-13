---
title: Capability Grant (能力裁剪)
description: 按用户/请求的实际权限，把 agent 无权的能力彻底切除
en_link: /v2/en/docs/building-blocks/capability-grant
---

## 概述

一句话定位：**SkillFilter 决定这个 agent 设计上有什么；grant 决定这个用户被授权用什么。有效能力 = 两者之交。**

为什么需要 grant：企业部署里，同一个 agent 面向大量权限不同的用户（多租户、多角色）。grant 层按用户的实际权限，把无权的能力**彻底切除**——被切除的能力对模型**不存在**：不进 tool schema、不可枚举、不可被 `reset_equipped_tools` 激活、不可被 `load_skill_through_path` 加载、不可执行。Harness 的技能链路（`HarnessSkillMiddleware` 的提示词与技能目录、harness 版 `load_skill_through_path`）同样受授权面约束。暂存缓存同样收口：沙箱 workspace projection 只投影本次已获授技能的 `.skills-cache` 子树——早前更宽授权暂存的残留文件仍在磁盘（由宽限期 GC 负责清理），但不再进入本次调用的沙箱；已进入复用或快照恢复工作区的残留文件，也会在授权收窄后的下一次调用中被撤销。

它与其他既有层正交，各层只回答一个问题：

| 层                              | 关系                                                                               |
| ------------------------------- | ---------------------------------------------------------------------------------- |
| **grant（本页）**               | 设置了白名单则只放行白名单内能力；未配置 resolver 或 resolver 返回 null 时为 ALLOW_ALL（不裁剪） |
| SkillFilter / 动态可见性（`skillFilter(...)`、RuntimeContext overlay、`filterVisible`） | 配置意图层：决定 agent 设计上展示哪些技能；运行时 overlay 可放宽也可收紧（灰度、临时启停是合法用途）。grant 排在其后执行，最终可见技能 = SkillFilter ∩ grant——overlay 抬不高授权面 |
| activation（`activatedGroups`） | 会话级软开关，决定已存在的能力「当前是否激活」；与 grant 独立叠加                  |
| Permission system               | 执行期审批（ALLOW / DENY / ASK）；grant 是事前切除，两者互补                       |
| 外部工具（`ToolRequestConfig`） | 由调用方经请求传入、外部工具在客户端执行无服务端安全问题、调用方自控，不在裁剪范围 |

一句话组合链：一个能力要真正执行，须依次通过——配置意图（SkillFilter）→ 用户授权（grant）→ 当前激活（activation）→ 本次 Permission 审批。各层只回答一个问题，交集生效，不存在同一问题两处配置。

## 快速接入

只需两件事：实现 `CapabilityResolver`，注册到 agent。

```java
CapabilityResolver resolver = (ctx, agent) -> {
    String userId = ctx.getUserId();
    // 粒度自选：按 (userId, agentId) 做 per-agent 差异，
    // 或忽略 agentId 按用户出一份数据（框架一视同仁）
    return new CapabilitySurface(
            authzService.allowedSkills(userId),      // 允许的 skill 名；null = 未设置
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

运行规则：

- **何时解析**：每次 `call()` 入口、服务端、恰好一次；resolver 返回 `null` 等同不裁剪。
- **生命周期**：解析结果随 `RuntimeContext` 走完整条调用链，请求内冻结、不持久化。
- **子代理**：未自配 resolver 的子 agent 直接复用父授权面，零重算。
- **Harness**：`HarnessAgent.builder().capabilityResolver(...)` 同样支持；解析发生在沙箱 acquire 与技能暂存之前，暂存清单、提示词目录与各道门看到同一份授权面。
- **缓存**：resolver 每次调用入口都会执行；后端是 DB / RPC 等高成本查询时，可按业务需要自行加缓存（如按 `userId` + TTL），并接受相应的权限变更生效延迟，框架不代管。
- **超集安全**：surface 内容可超出 agent 的实际能力范围——本 agent 没有的名字不会被任何读点命中。

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

`null`（未设置）与 `Set.of()`（明确为空）是两回事：前者是「未参与」，后者是「显式声明该维度零授权」。surface 一旦不是三维全 `null`，两者在判定上效果一致（都不放行该维度）；访问器（`allowedSkills()` 等）仍保留这一区分。

## 授权判定规则

只看白名单，命中**任一已设置维度**即放行：

- 授权一个 **skill** 即隐含授权其手册、脚本与关联工具（技能组，约定组名 `skillId + "_skill_tools"`），**并自动放行经 `activateOnSkill` 绑定到该 skill 的自定义工具组**（call 入口把绑定组并入授权面）；
- 授权一个 **ToolGroup** 即隐含授权组内全部工具；
- 逐名授权一个**工具**即授权该工具本身（不问分组，也不放大到组内其他工具）。

**技能用名字授权，不用 skillId。** 名字是业务身份、行业标准唯一定位：一份名单条目覆盖该名字在目录中的全部副本（目录按标准语义合并，同名只保留高优先仓库那份）。skillId（`name + "_" + source`）仍是框架内部的注册与寻址键：加载入参用 skillId；标准命名规则禁止名字含下划线，因此 skillId 的第一个下划线必是分隔符、拆分无歧义。

只提供白名单，不提供 deny（反向禁用）；默认拒绝——不在授权清单内的能力即「不存在」。

## 系统能力标记（`system`）

哪些能力不参与裁剪？**保证 agent 正常运行的基本核心能力**（框架元工具即属此类）。该标记不是业务形态的裁剪控制手段，不要拿它充当业务能力的开关。

框架自身的元工具（`reset_equipped_tools`、`load_skill_through_path`）标记为 system，在任何授权面下（包括 DENY_ALL）保持可用。装配方也可将自己的工具/组/skill 标记 `system = true`（`@Tool(system = true)` 或对应 Builder）——等同于装配方显式声明该能力免裁剪，注册时会输出 INFO 留痕。

**标记只作用于被标记的对象，不传染**：

- 工具 `system`：豁免该工具自身的可见与执行；
- 组 `system`：仅豁免该组自身的枚举与激活，组内成员工具仍受授权面控制（成员需要豁免时逐一标记）；
- 技能 `system`：豁免其提示词可见与加载。

> **信任边界**：`system` 标记对装配方（agent 开发者）开放且可信。该授权层的威胁模型是「终端用户 vs agent 能力面」，不设防装配方自身。

## fail-closed 行为

原则：**授权体系出问题时，宁可中止，不可放开。**

- resolver 抛异常 → **中止本次调用**：异常传播给调用方并记录 ERROR（授权体系故障 ≠「无授权」）；需要降级继续服务，请在 resolver 内部捕获并返回 `DENY_ALL`。
- 直连执行 → `Toolkit.callTool` / `callTools` 等不经 agent `call()` 入口的路径不经过授权解析，无 surface 即视为不裁剪；直连调用 `DynamicSkillMiddleware` 同理（rc 无 surface = 可信装配代码，技能照常可见）。需要按权限裁剪时，在 `RuntimeContext` 中显式放入即可（`rc.put(CapabilitySurface.class, surface)`）。

## 静态 skillBox 路径限制

`skillBox(...)` 已随 hook 系统于 2.0.0 标记为过时，**新代码不要使用**——skill 接入一律使用 `skillRepositories(...)` 动态路径（默认）。

授权兼容：`skillBox(...)` 与 capability resolver **可以组合**。静态提示词在注入前按当次授权面裁剪——SkillHook 从调用上下文读取授权面，经同一渲染管线过滤；未启用授权（或授权面为 ALLOW_ALL）时提示词与历史版本逐字节一致，零技能获授时整块不注入；子代理继承父级授权面时同样生效。

仍被拒绝的组合只有一种：`skillRepositories(...)` + `dynamicSkillsEnabled(false)` + resolver——该形态没有任何 per-call 技能提示词可供裁剪，配置等于无效授权，`build()` 直接抛 `IllegalStateException`（授权配置绝不允许静默退化为不裁剪的 agent）。

持久化与降级：授权状态（surface、托管标记、撤销范围）从不写入持久化状态；撤销清单（`projectedWorkspacePaths`）仅在启用授权后写入——未启用的部署持久化 JSON 与旧版逐字段一致，可安全回滚。已启用授权的部署回滚到旧版时，旧版无法识别该字段，会放弃恢复的沙箱状态并重建工作区（WARN 提示，不影响调用）。多实例部署请配置真实的执行互斥（`DistributedStore`）——默认的 Noop 互斥下，同作用域并发调用的投影状态为最后写入者胜出。
