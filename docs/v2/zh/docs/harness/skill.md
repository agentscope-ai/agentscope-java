---
title: "技能（Skill）"
description: "四层技能合成、技能市场、自学习闭环"
---

一个 skill 就是一份写好的能力包：一个目录里放一份 `SKILL.md`（说明用途、给 agent 看的指令），可以再带一些参考文档、脚本或样例。写好后丢给 agent，它会在合适的时候自己用。

Harness 让你从两个地方装 skill：

- **技能市场** —— Git 仓库、Nacos、MySQL、classpath、自定义后端
- **工作区** —— `workspace/skills/` 下大家共用；`<userId>/skills/` 下按用户隔离

两类来源同时生效，不需要二选一。除此之外，还可以打开**自学习闭环**：agent 自己起草 skill → 审核 → 后台周期性整理。

> 关于 skill 自身的结构、`SKILL.md` 写法、资源加载、tool 绑定、代码执行这些通用概念，参见 core 的 skill 文档。本文只讲在 Harness 这一层怎么用。

## 一个例子

把团队的 skill 仓库接进来，agent 立刻就能用：

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("assistant")
        .model(model)
        .workspace(workspace)
        .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
        .build();
```

后续推理时 agent 看得到这个仓库里的 skill，需要哪个就调 `load_skill_through_path` 加载详情。

## 接技能市场

`skillRepository(...)` 是统一入口，传什么后端都可以。

### Git

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-git-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
.skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
```

默认每次读取做轻量化的远端检查，HEAD 变了才 pull。仓库根下如果有 `skills/` 子目录会优先读它，否则读根目录。想自己控制同步节奏：`new GitSkillRepository(url, false)`，然后手动 `repo.sync()`。

### Nacos

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-skill</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
NacosSkillRepository market = new NacosSkillRepository(aiService, "namespace");
HarnessAgent.builder()
        .skillRepository(market)
        .build();
```

适合需要在线下发、变更订阅的场景。`market` 是 `AutoCloseable`，应用退出时关掉以释放订阅。

### MySQL

```java
MysqlSkillRepository registry = MysqlSkillRepository.builder(dataSource)
        .databaseName("agentscope")
        .skillsTableName("skills")
        .createIfNotExist(true)
        .writeable(true)
        .build();

HarnessAgent.builder()
        .skillRepository(registry)
        .build();
```

平台侧统一管理 skill 时常用。`writeable(true)` 后可以从 agent 侧写回；只读分发就传 `false`。

### Classpath

把 skill 跟 JAR 一起发：

```
src/main/resources/skills/
└── code-reviewer/
    └── SKILL.md
```

```java
.skillRepository(new ClasspathSkillRepository("skills"))
```

兼容标准 JAR 和 Spring Boot Fat JAR。

### 接多个

`skillRepository(...)` 可以重复调用；后注册的优先级更高：

```java
HarnessAgent.builder()
        .skillRepository(communityMarket)
        .skillRepository(internalRegistry)
        .skillRepository(teamGitRepo)
        .build();
```

## 把 skill 放到工作区

工作区里的 skill 不用任何注册，把目录放好就生效。

### 大家共用

```
workspace/skills/
└── code-reviewer/
    ├── SKILL.md
    ├── references/
    │   └── style-guide.md
    └── scripts/
        └── run-checks.sh
```

适合放项目特有的规范、内部约定。

### 单个用户用

如果想给某个用户单独装一个 skill，或给他覆盖一个共用版本，放到他 `userId` 命名的子目录下：

```
workspace/
├── skills/code-reviewer/SKILL.md   ← 共用版
└── alice/
    └── skills/
        └── code-reviewer/
            └── SKILL.md            ← 只对 alice 生效，覆盖共用版
