# 本地补丁说明（agentscope-java）

> 本地维护的 `2.1.0-SNAPSHOT`，供 titan-admin-ai 打包使用。本文档仅作本地留存参考，**不提交**、不进 PR、不进官方文档站。

## 当前状态（2026-10-28 晚）

- **main** = `4100c864c` = `origin/main` 纯净版（2026-10-28 已 reset，含 #3797 skill 资源对齐重写）
- **pr-disable-knowledge-context** = `000882402` = 最新 `origin/main`（`4100c86`）+ 补丁① + merge commit。领先 origin/main 12 个提交（11 cherry-pick + 1 merge）
- 备份 tag：`backup/pr-branch-before-20261028`（旧 merge 版 08e279e4b）、`backup/pr-update-before-20261028`（cherry-pick 中间版 adfc36070）、`c32e280d0`（a543f99 基线版，仍可达）
- ~~`patch-compaction-events` 分支~~ 已于 2026-10-27 删除（CompactionStartEvent 补丁彻底放弃）

```
# 本地分支（2026-10-28 晚状态）
main（= origin/main，纯净）:
4100c864c  fix(harness): align skill resources and execution guidance (#3797)
a543f993c  fix(dataagent): remove the public JWT fallback secret (#3566)

pr-disable-knowledge-context（PR 源，待 push）:
000882402  Merge main (4100c86) into pr-disable-knowledge-context  ← 冲突解决
c32e280d0  refactor(harness): use setter for disableKnowledgeContext per review feedback
  + 10 个 cherry-pick commit（fork 48f7257ae..6de7d10d7 的内容，-x 标注原始 SHA）
  a543f993c  官方 main
```

## 补丁①：disableKnowledgeContext()

**净变更**（`git diff origin/main 000882402 -- agentscope-harness docs/v2`）：7 文件

| 文件 | 改动 |
|------|------|
| `HarnessAgent.java` | +15 行：`disableKnowledgeContext()` builder 方法 + 子 agent 传播（两处） |
| `HarnessAgentBuilderSupport.java` | +4 行：`build()` 时 `markdownMw.setDisableKnowledgeContext(...)` |
| `WorkspaceContextMiddleware.java` | +22/-3：字段 + setter/getter；`collectWorkspaceMaterials` 关闭时 `knowledgeContent=""`/`knowledgeFiles=List.of()`；`buildGuidance` 关闭时不输出 Domain Knowledge 引导段 |
| `DisableKnowledgeContextSubagentPropagationTest.java` | +156：3 个测试（子 agent 传播） |
| `WorkspaceContextMiddlewareKnowledgePromptTest.java` | +177：5 个测试（引导段/KNOWLEDGE.md/文件目录注入与省略） |
| `docs/v2/en\|zh/docs/harness/workspace.md` | 各 +1：opt-out 表新增 `disableKnowledgeContext()` 行 |

### 行为定义

- **关闭时**：`knowledge/KNOWLEDGE.md` 不注入、`knowledge/` 文件目录不注入、`## Domain Knowledge` 引导段不输出
- **不受影响**：`AGENTS.md`、`MEMORY.md`、memory 工具、`additionalContextFile`
- **子 agent 传播**：主 agent 设置后所有子 agent 自动继承

## 2026-10-28 当日操作记录

1. **main 恢复纯净**：`reset --hard origin/main`（两次：先 a543f99 后 4100c86，期间上游又前进了 #3797）
2. **PR 分支重建**：cherry-pick fork 的 11 个补丁 commit（`48f7257ae..6de7d10d7`，保留 fork commit 身份）到最新 main，解决 WCM 冲突（保留 fork 短路逻辑 + 采纳上游新结构）→ c32e280d0，10/10 测试 + spotless 通过
3. **再次 merge main(4100c86)**：#3797 重写 WCM（host/sandbox 工作区语义、AGENTS.md 适用性文案、KNOWLEDGE_FILES_GUIDANCE 措辞、buildWorkspaceParagraph 加 rc 参数），与补丁① 无冲突自动合并成功；仅 docs/v2 workspace.md 冲突（#3797 改了材料说明段落，fork 补丁改了 opt-out 表）→ 采纳 main 新文案 + 重新加回 disableKnowledgeContext opt-out 行 → merge commit 000882402
4. **验证**：补丁测试 10/10 + WCM 相关全量 75/75（含 #3797 新增的 ContextPipelineV2Test 15、CompactionMiddlewareTest 15、SandboxPromptTest 14 等）+ spotless BUILD SUCCESS

## 已知问题

1. **fork 远程分支还在旧 head `6de7d10`**：本地 push 鉴权失败（token 过期/未配置）。**需用户 force-push**：
   ```
   git push --force-with-lease fork pr-disable-knowledge-context
   ```
   （fork 与上游同仓库，GitHub 拒绝普通 push，必须 force；`--force-with-lease` 防覆盖他人更新）。push 后 PR #3287 冲突消失
2. **harness 构建依赖本地 core 仓库**：构建 harness 前先 `mvn -o install -pl agentscope-core -DskipTests`
3. **main 若需含补丁的本地构建版**：当前 main 是纯净的（用户要求）。titan-admin-ai 打包若需补丁①，从 `pr-disable-knowledge-context` 取 jar 或临时 `git merge pr-disable-knowledge-context` 后构建

## 下次同步上游时

- 以 PR 分支 `pr-disable-knowledge-context`（`000882402`）为权威补丁源
- 上游继续前进后：`git checkout pr-disable-knowledge-context && git merge origin/main`（冲突解决原则：保留 disableKnowledgeContext 短路 + 采纳上游新结构/文案；文档冲突时采纳 main 新文案 + 重新加回 opt-out 行）
- 构建前 `mvn -o install -pl agentscope-core -DskipTests`；格式 `mvn -o spotless:check`
- 若 #3287 已合并 → main 保持 `reset --hard origin/main`（已含官方补丁）+ 删 PR 分支
