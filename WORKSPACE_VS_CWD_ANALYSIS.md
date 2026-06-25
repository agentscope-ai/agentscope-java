# Workspace vs CWD 设计分析：agentscope-java × opencode

## 1. 核心概念对照

两个项目对"目录"的语义建模存在根本性差异：

| 概念 | agentscope-java | opencode |
|------|----------------|----------|
| **Agent 私有存储** | `workspace`（overlay 上层，默认 `.agentscope/workspace`） | `.opencode/` 配置目录（与项目分离） |
| **用户项目根** | `project`（overlay 下层，默认 `user.dir`） | `worktree`（git worktree root） |
| **当前工作目录** | 无独立概念（≈ project ≈ user.dir） | `directory`（启动 opencode 的具体目录） |
| **写入默认目标** | workspace（copy-on-write 隔离） | 项目目录（直接操作用户代码） |
| **边界保护** | 硬沙箱（SANDBOXED/ROOTED/UNRESTRICTED） | 软权限（permission prompt） |
| **项目发现** | 无（直接用 `System.getProperty("user.dir")`） | Git 自动检测（remote URL hash → 项目 ID） |

## 2. agentscope-java 现状详解

### 2.1 已有的两层 Overlay 架构

`LocalFilesystemSpec.toFilesystem()` 构建的 overlay 模型已经很完善：

```
┌─────────────────────────────────────────────┐
│ OverlayFilesystem                           │
│  ┌──────────────────────────────────────┐   │
│  │ Upper: LocalFilesystemWithShell      │   │  ← R/W，root = workspace
│  │   root = .agentscope/workspace       │   │  ← shell pwd = project
│  │   shellCwd = project (user.dir)      │   │
│  └──────────────────────────────────────┘   │
│  ┌──────────────────────────────────────┐   │
│  │ Lower: LocalFilesystem (read-only)   │   │  ← R/O fallback
│  │   root = project (user.dir)          │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

**默认行为**：读操作先查 workspace 再 fallback 到 project；写操作全部落入 workspace。

**`projectWritable(true)` 模式**：启用 `ProjectAwareOverlay`，非 workspace 元数据路径的写操作路由到 project 目录——这正是 coding agent 需要的行为，但目前**仅被 `PlanModeAutoExample` 一个示例使用**。

### 2.2 路径控制已经很精细

- `LocalFsMode.ROOTED` + `PathPolicy`：绝对路径只允许访问 project + workspace + additionalRoots
- `additionalRoots`：等价于 Claude Code CLI 的 `--add-dir`
- `WorkspacePathNormalizer`：统一处理 workspace 内的路径前缀

### 2.3 System Prompt 已区分两个目录

`WorkspaceContextMiddleware.buildWorkspaceParagraph()` 在检测到 local overlay 时输出：

```
Project (the user's source tree you're assisting with): /path/to/project
Workspace (your home base — memory, sessions, skills, runtime data): /path/to/workspace
Shell commands run with `pwd` set to the project directory.
```

当 `ProjectAwareOverlay` 激活时额外说明：

```
File tools write project files (code, configs, etc.) to the project directory.
Workspace metadata paths (memory, sessions, skills, agents, knowledge) are written to the workspace.
```

### 2.4 现存的 Gap

| Gap | 影响 | 文件位置 |
|-----|------|----------|
| **`projectWritable` 未暴露为一等公民** | 构建 coding agent 需要知道这个 buried option | `LocalFilesystemSpec:93` |
| **无项目自动发现** | 必须显式配置或依赖 `user.dir`；monorepo 子目录启动时无法识别项目根 | `LocalFilesystemSpec:289-290` |
| **CWD 和 project 是同一个东西** | 在 monorepo 中从 `packages/foo/` 启动，project=`packages/foo/`，无法感知上层 git root | `HarnessAgent:1953-1956` |
| **`cwd` 字段命名误导** | `LocalFilesystem.cwd` 实际是 fs root，不是进程 CWD | `LocalFilesystem:82` |
| **Sandbox 模式无 project 概念** | 容器化 coding agent 无法直接操作用户源码 | `SandboxBackedFilesystem` 全文件 |
| **`isWorkspacePath()` 硬编码前缀集** | 新增 workspace 目录要改代码 | `ProjectAwareOverlay:44-58` |

## 3. opencode 做对了什么

### 3.1 两层目录模型（directory vs worktree）

这是 opencode 最关键的设计——把"从哪里启动"和"项目根在哪"分开：

```typescript
// InstanceContext — 贯穿整个系统的上下文
interface InstanceContext {
  directory: string   // 启动 opencode 的具体目录（CWD）
  worktree: string    // git worktree root（项目边界）
  project: Project.Info  // 项目 metadata（ID, VCS info）
}
```

**实际效果**：

```
monorepo/                    ← worktree (git root, 权限边界)
├── packages/
│   └── api/                 ← directory (CWD, 路径解析锚点)
│       ├── src/
│       └── package.json
├── .opencode/
└── .git/
```

- 相对路径 `src/index.ts` → 解析为 `monorepo/packages/api/src/index.ts`（相对于 directory）
- 权限检查 → `monorepo/` 以内 OK，以外需 permission prompt（相对于 worktree）
- 配置发现 → 从 `directory` 向上走到 `worktree`，逐层合并 `.opencode/` 配置

### 3.2 所有工具统一的路径解析模式

```typescript
// 每个工具都是这个模式——零歧义
const instance = yield* InstanceState.context
let filepath = params.filePath
if (!path.isAbsolute(filepath)) {
  filepath = path.resolve(instance.directory, filepath)  // 永远相对于 CWD
}
```

所有 tool（read、write、edit、glob、grep、apply_patch）一致使用 `instance.directory` 解析相对路径，包括 shell tool 的 `cwd` 默认值。

### 3.3 软权限边界（非硬沙箱）

```typescript
// 判断路径是否在项目内
function containsPath(filepath: string, ctx: InstanceContext): boolean {
  if (FSUtil.contains(ctx.directory, filepath)) return true
  if (ctx.worktree === "/") return false
  return FSUtil.contains(ctx.worktree, filepath)
}
```

项目内操作 → 正常权限检查（read/edit/shell）
项目外操作 → 额外 `external_directory` permission prompt

Shell 工具甚至**用 tree-sitter 解析 bash 命令**，提取 `rm`/`cp`/`mv` 等文件操作的路径参数，逐一检查边界。

### 3.4 Git 驱动的项目身份

```
项目 ID 的优先级链：
  1. git remote URL hash（同 repo 不同 clone → 同项目）
  2. 缓存文件 <git-common-dir>/opencode
  3. root commit hash
  4. 全局 fallback ID
