---
title: "技能（Skill）"
description: "四层技能合成、技能市场、自学习闭环"
---

一个 skill 就是一份写好的能力包：一个目录里放一份 `SKILL.md`（说明用途、给 agent 看的指令），可以再带一些参考文档、脚本或样例。写好后丢给 agent，它会在合适的时候自己用。

Harness 让你从两个地方装 skill：

- **技能市场** —— Git 仓库、Nacos、MySQL、classpath、自定义后端
- **工作区** —— `workspace/skills/` 下大家共用；`<userId>/skills/` 下按用户隔离

两类来源同时生效，不需要二选一。除此之外，还可以打开**自学习闭环**：agent 自己起草 skill → 审核 → 后台周期性整理。

一个 skill 目录长这样：

```
code-reviewer/
├── SKILL.md           # 必需，YAML frontmatter（name + description）+ 给 agent 看的指令
├── references/        # 可选，长篇参考资料，agent 按需读取
│   └── style-guide.md
└── scripts/           # 可选，agent 可以通过 shell 调用的脚本
    └── run-checks.sh
```

SKILL.md 写法：

```markdown
---
name: code-reviewer
description: 当用户需要代码评审、风格反馈或 PR 审核时使用。
---

# Code Reviewer

步骤：
1. 读 `references/style-guide.md` 获取项目规范
2. 跑 `scripts/run-checks.sh <目标路径>`，把结果汇总给用户
```

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

这里的 `workspace/<userId>/skills/` 是一个**逻辑路径**，不等于"一定是本机磁盘上的目录"。技能文件的读写统一走 `AbstractFilesystem` 抽象，实际落在哪儿由你配的[文件系统模式](./filesystem)决定，所以"按用户隔离 skill"这个能力跟具体存储后端解耦：

- **本机 + shell** —— 就是宿主磁盘上的 `workspace/alice/skills/...`；
- **共享存储（remote filesystem）** —— `skills/` 前缀被路由到 KV，用户隔离体现为命名空间键 `agents/<agentId>/users/alice/skills/...`，多副本之间一致；管理台改完下一轮推理即可生效；
- **沙箱（sandbox filesystem）** —— 宿主侧的用户目录在沙箱启动时通过 workspace projection 注入容器的 `/workspace`，agent 在沙箱里读到的是同一份。

