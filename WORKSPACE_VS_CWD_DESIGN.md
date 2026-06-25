# Workspace vs CWD 分阶段实施设计

基于 `WORKSPACE_VS_CWD_ANALYSIS.md` 的分析结论，本文档展开每个 Phase 的具体改法。

---

## Phase 0：零破坏的新增层（纯加法）

Phase 0 不改动任何现有类的行为。所有改动都是**新增 class** 或**新增 method**。现有用户代码编译、行为完全不变。

### 0-A. ProjectContext record

新建 `agentscope-harness/.../workspace/ProjectContext.java`

```java
package io.agentscope.harness.agent.workspace;

import java.nio.file.Path;

/**
 * 三层目录模型的不可变容器。
 *
 * <pre>
 * Layer 3: workspace   — agent 私有存储（MEMORY.md, sessions, skills）
 * Layer 2: projectRoot — 项目边界（git root 或显式配置）
 * Layer 1: cwd         — 当前工作目录（路径解析锚点, shell pwd）
 * </pre>
 */
public record ProjectContext(
    Path cwd,           // 当前工作目录
    Path projectRoot,   // 项目根（权限边界）
    Path workspace      // agent workspace
) {
    /** cwd 相对于 projectRoot 的位置 */
    public String relativePath() {
        if (cwd.equals(projectRoot)) return ".";
        return projectRoot.relativize(cwd).toString().replace('\\', '/');
    }

    /** 判断路径是否在项目边界内 */
    public boolean containsPath(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return abs.startsWith(projectRoot.toAbsolutePath().normalize())
            || abs.startsWith(workspace.toAbsolutePath().normalize());
    }

    /** 当 cwd == projectRoot 时为 true（即传统模式） */
    public boolean isCwdAtProjectRoot() {
        return cwd.toAbsolutePath().normalize()
            .equals(projectRoot.toAbsolutePath().normalize());
    }
}
```

**设计要点**：
- 用 record 而非 class，不可变，线程安全
- `containsPath` 同时覆盖 project 和 workspace（类似 opencode 的 `containsPath` 检查 `directory` + `worktree`）
- `isCwdAtProjectRoot()` 让调用方判断是否处于"传统单层模式"

### 0-B. ProjectDiscovery utility

新建 `agentscope-harness/.../workspace/ProjectDiscovery.java`

```java
package io.agentscope.harness.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 从 CWD 向上查找项目根。策略：
 *   1. .git 目录（与 opencode 一致，最可靠的项目标记）
 *   2. 构建系统标记文件（pom.xml, package.json, Cargo.toml 等）
 *   3. fallback 到 CWD 本身
 */
public final class ProjectDiscovery {

    private static final List<String> GIT_MARKERS = List.of(".git");

    private static final List<String> BUILD_MARKERS = List.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "package.json", "Cargo.toml", "go.mod", "pyproject.toml",
        "CMakeLists.txt", "Makefile", ".sln"
    );

    private ProjectDiscovery() {}

    /**
     * 从 startDir 向上查找项目根。
     *
     * @param startDir 起始目录（通常是 CWD）
     * @return 找到的项目根，或 startDir 本身
     */
    public static Path findProjectRoot(Path startDir) {
        Path start = startDir.toAbsolutePath().normalize();

        // 1. 优先找 .git — 与 opencode 的 git-centric 策略一致
        Path gitRoot = findUpwards(start, GIT_MARKERS);
        if (gitRoot != null) return gitRoot;

        // 2. 找构建系统标记
        Path buildRoot = findUpwards(start, BUILD_MARKERS);
        if (buildRoot != null) return buildRoot;

        // 3. fallback
        return start;
    }

    /**
     * 构建完整的 ProjectContext。
     */
    public static ProjectContext discover(Path cwd, Path workspace) {
        Path effectiveCwd = cwd != null
            ? cwd.toAbsolutePath().normalize()
            : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path projectRoot = findProjectRoot(effectiveCwd);
        Path effectiveWorkspace = workspace != null ? workspace : defaultWorkspace(projectRoot);
        return new ProjectContext(effectiveCwd, projectRoot, effectiveWorkspace);
    }

    private static Path defaultWorkspace(Path projectRoot) {
        return projectRoot.resolve(".agentscope/workspace");
    }

    private static Path findUpwards(Path start, List<String> markers) {
        Path current = start;
        Path root = current.getRoot();
        while (current != null && !current.equals(root)) {
            for (String marker : markers) {
                if (Files.exists(current.resolve(marker))) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return null;
    }
}
```