```

非 git 目录 → `worktree = "/"`, ID = global，权限边界退化为仅 `directory`。

### 3.5 Session 绑定目录上下文

```typescript
// 创建 session 时记录目录上下文
{
  directory: ctx.directory,        // 绝对路径
  path: relative(worktree, cwd),   // 相对于 worktree 的位置
  projectID: ctx.project.id,
}
```

允许同一项目在不同子目录有不同的 session，过滤列表时支持按 directory 筛选。

## 4. 设计提案

基于两个项目的对比分析，提出以下分层设计方案。核心思路是：**保持 agentscope-java 已有的 overlay 架构优势，引入 opencode 的 CWD / 项目根 / workspace 三层分离模型**。

### 4.1 引入 ProjectContext 三层目录模型

```java
/**
 * 三层目录模型，取代当前 project = user.dir 的单层假设。
 *
 * ┌───────────────────────────────────────────────────────┐
 * │ Layer 3: workspace (.agentscope/workspace)            │  Agent 私有存储
 * ├───────────────────────────────────────────────────────┤
 * │ Layer 2: projectRoot (git root / 项目边界)            │  权限边界 + 配置发现的终点
 * ├───────────────────────────────────────────────────────┤
 * │ Layer 1: cwd (用户启动的实际目录)                      │  路径解析锚点 + shell pwd
 * └───────────────────────────────────────────────────────┘
 */
