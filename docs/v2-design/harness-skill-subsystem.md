# HarnessAgent Skill Subsystem 重构方案

## 0. TL;DR

`io.agentscope.core.skill.*` 整包保留不动（继续服务于 `ReActAgent`），HarnessAgent 侧自建一套 skill 运行时，只复用 core 的两件东西：

1. `core.skill.repository.AgentSkillRepository` —— SPI 类型，作为 `HarnessAgent.Builder.skillRepository(...)` 的入参，与 `ReActAgent.Builder` 完全一致
2. `core.skill.AgentSkill` / `SkillUtil` / `MarkdownSkillParser` —— 数据载体与 markdown 解析工具

其余 core 件（`SkillBox` / `SkillToolFactory` / `SkillRegistry` / `AgentSkillPromptProvider` / `SkillHook` / `DynamicSkillMiddleware`）在 HarnessAgent 路径上完全不再被引用。harness 侧的 `FilesystemBackedSkillRepository` 与 `WritableFilesystemSkillRepository` 删除，由新的 `WorkspaceSkillRepository` 替代；`HarnessSkillMiddleware` 重写，不再继承 `DynamicSkillMiddleware`。

用户感知层（Builder 方法名、LLM 看到的工具名 `load_skill_through_path`、system prompt 中的 `<available_skills>` XML 形状）保持与 `ReActAgent` 完全一致。

---

## 1. 背景与动机

### 1.1 Repository surface 命名重叠

现有 5 个 `AgentSkillRepository` 实现里，"FileSystem" 这个词被用在三个语义不同的位置上：

| 实现 | 位置 | 后端 | 多租户 | 读/写 | 资源加载策略 |
|------|------|------|--------|-------|-------------|
| `FileSystemSkillRepository` | core | host `java.nio.Path` | ❌ | 构造参数 `writeable` | 预载全部 resources 到内存 |
| `ClasspathSkillRepository` | core | classpath | ❌ | 只读 | 预载全部 resources 到内存 |
| `FilesystemBackedSkillRepository` | harness | `AbstractFilesystem` | ✅ | 只读 | **仅 SKILL.md**，resources 永远 null |
| `WritableFilesystemSkillRepository` | harness（extends 上一个） | 同上 | ✅ | 读写 | 同上 |
| 第三方（Git / MySQL / Nacos） | extensions | 各自后端 | 视实现 | 视实现 | 视实现 |

`FileSystemSkillRepository` 与 `FilesystemBackedSkillRepository` 字面只差几个字符，语义却完全不同（host 单租户 vs. 抽象文件系统多租户）。`WritableFilesystemSkillRepository` 把 writability 拆成独立类，跟前者用构造参数表达 writability 的方式不一致。用户面对 builder API 不知道该挑哪个。

### 1.2 Layer 4 resources 残废

`FilesystemBackedSkillRepository.getAllSkills()` 走 `AbstractFilesystem` 读 SKILL.md 文本，把 `AgentSkill` 的 `resources` 字段构造为 `null`：

```java
AgentSkill skill = SkillUtil.createFrom(rr.fileData().content(), null, source);
```

`SkillToolFactory.loadSkillResourceImpl` 处理 `load_skill_through_path(path)` 时，从 `AgentSkill.getResources()` 拿 `Map<String,String>` 查找，namespaced 来源的 skill 永远查不到任何 `references/*.md` 或 `scripts/*.py`，只能返回 "Resource not found"。LLM 看到的 SKILL.md 里若引用了 `references/guide.md`，工具调用必然失败。

### 1.3 SkillBox upload 与 sandbox projection 撞车

`SkillBox.uploadSkillFiles()` 在 `DynamicSkillMiddleware.reloadSkills` 末尾被调用，把内存里的 resources 写到 host 的 `java.io.tmpdir/agentscope-code-execution-*/skills/<skill-id>/`。但：

- HarnessAgent 在 sandbox 模式下，shell 命令跑在 sandbox 内部，根本看不到 host 的临时目录
- 沙箱内已经由 `WorkspaceProjectionApplier` 在 `start()` 时把 `<workspace>/skills/` 整棵子树 hydrate 进 `/workspace/skills/`，内容哈希去重、跨 call 复用（见 [project-workspace-projection]）
- `AgentSkillPromptProvider.DEFAULT_CODE_EXECUTION_INSTRUCTION` 模板里的 `%s` 占位符（`uploadDir` 绝对路径）在 HarnessAgent 链路上没人填，那段 "Use execute_shell_command…" 提示根本不会出现

upload 这条路径与 projection 完全重叠且互斥（host 路径 vs. sandbox 路径）。继续保留只是徒增配置复杂度。

### 1.4 范围声明

- **core 整包不动**：`io.agentscope.core.skill.*` 全部保留，`ReActAgent` 用户路径零变更
- **harness 自由破坏**：旧的 `FilesystemBacked*` 两个类删除；`HarnessSkillMiddleware` 重写
- **复用 core 的 SPI 与数据类型**：harness Builder API 入参类型保持 `AgentSkillRepository`，第三方扩展（Git / MySQL / Nacos）零修改可插
- **harness 不再 import** `core.skill.SkillBox / SkillToolFactory / SkillRegistry / AgentSkillPromptProvider / SkillHook / DynamicSkillMiddleware / RegisteredSkill / SkillFilter`（runtime 用到 `SkillFilter` 时直接复用 core 的，不再二次过滤）

---

## 2. 复用 core 的部分

### 2.1 `AgentSkillRepository`（SPI）

继续作为 HarnessAgent.Builder 上 `skillRepository(...)` / `skillRepositories(...)` 的入参类型。理由：

- 与 `ReActAgent.Builder` 形状完全一致，用户从 ReActAgent 迁到 HarnessAgent 不需要改任何 import
- 第三方扩展（`agentscope-extensions-skill-git-repository`、`agentscope-extensions-skill-mysql-repository`、`agentscope-extensions-nacos-skill`）现成实现的就是这个接口，零适配可用
- 接口语义本身（`getAllSkills()` / `getSkill(name)` / `save()` / `delete()` / `skillExists()` / `getRepositoryInfo()` / `getSource()` / `isWriteable()`）足够 harness 运行时使用

### 2.2 `AgentSkill`（数据载体）

repository 返回的是 `List<AgentSkill>`，harness 运行时直接消费这个类型。`AgentSkill` 字段够用：

