---
title: "可视化Console"
description: "通过可视化界面配置 Agent、运行 Session 并管理工作，与 API 共用平台资源和执行记录。"
en_link: /v2/en/service/console/index
---

Console 和 API 是使用 AgentScope Service 的两种入口。Console 适合在浏览器中配置 Agent、直接提交工作、观察执行并处理需要人工参与的事项；API 则让应用和脚本完成相应的操作。两者使用同一套平台资源和权限规则，因此在 Console 中创建的 Agent 可以被应用直接调用，通过管理 API 创建的 Agent 也可以在 Console 中继续配置。

你可以完全通过 Console 使用平台，也可以让 Console 与应用配合工作。例如，先在页面中调整指令和工具，通过一次实际执行确认行为，再让应用使用同一个 Agent；应用接入后，团队仍可以在 Console 中管理配置、跟进相关工作和验收交付。两种入口都可以独立使用，也可以随工作需要交替使用。

<span id="从哪里开始"></span>
<span id="完成一项工作的路径"></span>

首次使用前，先完成[服务部署](/v2/zh/service/quickstart)，取得 Console 地址和账号。登录后确认当前 Namespace，因为 Agent、资源和工作记录都在相应空间中管理，账号权限决定你能够查看和修改哪些内容。下面先完成一个托管 Agent 的配置与执行，再介绍如何接入应用和扩展业务协作。

## 创建并配置 Agent

<span id="配置与测试-agent"></span>
<span id="console-agents"></span>
<span id="创建-managed-agent"></span>
<span id="为-agent-准备资源"></span>

打开 **Design → Agents → New agent**，为 Agent 填写名称和用途，并在 **Instructions** 中说明它需要完成什么工作、依据什么材料判断，以及应当怎样交付结果。首次使用时，将 **Runtime** 选择为 **AgentScope Managed**，由 Service 运行基于 HarnessAgent 内核的托管 Agent。**Model** 留空会使用部署中的默认模型，也可以填写需要的模型覆盖。

在 **Advanced settings** 中检查 **Agent key**，给 Agent 保留一个稳定的业务标识；使用中文名称时，可以另外填写 `notes-assistant` 这样的 key。确认运行方式后，点击 **Create & open agent**。进入详情页后，可以在 **Definition → Behavior** 继续调整行为和模型，而不需要重新创建 Agent。

<Frame caption="Agent 目录示例，使用固定演示数据。">
<img src="/imgs/service/agents.png" alt="Agent 目录与创建入口" />
</Frame>

如果 Agent 需要访问文件或调用业务系统，进入 **Definition → Tools & MCP** 选择工具，并为需要确认的操作设置 **Ask**。MCP 连接也在这里配置，需要保存服务器连接及相应工具的启用规则；这些设置属于当前 Agent 的定义，后续会话引用这个 Agent 时就会使用它们。工具如何声明、筛选和设置权限，见[工具、MCP 与权限](/v2/zh/service/tools)。

需要复用共享指令、Skills 或子 Agent 时，先在 **Resources → Workspaces** 中准备内容，再到当前 Agent 的 **Definition → Workspace** 选择 Workspace 和要绑定的版本。保存绑定后，检查哪些配置由 Workspace 继承、哪些由当前 Agent 覆盖；仅在资源目录中创建 Workspace，并不会让 Agent 自动使用它。**Definition → Skills** 和 **Subagents** 用于查看或调整相应能力，绑定和版本规则见[Workspace、Skills 与子 Agent](/v2/zh/service/workspaces)。

执行资源的关联在 **Runtime configuration** 中完成。为 Agent 选择默认 Environment 后，新会话就有了工具执行位置；选择 Memory 和 Vault 后，新会话可以按配置使用共享知识和工具凭据。这些资源需要先在 **Resources** 中准备，再关联到 Agent，不能仅凭资源已经存在就认定它可被使用。具体的配置与生效范围见[Agent 配置](/v2/zh/service/managed-agent-configuration)、[Environment](/v2/zh/service/environments)、[Memory](/v2/zh/service/memory)和[Vault](/v2/zh/service/vault)。

<span id="查看注册的-external-agent"></span>
<span id="连接-hosted-runtime-并创建-agent"></span>

已有应用先按[注册 External Agent](/v2/zh/service/register-agentscope-agent)完成运行接入，再到 Agents 目录查看绑定和实例。已有 Coding Agent 则先[连接 Runtime Host](/v2/zh/service/connect-hosted-agent)，再在创建表单中选择发现的 Hosted provider。这些 Agent 也可以在 Console 中接收工作或参与协作，但可用的工具和控制操作取决于其实际运行能力，应先单独执行一个小任务验证。