**设计要点**：
- 纯 static utility，无状态
- 查找策略与 opencode 一致：git 优先 → 构建标记 → fallback
- `discover()` 一步到位生成完整 `ProjectContext`
- **不改动** `LocalFilesystemSpec` 或 `HarnessAgent.Builder`——Phase 0 只提供工具，不注入

### 0-C. CodingAgentPreset 快捷 API

新建 `agentscope-harness/.../CodingAgentPreset.java`

```java
package io.agentscope.harness.agent;

import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.ProjectContext;
import io.agentscope.harness.agent.workspace.ProjectDiscovery;
import java.nio.file.Path;

/**
 * 一键构建 coding agent 所需的 filesystem 配置。
 *
 * <p>核心效果：agent 对项目目录有读写权限，可以直接生成/编辑代码文件，
 * 同时 workspace 元数据（memory, sessions, skills）仍然写入 workspace。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 最简用法——自动发现项目根，从 CWD 开始
 * HarnessAgent agent = HarnessAgent.builder()
 *     .model(Model.CLAUDE_SONNET)
 *     .filesystem(CodingAgentPreset.filesystem())
 *     .build();
 *
 * // 指定 CWD（monorepo 子目录场景）
 * HarnessAgent agent = HarnessAgent.builder()
 *     .model(Model.CLAUDE_SONNET)
 *     .filesystem(CodingAgentPreset.filesystem(Path.of("/repo/packages/api")))
 *     .build();
 * }</pre>
 */
public final class CodingAgentPreset {

    private CodingAgentPreset() {}

    /**
     * 使用自动项目发现，从进程 CWD 开始。
     */
    public static LocalFilesystemSpec filesystem() {
        return filesystem(null);
    }

    /**
     * 从指定 CWD 构建 coding agent filesystem 配置。
     *
     * <p>自动检测 project root（git root 或构建标记），并启用：
     * <ul>
     *   <li>{@code projectWritable(true)} — 代码写入项目目录
     *   <li>{@code mode(ROOTED)} — 路径限制在项目 + workspace 内
     *   <li>{@code inheritEnv(true)} — shell 继承环境变量
     * </ul>
     *
     * @param cwd 工作目录，null 则使用进程 CWD
     */
    public static LocalFilesystemSpec filesystem(Path cwd) {
        ProjectContext ctx = ProjectDiscovery.discover(cwd, null);

        LocalFilesystemSpec spec = new LocalFilesystemSpec()
            .project(ctx.projectRoot())       // 整个项目作为 overlay lower 层
            .projectWritable(true)            // 非 workspace 路径写入项目目录
            .mode(LocalFsMode.ROOTED)         // 绝对路径限制在已知根内
            .inheritEnv(true);                // shell 可以用 mvn, npm 等

        // 当 cwd 不等于 projectRoot 时，把 cwd 加入 additionalRoots
        // 这样 ROOTED 模式下绝对路径也能访问 cwd
        if (!ctx.isCwdAtProjectRoot()) {
            spec.addRoot(ctx.cwd());
        }

        return spec;
    }
}
```

**设计要点**：
- **不改 HarnessAgent.Builder**——通过 `builder.filesystem(CodingAgentPreset.filesystem())` 使用
- 内部调用 `ProjectDiscovery.discover()` 自动检测项目根
- 默认开启 `projectWritable(true)` + `ROOTED` + `inheritEnv`——coding agent 的最佳实践
- monorepo 场景下 cwd 作为 `additionalRoots` 确保绝对路径可达

### 0-D. Phase 0 的效果展示

**Before**（当前）：

```java
// 用户需要知道 overlay, projectWritable, LocalFsMode 的存在
HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .filesystem(new LocalFilesystemSpec()
        .project(Path.of("/my/project"))
        .projectWritable(true)
        .mode(LocalFsMode.ROOTED)
        .inheritEnv(true))
    .build();
```

**After**（Phase 0）：

```java
// 一行搞定
HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .filesystem(CodingAgentPreset.filesystem())
    .build();

// 或指定目录
HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .filesystem(CodingAgentPreset.filesystem(Path.of("/repo/packages/api")))
    .build();
```