- `name` / `description` / `metadata` —— 渲染 `<available_skills>` 时需要
- `skillContent` —— `load_skill_through_path(path="SKILL.md")` 直接返回
- `resources: Map<String,String>` —— 来自 host 内存预载（Layer 1/3、marketplace）的 skill 在这里命中
- `source` / `skillId` —— 渲染 `<skill-id>`、active 状态映射

> Layer 4 lazy resource 的问题不在 `AgentSkill` 本身，而是 `FilesystemBackedSkillRepository` 没有给到 lazy 兜底通道。harness 通过新增 marker 接口（§ 3.2）解决。

### 2.3 `SkillUtil` / `MarkdownSkillParser`

SKILL.md（YAML frontmatter + body）解析继续走 core 提供的 `SkillUtil.createFrom(skillMd, resources, source)`。harness 不重复实现一份 markdown 解析。

### 2.4 副作用：`@SuppressWarnings("deprecation")`

上述三类 core 件在 v2.0.0 javadoc 上都标了 `@Deprecated`。harness 在 import 和方法签名上需要加 `@SuppressWarnings("deprecation")`。这是已知代价，可以接受 —— 等 ReActAgent 用户全部迁走之后这些 core 件才会真的物理删除，那时 harness 同步切到自有数据类型即可，**本次重构不做这一步**。

---

## 3. harness 新增的内部 SPI 与类型

### 3.1 `SkillResources`（资源懒访问）

```java
package io.agentscope.harness.agent.skill;

import java.util.List;
import java.util.Optional;

public interface SkillResources {
    /** 文本内容,UTF-8 解码 */
    Optional<String> read(String relativePath);

    /** 二进制内容（图片、tar 之类） */
    Optional<byte[]> readBinary(String relativePath);

    /** 可用资源相对路径列表。用于 SkillLoadTool 在找不到时给 LLM 友好枚举 */
    List<String> list();

    /** 空实现 */
    static SkillResources empty() { ... }
}
```

- 这是 harness 内部的 SPI，不出现在用户 Builder API 上
- 设计目标：把"有什么资源"和"怎么取内容"解耦，让 `WorkspaceSkillRepository` 可以走 `AbstractFilesystem.read(ctx, ...)` 按需读取，不必预载

### 3.2 `LazyResourceCapable`（repository marker）

```java
package io.agentscope.harness.agent.skill;

import io.agentscope.core.agent.RuntimeContext;

public interface LazyResourceCapable {
    /**
     * 提供某个 skill 的资源懒访问通道。在 SkillLoadTool 内存 map 查询未命中时被调用。
     *
     * @param skillName  skill 的 name(非 skillId)
     * @param ctx        当前 RuntimeContext,实现可据此做 per-user namespace
     * @return           资源访问器;若该 skill 不属于本仓库,返回 empty
     */
    SkillResources resourcesFor(String skillName, RuntimeContext ctx);
}
```

- 这是 harness 内部 marker，repository 实现可选
- harness 运行时通过 `instanceof` 检测：实现了就拿到兜底通道，没实现就只有 `AgentSkill.resources` 内存 map
- core 的 `FileSystemSkillRepository` / `ClasspathSkillRepository` 以及所有第三方扩展都不需要实现这个接口 —— 它们的 resources 在 `getAllSkills()` 时已经预载到内存 map 里，正常工作
- 只有新的 `WorkspaceSkillRepository` 会实现它（也只有它的资源是不预载的）

### 3.3 `HarnessSkillEntry`（运行时内部包装）

```java
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.SkillResources;

record HarnessSkillEntry(AgentSkill skill, SkillResources lazyResources) {
    // skill 必非 null;lazyResources 可为 null(没有兜底通道)
}
```

`SkillCatalog` 持有 `Map<String, HarnessSkillEntry>`（key 是 skillId）。entry 在每次 onSystemPrompt 重建时由 `SkillRuntime` 装配：从 repository 拿到 `List<AgentSkill>`，若 repository `instanceof LazyResourceCapable` 则为每个 skill 调一次 `resourcesFor(name, ctx)`，组装成 entry。

### 3.4 为什么不新起 `Skill` record

讨论过但放弃。新起 `Skill` record 的优势是清爽（不带 deprecated import、resources 字段语义干净），代价是：

- 用户在 ReActAgent 与 HarnessAgent 之间切换时面对两个名字几乎一样的类型，违反 [§ 1.4 用户感知一致]
- 第三方仓库返回的是 `AgentSkill`，harness 内部还是要做一次 `AgentSkill → Skill` 转换，本质上等价于现在的 `HarnessSkillEntry` 包装
- 转换中无法承载 lazy resources（因为是从 `AgentSkill` 出发的），还得引入 `LazyResourceCapable`，复杂度并没有降低

结论：保持 `AgentSkill` 不动，用包装类型在运行时层加 lazy 能力。

---

## 4. Repository 实现

### 4.1 命名与组织

harness 侧**只新增一个** `WorkspaceSkillRepository`，本地目录与 classpath 场景直接复用 core 现有实现，避免类型重复：

| 用途 | 用哪个 | 位置 | 后端 | 多租户 | 读写 |
|------|--------|------|------|--------|------|
| 本地目录（host 单租户） | `FileSystemSkillRepository`（既有） | `io.agentscope.core.skill.repository` | host `Path` | ❌ | 构造参数 `writeable` |
| classpath（随 JAR 发布） | `ClasspathSkillRepository`（既有） | `io.agentscope.core.skill.repository` | classpath | ❌ | 只读 |
| 工作区 / sandbox / 多租户 | `WorkspaceSkillRepository`（**新增**） | `io.agentscope.harness.agent.skill` | `AbstractFilesystem` | ✅ | 构造参数 `writable` |

> 为什么不在 harness 重做一份 `LocalDirectorySkillRepository` / `ClasspathSkillRepository`？两个名字几乎一样、位于不同包、行为完全相同的类，对用户是更大的认知负担（"我该 import 哪个？"）。core 的两个老类虽然标了 `@Deprecated`（package-level 长期计划下线），但在本次重构范围内继续提供给 HarnessAgent 用户使用是最合理的选择。warning 留着，让 deprecation 系统该提示就提示，不为消 warning 搞重复实现。

### 4.2 `WorkspaceSkillRepository`（核心新增）

