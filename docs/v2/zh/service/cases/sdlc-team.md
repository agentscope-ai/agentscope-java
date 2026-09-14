---
title: "研发提效：从 GitHub Issue 到 PR 合并"
description: "使用全 Hosted Team 完成需求分析、实现、Review、CI、返工与审批。"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

让一个全 Hosted 团队完成“订单查询增加状态筛选与分页”：从 GitHub Issue 读取需求，开发、提交 PR、Review、运行 CI、修复问题，最后 Approve 并合并。Leader、Developer、Reviewer 和 QA 都运行在 Runtime Host 上，可使用同一种或不同的 Coding Agent provider。

## 目标与准备

需要已部署的 Service、可用 Runtime Host、已登录的 provider、Git、GitHub CLI 和 JDK 17+。使用专门的练习仓库，准备开发者与独立审查者身份；项目管理员配置分支规则、必需检查和合并权限。Hosted 身份与 GitHub 身份分别配置，创建多个 Agent 不会自动创建多个 GitHub 账号。

下载以下文件，去掉末尾 `.txt`，在练习仓库中放置：

| 下载 | 保存位置与用途 |
| --- | --- |
| [OrderQuery.java](/examples/service/sdlc-team/OrderQuery.java.txt) | 仓库根目录，待实现的查询函数 |
| [OrderQueryTest.java](/examples/service/sdlc-team/OrderQueryTest.java.txt) | 仓库根目录，固定验收检查 |
| [Issue 需求](/examples/service/sdlc-team/issue.md.txt) | 创建 GitHub Issue 时的正文 |
| [CI workflow](/examples/service/sdlc-team/ci.yml.txt) | `.github/workflows/ci.yml` |

样例是订单接口背后的 Java 查询逻辑，不包含 HTTP 服务。初始实现故意缺少筛选和分页：五项固定检查中三项失败；需求还要求 Developer 增加边界测试。将 `out/` 写入练习仓库的 `.gitignore`，提交样例与 CI，再创建需求 Issue。初始 CI 失败是待实现功能的基线。

本地编译与验收命令为：

```bash
mkdir -p out
javac --release 17 -d out OrderQuery.java OrderQueryTest.java
java -cp out OrderQueryTest
```

样例基线和参考修复已在本地验证；以下 GitHub 与 Team 流程需要在配置好的仓库实际执行。

## 1. 建立四个 Hosted 角色

按[Hosted 接入](/v2/zh/service/connect-hosted-agent)创建四个 Agent，逐个验证任务执行和协作工具。Developer 需要推送与创建 PR 的能力；Reviewer 需要读取代码和提交 Review；QA 需要读取 Actions 状态与日志；合并使用项目授权的身份。不同 GitHub 身份应通过隔离的 Host 用户、运行环境或工具凭据配置，避免多个进程共用一个会被切换的 CLI 登录状态。

| 角色 | 指令重点 | 交付 |
| --- | --- | --- |
| Leader | 分析需求、委派、跟踪返工；必要工作结束后明确完成协调节点 | 验收清单、任务图、最终交付索引 |
| Developer | 保留原验收检查，实现功能并补边界测试；不自行承担最终 Review | 分支、提交、PR、本地测试日志 |
| Reviewer | 针对实际提交检查 diff、正确性与测试；逐条跟踪问题 | 带提交标识的 Review 与复核结论 |
| QA | 在自己的 checkout 验证同一提交，检查 CI 及新增测试 | 命令、退出码、CI 链接、失败证据 |

在 **DESIGN → Teams** 选择 Hosted Leader，添加另外三个成员。团队 Instructions 可以直接使用：

```text
围绕输入的 GitHub Issue 完成交付。Leader 先明确验收要求，再委派 Developer。
PR 创建后，委派 Reviewer 和 QA 对同一个 head commit 检查。
发现问题时明确安排返工，更新提交后重新检查受影响的测试和 Review。
只用真实命令、文件、Review 与 CI 记录作为证据；标明对应提交。
缺少凭据、权限或关键需求时报告具体阻塞。完成约定交付后结束协调节点。
```

## 2. 从 GitHub Issue 发起工作

在 **WORK → Issues** 创建工作，填写 GitHub Issue URL、仓库 `OWNER/REPO`、目标分支、测试命令、验收标准以及“交付到可合并 PR”或“包括合并”的授权范围，选择该 Team 并要求人工验收。Leader 通过 GitHub 工具读取原始 Issue，记录链接和编号。

这条最小路径不要求先部署事件桥接。已有 GitHub Work Source 时，可以使用同步得到的 Service Issue；当前适配器处理 Issue 与评论同步，PR Review 和 CI 仍由 GitHub 工具查询，不因同步 Issue 自动接通整个流程。

在各自任务环境中，将 `REPO`、`ISSUE_NUMBER`、`BASE_BRANCH` 等变量设置为上述实际值。先确认当前 GitHub 身份：

```bash
gh auth status
gh issue view "$ISSUE_NUMBER" --repo "$REPO" --json number,title,body,url,comments
```