**不影响的代码**：
- 不调用 `CodingAgentPreset` 的现有代码行为完全不变
- `HarnessAgent.builder()` 的默认行为不变（默认仍是非 projectWritable 的 overlay）
- 所有现有 example、test 编译通过

### 0-E. Phase 0 新增文件清单

| 文件 | 位置 | 说明 |
|------|------|------|
| `ProjectContext.java` | `harness/.../workspace/` | 三层目录模型 record |
| `ProjectDiscovery.java` | `harness/.../workspace/` | 项目根自动发现 |
| `CodingAgentPreset.java` | `harness/.../agent/` | 一键 coding agent 配置 |

---

## Phase 1：CWD 与 projectRoot 分离

Phase 1 修改现有类，让 CWD 和 projectRoot 可以是不同的目录。改动遵循"不调用新 API 时行为不变"的原则。

### 1-A. LocalFilesystemSpec 增加 cwd(Path)

**修改文件**：`LocalFilesystemSpec.java`

```java
// ---- 新增字段 ----
/**
 * Agent 的当前工作目录。当设置了 cwd 且与 project 不同时：
 *   - shell pwd = cwd（而不是 project）
 *   - FilesystemTool 的相对路径基于 cwd 解析
 *
 * null 表示 cwd == project（向后兼容的默认行为）。
 */
private Path cwd;

// ---- 新增 setter ----
/**
 * 设置 agent 的当前工作目录。
 *
 * <p>在 monorepo 场景中，cwd 是用户实际工作的子目录（如 {@code packages/api/}），
 * 而 project 是整个 repo 根目录。设置 cwd 后：
 * <ul>
 *   <li>Shell 命令的 {@code pwd} 设为 cwd
 *   <li>{@code FilesystemTool} 的相对路径基于 cwd 解析
 *   <li>{@code ProjectAwareOverlay} 的写入路由不受影响（仍基于 project）
 * </ul>
 *
 * <p>不调用此方法时，cwd 默认等于 project（保持向后兼容）。
 */
public LocalFilesystemSpec cwd(Path cwd) {
    this.cwd = cwd;
    return this;
}

public Path getCwd() {
    return cwd;
}
```

**修改 toFilesystem()**：

```java
public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory localNamespaceFactory) {
    Path effectiveProject =
            project != null ? project : Paths.get(System.getProperty("user.dir"));

    // 新增：effectiveCwd 可以独立于 effectiveProject
    Path effectiveCwd = cwd != null ? cwd : effectiveProject;

    List<Path> policyRoots = new ArrayList<>();
    policyRoots.add(effectiveProject);
    policyRoots.add(workspace);
    // 当 cwd != project 时，cwd 也要加入 PathPolicy 允许列表
    if (!effectiveCwd.toAbsolutePath().normalize()
            .equals(effectiveProject.toAbsolutePath().normalize())) {
        policyRoots.add(effectiveCwd);
    }
    policyRoots.addAll(additionalRoots);
    PathPolicy pathPolicy = PathPolicy.of(policyRoots);

    LocalFilesystemWithShell upper =
            new LocalFilesystemWithShell(
                    workspace,
                    mode,
                    pathPolicy,
                    executeTimeoutSeconds,
                    maxOutputBytes,
                    env.isEmpty() ? null : Map.copyOf(env),
                    inheritEnv,
                    localNamespaceFactory,
                    effectiveCwd);     // ← 这里从 effectiveProject 改为 effectiveCwd
    LocalFilesystem lower = new LocalFilesystem(effectiveProject, true, 10, null);

    if (projectWritable) {
        LocalFilesystem projectFs =
                new LocalFilesystem(
                        effectiveProject, mode, pathPolicy, 10, localNamespaceFactory);
        return new ProjectAwareOverlay(
                (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
    }
    return OverlayFilesystem.of(upper, lower);
}
```

**行为变化分析**：

| 场景 | cwd 未设置（默认） | cwd 已设置 |
|------|-------------------|-----------|
| `effectiveCwd` | = `effectiveProject` | = `cwd` |
| shell pwd | project（不变） | cwd |
| PathPolicy 根 | project + workspace + extras（不变） | project + workspace + cwd + extras |
| overlay lower 层 root | project（不变） | project（不变） |
| `projectWritable` 写入目标 | project（不变） | project（不变）|