```java
public final class WorkspaceSkillRepository
        implements AgentSkillRepository, LazyResourceCapable {

    public WorkspaceSkillRepository(
            AbstractFilesystem filesystem,
            String skillsRelativeDir,                 // 默认 "skills"
            Supplier<RuntimeContext> contextSupplier,
            String source,                            // 默认 "workspace"
            boolean writable) { ... }

    @Override public List<AgentSkill> getAllSkills() {
        RuntimeContext ctx = contextSupplier.get();
        GlobResult glob = filesystem.glob(ctx, "SKILL.md", skillsRelativeDir);
        List<AgentSkill> out = new ArrayList<>();
        for (FileInfo fi : glob.matches()) {
            if (hasMetadataAncestor(fi.path(), skillsRelativeDir)) continue;
            ReadResult rr = filesystem.read(ctx, fi.path(), 0, 0);
            // resources 留 null,真正访问走 LazyResourceCapable.resourcesFor(...)
            out.add(SkillUtil.createFrom(rr.fileData().content(), null, source));
        }
        return out;
    }

    @Override public SkillResources resourcesFor(String skillName, RuntimeContext ctx) {
        String skillDir = skillsRelativeDir + "/" + skillName;
        return new FilesystemSkillResources(filesystem, skillDir, ctx);
    }

    @Override public boolean save(...) { /* writable 才允许;走 filesystem.write */ }
    @Override public boolean delete(...) { /* 同上 */ }
    @Override public boolean isWriteable() { return writable; }
    // ...
}
```

要点：

- 合并旧的 `FilesystemBackedSkillRepository` + `WritableFilesystemSkillRepository`，writability 退化为构造参数 `writable`
- `getAllSkills()` 只读 SKILL.md，与现行 `FilesystemBackedSkillRepository` 一致 —— 不预载 resources，节省 IO，per-call namespace 切换语义自然
- `resourcesFor(skillName, ctx)` 返回懒访问器，每次调用走 `filesystem.read(ctx, "skills/<name>/<relPath>")`
- `hasMetadataAncestor` 跳过 `_drafts/`、`.archive/` 等元数据子树，逻辑直接从现 `FilesystemBackedSkillRepository.hasMetadataAncestor` 搬过来

`FilesystemSkillResources` 是个简短的私有实现：

```java
final class FilesystemSkillResources implements SkillResources {
    private final AbstractFilesystem fs;
    private final String skillDir;
    private final RuntimeContext capturedCtx;

    @Override public Optional<String> read(String rel) {
        AbstractFilesystem.validatePath(rel);
        ReadResult r = fs.read(capturedCtx, skillDir + "/" + rel, 0, 0);
        return r.isSuccess() && r.fileData() != null
                ? Optional.ofNullable(r.fileData().content())
                : Optional.empty();
    }
    @Override public List<String> list() {
        GlobResult g = fs.glob(capturedCtx, "**/*", skillDir);
        // 把绝对/虚拟路径剥成相对 skillDir 的形式
        ...
    }
    // readBinary 走 downloadFiles 单文件,或 ASCII 编码读 + base64 还原
}
```

> 注意：`resourcesFor` 拿到的 `ctx` 在闭包里被捕获。一次 `onSystemPrompt` 里 entry 全部构建完后，之后这一轮内 LLM 不管发起几次 `load_skill_through_path`，都用同一个 ctx。下一轮 onSystemPrompt 重建 entry，ctx 也跟着更新 —— 与现行 `FilesystemBackedSkillRepository.contextSupplier` 语义一致。

### 4.3 用户决策树

```
我要把 skill 内容放哪里?
├── 跟工作区一起,走 sandbox/远端文件系统    → WorkspaceSkillRepository (默认自动注册,无需手写)
├── 项目里一个独立本地目录(host 单租户)     → core.skill.repository.FileSystemSkillRepository
├── 跟 JAR 一起发布                         → core.skill.repository.ClasspathSkillRepository
├── 外部 Git 仓库                            → agentscope-extensions-skill-git-repository
├── MySQL / Nacos 控制台维护                 → 对应 extension
└── 自定义后端                               → 自己实现 AgentSkillRepository
```

---

## 5. HarnessAgent.Builder API

### 5.1 默认零配置

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("Assistant").model(model).workspace(workspace)
    .build();
// 内部自动注册 WorkspaceSkillRepository(filesystem, "skills", ctx, "workspace", writable=true)
// 用户在 <workspace>/skills/<name>/SKILL.md 放文件即可
```

### 5.2 与 ReActAgent 一致的方法形状

```java
public Builder skillRepository(AgentSkillRepository repo) { ... }
public Builder skillRepositories(List<AgentSkillRepository> repos) { ... }
public Builder skillFilter(SkillFilter filter) { ... }
```

- 方法名、入参类型、语义都与 `ReActAgent.Builder` 完全一致
- 用户从 ReActAgent 的代码片段直接粘到 HarnessAgent，编译通过、运行正确

### 5.3 HarnessAgent 专属补充方法

```java
public Builder projectGlobalSkillsDir(Path dir) { ... }       // 已有,Layer 1
public Builder disableDefaultWorkspaceSkills() { ... }        // 新增,关闭默认 WorkspaceSkillRepository
public Builder skillVisibilityFilter(SkillVisibilityFilter f) // 已有,canary/allow-list 等
```

`disableDefaultWorkspaceSkills()` 用于"我自己手动管全部 skill 来源"的高级用户场景。

### 5.4 内部四层 compose 与 stage

`HarnessAgentBuilderSupport.composeSkillRepositories` 继续按低到高优先级组装：

| Layer | 来源 | 命中条件 | 是否需要 stage |
|-------|------|---------|--------------|
| 1 | `projectGlobalSkillsDir` → core `FileSystemSkillRepository` | 用户设了 `projectGlobalSkillsDir` | ✅（host 上不在 workspace 内） |
| 2 | 用户传入的 `skillRepositories` / `skillRepository`（marketplace） | 总是 | ✅（资源仅在内存） |
| 3 | `wsManager.getSkillsDir()` → core `FileSystemSkillRepository` | workspace 目录下存在 `skills/` | ❌（已在 workspace 内） |
| 4 | `WorkspaceSkillRepository(filesystem, "skills", ctx, ...)` | 未调 `disableDefaultWorkspaceSkills` | ❌（已在 workspace 内/sandbox 内） |

合并完成后由 `HarnessSkillMiddleware` 装配阶段做 marketplace stage（详见 § 7.4）：Layer 1/2 的资源被物化到 `<wsRoot>/.skills-cache/<source-ns>/<skill-name>/`，让 Layer 1/2 的脚本在 shell 模式下可执行。Layer 3/4 不动。

这是内部细节，用户不需要感知"层"的存在。

---

## 6. Harness skill runtime

### 6.1 模块清单

```
io.agentscope.harness.agent.skill.runtime/
├── SkillCatalog.java             // 一次 call 的 entry 快照(Map<skillId, HarnessSkillEntry>)
├── SkillPromptBuilder.java       // 渲染 <available_skills> XML
├── SkillLoadTool.java            // 实现 load_skill_through_path 工具
├── SkillRuntime.java             // 聚合三者,middleware 用它