```

前提是调用时 `RuntimeContext.userId` 传了"alice"。

## 同名冲突谁说了算

四个来源都可能给出同名 skill。优先级从低到高：

| 优先级 | 来源 | 怎么配 |
|--------|------|--------|
| 1（最低） | 项目全局目录 | `projectGlobalSkillsDir(Path)`，如 `~/.agentscope/skills/` |
| 2 | 市场 | `skillRepository(...)`，后注册的覆盖先注册的 |
| 3 | 工作区共用 | `workspace/skills/` |
| 4（最高） | 用户隔离 | `<userId>/skills/` |

下层独有的 skill 仍然保留，只在重名时被上层覆盖。

举例：团队 Git 上有通用 `code-reviewer`，项目 `workspace/skills/code-reviewer/` 写了项目专属版本，那 agent 看到的就是项目版；Alice 又在自己目录覆盖了一份，那 Alice 调用时拿到的是她自己的版本，其他用户还是项目版。

## 常用 Builder 选项

| 方法 | 说明 |
|------|------|
| `skillRepository(repo)` | 追加一个市场；可重复调用 |
| `skillRepositories(list)` | 一次性替换所有市场 |
| `projectGlobalSkillsDir(path)` | 启用项目全局目录；目录不存在则跳过 |
| `disableDynamicSkills()` | 关掉"每次推理前重新合并"，改成 build 时合并一次 |

子 agent 自动继承父的市场列表和项目全局目录，不用重复配。

什么时候用 `disableDynamicSkills()`：单次任务，跑完就退出；或市场后端慢、不想每轮拉。平时不用动这个开关。

## 自学习闭环（可选）

Harness 拼了一套"让 agent 自己起草 / 沉淀 / 整理 skill"的闭环。各阶段独立可开，按需启用：

### 第一步：让 agent 能自己写 skill

```java
HarnessAgent.builder()
    ...
    .enableSkillManageTool(SkillManageConfig.defaults())
    .build();
```

启用后 agent 获得两个工具：

- `propose_skill` —— 把新 skill 写成草稿到 `skills/_drafts/<name>/`，等审核
- `skill_manage` —— 编辑已有 skill（创建 / 修改 / 添加附属文件 / 删除）

如果不想要"草稿 → 审核"两步流程，让 agent 写完直接生效：`.enableSkillManageTool(true)`（`autoPromote=true`）。生产场景不建议。

同时 agent 每次调 `load_skill_through_path` / `read_skill` 时，框架自动记一笔使用计数，存到 `skills/.usage.json`——为后面的清理、灰度发布提供数据。

### 第二步：加审核闸门 + 可见性过滤

```java
.enableSkillPromotionGate(
    new LocalApprovalGate(LocalApprovalGate.defaultPrompter()),    // 谁来批
    new CompositeFilter(List.of(                                    // 怎么暴露
        new EnvironmentFilter("prod", skillUsageStore),
        new CanaryFilter(0.10, skillUsageStore)
    )))
.environment("prod")
```

- **闸门** —— 草稿要变正式 skill 必须经过它。内置三种：直接拒绝（默认）、本地人工确认（stdin 等）、推消息后等。
- **可见性过滤** —— 决定 agent 在推理时能看到哪些"agent 自己创建"的 skill。可按部署环境、灰度比例、白名单组合。

### 第三步：后台周期性整理

```java
.enableSkillCurator(SkillCuratorConfig.builder()
    .intervalHours(7 * 24)        // 一周跑一次
    .minIdleHours(2)              // 距上次 call 至少过 2 小时才允许跑
    .staleAfterDays(30)
    .archiveAfterDays(90)
    .build())
```

后台会按节流闸门跑：超过 30 天没用的 skill 标为 stale，超过 90 天直接归档到 `skills/.archive/`。可选叠加一个 LLM "伞合并"扫描（默认只 dry-run，输出报告，不实际改）。

### 程序化触发

业务层可以用：

```java
List<SkillAuditLog.Entry> entries = agent.queryAudit(LocalDate.now(), e -> true);

agent.runCuratorOnce()                                       // 立刻跑一次整理（绕过节流）
     .subscribe(report -> System.out.println(report));

agent.promoteSkill("notes-taker", "alice")                   // 手动晋升一份草稿
     .subscribe(result -> System.out.println(result));
```

## 一些建议

**`description` 决定 agent 用不用这个 skill。** agent 一开始只看得到 name 和 description，觉得相关才会 load 详情。写"数据分析工具"远不如写"当用户要算统计、出报表、做趋势图时使用"有效。

**`SKILL.md` 保持精简。** 控制在 2k tokens 上下，详细参考资料放 `references/`，脚本放 `scripts/`。agent 需要时会自己读。

**通用能力放市场，项目特有的写工作区。** 代码评审、表格分析这种放团队 Git 上集中维护；公司内部 RPC 规范、本项目的命名约定写到 `workspace/skills/` 里跟着代码版本走。

**用户目录用来"覆盖+补充"，不要拿来当主存放。** 关键能力请放在所有用户都能看到的层。

**自学习按顺序启用**：没人写新 skill 之前开 curator 没意义。先开 `enableSkillManageTool`，再加 promotion gate 让审核流程介入，最后用 curator 处理"老的不再用"。

## 相关文档

- [工作区](./workspace) — `skills/` 目录的整体布局
- [文件系统](./filesystem) — 多租户隔离与按用户切目录
- [架构](./architecture) — skill 集合是怎么每轮重新合成的