关键：**overlay lower 层 root 始终是 project**（这样 `read_file("../../lib/shared.ts")` 即使相对于 cwd 解析后超出 cwd，只要在 project 范围内仍可读）。shell pwd 变为 cwd（符合用户"在这个子目录工作"的预期）。

### 1-B. cloneLocalSpecForSubagent 同步 cwd

**修改文件**：`HarnessAgentBuilderSupport.java`，`cloneLocalSpecForSubagent()` 方法

```java
private static LocalFilesystemSpec cloneLocalSpecForSubagent(LocalFilesystemSpec parent) {
    LocalFilesystemSpec spec = new LocalFilesystemSpec();
    if (parent.getProject() != null) {
        spec.project(parent.getProject());
    }
    // 新增：同步 cwd，subagent 继承父 agent 的工作目录上下文
    if (parent.getCwd() != null) {
        spec.cwd(parent.getCwd());
    }
    if (parent.getMode() != null) {
        spec.mode(parent.getMode());
    }
    spec.additionalRoots(parent.getAdditionalRoots());
    // 注意：projectWritable 不继承，ISOLATED subagent 默认不写用户项目
    return spec;
}
```

**设计决策**：subagent 继承 `cwd`（相对路径解析保持一致）但**不继承 `projectWritable`**。这是安全考量——subagent 默认不应该写入用户项目，除非 declaration 显式声明。

### 1-C. CwdAwarePathNormalizer

新建 `agentscope-harness/.../workspace/CwdAwarePathNormalizer.java`

```java
package io.agentscope.harness.agent.workspace;

import java.nio.file.Path;

/**
 * 当 cwd != projectRoot 时，将 LLM 提供的相对路径从"相对于 cwd"转换为
 * "相对于 overlay 可处理的路径"。
 *
 * <p>LLM 认为自己在 cwd 下工作。它说 "src/index.ts" 意味着 cwd/src/index.ts。
 * 但 overlay 的 lower 层 root 是 projectRoot。所以需要把
 * cwd-relative 转成 projectRoot-relative。
 *
 * <p>绝对路径不受影响（交给 PathPolicy 处理）。
 * 当 cwd == projectRoot 时，此 normalizer 是一个 identity 操作。
 */
public final class CwdAwarePathNormalizer implements WorkspacePathNormalizer {

    private final Path cwd;
    private final Path projectRoot;
    private final Path workspace;

    public CwdAwarePathNormalizer(Path cwd, Path projectRoot, Path workspace) {
        this.cwd = cwd.toAbsolutePath().normalize();
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return rawPath;

        Path parsed = Path.of(rawPath);

        // 绝对路径：原样返回，PathPolicy 负责边界检查
        if (parsed.isAbsolute()) {
            Path abs = parsed.normalize();
            // 如果在 project 内，转为 project-relative（给 overlay lower 层用）
            if (abs.startsWith(projectRoot)) {
                return projectRoot.relativize(abs).toString().replace('\\', '/');
            }
            // 如果在 workspace 内，转为 workspace-relative（给 overlay upper 层用）
            if (abs.startsWith(workspace)) {
                return workspace.relativize(abs).toString().replace('\\', '/');
            }
            // 其他绝对路径原样返回
            return rawPath;
        }

        // 相对路径：基于 cwd 解析，然后转为 projectRoot-relative
        Path resolved = cwd.resolve(parsed).normalize();

        if (resolved.startsWith(projectRoot)) {
            return projectRoot.relativize(resolved).toString().replace('\\', '/');
        }
        if (resolved.startsWith(workspace)) {
            return workspace.relativize(resolved).toString().replace('\\', '/');
        }

        // 解析后超出所有已知根：返回绝对路径，让 PathPolicy reject
        return resolved.toString();
    }
}
```

**什么时候用**：只有当 `cwd != projectRoot` 时才需要这个 normalizer。当两者相同时，现有的 `WorkspacePathNormalizer` 或 null normalizer 已经够用。

### 1-D. FilesystemTool 接入 CwdAwarePathNormalizer

**修改文件**：`HarnessAgent.java` 内部构建 `FilesystemTool` 的地方。