io.agentscope.harness.agent.middleware/
└── HarnessSkillMiddleware.java   // 重写,不再 extends DynamicSkillMiddleware
```

### 6.2 `HarnessSkillMiddleware.onSystemPrompt` 流程

```java
@Override
public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
    RuntimeContext rc = resolveRc(agent);

    // 1. 计算 source namespace(处理多仓库 source 冲突,详见 § 7.4.3)
    //    输入: List<AgentSkillRepository> repos
    //    输出: IdentityHashMap<AgentSkillRepository, String> sourceNs
    Map<AgentSkillRepository, String> sourceNs = resolveSourceNamespaces(repos);

    // 2. 合并所有仓库(Layer 1/2/3/4),后者覆盖前者(by AgentSkill.name)
    //    同时记录每个 winner 的来源仓库,用于后续 stage 和 files-root 决策
    LinkedHashMap<String, RepoBound> mergedByName = mergeRepositories(rc, sourceNs);
    if (mergedByName.isEmpty()) return Mono.just(currentPrompt);

    // 3. 应用 SkillVisibilityFilter(harness 特有,canary/allow-list)
    List<RepoBound> visible = applyVisibilityFilter(mergedByName.values(), rc);
    if (visible.isEmpty()) return Mono.just(currentPrompt);

    // 4. Marketplace stage:对来自 Layer 1/2 的 winner,把 resources 物化到
    //    <wsRoot>/.skills-cache/<source-ns>/<skill-name>/(详见 § 7.4)
    //    Layer 3/4 winner 不动。返回每个 skill 的 filesRoot 解析结果。
    Map<String, FilesRoot> filesRootByName = stageMarketplaceAndResolveRoots(visible);

    // 5. 给每个 skill 装配 lazy resources(若来源仓库 instanceof LazyResourceCapable)
    SkillCatalog catalog = SkillCatalog.from(visible, filesRootByName, rc);

    // 6. 装/换 load_skill_through_path 工具,引用最新 catalog 与 rc
    runtime.installTool(catalog, toolkit, rcSupplier);

    // 7. 拼 prompt:<available_skills> XML 每个 <skill> 带 <files-root>;
    //    code-execution 段按 shell 可用性决定是否输出(详见 § 6.5)
    SkillFilter effective = builderFilter.overlay(rc.get(SkillFilter.class));
    String append = SkillPromptBuilder.render(catalog, effective, shellMode);

    return Mono.just(joinPrompt(currentPrompt, append));
}
```

注意：

- 每次 `call()` 都重建一次 catalog；与现行 `DynamicSkillMiddleware` 节奏一致，per-user namespace 切换需要
- `runtime.installTool(...)` 内部用 `AtomicReference<SkillCatalog>` 持有当前快照，工具调用时通过该引用拿到最新版本 —— 工具实例只注册一次（首次），后续仅刷新引用
- Step 4 的 stage 输出按内容 hash 比对，未变化 skip 写盘；hash 不变 → projection hash 不变 → sandbox 不重 hydrate
- `shellMode` 取自 `toolkit` 上是否注册了 `ShellExecuteTool`，以及 filesystem 是 `SandboxBackedFilesystem` 还是 `LocalFilesystemWithShell`（详见 § 6.5）

### 6.3 `SkillLoadTool` 查询顺序

```java
private String loadResource(String skillId, String path) {
    HarnessSkillEntry entry = catalogRef.get().get(skillId);
    if (entry == null) throw new IllegalArgumentException("Skill not found: " + skillId);

    AgentSkill skill = entry.skill();
    activate(skillId);

    // 1. 特殊路径:SKILL.md → 返回主文(响应里带 Files root)
    if ("SKILL.md".equals(path)) {
        return formatSkillMarkdownResponse(skillId, skill, entry.filesRoot());
    }

    // 2. 内存 map 命中(Layer 1/3 + marketplace 已经预载的资源)
    Map<String, String> mem = skill.getResources();
    if (mem != null && mem.containsKey(path)) {
        return formatResourceResponse(skillId, path, mem.get(path));
    }

    // 3. lazy 通道兜底(WorkspaceSkillRepository 的资源走这里)
    if (entry.lazyResources() != null) {
        Optional<String> lazy = entry.lazyResources().read(path);
        if (lazy.isPresent()) {
            return formatResourceResponse(skillId, path, lazy.get());
        }
    }

    // 4. 找不到 —— 合并两边的可用列表,给 LLM 友好枚举
    List<String> available = new ArrayList<>();
    available.add("SKILL.md");
    if (mem != null) available.addAll(mem.keySet());
    if (entry.lazyResources() != null) available.addAll(entry.lazyResources().list());
    throw new IllegalArgumentException(formatNotFound(skillId, path, available));
}
```

### 6.4 路径策略矩阵（4 fs 模式 × 4 skill 来源）

LLM 通过 `load_skill_through_path` 读资源走相对路径（始终是 `skills/<name>/<rel>` 或 `.skills-cache/<source-ns>/<name>/<rel>`，由 fs 自己处理 namespace 前缀和 sandbox 边界）。**真正复杂的是 shell 执行场景下的绝对路径**：每个 skill 的绝对路径根因 fs 模式和 skill 来源不同而不同。

#### 6.4.1 `load_skill_through_path` 读资源路径

| 场景 | filesystem | LLM 看到的相对路径 | filesystem.read 实际读到哪里 |
|------|-----------|------------------|---------------------------|
| Sandbox 模式 | `SandboxBackedFilesystem` | `skills/<name>/<rel>` 或 `.skills-cache/<source-ns>/<name>/<rel>` | sandbox 内部 `/workspace/skills/...` 或 `/workspace/.skills-cache/...`（由 projection 投影） |
| Local-with-shell | `LocalFilesystemWithShell` | 同上 | `<cwd>/skills/...` 或 `<cwd>/.skills-cache/...` |
| Local（无 shell） | `LocalFilesystem` with/without `NamespaceFactory` | 同上 | 同上，带 namespace 时自动加前缀 `<cwd>/<ns>/skills/...` |
| Composite | `CompositeFilesystem` | 同上 | 按 prefix 路由到对应后端；通常 `.skills-cache` 落在 default backend（local） |

要点：

- LLM **始终**用相对路径，不需要感知 namespace 或 sandbox 内部布局
- sandbox 模式下不存在"再加 namespace 前缀"的问题 —— sandbox 实例本身就是 namespace 边界（见 [project-sandbox-namespace-isolation]）

#### 6.4.2 Shell 执行的绝对路径根（`<files-root>`）

每个 skill 在 prompt 里携带一个 `<files-root>` 字段，告诉 LLM 该 skill 的资源根绝对路径。规则按 `(fs 模式, skill 来源)` 决定：

| Skill 来源 \ fs 模式 | Sandbox 模式 | Local-with-shell | Local（无 shell） | Composite |
|---------------------|--------------|------------------|----------------|-----------|
| Layer 1 `projectGlobalSkillsDir` | `/workspace/.skills-cache/_global/<id>` | `<wsRoot>/.skills-cache/_global/<id>` | 不渲染 `<files-root>` | 不渲染 |
| Layer 2 marketplace | `/workspace/.skills-cache/<source-ns>/<id>` | `<wsRoot>/.skills-cache/<source-ns>/<id>` | 不渲染 | 不渲染 |
| Layer 3 `<workspace>/skills/` | `/workspace/skills/<id>` | `<wsRoot>/skills/<id>` | 不渲染 | 不渲染 |
| Layer 4 `WorkspaceSkillRepository` | `/workspace/skills/<id>` | `<wsRoot>/skills/<id>` | 不渲染 | 不渲染 |

要点：

- "不渲染 `<files-root>`" 的两个模式下（Local 无 shell / Composite），LLM 没有 shell 工具可用，`<files-root>` 写出来反而误导，索性不写
- `<source-ns>` 由 § 7.4.3 的冲突解析机制确定，绝大多数情况下等于 `repo.getSource()`，只有重复时退化为 `<source>_<repoIndex>`
- Layer 1 用固定 namespace `_global`，因为 core `FileSystemSkillRepository` 默认 `source()` 是按 baseDir 名拼的 `"filesystem-<dir-name>"`，含信息量低
- `<wsRoot>` 是 host 上 `wsManager.getRoot()` 的绝对路径

### 6.5 Code-execution prompt 按 shell 可用性分档

**触发条件**：toolkit 上注册了 `ShellExecuteTool`。HarnessAgent 默认在 filesystem `instanceof AbstractSandboxFilesystem` 时注册（覆盖 `SandboxBackedFilesystem` 与 `LocalFilesystemWithShell` 两种实现）。

**渲染规则**：

- 若 shell 不可用（`LocalFilesystem` 不带 shell / `CompositeFilesystem`），不输出 code-execution 段，也不在 `<skill>` XML 里写 `<files-root>`
- 若 shell 可用，输出统一模板，**不再写死单一根路径**，而是引导 LLM 使用每个 skill 自带的 `<files-root>`：

```
## Code Execution

