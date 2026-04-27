# Sandbox 文件系统 API 设计分析

## 问题背景

当前 `HarnessAgent.Builder` 中有 4 个分散的 sandbox 专属参数：

```java
private SandboxClient<?> sandboxClient;
private SandboxClientOptions sandboxClientOptions;
private SandboxSnapshotSpec sandboxSnapshotSpec;
private SandboxManifest defaultSandboxManifest;
```

用户要开启 Docker 沙箱，需要在 Builder 上同时配置多处：

```java
HarnessAgent.builder()
    .sandboxClientOptions(new DockerSandboxClientOptions().image("python:3.12-slim"))
    .sandboxSnapshotSpec(new LocalSnapshotSpec(...))
    .defaultSandboxManifest(manifest)
    ...
```

这与"用户第一感知永远是文件系统"的核心设计原则冲突——用户通过 4 个沙箱专属参数感知沙箱，而不是通过文件系统类型。

---

## 目标

将 sandbox 配置收敛到文件系统类型的选择上，让用户通过 `abstractFilesystem()` 这一个入口完成所有配置，其余 sandbox 内部细节对用户完全隐藏。

---

## 方案对比

### 方案 A：让 `SandboxBackedFilesystem` 兼做配置载体

将 snapshot spec、manifest 等配置塞入现有的 `SandboxBackedFilesystem`，Builder 检测到
`abstractFilesystem instanceof SandboxBackedFilesystem` 后自动提取并组装 hook。

```java
HarnessAgent.builder()
    .abstractFilesystem(
        new SandboxBackedFilesystem(new DockerSandboxClientOptions().image("python:3.12-slim"))
            .snapshotSpec(new LocalSnapshotSpec(...))
    )
```

**缺点：**

- `SandboxBackedFilesystem` 同时扮演"配置描述对象"（build 时）和"运行时 session 代理"
  （call 时）两个角色，职责混乱。
- 类名是实现细节（"backed by sandbox"），不应直接暴露给用户。
- Builder 内部代码需要同时处理"用户配置的 SandboxBackedFilesystem"和"内部创建的
  SandboxBackedFilesystem"两种来源，容易混淆。

---

### 方案 B：新建用户面向的具名文件系统类型（推荐）

为每种后端引入一个用户可见的文件系统类，`SandboxBackedFilesystem` 保持为内部代理，
对用户完全不可见。Builder 识别到具名类型后，在内部自动创建代理和 hook。

```java
// 本地文件系统（默认行为）
HarnessAgent.builder()
    .abstractFilesystem(new LocalFilesystem(workspace))   // 对应已有的 LocalFilesystemWithShell

// Docker 沙箱
HarnessAgent.builder()
    .abstractFilesystem(
        new DockerFilesystem()
            .image("python:3.12-slim")
            .snapshotSpec(new LocalSnapshotSpec(...))
            .manifest(manifest)
    )
```

Builder 内部逻辑：

```
abstractFilesystem instanceof DockerFilesystem
    → 提取 options / snapshotSpec / manifest
    → 内部创建 SandboxBackedFilesystem（代理）
    → 创建 SandboxLifecycleHook
    → 将代理作为实际 backend
```

**优点：**

- 类名即含义，`LocalFilesystem` / `DockerFilesystem` 对称，用户不需要理解 Sandbox 抽象层
- `SandboxBackedFilesystem` 职责单一：纯运行时 session 代理，完全内部化
- 结构天然支持未来扩展：`RemoteFilesystem`、`KubernetesFilesystem` 等只需新增类，
  Builder API 不变

---

## 推荐方案（B）的类结构

```
AbstractFilesystem                          ← 顶层接口（对用户可见，工具层依赖此接口）
├── LocalFilesystem                         ← 本地文件系统（alias for LocalFilesystemWithShell，用户直接 new）
└── DockerFilesystem                        ← Docker 沙箱文件系统（用户直接 new，内部持有配置）
                                              实现 AbstractFilesystem 仅作为标记 / 配置载体
                                              不实际执行任何 fs 操作（由 Builder 替换为代理）

内部（用户不可见）
└── SandboxBackedFilesystem                 ← 运行时代理，由 Builder 创建，注入实际 session
```

`DockerFilesystem` 的公开 API 设计：

```java
new DockerFilesystem()
    .image("python:3.12-slim")           // Docker 镜像
    .workspaceRoot("/workspace")         // 容器内工作区根目录（可选，有默认值）
    .environment(Map<String,String>)     // 环境变量（可选）
    .memorySizeBytes(512 * 1024 * 1024L) // 内存限制（可选）
    .cpuCount(2L)                        // CPU 限制（可选）
    .exposedPorts(8080, 8443)            // 端口映射（可选）
    .snapshotSpec(new LocalSnapshotSpec(...))  // 快照策略（可选，默认 Noop）
    .manifest(manifest)                  // 初始工作区布局（可选）
```

---

## 配置参数归属分析

| 参数 | 归属 | 理由 |
|------|------|------|
| `SandboxClientOptions`（image、env 等） | `DockerFilesystem` 字段 | 描述文件系统后端的静态配置 |
| `SandboxManifest`（初始文件布局） | `DockerFilesystem` 字段 | 属于工作区初始化配置，和文件系统绑定 |
| `SandboxSnapshotSpec`（快照存储策略） | `DockerFilesystem` 字段 | 属于文件系统持久化策略 |
| `SandboxClient`（实现实例） | Builder 内部自动推导 | 实现细节，用户永远不需要感知 |
| `SandboxContext`（per-call 运行时覆盖） | 保留在 `RuntimeContext` | 动态参数，每次调用可能不同 |

结论：`HarnessAgent.Builder` 的 4 个 sandbox 专属参数可以全部消除，只保留
`abstractFilesystem()` 作为唯一入口。

---

## 实现步骤（待执行）

1. 新建 `DockerFilesystem` 类：
   - 持有 `DockerSandboxClientOptions`、`SandboxSnapshotSpec`、`SandboxManifest`
   - 提供流式 builder 风格的 setter
   - 实现 `AbstractFilesystem` 接口作为标记（所有方法抛 `UnsupportedOperationException`，
     因为 Builder 会在内部替换为真正的代理）

2. 修改 `HarnessAgent.Builder.build()`：
   - 检测 `abstractFilesystem instanceof DockerFilesystem`
   - 从中提取配置，创建 `SandboxBackedFilesystem` 代理和 `SandboxLifecycleHook`
   - 移除 `sandboxClient`、`sandboxClientOptions`、`sandboxSnapshotSpec`、
     `defaultSandboxManifest` 4 个字段和对应的 builder 方法

3. 移除 `SandboxClientOptions.createClient()` 抽象方法（该职责已不再需要对外暴露）

---

## 迁移影响

现有代码：
```java
HarnessAgent.builder()
    .sandboxClientOptions(new DockerSandboxClientOptions().image("python:3.12-slim"))
    .sandboxSnapshotSpec(new LocalSnapshotSpec(...))
    .defaultSandboxManifest(manifest)
```

迁移后：
```java
HarnessAgent.builder()
    .abstractFilesystem(
        new DockerFilesystem()
            .image("python:3.12-slim")
            .snapshotSpec(new LocalSnapshotSpec(...))
            .manifest(manifest)
    )
```
