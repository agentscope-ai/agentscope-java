---
title: "托管与混合团队：从客户需求到售前方案"
description: "直接创建 Managed Team 交付方案和 PoC 计划，再接入 External 查询与 Hosted 实施。"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

售前团队拿到一份客户需求，需要澄清问题、核对产品能力、设计方案并给出 PoC 计划。本案例先在控制台创建五个 Managed Agent，绑定知识并组成 Team；再扩展实时能力查询与 PoC 编码成员，展示 Managed、External、Hosted 如何围绕同一交付协作。

基础流程不需要开发 Agent 应用，交付是可下载、可追溯、可人工复核的方案文件。

## 准备客户需求与知识

需要已部署的 Service、可用 Managed 模型、可写文件且支持所需工具的 Environment，以及创建 Agent、Team、Memory Store 和 Issue 的权限。先按[创建 Managed Agent](/v2/zh/service/create-managed-agent)验证问答和文件交付。

下载三个纯文本资料包。其中的 Northstar Retail 和产品能力均为虚构练习材料，不是 AgentScope Service 的产品承诺：

| 资料 | 放置方式 |
| --- | --- |
| [客户需求](/examples/service/presales-team/customer-brief.txt) | 把正文作为 Issue 输入，保留 `customer-v1` 标记 |
| [产品知识](/examples/service/presales-team/product-knowledge.txt) | 按 File 标记创建 `product/capabilities.md` 与 `product/reference-case.md` 两条 Memory 文档 |
| [交付规范](/examples/service/presales-team/delivery-guide.txt) | 创建 `delivery/poc-guide.md` Memory 文档 |

客户有 80 家门店、每天约 3,000 次咨询，希望做网页客服与订单查询。数据地域、SSO 协议、峰值并发、模型预算和质量指标尚未确认。不要让 Agent 用每天咨询量推断准确峰值，或直接承诺四周生产上线。

## 1. 创建五个 Managed Agent

在 **DESIGN → Agents** 创建以下角色，Runtime 选择 Managed，配置模型和默认 Environment。可以先使用同一个已经验证的模型，再按工作需要调整。

| 角色 | Instructions 中的职责 | 知识与输出 |
| --- | --- | --- |
| 方案负责人 / Leader | 澄清目标、委派、整合、结束协调 | 全部资料；统一方案与交付索引 |
| 需求分析 | 区分明确要求、推断与缺失条件 | 客户输入、交付规范；需求与问题清单 |
| 产品方案 | 把需求对应到已确认产品能力 | 产品知识；能力映射、架构与限制 |
| 交付规划 | 制定 PoC 阶段、依赖与验收方法 | 产品与交付知识；计划和验收表 |
| 质量复核 | 核查关键承诺、来源与前后矛盾 | 全部资料；问题与复核结论 |

在 **Runtime → Session defaults → Default memory stores** 绑定角色需要的 Store，保存后通过新会话检查 `memory_store_list` / `memory_store_read` 的实际读取结果。只回答正确但没有来源，不能证明知识绑定生效。资源配置见[Memory](/v2/zh/service/memory)和[Environment](/v2/zh/service/environments)。

各角色都加入以下共同要求：

```text
只依据输入、实际读取的知识或真实工具结果作关键判断。
每项关键结论标注文档路径和版本；缺少依据时列为待确认。
区分现有能力、需要集成的能力和本次不支持的能力。
给出 PoC 建议和验收方法，不把建议指标写成客户已确认指标。
文件交付必须来自实际创建和上传；无法交付时明确说明。
```

## 2. 创建 Team 并验证委派

在 **DESIGN → Teams** 选择方案负责人为 Leader，添加其他四名成员。团队指令可使用：

```text
先委派需求分析，再根据分析安排产品方案和交付规划。
把方案交给质量复核检查；遇到事实矛盾或缺失来源时安排修订。
通过持久评论和 Artifact 共享结果，不假定成员文件系统共享。
最终交付 proposal.md、requirements.csv、poc-plan.md、open-questions.md。
汇总全部必要成员结果后，明确完成协调节点，交由人工验收。
```

各成员独立验证后，再运行整队。这里使用 Team 的动态委派，不依赖所有成员每次同时运行。需要强制固定的审批顺序时，用[Workflow](/v2/zh/service/workflows)包装相应团队步骤。

## 3. 提交一份客户需求

在 **WORK → Issues** 创建“Northstar Retail 客服方案与 PoC”，选择该 Team，采用人工验收。把客户需求全文粘贴到说明中，并加入：