You have access to the execute_shell_command tool. Each skill in <available_skills> includes a
<files-root> element giving the absolute path to that skill's files.

Workflow:
1. After loading a skill, look at its <files-root> in <available_skills>
2. List its files:   ls <files-root>/
3. Run scripts:      python3 <files-root>/scripts/foo.py
4. Always use absolute paths derived from <files-root>; never invent paths
```

- `<files-root>` 内容按 § 6.4.2 的矩阵决定（sandbox 模式 `/workspace/...` / Local-with-shell 模式 `<wsRoot>/...`）
- LLM 拿到 SKILL.md 后，把里面的相对路径（如 `scripts/foo.py`）跟 `<files-root>` 拼接即可
- `load_skill_through_path` 的响应文本里也会带一行 `Files root: <abs-path>`，作为 fallback（详见 § 6.3 的 `formatSkillMarkdownResponse`）

> 这样设计的好处：LLM 不需要推理"我这个 skill 是 marketplace 还是 workspace"，prompt 直接告诉它每个 skill 该用哪个根，多仓库混搭也不会拼错路径。

---

## 7. 与 HarnessAgent 其它组件的协作

### 7.1 SkillCurator / SkillManageTool / ProposeSkillTool

这三者目前都依赖 `WritableFilesystemSkillRepository`：

- `HarnessAgent.Builder.build()` 在 `skillManageToolEnabled` 时，把 Layer 4 的 `FilesystemBackedSkillRepository` 替换为 `WritableFilesystemSkillRepository`，再额外构造一个 drafts repo

迁移：

- 改为构造 `WorkspaceSkillRepository(filesystem, mainDir, ctxSupplier, "workspace-writable", writable=true)` 作为 main repo
- 同样构造 drafts repo（`WorkspaceSkillRepository(filesystem, draftsDir, ctxSupplier, "workspace-drafts", writable=true)`）
- `SkillManageTool` / `SkillPromoter` / `SkillCurator` 接收的仓库类型由 `WritableFilesystemSkillRepository` 改为 `WorkspaceSkillRepository`（或 `AgentSkillRepository` + 检查 `isWriteable()`，二选一，倾向后者以减少耦合）

### 7.2 SkillUsageMiddleware / SkillVisibilityFilter / SkillAuditLog

接口不变（消费的是 `AgentSkill` 与 `RuntimeContext`），只是来源从 `DynamicSkillMiddleware` 维护的 `SkillBox` 改为新的 `SkillCatalog`。`SkillUsageMiddleware` 监听的工具名 `load_skill_through_path` / `read_skill` 保持不变。

### 7.3 第三方扩展（Git / MySQL / Nacos）

零修改。它们实现的是 `AgentSkillRepository`，HarnessAgent.Builder.skillRepository(...) 直接接收，进 Layer 2 合并。它们不实现 `LazyResourceCapable` —— 但本来 Git/MySQL/Nacos 仓库的资源都是预载到内存的，`AgentSkill.resources` 内存 map 命中，正常工作。

> 这三类仓库要让脚本可执行（shell 模式下）依赖 § 7.4 的 stage 机制把内存资源物化到 `.skills-cache/`。

### 7.4 Marketplace 脚本执行：stage 机制

#### 7.4.1 问题

`AgentSkillRepository.getAllSkills()` 返回的 `AgentSkill.resources` 是内存里的 `Map<String,String>`，shell 命令找不到它。具体到几种来源：

| Skill 来源 | 资源在哪 | shell 执行能拿到吗 |
|-----------|---------|------------------|
| Layer 1 `projectGlobalSkillsDir` | host disk（但在 workspace 外） | ❌ workspace 之外，sandbox projection 不到，Local-with-shell 默认 cwd 是 workspace 也找不到 |
| Layer 2 marketplace | 内存 | ❌ disk 上没有 |
| Layer 3 `<workspace>/skills/` | host disk（workspace 内） | ✅ projection 自动投，shell 直接读 |
| Layer 4 `WorkspaceSkillRepository` | sandbox 内（或 host 带 namespace） | ✅ filesystem 与 shell 同一个边界 |

要让 Layer 1/2 也可执行，就必须把它们的资源物化到一个 shell 能看见的位置 —— 用 `<wsRoot>/.skills-cache/<source-ns>/<skill-name>/` 这个 host 上的子树，并让 sandbox projection 把它带进去。

#### 7.4.2 Stage 流程

`HarnessSkillMiddleware.onSystemPrompt` 第 4 步（见 § 6.2）实现：

```java
Map<String, FilesRoot> stageMarketplaceAndResolveRoots(List<RepoBound> visible) {
    Path cacheRoot = wsManager.getRoot().resolve(".skills-cache");
    Map<String, FilesRoot> filesRoots = new HashMap<>();
    Set<Path> retainedDirs = new HashSet<>();

    for (RepoBound bound : visible) {
        AgentSkill skill = bound.skill();
        AgentSkillRepository repo = bound.repo();
        String name = skill.getName();

        if (isWorkspaceNative(repo)) {
            // Layer 3/4:直接用 workspace skills/ 路径
            filesRoots.put(name, FilesRoot.workspaceNative(name));
            continue;
        }

        // Layer 1/2:stage
        String sourceNs = bound.sourceNamespace();   // 来自 § 7.4.3 解析
        Path stagedDir = cacheRoot.resolve(sourceNs).resolve(name);
        materializeIfChanged(stagedDir, skill.getResources());  // SHA-256 比对,未变 skip
        retainedDirs.add(stagedDir);
        filesRoots.put(name, FilesRoot.cached(sourceNs, name));
    }

    // GC 孤儿:cacheRoot 下不在 retainedDirs 的整个删除
    garbageCollectOrphans(cacheRoot, retainedDirs);
    return filesRoots;
}
```

要点：

- `materializeIfChanged` 单文件级 SHA-256 比对，未变化的文件跳过写盘 —— 避免无意义 mtime 抖动
- 不变的文件 → projection hash 不变 → sandbox 不重 hydrate
- `garbageCollectOrphans` 在每次装配末尾做白名单清理：本次 visible skills 之外的 staged 目录整个删，无状态、不会泄漏
- workspace 只读（罕见）的情况下 stage 步骤跳过、warning log；这时 marketplace 脚本无法执行，由用户预先 stage 或换 fs 模式

#### 7.4.3 多仓库 source 冲突解析

`AgentSkillRepository.getSource()` 是 stage 子目录的 namespace。如果用户配了多个仓库且 `getSource()` 重复（典型场景：两个 `GitSkillRepository`，都没改默认 source），stage 路径会撞，skillId（`name + "_" + source`）也会撞 —— 后者是既有问题，前者是 stage 引入的。

解析策略：

```java
Map<AgentSkillRepository, String> resolveSourceNamespaces(List<AgentSkillRepository> repos) {
    Map<String, Integer> count = new HashMap<>();
    for (AgentSkillRepository repo : repos) {
        count.merge(repo.getSource(), 1, Integer::sum);
    }
    Map<AgentSkillRepository, String> ns = new IdentityHashMap<>();
    Map<String, Integer> seen = new HashMap<>();
    for (int i = 0; i < repos.size(); i++) {
        AgentSkillRepository repo = repos.get(i);
        String src = repo.getSource();
        if (count.get(src) == 1) {
            ns.put(repo, src);
        } else {
            int idx = seen.merge(src, 1, Integer::sum);
            String resolved = src + "_" + idx;
            ns.put(repo, resolved);
            log.warn("Skill repository source '{}' is used by multiple repositories; "
                    + "disambiguating as '{}' for repo at index {}", src, resolved, i);
        }
    }
    return ns;
}
```

- 唯一时 source 名直接用
- 重复时按出现顺序附加 `_1` / `_2` 后缀
- skillId 展示也跟着 sourceNs 走，确保 `<skill-id>` 在 LLM 看到的 prompt 里唯一

Layer 1 特殊处理：用固定 namespace `_global`，因为 core `FileSystemSkillRepository` 默认 `getSource()` 是 `"filesystem-<...>"`，含信息量低且每个实例不同；统一用 `_global` 更稳定。

#### 7.4.4 `<files-root>` 暴露给 LLM

每个 skill 在 `<available_skills>` XML 里多一个 `<files-root>` 子元素（详见 § 6.4.2 矩阵），并且 `load_skill_through_path` 响应里也带一行：

```xml
<skill>
  <name>git-changelog</name>
  <description>Build changelog from git log</description>
  <skill-id>git-changelog_git-myorg</skill-id>
  <files-root>/workspace/.skills-cache/git-myorg/git-changelog</files-root>