当前代码中 `FilesystemTool` 的 `pathNormalizer` 只在 sandbox 模式下设置（剥离 `/workspace/` 前缀）。在 local overlay 模式下传 null。

改动点：在 `HarnessAgent.build()` 中检测 `localFilesystemSpec.getCwd() != null && !cwd.equals(project)` 时，构建 `CwdAwarePathNormalizer` 并注入 `FilesystemTool`。

```java
// 在 HarnessAgent.Builder.build() 中，构建 FilesystemTool 时
WorkspacePathNormalizer pathNorm = null;
if (localFilesystemSpec != null && localFilesystemSpec.getCwd() != null) {
    Path effectiveProject = localFilesystemSpec.getProject() != null
        ? localFilesystemSpec.getProject()
        : Paths.get(System.getProperty("user.dir"));
    Path effectiveCwd = localFilesystemSpec.getCwd();
    if (!effectiveCwd.toAbsolutePath().normalize()
            .equals(effectiveProject.toAbsolutePath().normalize())) {
        pathNorm = new CwdAwarePathNormalizer(
            effectiveCwd, effectiveProject, resolvedWorkspace);
    }
}
// 传给 FilesystemTool 构造器
```

**行为变化**：

```
场景：monorepo/packages/api/ 下工作

LLM 调用: read_file("src/index.ts")
  ↓ CwdAwarePathNormalizer
  → 解析为 monorepo/packages/api/src/index.ts
  → 转为 project-relative: packages/api/src/index.ts
  ↓ overlay.read()
  → lower 层 (root=monorepo/) 读取 packages/api/src/index.ts ✓

LLM 调用: read_file("../../lib/shared.ts")
  ↓ CwdAwarePathNormalizer
  → 解析为 monorepo/lib/shared.ts
  → 转为 project-relative: lib/shared.ts
  ↓ overlay.read()
  → lower 层读取 lib/shared.ts ✓

LLM 调用: write_file("src/new.ts", "...")
  ↓ CwdAwarePathNormalizer
  → project-relative: packages/api/src/new.ts
  ↓ ProjectAwareOverlay.write()
  → 非 workspace 路径 → 写入 project 的 packages/api/src/new.ts ✓
```

### 1-E. WorkspaceContextMiddleware 增加 CWD 显示

**修改文件**：`WorkspaceContextMiddleware.java`，`buildWorkspaceParagraph()` 方法

```java
private static String buildWorkspaceParagraph(Path workspace, AbstractFilesystem fs) {
    StringBuilder sb = new StringBuilder("## Workspace\n");
    LocalFilesystemWithShell localUpper = detectLocalUpper(fs);
    Path shellCwd = localUpper != null ? localUpper.getShellCwd() : null;

    if (shellCwd != null) {
        // 检测 project root（overlay lower 层的 root）
        Path projectRoot = detectProjectRoot(fs);

        if (projectRoot != null && !shellCwd.toAbsolutePath().normalize()
                .equals(projectRoot.toAbsolutePath().normalize())) {
            // CWD != project root → monorepo 子目录模式
            sb.append("Current directory (where you are): ")
              .append(shellCwd.toAbsolutePath()).append("\n");
            sb.append("Project root (full project boundary): ")
              .append(projectRoot.toAbsolutePath()).append("\n");
            sb.append("You are working in subdirectory: ")
              .append(projectRoot.relativize(shellCwd.toAbsolutePath().normalize()))
              .append("\n");
        } else {
            // CWD == project root → 传统模式，保持现有输出不变
            sb.append("Project (the user's source tree you're assisting with): ")
              .append(shellCwd.toAbsolutePath()).append("\n");
        }
        // ... 后续 workspace、mode、overlay 描述与现有逻辑一致
    }
    // ...
}

/** 从 overlay lower 层提取 project root */
private static Path detectProjectRoot(AbstractFilesystem fs) {
    if (fs instanceof OverlayFilesystem ov
            && ov.getLower() instanceof LocalFilesystem lower) {
        return lower.getCwd();  // LocalFilesystem.cwd 就是它的 root
    }
    return null;
}
```

**行为变化**：

