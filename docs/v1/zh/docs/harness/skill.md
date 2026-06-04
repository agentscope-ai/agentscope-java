# 技能（Skill）

一个 skill 就是一份写好的能力包：一个目录里放一份 `SKILL.md`（说明用途、给 agent 看的指令），可以再带一些参考文档、脚本或样例。写好后丢给 agent，它会在合适的时候自己用。

harness 让你从两个地方装 skill：

- **接 skill 市场**：Git 仓库、Nacos、MySQL、classpath、或者自己写的后端
- **放在工作区**：项目里 `workspace/skills/` 下的就所有人共用；放在 `<userId>/skills/` 下的只有那个用户看得到

两类来源同时生效，不需要二选一。

> 关于 skill 自身的结构、`SKILL.md` 写法、资源加载、tool 绑定、代码执行这些通用概念，见 [Agent Skill](../task/agent-skill.md)。本文只讲 harness 这一层的用法。

---

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

没了。后续推理时，agent 看得到这个仓库里的 skill，需要用哪个就调 `load_skill_through_path` 加载详情。

---

## 接 skill 市场

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

默认每次读取都会做轻量化的远端检查，HEAD 变了才 pull。仓库里如果有 `skills/` 子目录会优先读它，否则读根目录。想自己控制同步节奏：`new GitSkillRepository(url, false)`，然后手动 `repo.sync()`。

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

适合需要在线下发、变更订阅的场景。`NacosSkillRepository` 实现了 `AutoCloseable`，应用退出时记得关掉以释放订阅。

### MySQL

```java
MysqlSkillRepository registry = MysqlSkillRepository.builder(dataSource)
        .databaseName("agentscope")
        .skillsTableName("skills")
        .resourcesTableName("skill_resources")
        .createIfNotExist(true)
        .writeable(true)
        .build();

HarnessAgent.builder()
        .skillRepository(registry)
        .build();
```

平台侧统一管理 skill 时常用。`writeable(true)` 之后可以从 agent 侧写回；只读分发就传 `false`。

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

`skillRepository(...)` 可以重复调用：

```java
HarnessAgent.builder()
        .skillRepository(communityMarket)
        .skillRepository(internalRegistry)
        .skillRepository(teamGitRepo)
        .build();
```

或者一次性传一组：

```java
.skillRepositories(List.of(communityMarket, internalRegistry, teamGitRepo))
```

> 注意 `skillRepositories(list)` 会把之前用 `skillRepository(...)` 加的全部清掉，再用传入的列表替换。

---

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

这个 agent 接到的任何请求都能看到 `code-reviewer`。适合放项目特有的规范、内部约定这类东西。

### 单个用户用

如果想给某个用户单独装一个 skill，或者给他覆盖一个共用版本，放在以他 userId 命名的子目录下：

```
workspace/
├── skills/code-reviewer/SKILL.md   ← 共用版
└── alice/
    └── skills/
        └── code-reviewer/
            └── SKILL.md            ← 只对 alice 生效，且覆盖共用版
```

- Alice 调用时：拿到自己的覆盖版
- Bob 调用时：还是用共用版，看不到 Alice 那份
- Alice 在自己目录下放了一个 `notes-taker`：只有她能用

> 这一层的目录前缀是 `RuntimeContext.userId` 决定的，所以前提是调用方把 `userId` 传进了 `RuntimeContext`。具体怎么按用户切目录见 [文件系统 · NamespaceFactory](./filesystem.md#namespacefactory-与多租户)。

---

## 同名 skill 谁说了算

四个来源都可能给出同名 skill。优先级从低到高：

| 优先级 | 来源 | 怎么配 |
|--------|------|--------|
| 1（最低） | 项目全局目录 | `projectGlobalSkillsDir(Path)`，比如 `~/.agentscope/skills/` |
| 2 | 市场 | `skillRepository(...)`，后注册的覆盖先注册的 |
| 3 | 工作区共用 | `workspace/skills/` |
| 4（最高） | 用户隔离 | `<userId>/skills/` |

下层独有的 skill 仍然保留，只在重名时被上层覆盖。

举例：团队 Git 上有一份通用 `code-reviewer`，项目里 `workspace/skills/code-reviewer/` 写了项目专属版本，那 agent 看到的就是项目版；Alice 又在自己目录下覆盖了一份，那 Alice 调用时拿到的是她自己的版本，其他用户还是项目版。

---

## Builder API

| 方法 | 说明 |
|------|------|
| `skillRepository(repo)` | 追加一个市场。可重复调用；传 `null` 忽略 |
| `skillRepositories(list)` | 一次性替换所有市场（会清掉之前 `skillRepository(...)` 加的） |
| `projectGlobalSkillsDir(path)` | 启用项目全局目录。目录不存在则跳过 |
| `disableDynamicSkills()` | 关掉「每次调用前重新合并」的行为，改成 build 时合并一次，运行期不再变化 |

子 agent 会自动继承父 agent 的市场列表和项目全局目录，不用再配一遍。

什么时候用 `disableDynamicSkills()`：

- 单次任务、跑完就退出，不需要 skill 在运行中热更新
- 接的市场后端比较慢，不想每轮推理都拉一次

平时不用动这个开关。

---

## 一些建议

**`description` 决定 agent 会不会用这个 skill。** agent 一开始只看得到每个 skill 的 name 和 description，看到觉得相关才会 load 详情。写「这是一个数据分析工具」远不如写「当用户要算统计、出报表、做趋势图时使用」有效。

**`SKILL.md` 保持精简。** 控制在 2k tokens 上下，详细参考资料放 `references/`，脚本放 `scripts/`。agent 需要时会自己读。

**通用能力放市场，项目特有的写工作区。** 代码评审、表格分析这种放团队 Git 上集中维护；公司内部 RPC 规范、本项目的命名约定写到 `workspace/skills/` 里跟着代码走。

**用户目录用来「覆盖+补充」，不要拿来当主存放。** 关键能力请放在所有用户都能看到的层。

**多市场同名时**约定一个前缀（`acme/code-review` vs `community/code-review`），避免不知道谁覆盖了谁。

---

## 相关文档

- [Agent Skill](../task/agent-skill.md) — `SKILL.md` 字段、资源结构、tool 绑定、代码执行
- [工作区](./workspace.md) — 工作区目录的整体布局
- [文件系统](./filesystem.md) — 多租户与按用户隔离怎么配
- [工具](./tool.md) — `load_skill_through_path` 等内置工具