</skill>
```

```
Successfully loaded skill: git-changelog_git-myorg
Files root: /workspace/.skills-cache/git-myorg/git-changelog
Content:
---
... SKILL.md 内容 ...
---
```

shell 模式不可用时（Local 无 shell / Composite），既不渲染 `<files-root>` 也不发 code-execution 段，避免误导。

#### 7.4.5 对其他 Repository 的影响（兼容性核查）

| 组件 | 是否受影响 | 原因 |
|------|----------|------|
| `WorkspaceSkillRepository.getAllSkills()` | ❌ | glob 在 `skills/` 下扫，`.skills-cache` 平行不在树里 |
| core `FileSystemSkillRepository`（Layer 1/3 指向 `skills/`） | ❌ | base 是 `skills/`，看不到 `.skills-cache` |
| `wsManager.getSkillsDir()`（Layer 3） | ❌ | 同上 |
| `SkillCurator` / `SkillManageTool` / `SkillPromoter` | ❌ | 工作目录 `skills/` 和 `skills/_drafts/`，不与 `.skills-cache` 重合 |
| `disableDefaultWorkspaceSkills()` | ❌ | stage 触发条件是"该 skill 属于 Layer 1/2"，与是否启用默认 Layer 4 无关 |
| 同名 skill 冲突 | ❌ | compose dedup 仍按 name，winner 唯一；只 stage winner |
| sandbox projection（空 stage） | ❌ | projection applier 已 `if (!Files.exists(resolved)) continue;`，hash 不变、零 IO |

需要照顾的边界点：

1. **`WorkspaceSkillRepository` 的 `skillsRelativeDir` 配成 `"."` 或 `""`**：非默认配置会让 glob 扫整个 workspace，把 `.skills-cache/<source>/<name>/SKILL.md` 当 skill 列出。`hasMetadataAncestor` 在 base="." 时 marker `"/./"` 匹配不到。修复：在 `hasMetadataAncestor` 里加一个判断"当 base 是 `.` 或 `""` 时，检查 path 第一段是否以 `.` 或 `_` 开头"，与既有元数据前缀过滤逻辑对齐。
2. **core `FileSystemSkillRepository` 被指向 `<wsRoot>`（而非 `<wsRoot>/skills/`）**：现有 `hasSkillFile` 检查直接子目录有无 SKILL.md，`.skills-cache/SKILL.md` 不存在 → 自然被过滤。但这层防御依赖 stage 结构必须是 `.skills-cache/<source>/<name>/SKILL.md` 至少两层 —— § 7.4.2 的 `stagedDir` 路径已保证这一点。
3. **Sandbox projection hash 抖动**：`.skills-cache` 加进 `DEFAULT_WORKSPACE_PROJECTION_ROOTS` 后，marketplace 资源变化会触发 sandbox 重新 hydrate `.skills-cache` 子树（其他子树因 hash 不变跳过）。这是必要的：内容变了不重 hydrate 就跑的是旧脚本。Stage 自身的 SHA-256 比对保证"未变不写盘"，所以只有真变化时才抖。

#### 7.4.6 `.skills-cache` 结构约束（文档级 contract）

- 第一级目录必须是 `<source-ns>`（不直接放 `SKILL.md`）
- 第二级目录必须是 `<skill-name>`
- 不允许在 `.skills-cache` 顶层放任何文件
- `_global` 作为 Layer 1 的固定 source-ns
- 用户不应手工写入 `.skills-cache`；GC 每次 onSystemPrompt 都会执行白名单清理

---

## 8. 不保留的特性与理由

### 8.1 Skill 激活 → 启用 tool group 联动不复刻

旧 `SkillBox.setSkillActive` + `Toolkit.updateToolGroups` 把 "LLM 激活某 skill" 和 "启用对应一组 tool" 绑在一起。新设计不复刻：

- HarnessAgent 用户的 skill 主要是 markdown 操作指南，绑定动态 tool group 的用例罕见
- 真正需要"按 skill 切换可用工具"的场景，用户可以自己写 hook 或 middleware 实现，比 SkillBox 那套隐式联动更清晰
- 简化心智模型：skill 是"文档 + 资源"，tool 是"能力"，两者解耦

`HarnessSkillEntry` 仍保留一个 `active` 概念给 `SkillLoadTool` 用（标记"该 skill 这一轮已被 LLM 载过"），但不再驱动 toolkit 状态变化。`SkillUsageMiddleware` 仍能据此统计。

### 8.2 `SkillBox.registration().subAgent(...) / .mcpClient(...)` 不复刻

把 sub-agent / MCP 挂到 skill 维度组织本身就别扭 —— HarnessAgent.Builder 上已有 `subagent(...)` 和 MCP 直挂入口，更直接。

### 8.3 `SkillBox.uploadSkillFiles()` + 临时 workDir 不复刻

已由 sandbox 的 `WorkspaceProjectionApplier` 覆盖（见 [project-workspace-projection]）。host LocalFs 模式下 skills 本来就在 workspace 内，shell 直接访问即可。

---

## 9. 删除清单（仅 harness 侧）

- `io.agentscope.harness.agent.skill.FilesystemBackedSkillRepository` —— 由 `WorkspaceSkillRepository(writable=false)` 替代
- `io.agentscope.harness.agent.skill.WritableFilesystemSkillRepository` —— 由 `WorkspaceSkillRepository(writable=true)` 替代
- `io.agentscope.harness.agent.middleware.HarnessSkillMiddleware` —— 重写：不再 `extends DynamicSkillMiddleware`，改为直接实现 `MiddlewareBase`

core 侧零删除。第三方 extension 零修改。

---

## 10. 实施分阶段

每个 Phase 内部尽量保证编译通过和测试通过。

### Phase 1：harness 新 SPI 与 repository 实现

- 新增 `io.agentscope.harness.agent.skill.SkillResources` 接口
- 新增 `io.agentscope.harness.agent.skill.LazyResourceCapable` marker 接口
- 新增 `io.agentscope.harness.agent.skill.WorkspaceSkillRepository`（吸收旧两个类的逻辑 + lazy resources）
- 单元测试：覆盖 `WorkspaceSkillRepository` 的 read/write/lazy resources/namespace 切换四类用例

> 本地目录 / classpath 场景复用 core 既有 `FileSystemSkillRepository` / `ClasspathSkillRepository`，harness 不重复实现（详见 § 4.1）。

此阶段旧的 `FilesystemBacked*` 两个类仍在，新代码与旧代码并存，HarnessAgent 还走旧路径。

### Phase 2：harness skill runtime

- 新增 `SkillCatalog` / `SkillPromptBuilder` / `SkillLoadTool` / `SkillRuntime`
- 改写 `HarnessSkillMiddleware`：不再 extends `DynamicSkillMiddleware`，自行实现 `onSystemPrompt`，使用 runtime
- 单元测试：`SkillLoadTool` 的查询顺序（特别是 lazy 兜底）、prompt 渲染、错误信息合并枚举

此阶段 `HarnessAgent.Builder` 暂时仍走旧 middleware；新 middleware 通过单元测试验证。

### Phase 3：切换 HarnessAgent.Builder + marketplace stage

- `HarnessAgentBuilderSupport.composeSkillRepositories` Layer 4 改为 `WorkspaceSkillRepository`（替代旧 `FilesystemBackedSkillRepository`）；Layer 1/3 继续用 core `FileSystemSkillRepository`，不变
- `HarnessAgent.Builder.build()` 装配新的 `HarnessSkillMiddleware`
- `SkillCurator` / `SkillManageTool` / `ProposeSkillTool` / `SkillPromoter` 适配到新的 `WorkspaceSkillRepository`
- 加入新 builder 方法：`disableDefaultWorkspaceSkills()`
- **实现 marketplace stage**（§ 7.4）：
  - `resolveSourceNamespaces` 多仓库冲突解析
  - `stageMarketplaceAndResolveRoots` 物化 + SHA-256 比对 + 孤儿 GC
  - 把 `.skills-cache` 追加到 `SandboxFilesystemSpec.DEFAULT_WORKSPACE_PROJECTION_ROOTS`
  - `WorkspaceSkillRepository.hasMetadataAncestor` 修复 base="."/"" 边界
- **SkillPromptBuilder 按 shell 可用性分档**：
  - sandbox / Local-with-shell：渲染 `<files-root>` + code-execution 段
  - Local 无 shell / Composite：两者都不渲染
- 集成测试矩阵：
  - sandbox + Layer 3 skill 脚本执行
  - sandbox + Layer 2 marketplace 脚本执行（stage → projection → exec）
  - Local-with-shell + Layer 1 `projectGlobalSkillsDir` 脚本执行
  - 多 marketplace 仓库 source 冲突场景
  - Composite 模式仅 `load_skill_through_path` 可读，shell 工具未注册（行为正确）

### Phase 4：删除旧 harness 类

- 删除 `harness.skill.FilesystemBackedSkillRepository`
- 删除 `harness.skill.WritableFilesystemSkillRepository`
- 旧 `HarnessSkillMiddleware` 已经在 Phase 2 改写完毕，此处确认无残留引用

### 文档

- 更新 `docs/v2/{en,zh}/integration/skill/index.md`，重写 wiring 示例为 HarnessAgent 默认零配置 + 三个 repository 选择
- `agentscope-extensions-skill-{git,mysql}-repository` 的 README 加一句"也可在 HarnessAgent 上以 `.skillRepository(...)` 方式接入"，配代码片段

---

## 11. 验收要点

- ReActAgent 用户：现有代码继续编译运行，行为零变化
- HarnessAgent 默认零配置：`<workspace>/skills/<name>/SKILL.md` 自动被 LLM 看见
- HarnessAgent + sandbox + workspace skill：LLM 通过 `load_skill_through_path` 既能拿 SKILL.md 也能拿 `references/*.md`、`scripts/*.py`；通过 `execute_shell_command` 能直接跑 `/workspace/skills/<id>/scripts/foo.py`
- HarnessAgent + sandbox + marketplace skill：LLM 同样能通过 `load_skill_through_path` 读资源；shell 执行用 `<files-root>` 拼出的 `/workspace/.skills-cache/<source-ns>/<id>/scripts/foo.py` 路径直接跑通
- HarnessAgent + Local-with-shell：marketplace skill 通过 `<wsRoot>/.skills-cache/<source-ns>/<id>/...` 执行
- HarnessAgent + Composite：marketplace skill 可读不可执行（行为正确，prompt 不渲染 `<files-root>` 与 code-execution 段）
- HarnessAgent + host LocalFs 多租户：不同 RuntimeContext 下 LLM 看到不同的 skill 列表，且 `load_skill_through_path` 正确返回各自 namespace 下的内容
- 多 marketplace 仓库 source 冲突：自动 namespacing 为 `<source>_<idx>`，prompt 与 stage 路径都不撞
- 第三方扩展：`agentscope-extensions-skill-git-repository` / `mysql` / `nacos-skill` 不修改任何代码，直接以 `.skillRepository(repo)` 形式接入 HarnessAgent.Builder

---

## 12. 开放问题

1. **`SkillCurator` 等周边对 `WorkspaceSkillRepository` 的依赖方式**：是直接持有具体类型（编译期绑定），还是通过 `AgentSkillRepository` + `isWriteable()` 检查？倾向后者，但 `SkillPromoter` 涉及 draftsDir/mainDir 路径，可能需要更具体的类型 —— 在 Phase 3 适配时拍板。
2. **`AgentSkill` 长期归属**：当 ReActAgent 全部迁离后，core 的 `skill` 包是否物理删除，`AgentSkill` 是否迁到 harness？本次重构不做，留给后续 release。
3. **多 source 冲突时是自动 namespacing 还是直接报错**：当前 § 7.4.3 选择"自动加 `_<idx>` 后缀 + warning"，让用户即时可用；但 prompt 里出现 `<skill-id>git-changelog_git_1</skill-id>` 这种 LLM 不易理解的字符串。备选：build 阶段直接抛 `IllegalStateException`，逼用户给每个仓库显式传 source。Phase 3 实施时拍。
4. **`<files-root>` 是绝对路径还是相对 workspace 根的相对路径**：当前方案是绝对路径，因为 LLM 用它直接拼 shell 命令；相对路径需要 LLM 知道 "workspace 根是 `/workspace`"，多一层心智。但绝对路径在 sandbox/Local-with-shell 切换时不可移植 —— prompt 重新生成会带不同前缀。基本不影响（每次 onSystemPrompt 重新拼），但若未来支持"运行时切换 fs 模式"会需要重新考虑。
5. **Layer 1 `projectGlobalSkillsDir` 的 stage 是 copy / symlink / hardlink**：copy 简单但浪费空间；symlink 在 sandbox projection 用 tar 时会被解引用（tar 默认行为）—— 实际等价于 copy，无副作用，但 host 上 `.skills-cache/_global/<id>/` 会是 symlink 形式占空间小；hardlink 要求同一文件系统，可移植性差。倾向 copy，靠 SHA-256 比对避免重复 IO，Phase 3 实施时再权衡。
6. **`.skills-cache` workspace 只读时的 fallback**：罕见但存在（典型场景：CI/CD 把 workspace 挂成 read-only volume）。当前方案是跳过 stage + warning log，marketplace skill 不可执行。是否提供一个 `stageDir(Path override)` 让用户指定可写位置？倾向加，但默认值留空（即 `<wsRoot>/.skills-cache`）。
7. **`hasMetadataAncestor` 判断逻辑**：是否同时按 base 值和 path 头段两种方式判断元数据前缀？当前实现只按"<base> 之后的段"判断，base="."/"" 时会失效。修复方案在 § 7.4.5 已写明，但是否要顺手把 core 的 `SkillFileSystemHelper.getAllSkillNames` 也加上 dot/underscore 前缀过滤（一致性问题）？倾向不动 core，仅在 harness `WorkspaceSkillRepository` 修复。