```
传统模式（cwd == project）的 prompt 输出不变：
  Project (the user's source tree...): /monorepo
  Workspace (...): /monorepo/.agentscope/workspace

monorepo 子目录模式的 prompt 输出：
  Current directory (where you are): /monorepo/packages/api
  Project root (full project boundary): /monorepo
  You are working in subdirectory: packages/api
  Workspace (...): /monorepo/.agentscope/workspace
  Relative paths resolve from the current directory.
  Shell commands run with `pwd` set to the current directory.
```

### 1-F. CodingAgentPreset 升级

Phase 1 完成后，`CodingAgentPreset` 可以更新使用 `cwd()` API：

```java
public static LocalFilesystemSpec filesystem(Path cwd) {
    ProjectContext ctx = ProjectDiscovery.discover(cwd, null);

    LocalFilesystemSpec spec = new LocalFilesystemSpec()
        .project(ctx.projectRoot())
        .projectWritable(true)
        .mode(LocalFsMode.ROOTED)
        .inheritEnv(true);

    // Phase 1 新增：当 cwd 不在 project root 时，设置独立的 cwd
    if (!ctx.isCwdAtProjectRoot()) {
        spec.cwd(ctx.cwd());
    }

    return spec;
}
```

### 1-G. Phase 1 变更文件清单

| 文件 | 类型 | 变更内容 |
|------|------|----------|
| `LocalFilesystemSpec.java` | 修改 | +`cwd` 字段、setter、getter；`toFilesystem()` 使用 `effectiveCwd` |
| `HarnessAgentBuilderSupport.java` | 修改 | `cloneLocalSpecForSubagent()` 同步 cwd |
| `CwdAwarePathNormalizer.java` | **新增** | cwd-relative → project-relative 路径转换 |
| `HarnessAgent.java` (build方法) | 修改 | 检测 cwd 并注入 CwdAwarePathNormalizer |
| `WorkspaceContextMiddleware.java` | 修改 | `buildWorkspaceParagraph()` 区分 cwd/projectRoot |
| `CodingAgentPreset.java` | 修改 | 使用 `spec.cwd()` |

### 1-H. 向后兼容保证

| 条件 | 行为 |
|------|------|
| 不调用 `spec.cwd(...)` | `effectiveCwd = effectiveProject`，与当前完全一致 |
| 不用 `CodingAgentPreset` | 完全不受影响 |
| 现有 `spec.project(path)` | 等价于 `cwd = project = path`（不变） |
| subagent SHARED 模式 | 不继承 cwd（不变） |
| subagent ISOLATED 模式 | 继承 cwd（新行为，但不会破坏因为之前 cwd 概念不存在） |

---

## Phase 2：ProjectContext 贯穿 + Sandbox 集成

Phase 2 是更深层的架构改动，让三层目录模型成为框架的一等公民。

### 2-A. ProjectContext 注入 RuntimeContext

**修改文件**：不改 `RuntimeContext` 类本身（它在 core 模块），利用它已有的 typed attribute 机制。

```java
// 在 HarnessAgent 内部，构建 RuntimeContext 时注入 ProjectContext
RuntimeContext rc = RuntimeContext.builder()
    .userId(userId)
    .sessionId(sessionId)
    .put(ProjectContext.class, projectContext)   // typed attribute
    .build();
```

**消费方**——任何能拿到 RuntimeContext 的 middleware / tool 都可以读：

```java
// 在 middleware 或 tool 内部
ProjectContext ctx = runtimeContext.get(ProjectContext.class);
if (ctx != null) {
    Path cwd = ctx.cwd();
    Path root = ctx.projectRoot();
    // ...
}
```

**设计理由**：
- `RuntimeContext` 在 `agentscope-core` 模块，`ProjectContext` 在 `agentscope-harness` 模块
- 不能让 core 依赖 harness → 不能给 RuntimeContext 加 `ProjectContext` 字段
- 但 `RuntimeContext.put(Class, value)` / `get(Class)` 的 typed attribute 机制**正是为这种扩展场景设计的**
- 零 core 模块改动

### 2-B. ProjectContext 在 HarnessAgent.build() 中构建

**修改文件**：`HarnessAgent.java` 的 `build()` 方法