public record ProjectContext(
    Path cwd,          // 当前工作目录（路径解析锚点，shell pwd）
    Path projectRoot,  // 项目根（git root 或显式配置，权限边界）
    Path workspace,    // agent workspace（私有存储）
    ProjectId id       // 项目身份标识
) {
    /** cwd 相对于 projectRoot 的位置，用于 session 上下文 */
    public String relativePath() {
        return projectRoot.relativize(cwd).toString();
    }

    /** 判断路径是否在项目边界内 */
    public boolean containsPath(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return abs.startsWith(projectRoot) || abs.startsWith(workspace);
    }
}
```

与现有架构的映射关系：

| 新概念 | 现有对应 | 变更 |
|--------|----------|------|
| `cwd` | `LocalFilesystemSpec.project` (≈ user.dir) | 语义不变，改名 |
| `projectRoot` | **新增** | 通过 ProjectDiscovery 自动检测 |
| `workspace` | `HarnessAgent.Builder.workspace` | 不变 |

### 4.2 ProjectDiscovery：自动发现项目根

```java
/**
 * 从 CWD 向上检测项目根，参考 opencode 的 git-centric 策略。
 * 
 * 检测优先级：
 *   1. .agentscope/project.yaml 中的显式配置
 *   2. Git root（git rev-parse --show-toplevel 或 .git 目录向上查找）
 *   3. 项目标记文件（pom.xml, build.gradle, package.json, Cargo.toml...）
 *   4. 回退到 CWD 本身
 */
public class ProjectDiscovery {

    private static final List<String> PROJECT_MARKERS = List.of(
        ".git",
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "package.json", "Cargo.toml", "go.mod", "pyproject.toml",
        "CMakeLists.txt", ".sln"
    );

    public static ProjectContext discover(Path cwd) {
        Path effectiveCwd = cwd != null ? cwd : Path.of(System.getProperty("user.dir"));

        // 1. 显式配置
        Path explicitRoot = findExplicitConfig(effectiveCwd);
        if (explicitRoot != null) {
            return buildContext(effectiveCwd, explicitRoot);
        }

        // 2. Git root
        Path gitRoot = findGitRoot(effectiveCwd);
        if (gitRoot != null) {
            return buildContext(effectiveCwd, gitRoot);
        }

        // 3. 项目标记文件
        Path markerRoot = findUpwards(effectiveCwd, PROJECT_MARKERS);
        if (markerRoot != null) {
            return buildContext(effectiveCwd, markerRoot);
        }

        // 4. 回退
        return buildContext(effectiveCwd, effectiveCwd);
    }
}
```

### 4.3 改造 LocalFilesystemSpec，支持 CWD ≠ projectRoot

```java
public class LocalFilesystemSpec {

    // 重命名：project → projectRoot（明确语义）
    private Path projectRoot;

    // 新增：cwd（可以是 projectRoot 的子目录）
    private Path cwd;

    /**
     * 设置用户的实际工作目录。相对路径解析和 shell pwd 以此为锚。
     * 当在 monorepo 子目录下启动时，cwd ≠ projectRoot。
     * 默认 = System.getProperty("user.dir")。
     */
    public LocalFilesystemSpec cwd(Path cwd) {
        this.cwd = cwd;
        return this;
    }