不管跑在哪种模式，`<userId>/skills/` 都按同样的优先级覆盖共用版。各模式下的隔离键、物理表现以及 `userId` 的作用，详见[文件系统](./filesystem#多用户隔离怎么实现)。

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

## Agent 是怎么读取和执行 skill 的

每轮推理时，agent 会在 system prompt 里看到一个 `<available_skills>` 块，列出当前可见的所有 skill：

```xml
<available_skills>
<skill>
  <name>code-reviewer</name>
  <description>当用户需要代码评审、风格反馈或 PR 审核时使用。</description>
  <skill-id>code-reviewer_workspace-namespaced</skill-id>
  <files-root>/workspace/skills/code-reviewer</files-root>
</skill>
...
</available_skills>
```

每个条目只携带最少的元数据，方便 agent 判断要不要加载详情。`<files-root>`（如果有）是 agent 通过 shell 执行该 skill 脚本时使用的绝对路径，详见下面。

### 读 SKILL.md 和资源文件

加载某个 skill 时 agent 会调用内置工具 `load_skill_through_path`：

- `load_skill_through_path(skillId, path="SKILL.md")` 返回 markdown 正文
- `load_skill_through_path(skillId, path="references/style-guide.md")` 返回该 skill 目录下的任意文件

具体怎么取文件，取决于 skill 来自哪里：

| Skill 来源 | path 解析方式 |
|-----------|--------------|
| 项目全局目录（Layer 1） | 注册时预载到内存 |
| 市场——Git / MySQL / Nacos / classpath（Layer 2） | 由后端预载到内存 |
| `workspace/skills/` 共用（Layer 3） | 注册时预载到内存 |
| `<userId>/skills/` 用户隔离（Layer 4） | SKILL.md 预载；其它文件按需通过 `AbstractFilesystem` 读取（自动遵循 per-user namespace + sandbox 路由） |

agent 感知不到这种差异，`load_skill_through_path` 调起来都一样。底层查找顺序是"内存命中 → 文件系统读取 → 找不到时返回所有真正可用的路径列表"，所以传错 path 也只会拿到清单而不是死路。

### `<files-root>` 和 shell 执行

当一个 skill 自带脚本（例如 `scripts/run-checks.sh`），agent 需要绝对路径才能通过 `execute_shell_command` 调用它。这个绝对路径就是 skill 条目里的 `<files-root>`。它怎么算出来取决于文件系统模式：

| 文件系统模式（是否有 shell） | 工作区 skill 的 `<files-root>` | 市场 skill 的 `<files-root>` |
|---------------------------|----------------------------|---------------------------|
| Sandbox | `/workspace/skills/<name>` | `/workspace/.skills-cache/<source>/<name>` |
| Local-with-shell | `<wsRoot>/skills/<name>` | `<wsRoot>/.skills-cache/<source>/<name>` |
| Local 不带 shell / Composite | （不渲染——没注册 shell 工具） | （不渲染） |

所以 agent 发出来的 shell 命令永远是 `execute_shell_command("python3 <files-root>/scripts/foo.py")`——不用猜路径，不用记每种来源对应哪个前缀。

### 市场 skill 文件实际落在哪儿

市场 skill 的资源最初只在内存里。要让 shell 能跑它们，harness 在每轮推理前把它们物化到 `<wsRoot>/.skills-cache/<source>/<name>/`：

- 文件级 SHA-256 去重，只重写变化过的文件
- 已经下架的 skill（或被从 builder 中移除的整个仓库）留下的孤儿目录，会在同一轮顺手清掉
- Sandbox 模式下，`.skills-cache` 默认包含在 workspace projection roots 里，沙箱启动时（以及内容变化时）会跟 `workspace/skills/` 一起 hydrate 进沙箱

工作区 skill（Layer 3 / Layer 4）不需要 stage——它们本来就在工作区目录里。

如果两个仓库返回了相同的 `getSource()`，第二个会自动加后缀（`<source>_2`、`<source>_3` …），并打 warning log，所以路径和 skill-id 不会撞。

## 在沙箱里运行 skill

[沙箱模式](./filesystem#模式-2沙箱sandboxfilesystemspec-系列)下，文件操作和 shell 都在隔离容器里执行，宿主完全不受影响。这就带来一个问题：skill 的脚本（`scripts/run-checks.sh`、`scripts/foo.py` 之类）写在宿主侧，agent 却要在容器里把它们跑起来。harness 用"物化 → 投影 → 容器内执行"三步把这件事做成透明的，下面拆开讲。

### 哪些 skill 会进沙箱

容器里能跑的 skill 分两类，进沙箱前的落点不同：

| 来源 | 进沙箱前住哪儿 | 沙箱里的路径 |
|------|--------------|-------------|
| 工作区 skill（Layer 3 `workspace/skills/`、Layer 4 `<userId>/skills/`） | 本来就在工作区目录树里 | `/workspace/skills/<name>` |
| 市场 skill（Layer 1 项目全局、Layer 2 Git / MySQL / Nacos / classpath） | 资源最初只在内存里 | `/workspace/.skills-cache/<source>/<name>` |

### 第一步：把市场 skill 物化到宿主

市场 skill 的资源拿到时只是内存里的字节，shell 没法直接执行。每轮推理前，`MarketplaceStager` 把它们写到宿主的 `<wsRoot>/.skills-cache/<source>/<name>/`：

- **文件级 SHA-256 去重** —— 只重写变化过的文件，没变的跳过；
- **孤儿清理** —— 已下架的 skill、或从 builder 里移除的整个仓库，留下的目录在同一轮顺手删掉；
- **恢复执行位** —— 资源在入库时被转成字符串，POSIX 权限丢了，所以 stager 用启发式补回 `+x`：文件开头是 shebang（`#!`），或后缀是已知脚本类型（`.sh`/`.bash`/`.py`/`.rb`/`.pl`/`.js`/`.mjs`），就加上可执行位（按 `chmod +x` 的语义，只给本来有读权限的位加执行位）。纯静态资产（`.json`/`.md`/`.txt`）保持 644。

工作区 skill（Layer 3 / Layer 4）跳过这一步——它们本来就在工作区目录里。

### 第二步：把工作区投影进沙箱（Workspace Projection）

沙箱 `start()` 时，harness 把宿主工作区里的"静态资产"打成 tar，hydrate 进容器的 `/workspace`。默认投影的根（`workspaceProjectionRoots`）正好覆盖 skill 需要的两个目录：

```
AGENTS.md  skills/  subagents/  knowledge/  .skills-cache/
```

所以 `workspace/skills/`（含 `<userId>/skills/`）和上一步物化出来的 `.skills-cache/` 会一起进沙箱。投影对所有被包含的文件按内容算一个整体 SHA-256，跟上次一样就跳过 hydrate，所以反复 `call()` 不会重复传一样的文件；只有内容变了才重新注入。

可调项（在 `DockerFilesystemSpec` / `KubernetesFilesystemSpec` 等沙箱 spec 上）：

| 方法 | 作用 |
|------|------|
| `workspaceProjectionRoots(List)` | 自定义投影哪些根目录（默认含 `skills`、`.skills-cache`） |
| `workspaceProjectionEnabled(false)` | 完全关掉投影——关了之后沙箱里就没有 skill 文件，脚本自然跑不了 |

### 第三步：在容器里执行脚本

`<available_skills>` 块里每个 skill 的 `<files-root>` 在沙箱模式下用**容器内**前缀渲染：

| skill 类型 | `<files-root>` |
|-----------|----------------|
| 工作区 skill | `/workspace/skills/<name>` |
| 市场 skill | `/workspace/.skills-cache/<source>/<name>` |

于是 agent 直接发：

```
execute_shell_command("python3 /workspace/skills/code-reviewer/scripts/run-checks.sh <目标路径>")
```

这条命令在容器里跑，读到的就是投影进来的那份文件。agent 不用知道 skill 来自哪一层，前缀由框架算好。

> 如果沙箱后端把工作区挂在非默认位置（比如 AgentRun 是 `/home/agentscope/workspace`），`<files-root>` 前缀会跟着换，agent 拿到的依然是正确的绝对路径。

### 跨调用保留脚本副作用

脚本如果装了依赖、生成了产物（`npm install`、`pip install`、编译输出），想下次 `call()` 还在，就给沙箱配[快照](./filesystem#快照策略)（`snapshotSpec(...)`）。快照保存整个 `/workspace`，下次同一 scope key 的调用先恢复快照、再叠加投影，所以装过的东西不用重装。

### 注意：读 SKILL.md 不需要沙箱

容易混淆的一点：**读** skill（`load_skill_through_path` 取 `SKILL.md` / `references/`）走的是内存或宿主文件系统，跟沙箱无关；只有**用 shell 跑脚本**才需要文件真的进到容器里。所以即便关了投影、或某个 skill 根本没带脚本，agent 依然能正常读它的说明和参考资料。

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