```java
// 在 resolvedWorkspace 确定之后，构建 ProjectContext
ProjectContext projectContext = null;
if (localFilesystemSpec != null) {
    Path effectiveProject = localFilesystemSpec.getProject() != null
        ? localFilesystemSpec.getProject()
        : Paths.get(System.getProperty("user.dir"));
    Path effectiveCwd = localFilesystemSpec.getCwd() != null
        ? localFilesystemSpec.getCwd()
        : effectiveProject;
    projectContext = new ProjectContext(effectiveCwd, effectiveProject, resolvedWorkspace);
}
```

然后在 agent 的每次 `call()` 入口处，把 `ProjectContext` 注入到该次调用的 `RuntimeContext` 里：

```java
// HarnessAgent.call() 内部
if (this.projectContext != null) {
    runtimeContext.put(ProjectContext.class, this.projectContext);
}
```

### 2-C. WorkspaceContextMiddleware 使用 ProjectContext

**修改文件**：`WorkspaceContextMiddleware.java`

Phase 1 中通过 `detectLocalUpper()` 和 `detectProjectRoot()` 间接获取 cwd/projectRoot。Phase 2 改为直接从 RuntimeContext 读取 ProjectContext：

```java
@Override
public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
    // 优先从 RuntimeContext 取 ProjectContext
    ProjectContext projectCtx = ctx != null ? ctx.get(ProjectContext.class) : null;
    // 如果有 ProjectContext，用它构建更精确的 prompt
    // 如果没有（旧路径），回退到现有的 detectLocalUpper() 逻辑
}
```

### 2-D. ProjectAwareOverlay.WORKSPACE_PREFIXES 外部可配

**修改文件**：`ProjectAwareOverlay.java`

```java
// 从硬编码 Set 改为构造器注入
public class ProjectAwareOverlay extends OverlayFilesystem
        implements AbstractSandboxFilesystem {

    private final Set<String> workspacePrefixes;

    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot,
            Set<String> workspacePrefixes) {  // 新参数
        super(upper, lower);
        this.shellBackend = upper;
        this.projectFs = projectFs;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.workspacePrefixes = workspacePrefixes != null
            ? Set.copyOf(workspacePrefixes)
            : DEFAULT_WORKSPACE_PREFIXES;
    }

    // 保留旧构造器作为向后兼容
    public ProjectAwareOverlay(
            AbstractSandboxFilesystem upper,
            AbstractFilesystem lower,
            LocalFilesystem projectFs,
            Path workspaceRoot) {
        this(upper, lower, projectFs, workspaceRoot, null);
    }
}
```

同时在 `WorkspaceConstants` 里集中定义默认前缀集（取代 `ProjectAwareOverlay` 里的硬编码）：

```java
// WorkspaceConstants.java 新增
public static final Set<String> WORKSPACE_METADATA_PREFIXES = Set.of(
    MEMORY_MD,     // "MEMORY.md"
    MEMORY_DIR,    // "memory"
    AGENTS_MD,     // "AGENTS.md"
    AGENTS_DIR,    // "agents"
    SKILLS_DIR,    // "skills"
    KNOWLEDGE_DIR, // "knowledge"
    "rules", "tools.json", "subagents", "plans", ".index", ".skills-cache",
    "large_tool_results"
);
```

### 2-E. Sandbox Project Mount

**修改文件**：`SandboxFilesystemSpec.java` 及各 sandbox provider

为 sandbox 模式引入项目挂载概念，让容器内的 coding agent 可以访问 host 项目文件。

```java
// SandboxFilesystemSpec 新增
/**
 * 将 host 项目目录挂载到容器内的 /project。
 *
 * <p>挂载后容器内的文件布局：
 * <pre>
 * /workspace  — agent workspace（已有）
 * /project    — host 项目目录（新增）
 * </pre>
 *
 * <p>同时将 shell 的 CWD 设为 /project，使 LLM 的 shell 操作
 * 符合在项目目录中工作的预期。
 */
private Path projectMount;
private boolean projectMountWritable = false;

public SandboxFilesystemSpec mountProject(Path hostProjectDir) {
    this.projectMount = hostProjectDir;
    return this;
}

public SandboxFilesystemSpec projectMountWritable(boolean writable) {
    this.projectMountWritable = writable;
    return this;
}
```

各 sandbox provider (Docker, K8s, E2B, Daytona, AgentRun) 的 `SandboxContext` 构建时，如果 `projectMount` 不为 null，添加 volume mount：