```text
目标：给出可与客户讨论的方案，不执行对外发送或生产部署。
范围：网页客服、知识回答、只读订单查询和人工转接。
交付：方案、需求到能力映射、四周 PoC 建议、待确认问题。
验收：引用来源；不承诺未支持能力；所有未确认条件明确列出。
PoC 四周从访问权限与样例数据就绪后开始，不等于生产上线承诺。
```

观察 Task map 和评论：需求分析应产生待确认项，产品方案引用真实知识，交付规划说明前提，质量复核检查版本与承诺，最后由 Leader 汇总。

## 4. 检查文件与事实

| 文件 | 验收内容 |
| --- | --- |
| `proposal.md` | 目标、方案、系统边界、集成依赖、限制与来源 |
| `requirements.csv` | 每项需求对应能力、来源、支持状态与待确认项 |
| `poc-plan.md` | 四阶段建议、前提、验证方法；指标未定时标记 TBD |
| `open-questions.md` | 数据地域、SSO、峰值、预算、接口可达性与质量目标 |

复核者应指出：本例产品知识不提供语音通话和离线移动应用；历史案例没有给出生产准确率或保证响应时间；订单查询和人工转接需要对接 API，绑定资料不会自动完成集成。

在[Inbox](/v2/zh/service/inbox)下载 Artifact，核对来源和内容后接受或要求修改。评论中的本地文件路径不等于可下载文件。Request changes 记录反馈后，需显式安排修订执行；Agent 完成与人的验收分别跟踪。

可以让团队再回答“客户要求语音客服，能否承诺？”来检查边界处理。正确结果应指出当前知识未支持，并列出进一步评估的问题。

## 5. 验证资料更新

将 `product/capabilities.md` 更新为 `product-v2`，增加“语音通话可作为单独评估的试点，不属于现有标准交付”，新建工作并要求重新读取资料。新方案应区分标准能力与待评估试点，并引用新版本。

旧方案不会自动被改写；保留它使用的输入和来源版本。升级回归时使用同一批资料和问题核对事实，而不要求模型逐字生成相同文章。

## 6. 加入 External 实时能力查询

当能力、可用地域或实施资源经常变化时，增加一个用 AgentScope 开发的 External 成员，封装企业只读查询工具。按[External 注册与任务接入](/v2/zh/service/register-agentscope-agent)完成单任务验证后加入 Team。

新成员返回目标产品、查询地域、实际能力、约束、来源版本和查询时间。Leader 将其与 Memory 资料对照：数据过期或矛盾时说明差异并安排核实，不把两份来源任意拼成承诺。调用失败时保留静态方案，实时可用性标为未确认。

企业接口凭据由 External 应用管理。如果由 Managed Agent 直接访问带认证 MCP，则在 [Vault](/v2/zh/service/vault)配置相应凭据并验证绑定；把密钥写入 Instructions 不能替代工具认证配置。

## 7. 加入 Hosted PoC 实施

方案评审通过后，可以加入 Hosted PoC Agent，提供获批方案、演示仓库、可用接口和验收标准：

```text
只实现获批 PoC 范围。使用演示数据或明确提供的测试 API。
交付运行说明、代码提交或 Artifact、实际测试命令与结果、未完成项。
如接口未提供，明确使用模拟数据，不声称已经接通真实系统。
```

Hosted 成员在自己的任务目录编写并测试演示工程，通过提交和 Artifact 返回。Managed 质量复核 Agent 检查代码交付与原方案是否一致，Leader 汇总；成员目录不自动共享。需要 PR、Review、CI 与 Approve 时，继续采用[研发闭环案例](/v2/zh/service/cases/sdlc-team)的执行方式。

## 失败处理与收尾

| 现象 | 处理 |
| --- | --- |
| 模型没有读取知识 | 检查 Store 授权与绑定，新会话验证工具读取 |
| 方案声称支持资料未提供的能力 | 要求定位来源并修订，不凭运行成功接受 |
| 成员超时或失败 | Leader 保留已有成果，说明缺失部分并安排后续工作 |
| 文件无法下载 | 检查是否真正上传 Artifact，而非只输出路径 |
| 动态查询与静态资料冲突 | 标注版本和时间，核实后再形成结论 |
| PoC 仅使用模拟接口 | 在演示说明和验收中保留这一范围 |

完成后归档练习工作，保留可复用资料与验收记录；停用练习 Team 前检查运行中的任务。基础资料可以直接用于练习，模型输出、混合协作和文件交付仍需在实际部署中验证。