    /**
     * 设置项目根目录（权限边界、配置发现终点）。
     * 默认通过 ProjectDiscovery 从 cwd 自动检测。
     */
    public LocalFilesystemSpec projectRoot(Path root) {
        this.projectRoot = root;
        return this;
    }

    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory nsf) {
        Path effectiveCwd = cwd != null
            ? cwd : Paths.get(System.getProperty("user.dir"));
        Path effectiveProjectRoot = projectRoot != null
            ? projectRoot : ProjectDiscovery.discoverRoot(effectiveCwd);

        // PathPolicy 基于 projectRoot（不是 cwd），因为 projectRoot 是权限边界
        PathPolicy pathPolicy = PathPolicy.of(effectiveProjectRoot, workspace, additionalRoots);

        LocalFilesystemWithShell upper = new LocalFilesystemWithShell(
            workspace, mode, pathPolicy,
            executeTimeoutSeconds, maxOutputBytes, env, inheritEnv, nsf,
            effectiveCwd       // shell pwd = cwd（不是 projectRoot）
        );

        // 下层 fallback 基于 projectRoot（可以读到整个项目的文件）
        LocalFilesystem lower = new LocalFilesystem(effectiveProjectRoot, true, 10, null);

        if (projectWritable) {
            LocalFilesystem projectFs = new LocalFilesystem(
                effectiveProjectRoot, mode, pathPolicy, 10, nsf);
            return new ProjectAwareOverlay(upper, lower, projectFs, workspace);
        }
        return OverlayFilesystem.of(upper, lower);
    }
}
```

**行为变化**：

```
monorepo/                    ← projectRoot (overlay lower 层的 root, PathPolicy 边界)
├── packages/
│   └── api/                 ← cwd (shell pwd, 相对路径解析锚点)
│       ├── src/
│       └── package.json
├── .agentscope/
│   └── workspace/           ← workspace (overlay upper 层的 root)
└── .git/
```

- `read_file("src/index.ts")` → 相对于 cwd → `monorepo/packages/api/src/index.ts`
- `read_file("../../lib/shared.ts")` → 相对于 cwd → `monorepo/lib/shared.ts`（在 projectRoot 内，允许）
- `shell("ls")` → pwd = cwd = `monorepo/packages/api/`
- `write_file("src/new.ts", ...)` → projectWritable 时写到 `monorepo/packages/api/src/new.ts`

### 4.4 升级 WorkspaceContextMiddleware 的 Prompt

```java
private static String buildWorkspaceParagraph(
        Path workspace, AbstractFilesystem fs, ProjectContext ctx) {

    StringBuilder sb = new StringBuilder("## Workspace\n");

    if (ctx != null && ctx.cwd() != null) {
        sb.append("Current directory (where you are working): ")
          .append(ctx.cwd().toAbsolutePath()).append("\n");

        if (!ctx.cwd().equals(ctx.projectRoot())) {
            sb.append("Project root (full project boundary): ")
              .append(ctx.projectRoot().toAbsolutePath()).append("\n");
            sb.append("You are in subdirectory: ")
              .append(ctx.relativePath()).append("\n");
        }

        sb.append("Workspace (agent storage — memory, skills, sessions): ")
          .append(workspace.toAbsolutePath()).append("\n");

        // 行为说明
        sb.append("Relative paths resolve from the current directory.\n");
        sb.append("Shell commands run with `pwd` set to the current directory.\n");

        if (fs instanceof ProjectAwareOverlay) {
            sb.append("File writes to project paths go to the project directory directly.\n");
            sb.append("Workspace metadata (memory/, skills/, agents/) writes go to workspace.\n");
        }
    }
    // ... existing sandbox/remote branches ...
}
```

### 4.5 引入 CodingAgentPreset（高层快捷 API）

当前 `projectWritable(true)` 太底层，需要用户理解 overlay 机制。应提供 coding agent 专用的 preset：

```java
public class CodingAgentPreset {

    /**
     * 一行代码构建一个 coding agent 的 filesystem。
     *
     * 等价于：
     *   new LocalFilesystemSpec()
     *       .projectWritable(true)
     *       .mode(LocalFsMode.ROOTED)
     *       .inheritEnv(true)
     *       // 自动 ProjectDiscovery
     *
     * 使用场景：
     *   HarnessAgent.builder()
     *       .filesystem(CodingAgentPreset.filesystem())
     *       // 或
     *       .filesystem(CodingAgentPreset.filesystem(Path.of("/my/project")))
     */
    public static LocalFilesystemSpec filesystem() {
        return filesystem(null);
    }

    public static LocalFilesystemSpec filesystem(Path cwd) {
        return new LocalFilesystemSpec()
            .cwd(cwd)
            .projectWritable(true)
            .mode(LocalFsMode.ROOTED)
            .inheritEnv(true);
    }

    /**
     * 完整的 coding agent builder preset。
     *
     * 使用：
     *   HarnessAgent agent = CodingAgentPreset.apply(HarnessAgent.builder())
     *       .model(Model.CLAUDE_SONNET)
     *       .build();
     */
    public static HarnessAgent.Builder apply(HarnessAgent.Builder builder) {
        return builder
            .filesystem(filesystem())
            .systemPrompt(CODING_AGENT_SYSTEM_PROMPT);
    }
}
```

### 4.6 FilesystemTool 路径解析对齐 CWD

当前 `FilesystemTool` 的路径通过 `WorkspacePathNormalizer` 处理，相对路径基于 workspace root 解析。在引入 CWD 后，需要支持**相对路径基于 CWD 解析**：

```java
public class CwdAwarePathResolver implements WorkspacePathNormalizer {

    private final Path cwd;         // 相对路径解析锚点
    private final Path projectRoot; // 权限边界
    private final Path workspace;   // workspace root

    @Override
    public String normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return rawPath;

        Path parsed = Path.of(rawPath);

        if (parsed.isAbsolute()) {
            // 绝对路径直接用（PathPolicy 会做边界检查）
            return parsed.normalize().toString();
        }