## 在 Console 中运行 Session

<span id="测试并交给应用调用"></span>

在 Agent 详情页打开 **Connections → Session API**，直接填写 **Task message**。首次体验时，可以将 **Application API key** 留空，使用当前登录身份执行。点击 **Create Session and submit** 后，Console 会先创建引用当前 Agent 的 Session，再将输入提交为一个 Turn。这是一次真实的后台执行，使用的是当前配置的模型、工具和运行环境。

任务提交后，页面会显示 Session ID、Turn ID 和执行状态，并持续更新消息与 **Tool activity**。如果状态为 `queued`，表示工作已经接收但仍在排队；只有看到相应 Turn 完成后，才能判断本轮执行已经结束。检查工具记录中的输入和结果，可以确认 Agent 是否实际访问了材料、调用了工具，以及最终回复是否有依据。页面提供产物记录时，可以下载文件并核对交付内容，文件访问方式见[文件与产物](/v2/zh/service/files)。

如果执行需要你补充信息或决定是否允许某项操作，先阅读 **Required actions** 中的请求，再按要求提交回应。被指定给某个人的审批应使用该人员的登录身份处理，应用调用凭据不会自动获得代替他审批的权限。页面也会根据当前目标支持的能力提供补充输入、取消或恢复操作；提交控制请求后，仍应观察执行状态，确认请求是否真正生效。

后续任务可以通过 **Submit next Turn** 提交到同一个 Session。对于 Managed Agent，这样可以沿用已有对话上下文；如果希望开始一段独立工作，点击 **New Session**。调整 Agent 定义或资源绑定后，也应新建 Session 来验证新配置，因为已有 Session 会保留创建时选定的配置。刷新页面用于恢复已有 Session 和 Turn 的显示，不会自动重新提交任务。

需要先在对话中澄清需求时，也可以使用 **Work → Chat → New chat**。Agent 详情页中的 Session API 区域更适合同时验证执行行为和应用调用方式，两种入口都应以实际执行结果来判断配置是否符合预期。

## 与 API 共用 Agent 服务

<span id="发布给业务应用"></span>
<span id="通过-session-api-接入业务应用"></span>

在 Console 中完成配置后，应用可以直接使用这个 Agent 的平台 ID 创建 Session，Service 会按该 Agent 已保存的定义执行工作。反过来，使用管理 API 创建或更新 Agent 后，有相应权限的用户可以在同一 Namespace 的 Console 中继续管理它。Console 和 API 通过同一个资源 ID 关联，后续配置调整也保存在同一份 Agent 定义中。

在 **Connections → Session API → Application credentials** 中选择已有 Application，或者创建代表自己业务应用的 Application，再选择需要的 scope 并点击 **Issue key**。该凭据会授权当前执行目标和选定的调用操作，适合由业务后端创建 Session、提交任务并读取结果；它不等同于用于配置平台资源的用户身份。新 key 只在创建时显示，离开页面前应保存到业务后端。