Leader 将筛选先于分页、空筛选、无匹配、大页码、非法参数、不修改输入等要求整理为检查表，再发给 Developer。

## 3. 实现并创建 PR

Developer 在 Host 分配的任务目录中克隆练习仓库，创建本次工作专属分支。Host 不会自动把管理员本机打开的仓库作为输入。Reviewer 和 QA 使用各自 checkout，通过 Git 提交共享代码，通过 Comment / Artifact 共享报告。

执行原测试记录基线，完成实现和新增测试后记录新日志、退出码与提交。创建 PR 前，把需求、修改、测试结果和原 Issue 链接写到 `pr-body.md`。需要合并后自动关闭 Issue 时，在正文写入实际的 `Closes #编号`。

```bash
git push -u origin "$FEATURE_BRANCH"
gh pr create --repo "$REPO" --base "$BASE_BRANCH" --head "$FEATURE_BRANCH"   --title 'feat: filter and paginate order queries' --body-file pr-body.md
```

保存返回的 PR URL。重试前先查询已有 PR，避免相同分支重复创建。[GitHub CLI 的 PR 创建说明](https://cli.github.com/manual/gh_pr_create)列出了目标分支与正文文件参数。

## 4. Review、CI 与返工

Reviewer 与 QA 获取 PR 后，先记录其 head SHA，再分别 checkout 和检查：

```bash
gh pr view "$PR_URL" --json headRefOid,url,baseRefName
gh pr checkout "$PR_URL"
git rev-parse HEAD
gh pr diff "$PR_URL"
gh pr checks "$PR_URL" --required
```

核对本地 HEAD 与待审提交一致。QA 在该提交上执行测试，并查看 GitHub 对应检查的日志与执行版本。分页测试要覆盖 `Integer.MAX_VALUE`，避免 `(page - 1) * size` 的整数溢出。

有问题时，Reviewer 将具体文件、问题和验收要求写入 `review.md`，提交 Request changes；Leader 读取反馈并安排新的成员工作。**Service Inbox 的 Request changes 不会自动启动返工**。

```bash
gh pr review "$PR_URL" --request-changes --body-file review.md
```

Developer 推送修订后，更新 head SHA，重新检查受影响的内容。`gh pr checks --required` 只查看仓库配置的必需检查；没有配置必需检查不能作为测试通过。检查仍在等待、被取消或缺失时都不能宣称交付已通过。[检查命令说明](https://cli.github.com/manual/gh_pr_checks)。

## 5. Approve、合并与验收

Reviewer 在独立审查身份下复核最终提交，再提交 GitHub Approve：

```bash
gh pr review "$PR_URL" --approve --body-file review.md
```

Approve 使用的报告应包含最终提交与复核证据；具体行为见[Review 命令说明](https://cli.github.com/manual/gh_pr_review)。需要审批关口时可用 Workflow 包装 Team，但 Service 的工具批准或 Issue 验收不能替代 GitHub Review。

当工作授权包含合并、当前提交的必需 CI 和 Review 满足仓库规则时，由授权执行者合并。`REVIEWED_SHA` 是已完成检查的提交，不能在合并前临时取最新值绕过复核：

```bash
gh pr merge "$PR_URL" --squash --match-head-commit "$REVIEWED_SHA"
```

若仓库使用合并队列，继续观察实际合并结果；命令被接受不等于已合并。新提交、冲突或规则未满足时回到检查阶段，不使用管理员绕过选项。[合并命令说明](https://cli.github.com/manual/gh_pr_merge)。

Leader 最终交付原 Issue、PR、最终提交、Review、CI、合并状态和未完成项。到 [Inbox](/v2/zh/service/inbox)核对结果；若只授权到可合并 PR，交付中明确仍待管理员合并。

## 失败分支与事件驱动扩展

| 情况 | 处理 |
| --- | --- |
| 需求不明确 | Leader 留下具体问题，获得输入后继续 |
| CI 失败 | QA 提供执行链接与失败日志，Developer 修复后重跑 |
| Review 后 PR 有新提交 | 重新核对该提交，旧证据保留但不能覆盖新版本 |
| Host 中断 | 查询原 Task/Attempt 和远端分支，恢复前确认已有提交与 PR |
| 推送冲突或合并冲突 | 更新分支并重新测试、审查；不丢弃其他人的提交 |
| 审批身份或权限不足 | 保留代码与报告，交由有相应权限的审查者处理 |

先跑通主动查询 CI 的闭环，再接入 [Automation](/v2/zh/service/automation)：事件桥接器校验 GitHub 事件，按仓库、PR 和提交关联原工作，并用 delivery ID 去重。通用 webhook 创建的新任务不会自动恢复原 Team；必须明确关联与后续派发方式。

练习结束后保留交付记录，清理练习分支与凭据，并检查仍在运行的 Host 任务。验收至少包含一次真实返工或失败恢复，而不只是顺利通过的截图。
