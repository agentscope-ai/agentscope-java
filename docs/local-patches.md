# 本地补丁说明（agentscope-java）

> 本地维护的 `2.1.0-SNAPSHOT`，供 titan-admin-ai 打包使用。
> 本文档仅作本地留存参考，**不提交**、不进 PR、不进官方文档站。

## 当前状态（2026-10-28）

- **main** = `6cc90f50f`（merge commit）= `16cb024ae`（10-27 时的 origin/main）+ 补丁① 11 commits。本地构建用，离线可编译
- **pr-disable-knowledge-context** = `08e279e4b`（merge commit）= 最新 `origin/main`（`a543f99`，#3566）+ 补丁① + #3779 冲突解决。PR 源分支
- 备份 tag：`backup/local-main-before-20260925`、`backup/main-merge-before-20261028`（误合并到 main 的版本）、`backup/pr-branch-before-20261028`（分支 merge 版）
- ~~`patch-compaction-events` 分支~~ 已于 2026-10-27 删除

```
# 本地分支（2026-10-28 状态）
main:
6cc90f50f  Merge branch 'pr-disable-knowledge-context'
16cb024ae  官方 main（#3774）
  + 11 个补丁① commit

pr-disable-knowledge-context（PR 源）:
08e279e4b  Merge origin/main (a543f99) into pr-disable-knowledge-context  ← 冲突解决
a543f993c  官方 main 最新（#3566）
  + 11 个补丁① commit
```

**已知问题**：
1. **fork 远程分支落后**：`fork/pr-disable-knowledge-context` 还在 `6de7d10`（10-10 旧 head）。本地 push 鉴权失败（token 过期/未配置）。**需用户手动 push**：`git push fork pr-disable-knowledge-context`，push 后 GitHub PR 冲突消失
2. **PR 分支全量构建报错（上游问题）**：`SkillManageTool.java` L68/L72 引用 `SkillUtil.MAX_SKILL_NAME_LENGTH`，该类只存在于 10-27 时的 core 构建；10-28 上游 #3814 后该常量消失，最新 core 源编译不过。与补丁① 无关，等上游修复
3. **main 落后 origin/main 13 个提交**：不影响离线构建。若上游 #3287 合并 → `reset --hard origin/main`；若未合并 → 可从 PR 分支重新 merge（冲突解决参照 08e279e4b）

---

## ① disableKnowledgeContext（已合并进本地 main）

**目的**：在 `HarnessAgent.Builder` 上加细粒度开关 `disableKnowledgeContext()`，抑制知识上下文注入，但保留 `AGENTS.md` / `MEMORY.md` / 记忆工具。

**抑制内容**（开关置 true 时）：
- `## Domain Knowledge` 引导段
- `knowledge/KNOWLEDGE.md` 全文
- `Knowledge files:` 目录
- 空的 `<domain_knowledge_context>` XML 元素

**保留内容**：`AGENTS.md`、`MEMORY.md`、`<agents_context>`、`<memory_context>`、记忆工具。

**改动文件（7 个）**：
| 文件 | 改动 |
|------|------|
| `HarnessAgent.java` | builder 加 `disableKnowledgeContext()`；build() 里 6 参构造 + `setDisableKnowledgeContext()` |
| `HarnessAgentBuilderSupport.java` | 两个子代理 factory 捕获并重新应用该 flag |
| `WorkspaceContextMiddleware.java` | 非 final 字段 + setter/getter；`collectWorkspaceMaterials()` 短路；`buildGuidance()` 空则早返回 |
| `WorkspaceContextMiddlewareKnowledgePromptTest.java` | 5 个单测 |
| `DisableKnowledgeContextSubagentPropagationTest.java` | 3 个单测 |
| `docs/v2/en/docs/harness/workspace.md` | Opt-out 表加 1 行 |
| `docs/v2/zh/docs/harness/workspace.md` | 同上 |

**PR 状态**：上游 PR #3287 head `9bf5340dc`，10 条 oss-maintainer review 含 APPROVED，等官方合并。

**官方合并后操作**：
1. `git fetch origin`
2. `git reset --hard origin/main`（absorb 补丁①，丢弃本地 merge commit）
3. `git branch -D pr-disable-knowledge-context`（如分支还在）
4. 跑 harness 测试 → `mvn install -DskipTests -pl agentscope-core,agentscope-harness -am`
5. 验证 titan-admin-ai 编译
6. 更新本文档

## 下次同步上游时

- 以 PR 分支 `pr-disable-knowledge-context`（`08e279e4b`）为权威补丁源
- 上游继续前进后：`git checkout pr-disable-knowledge-context && git merge origin/main`（补丁① 区域若有冲突，参照 08e279e4b 解决方式：保留 disableKnowledgeContext 短路 + 采纳上游新逻辑）→ push 更新 PR
- 若 #3287 已合并 → main `reset --hard origin/main` + 删 PR 分支 + push 后 GitHub 侧删除