```java
// DockerSandboxProvider 示例
if (projectMount != null) {
    String mountMode = projectMountWritable ? "rw" : "ro";
    volumes.add(projectMount.toAbsolutePath() + ":/project:" + mountMode);
    // shell cwd 设为 /project
    containerConfig.workingDir("/project");
}
```

**设计权衡**：
- 默认 `ro`（只读）——sandbox 的核心价值是隔离，直接写 host 文件需要显式 opt-in
- mount 到 `/project` 而不是 `/workspace`，避免与现有 workspace 冲突
- 不影响不设置 `mountProject` 的现有 sandbox 用户

### 2-F. Phase 2 变更文件清单

| 文件 | 类型 | 变更内容 |
|------|------|----------|
| `HarnessAgent.java` | 修改 | build() 构建 ProjectContext 并注入 RuntimeContext |
| `WorkspaceContextMiddleware.java` | 修改 | 优先从 RuntimeContext 取 ProjectContext |
| `ProjectAwareOverlay.java` | 修改 | WORKSPACE_PREFIXES 改为构造器注入 |
| `WorkspaceConstants.java` | 修改 | 新增 `WORKSPACE_METADATA_PREFIXES` 常量 |
| `LocalFilesystemSpec.java` | 修改 | toFilesystem() 传入可配前缀集 |
| `SandboxFilesystemSpec.java` | 修改 | 新增 `mountProject()`, `projectMountWritable()` |
| Docker/K8s/E2B/Daytona sandbox providers | 修改 | 处理 project mount volume |

---

## 三个 Phase 的依赖关系

```
Phase 0 (纯新增)
  │ ProjectContext, ProjectDiscovery, CodingAgentPreset
  │ ← 可以独立发布，不影响任何现有行为
  │
  ▼
Phase 1 (小改 + 新增)
  │ LocalFilesystemSpec.cwd(), CwdAwarePathNormalizer
  │ cloneLocalSpecForSubagent 同步, WorkspaceContextMiddleware 升级
  │ ← 需要 Phase 0 的 ProjectDiscovery（被 CodingAgentPreset 使用）
  │ ← 不调用 cwd() 时行为完全不变
  │
  ▼
Phase 2 (架构深化)
  │ ProjectContext 贯穿 RuntimeContext
  │ WORKSPACE_PREFIXES 外部可配
  │ Sandbox project mount
  │ ← 需要 Phase 0 的 ProjectContext record
  │ ← 需要 Phase 1 的 cwd/projectRoot 分离
```

## 用户场景覆盖

### 场景 1：简单项目的 coding agent

```java
// Phase 0 即可满足
HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .filesystem(CodingAgentPreset.filesystem())
    .build();

// 效果：agent 可以直接在用户项目目录生成/编辑代码
agent.call(rc, "Create a new REST controller at src/main/java/.../UserController.java");
// → 文件写入 /current/project/src/main/java/.../UserController.java ✓
```

### 场景 2：monorepo 子目录工作

```java
// Phase 1 开始支持
HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .filesystem(CodingAgentPreset.filesystem(Path.of("/repo/packages/api")))
    .build();

// 效果：
// - shell pwd = /repo/packages/api
// - read_file("src/index.ts") → /repo/packages/api/src/index.ts
// - read_file("../../lib/shared.ts") → /repo/lib/shared.ts (在 project root 内, OK)
// - write_file("src/new.ts") → /repo/packages/api/src/new.ts
```

### 场景 3：sandbox 中的 coding agent

```java
// Phase 2 开始支持
HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .filesystem(new SandboxFilesystemSpec()
        .docker("node:20")
        .mountProject(Path.of("/host/project"))
        .projectMountWritable(true))
    .build();

// 效果：容器内有 /workspace (agent存储) + /project (用户代码, rw)
// shell pwd = /project
// 代码修改直接反映到 host 文件系统
```

### 场景 4：不使用 CodingAgentPreset 的现有用户

```java
// 任何 Phase 都不影响
HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .build();
// → 默认 overlay，写入 workspace，行为与今天完全一致

HarnessAgent agent = HarnessAgent.builder()
    .model(Model.CLAUDE_SONNET)
    .filesystem(new LocalFilesystemSpec().project(somePath))
    .build();
// → 有 project overlay，写入 workspace，行为与今天完全一致
```
