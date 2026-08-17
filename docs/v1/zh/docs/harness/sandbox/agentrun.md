# AgentRun 沙箱后端

[AgentRun](https://help.aliyun.com/zh/functioncompute/fc/developer-reference/api-agentrun-2025-09-10-createsandbox)（阿里云函数计算 FC 3.0 Sandbox API，版本 `2025-09-10`）是阿里云提供的托管沙箱服务。`agentscope-harness` 通过 `io.agentscope.harness.agent.sandbox.impl.agentrun.AgentRunFilesystemSpec` 接入该后端，与 Docker / Daytona / E2B / Kubernetes 并列，可在 `HarnessAgent#filesystem(...)` 中直接声明使用。

实现要点：
- **数据面优先**：调用 `https://{accountId}.agentrun-data.{region}.aliyuncs.com` + `X-API-Key` + `X-Acs-Parent-Id` 鉴权，不引入完整 Aliyun OpenAPI SDK；
- **`sandboxId` 由 `sessionId` 派生**：使用 SHA-256 + Crockford Base32 输出 26 字符（ULID 形状）。利用 AgentRun 允许自定义 sandboxId 的特性，把「销毁后同 id 重建」等价于 resume；
- **执行通道走 MCP**：复用 `io.agentscope.core.tool.mcp.McpClientBuilder.streamableHttpTransport(...)`，调用模板预先启用的 `process_exec_cmd` / `read_file` / `write_file`；
- **持久化默认 NAS-first**：`workspaceRoot` 指向 NAS 挂载时，`AbstractBaseSandbox` 的 4-分支起始命中 Branch A，`doPersist/Hydrate` 退化为 no-op；无 NAS 时回退到 tar-via-MCP（与 Daytona/E2B TAR 同形）。

## 1. 何时选用 AgentRun

| 场景 | 推荐后端 |
|------|---------|
| 已使用阿里云 / 业务侧资源在中国大陆区域、需要低延迟 | **AgentRun** |
| 需要按沙箱实例独立挂载 NAS/OSS、文件自动持久、不想自己管 tar 快照 | **AgentRun**（NAS-first） |
| 单进程开发、本地有 Docker daemon | Docker |
| 多副本但暂无 K8s 集群、想要托管 sandbox-as-a-service | Daytona / E2B / **AgentRun** |
| 已有自建 Kubernetes 集群 | Kubernetes |

AgentRun 的差异化优势在于**实例级 NAS/OSS 动态挂载**，让工作区文件直接落到云上托管存储，省去手动管理 `OssSnapshotSpec` tar 包的开销；同时仍可叠加 harness 的 `SandboxSnapshotSpec` 做**可移植冷备**（见 §6）。

## 2. 前置准备（必读）

1. **AgentRun 模板**：在 [AgentRun 控制台](https://help.aliyun.com/zh/functioncompute/fc/)创建 Template，`containerConfiguration` 选择目标运行环境（默认基于 Ubuntu）。
2. **激活 MCP 工具**：通过 `ActivateTemplateMCP` 在模板上启用 `process_exec_cmd` / `read_file` / `write_file` 三件套——adapter 仅使用这三个工具。
3. **凭据**：准备主账号 ID（`X-Acs-Parent-Id`）和数据面 API Key（`X-API-Key`）；adapter 不签名 ACS3，直接走 API Key。
4. **RAM 角色权限**：模板执行角色需具备访问 NAS / OSS 的读写权限；具体策略参考阿里云文档「[Sandbox 支持实例级别动态挂载 OSS](https://help.aliyun.com/zh/functioncompute/fc/sandbox-supports-instance-level-dynamic-mount-of-oss-test-invitation)」。
5. **NAS 文件系统（推荐）**：准备一个与沙箱**同地域**、同 VPC 可达的 NAS 文件系统，记录 `serverAddr`；或准备**标准存储**、**同地域**的 OSS Bucket。

## 3. 最佳实践配置（NAS-first，核心推荐）

NAS 模式下,工作区文件落到 NAS 实例,沙箱销毁/重建之间天然持久,**无需 Snapshot**。

```java
import io.agentscope.harness.agent.sandbox.impl.agentrun.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.sandbox.IsolationScope;

AgentRunNasMountConfig nas = new AgentRunNasMountConfig()
    .setServerAddr("12345abc-xxx.cn-hangzhou.nas.aliyuncs.com")
    .setMountDir("/mnt/nas")     // 必须以 /home、/mnt 或 /data 开头
    .setRemotePath("/")
    .setEnableTLS(false);

AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec()
    .setApiKey(System.getenv("AGENTRUN_API_KEY"))
    .setAccountId(System.getenv("ALIYUN_ACCOUNT_ID"))
    .setRegion("cn-hangzhou")
    .setTemplateName("agentscope-default")
    .setMcpServerUrl(System.getenv("AGENTRUN_MCP_URL"))   // 必填,见 §8
    .setNasConfig(nas)
    .setWorkspaceRoot("/mnt/nas/workspace")               // ★ 工作区落在 NAS 内
    .setSandboxIdleTimeoutSeconds(1800)
    .isolationScope(IsolationScope.SESSION);

HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model(model)
    .filesystem(spec)
    .build();
```

行为说明：
- `workspaceRoot` 以 `nasConfig.mountDir` 为前缀,adapter 自动判定 `workspaceOnNas=true`,并对 `Sandbox#start()` 的探测分支返回 true（**Branch A 常态命中**）。
- 默认 `snapshotSpec=NoopSnapshotSpec`,`AbstractBaseSandbox.stop()` 内置 short-circuit,不会触发 `doPersistWorkspace`,仅在 NAS 模式下显式 `mcp.exec("sync", 5)` 让 ossfs/NFS 落盘。
- `sandboxIdleTimeoutSeconds`(默认 1800)是 AgentRun 侧的闲置回收阈值,超时后实例自动销毁;adapter 在下次 `start()` 时通过同 id 重建恢复语义。
- `sessionId` 与 `sandboxId` 的映射:`AgentRunSandboxClient#deriveSandboxId` 对 `sessionId` 做 SHA-256 后截取 26 字符 Crockford Base32 输出,匹配 AgentRun 公开示例 ULID 形状,**同 sessionId → 同 sandboxId**。

## 4. 退路方案 A:无 NAS,纯 tar 快照

如果你尚未开通 NAS,或仅做 demo,可以省略 `nasConfig`/`ossMountConfigs`,工作区写到沙箱临时盘:

```java
AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec()
    .setApiKey(...)
    .setAccountId(...)
    .setRegion("cn-hangzhou")
    .setTemplateName("agentscope-default")
    .setMcpServerUrl(...)
    .setWorkspaceRoot("/home/agentscope/workspace")
    .snapshotSpec(new OssSnapshotSpec(...));     // ← 必须显式挂 SnapshotSpec
```

退路模式的代价:
- `doPersistWorkspace` 在 `stop()` 时通过 MCP 远程执行 `tar -cf - -C <root> . | base64 -w0`,再走 `OssSnapshotSpec` 上传——大工作区(>100MB)上耗时显著;
- `doHydrateWorkspace` 在 `start()` 时分块 base64 写入 `/tmp/agentscope-ws.b64` 后再 `python3 -c "tar xf -"` 解压,链路较长。

> 仅在 NAS/OSS 挂载暂时不可用时使用,生产环境优先选 §3。

## 5. 进阶方案 B:NAS 运行时 + OSS Snapshot 冷备

NAS 提供低延迟的实时读写,但备份/迁移/审计场景里仍可能希望工作区有**版本化、跨集群可携带**的 tar 归档。可以同时配置:

```java
AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec()
    .setApiKey(...).setAccountId(...).setRegion("cn-hangzhou")
    .setTemplateName("agentscope-default")
    .setMcpServerUrl(...)
    .setNasConfig(nas)
    .setWorkspaceRoot("/mnt/nas/workspace")
    .snapshotSpec(new OssSnapshotSpec(                  // 与 NAS 不同的 bucket / key prefix
        ossClient,
        "agentscope-snapshots",
        "agentrun/workspaces/"));
```

注意:
- **快照 bucket 必须与挂载 bucket 隔离**(或至少 key prefix 错开),避免循环引用;
- adapter 在 `stop()` 时:先 `mcp.exec("sync", 5)` 让 NAS 落盘 → 再触发上层 `SnapshotSpec` 的 tar 持久化;
- **next start()** 优先走 NAS(Branch A);若 NAS 卷不可达(Branch B/C),从 OSS Snapshot 恢复。

## 6. AgentRun 原生持久化 vs `SandboxSnapshotSpec`

| 维度 | AgentRun 原生 NAS/OSS 挂载 | harness `SandboxSnapshotSpec` |
|------|--------------------------|------------------------------|
| **定位** | **运行时存储层**(挂载到文件系统的 mount point) | **可移植归档接口**(打包 tar 到外部 KV/对象存储) |
| **延迟** | 接近本地盘(NFS / ossfs FUSE) | 取决于 spec(OSS/Redis/Local) |
| **触发时机** | 每次写文件实时同步 | 仅在 `Sandbox#stop()` 整体打包 |
| **粒度** | 文件级 | 工作区级 tar |
| **跨集群可携带** | NAS 跨 region 受限;OSS 可跨 region 复制 | tar 任何 spec 后端都能读取 |
| **依赖资源** | NAS 文件系统 / OSS Bucket(同地域、同 VPC 可达) | 任何 KV/对象存储 |
| **耦合度** | 与 AgentRun 模板配置强耦合 | adapter 无关、跨后端可复用 |

**三种组合决策树**:

```
question: 你需要工作区跨「沙箱销毁/重建」恢复吗?
├── 否(短时任务、CI 场景) → NoopSnapshotSpec, 无 NAS  → §4 退路模式纯运行时
├── 是 + 单一 region/账号 + 低延迟优先 → §3 NAS-only
└── 是 + 需要跨 region 备份 / 审计 / 离线分析 → §5 NAS + OSS Snapshot
```

## 7. 限制清单

- **单实例 ≤ 5 个 OSS 挂载点**(`AgentRunSandboxClientOptions.MAX_OSS_MOUNTS`),超出 adapter 在 `validate()` 阶段抛 `SandboxConfigurationException`;
- **`mountDir` 必须以 `/home/`、`/mnt/` 或 `/data/` 开头**(AgentRun 模板侧约束),否则 `validate()` 失败;
- **OSS Bucket 必须是标准存储,且与沙箱同地域**;归档/低频存储不支持(平台限制,adapter 不主动校验);
- **沙箱实例最小内存 ≥ 512 MiB**(AgentRun 模板侧;adapter 不校验);
- **`StopSandbox` 在 AgentRun 侧是终态**,不可恢复——adapter **不调用** `StopSandbox`,而是通过 `DeleteSandbox` + 同 `sandboxId` 重建模拟 resume 语义;
- **MCP URL 必填**:adapter **不从 `GetSandbox` 响应自动发现** MCP URL,需要从控制台/控制面查到完整 URL 后填入 `setMcpServerUrl(...)`(后续若 AgentRun 暴露 `accessEndpoint`,会升级为自动发现);
- **`SandboxIdleTimeoutSeconds`**:超时后实例被回收,任何未 sync 到 NAS 的临时文件丢失;NAS 模式下 adapter 在 `stop()` 显式 `sync`,但建议把关键文件写到 `workspaceRoot` 子目录里。

## 8. 排错速查

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| `AgentRun API key is required` | `setApiKey` 未调用或环境变量未注入 | 检查 `AGENTRUN_API_KEY` |
| `AgentRun MCP server URL is required` | `setMcpServerUrl` 未配置 | 从 AgentRun 控制台拷贝完整 MCP URL,填入 spec |
| `ossConfigs[i].mountDir must start with one of [/home/, /mnt/, /data/]` | OSS/NAS 挂载点路径不合法 | 改为合规前缀 |
| `AgentRun supports at most 5 OSS mounts ... got N` | 超出单实例 OSS 挂载点上限 | 合并或拆分多个 sandbox |
| `AgentRun HTTP 403 ...` | RAM 角色对 OSS/NAS 权限不足,或 API Key 失效 | 检查 RAM Policy / 重新签发 Key |
| `AgentRun HTTP 409 ... / sandbox already exists` | 同 sandboxId 之前未清理(idle timeout 内仍存活) | adapter 会自动复用,如人工误操作请等待 idle 超时或显式 DELETE |
| `AgentRun sandbox did not become READY in time` | 模板冷启动 > 5 min(默认),或资源紧张 | 调大 `setReadTimeoutSeconds`,或换更轻量模板 |
| MCP `process_exec_cmd` 失败 | 模板未激活 MCP 工具 | 通过 `ActivateTemplateMCP` 启用 `process_exec_cmd` / `read_file` / `write_file` |
| NAS 挂载点存在但 `cat /mnt/nas/x` 返回空 | RAM 权限不全 / 跨 region / 跨 VPC 不可达 | 用阿里云控制台「沙箱诊断」+ `GetSandbox` 排查 |

**观察沙箱状态**:adapter 在 `start()` 时调用 `GetSandbox` 轮询,若需要在外部观察,可用同 sandboxId 直接调用:
```
GET https://{accountId}.agentrun-data.{region}.aliyuncs.com/2025-09-10/sandboxes/{sandboxId}
   X-API-Key: ...
   X-Acs-Parent-Id: {accountId}
```
返回的 `status` 字段包含 `READY` / `RUNNING` / `FAILED` 等。

## 9. 延伸阅读

- [Sandbox](./index.md) — 沙箱总体设计与隔离范围
- [Filesystem](../filesystem.md) — 三种 filesystem 模式与选型
- [AgentRun 官方文档 — CreateSandbox](https://help.aliyun.com/zh/functioncompute/fc/developer-reference/api-agentrun-2025-09-10-createsandbox)
- [AgentRun 官方文档 — 实例级 OSS/NAS 动态挂载](https://help.aliyun.com/zh/functioncompute/fc/sandbox-supports-instance-level-dynamic-mount-of-oss-test-invitation)
