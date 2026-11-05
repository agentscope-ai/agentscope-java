---
title: "持久归档与材料恢复"
---

`JevContextArchive` 保存上下文压缩边界的消息快照，并按用户、Agent、会话作用域恢复材料。适合后续需要回看证据、核查压缩决定或取回旧工具结果的任务。

## 示例场景：压缩后还需要核对旧文件证据

Agent 已把过时文件内容移出当前上下文，后来用户要求说明之前读到什么。重新执行工具可能读到新版本内容，重放写工具还可能产生重复副作用。

归档保存当时的消息快照。恢复时读取这份材料，不重新执行工具，也不直接覆盖正在推进的 Agent 历史。

## 1. 选择归档存储

```java
import io.agentscope.extensions.judge.jev.context.FileJevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextArchive;

var archive = new FileJevContextArchive(archiveRoot, 20_000_000);
```

`archiveRoot` 是宿主提供的授权存储目录，第二个参数限制单份归档字节数。文件实现按作用域哈希隔离、原子发布，并在恢复时校验内容；支持 POSIX 的文件系统使用私有目录和文件权限。

需要共享存储时，可使用 `StoreJevContextArchive(baseStore, maxBytes)`。它复用 BaseStore 命名空间和 CAS 创建语义；内存 Store 仅适合测试，多副本生产环境应提供持久共享后端。

## 2. 采用压缩前写入，保存返回引用

把 `archive` 传给 [JevContextCompactor](/v2/zh/jev/guides/context-compaction-api)。ENFORCE 采用压缩前会先归档，写入失败时不采用候选；SHADOW 不写归档。

应用从 Plan 或 `context_compaction` 决策记录取得归档引用，连同认证后的作用域保存。引用是恢复入口，不是允许模型指定任意文件路径的参数。

## 3. 在原作用域恢复消息

```java
var scope = new JevContextArchive.Scope(userId, agentId, sessionId);
var pending = archive.restore(scope, archiveReference);
var observed = pending.doOnNext(previous ->
    System.out.println("恢复消息数：" + previous.size()));
```

这些身份来自宿主认证与调用上下文；`archiveReference` 来自先前成功归档。应用可以向用户展示旧证据，或按自己的策略把所需材料加入后续输入，不应直接用旧快照覆盖压缩之后新产生的历史。

归档包含消息 ID、时间、工具参数、结果和元数据，需要按原会话数据管理 ACL、保留期与存储保护。未知作用域、损坏内容或不存在的引用都会失败，不能回退为读取任意路径。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevContextCompactionExample
```

用例在临时目录归档压缩前的 6 条消息，保留当前测试证据和用户约束，再用同一 user/agent/session 恢复。没有重新派发历史工具；终端会打印归档路径。[完整源码](/examples/jev/source/JevContextCompactionExample.java.txt)

## 恢复范围

归档保存的是本次压缩边界收到的快照。如果更早已被其他摘要或卸载机制改变，不能据此声称恢复了最初完整对话。完整历史仍以宿主 transcript/offload 记录为准。

存储 I/O 开始后，取消可能留下未采用的归档，由宿主管理清理策略。只需同一进程内的计划恢复时，可使用 [JevContextPlanner.Plan.restore()](/v2/zh/jev/guides/context-planner-api)；它不是本页的持久存储接口。