展开 **API example** 可以查看当前目标对应的 Session 创建和 Turn 提交请求。应用沿用同一个目标 ID，即可把在 Console 中验证过的 Agent 接入自己的产品；调用时保存返回的 Session ID 和 Turn ID，后续读取进度、提交回应或恢复页面时继续使用这些记录。完整调用过程见[通过 Session API 接入应用](/v2/zh/service/service-api)，Agent 定义的管理请求见[API 参考](/v2/zh/service/api-reference#agents)。

如果希望在接入前验证应用身份，可以在尚未创建 Session 时填入 Application API key，再提交一次任务。这样可以检查应用被授予的目标和操作是否足以完成实际调用。批量管理、自动化接入或界面尚未提供的参数可以通过对应 API 完成；Console 仍可用于管理相关资源和查看其关联工作。

## 分派、跟进与验收工作

<span id="console-tasks"></span>
<span id="先用-chat-明确需求"></span>
<span id="创建并分派一项工作"></span>
<span id="跟进讨论与交付"></span>
<span id="在-inbox-中验收与审批"></span>

Session 适合提交任务并持续交互。如果业务还需要明确的负责人、讨论和验收过程，可以进入 **Work → Issues → New issue**，填写目标、交付要求和验收标准，再分派给 Agent、Team 或人员。也可以在 Chat 中使用 **Create issue**，但提交前应检查并补全带入的资料；保存对话来源引用，并不意味着协作成员能够读取完整的私人对话。

工作开始后，在 Issue 中查看讨论和交付，通过关联的 **Executions** 了解成员任务、执行步骤及失败原因。需要补充要求时，可以通过评论和 Mention 将信息交给相应成员，并检查路由后的执行是否继续推进。工作进入 `Blocked` 时，应先解决缺失的条件；进入 `In review` 时，则应对照验收标准检查结果，而不是仅凭某次执行成功就判定业务已经完成。

在 **Work → Inbox → Needs action** 中打开 **Review result**，核对结果、附件和相关子任务，再选择 **Accept result** 或 **Request changes** 并提交 review。退回修改会记录反馈，但仍需要跟进后续执行。操作审批与结果验收是不同的决定：允许一次工具或流程操作，不代表已经接受最终交付。Session 中的工具待办应在对应会话中处理，并非所有请求都会出现在 Inbox。Issue 的状态与 API 操作见[工作分派、审批与验收](/v2/zh/service/issues)。

<Frame caption="Inbox 结果验收示例，使用固定演示数据。">
<img src="/imgs/service/inbox.png" alt="Inbox 中的 Issue 与验收" />
</Frame>

## 配置 Team 与 Workflow

<span id="console-orchestration"></span>
<span id="先组成一个-team"></span>
<span id="把固定步骤写成-workflow"></span>
<span id="跟进并控制运行"></span>

当一个 Agent 难以独立完成目标时，可以先验证各个成员，再进入 **Design → Teams → New team** 选择 Leader Agent 和 Additional members，并说明共同的交付要求。在 **Advanced coordination instructions** 中写清楚委派和复核规则后，提交一个 Team 任务，检查实际分工、成员结果和 Leader 汇总。Managed、External 与 Hosted Agent 可以按各自支持的能力共同参与，成员选择与运行方法见[创建与运行 Team](/v2/zh/service/create-team)。

如果工作需要按固定步骤推进，进入 **Design → Workflows**，在 **Workflow design** 中配置节点、依赖和输入映射，再依次保存草稿、校验并发布版本。通过 **Run Workflow** 可以填写输入并关联 Issue，也可以在 Session API 区域测试已经发布的 revision。运行开始后会固定所选版本，后续修改草稿不会改变这次执行。节点和控制操作的详细说明见[编排 Workflow](/v2/zh/service/workflows)。

Team 和 Workflow 同样可以通过 Session API 区域创建会话并观察结果，因此应用接入仍使用 Session 和 Turn。它们的每个 Turn 分别启动一项协作任务或流程执行，不会像 Managed Agent 那样自动延续整段对话上下文。选择哪种协作方式，见[多 Agent 编排概览](/v2/zh/service/orchestration)。

## 定时工作、事件与消息渠道

<span id="console-automation"></span>
<span id="创建定时工作"></span>
<span id="跟进运行与重复触发"></span>
<span id="让外部事件触发工作"></span>
<span id="连接消息平台"></span>

需要定期运行 Agent 或 Team 时，进入 **Work → Automations → New automation**，保存要执行的 Runbook、负责人和交付要求，并检查计划、时区与 Next runs。先保存为关闭状态，通过 **Test run** 检查实际结果，再启用规则。需要由外部事件发起工作时，可以添加 Webhook trigger，配置接收的事件类型，并按页面提供的 URL 和 secret 接入发送方。后续可以从 Webhook deliveries 和 Runs 分别检查事件接收与执行，具体规则见[计划与事件触发](/v2/zh/service/automation)。

如果希望用户从已有消息平台提出需求，进入 **Design → Channels** 配置平台连接、凭据和路由，并完成外部平台要求的回调或事件订阅。连接显示运行中之后，还应从测试账号发起一项工作，检查消息是否被接收、是否交给正确目标，以及结果是否成功回传。当前飞书支持将消息关联为 Issue 并交给 Agent 或 Team；其他适配器的能力应分别确认，详情见[接入消息渠道](/v2/zh/service/channels)。

Console 中的规则和渠道也可以通过 API 管理，使用哪种方式取决于这次操作由人完成还是由业务系统自动完成。当前 Automation 和渠道工作路由都不能直接以 Workflow 为执行目标；已有工作也不会因为规则暂停或浏览器关闭而自动取消，需要进入相应执行记录处理。