        // 相对路径基于 CWD 解析
        Path resolved = cwd.resolve(parsed).normalize();

        // 如果解析后的路径在 project 内，转为相对于 project 的路径
        // （因为 overlay 的 lower 层以 projectRoot 为 root）
        if (resolved.startsWith(projectRoot)) {
            return projectRoot.relativize(resolved).toString();
        }

        // 如果在 workspace 内，转为相对于 workspace 的路径
        if (resolved.startsWith(workspace)) {
            return workspace.relativize(resolved).toString();
        }

        // 其他情况保持绝对路径，由 PathPolicy 决定是否允许
        return resolved.toString();
    }
}
```

### 4.7 为 Sandbox 模式引入 Project Mount

Sandbox (Docker/K8s/E2B) 当前完全隔离，coding agent 在容器内无法操作用户源码。可引入 project mount：

```java
public class SandboxFilesystemSpec {

    // 新增：将 host 项目目录挂载到容器
    private Path projectMount;
    private boolean projectReadOnly = true;

    /**
     * 挂载 host 项目目录到容器的 /project。
     * coding agent 可以读取/编辑用户源码。
     */
    public SandboxFilesystemSpec mountProject(Path hostProject) {
        this.projectMount = hostProject;
        return this;
    }

    public SandboxFilesystemSpec projectWritable(boolean writable) {
        this.projectReadOnly = !writable;
        return this;
    }

    // toFilesystem 时传入 sandbox config:
    //   volumes: ["/host/project:/project:rw"]
    //   shell cwd: /project
}
```

## 5. 与现有代码的兼容路径

### 不破坏的变更（Phase 0）

1. **`LocalFilesystemSpec` 增加 `cwd(Path)` 方法** — 当不调用时行为完全不变（cwd = project = user.dir）
2. **`ProjectDiscovery` 作为独立 utility** — 纯静态方法，不改变任何现有类
3. **`CodingAgentPreset` 作为新增 class** — 封装最佳实践，不动现有 API
4. **`WorkspaceContextMiddleware` 增加 cwd 显示** — 当 cwd ≠ project 时额外输出

### 需要小改的变更（Phase 1）

5. **`LocalFilesystem.cwd` 字段重命名为 `rootDir`** — 消除歧义（或保留 `cwd` 但改 Javadoc）
6. **`FilesystemTool` 支持 `CwdAwarePathResolver`** — 当 cwd ≠ projectRoot 时启用
7. **`ProjectAwareOverlay.WORKSPACE_PREFIXES` 外部可配** — 避免硬编码

### 较大变更（Phase 2）

8. **`ProjectContext` 贯穿 `RuntimeContext`** — 让 middleware、tool、prompt 都能访问三层目录信息
9. **Sandbox project mount** — 需要改各 sandbox provider 的容器创建逻辑

## 6. 总结

| 维度 | agentscope-java 现状 | opencode 做法 | 建议 |
|------|---------------------|---------------|------|
| **项目发现** | 无（user.dir 一刀切） | Git root 自动检测 | 引入 ProjectDiscovery |
| **CWD 独立性** | CWD ≡ project（不区分） | CWD ≠ worktree（明确分离） | 引入 `cwd(Path)` |
| **写入路由** | 有 `projectWritable` 但 buried | 默认写项目目录 | CodingAgentPreset 暴露 |
| **边界保护** | 硬沙箱（三级 mode） | 软权限（prompt 确认） | 保持硬沙箱 + 考虑 confirm 层 |
| **路径解析** | 相对于 workspace root | 相对于 CWD | CwdAwarePathResolver |
| **System Prompt** | 已有双目录描述 | 显示 CWD + worktree | 扩展为三层目录描述 |
| **Sandbox 项目访问** | 无 | N/A（opencode 无 sandbox） | Project mount 方案 |

**核心洞察**：agentscope-java 的 overlay 架构比 opencode 更强（支持 sandbox、remote、分布式），但在 coding agent 场景下，**CWD 和 project 的混同**导致了"用户想在当前目录生成代码"这个最常见需求被 workspace 隔离拦住了。解法不是推翻 overlay，而是：

1. 让 CWD 从 project 中独立出来（monorepo 支持）
2. 让 `projectWritable` 成为 coding agent 的默认姿态（通过 Preset）
3. 让路径解析以 CWD 为锚点（对齐 opencode 的一致性）
