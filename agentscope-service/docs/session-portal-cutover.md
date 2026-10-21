# Session Portal 新环境切换与回退

本手册用于本轮允许不兼容重建的新架构。实际生产切流、旧环境退役、提交与镜像发布
尚未执行，需要按具体部署窗口安排。源码进程验证和后补测试边界见
[实施记录](session-portal-implementation-status.md) 与 [测试清单](session-portal-test-backlog.md)。

## 交付输入

- 同一候选版本的 Control、Portal、Dataplane、Go Runtime、Scheduler、Gateway、Hands
  制品及版本清单。UI和Java/Python/DSH客户端与该协议版本对应。
- 新的cp、rt、portal、dp数据空间，迁移账号和五个独立业务账号。迁移owner不进入
  普通服务Secret；业务账号无CREATE，迁移元数据只读。
- 共享Workspace/Artifact归档、Hands每副本独立持久化结果日志。Vault key、JWT、内部
  token和平台Hands token由部署方持久管理。
- 普通负载均衡和逐组件副本/并发预算。每个Dataplane除业务连接池外，还有默认8连接
  的native池；数据库总预算按实际副本累加。没有region或shard配置。

## 新环境准备与配置导入

1. 保留旧环境的数据库、文件、Worker结果日志、版本清单和密钥备份。确认恢复在独立
   环境可用，记录旧运行和供应商Sandbox归属，不对旧数据库直接执行新迁移。
2. 按[部署说明](../deploy/README.md)与[数据库说明](session-portal-schema.md)执行初始化、
   业务授权、普通服务启动。Compose有三个迁移Job及授权Job；Helm先schema.only、
   授权，再安装业务组件。普通副本始终校验schema。
3. 经新环境的公开资源API创建身份/Namespace、Environment、Vault、Memory、Agent及
   Application，并重新生成相应访问凭据。先导入依赖资源，再导入Agent和其绑定。
   记录旧资源ID到新资源ID的映射；不复制旧Session/Turn/Inbox、租约或执行收据。
4. 环境/工具引用必须使用新ID；检查资源权限、模型凭据、共享文件访问和平台Hands
   Environment范围。敏感凭据由安全配置渠道设置，不加入普通资源导出或版本库。
5. 使用新客户端和新Gateway独立地址执行验收。新Managed配置必须采用sql-v1，旧
   workspace-v1明确拒绝，不自动读取为空历史，也不自动转换旧日志。

初期使用不同Gateway地址保留两套环境的明确边界。业务系统保留已有Session对应的
环境地址，已有会话继续访问原环境；新会话选择新地址。这是业务连接配置，不新增
Service内部运行分区协议。无需sticky Session，任意新Portal副本可读取同一公开游标。

## 切换前检查

| 检查 | 通过标准 |
| --- | --- |
| 公共与内部边界 | Gateway资源操作到Control，Session/Turn到Portal；内部路径在Gateway拒绝，运行服务无公网直连 |
| 创建与投递 | 同幂等键创建/提交保持同Session/Turn；202与终态区分，重复投递无额外模型调用 |
| 多副本与重启 | 另一Portal读取相同cursor/快照；执行者重启后的恢复显式进行，旧fence不能提交 |
| 工具与文件 | 平台/self-hosted按所用环境验证；稳定结果去重、归档保留、未知副作用有可查询恢复入口 |
| 权限与预算 | 撤权/过期拒绝，不无限延长缓存；并发占用符合已选语义，用量去重，预算记录在途超额性质 |
| Checkpoint与界面 | 分页、恢复/fork命令、固定Agent和空目标、预算版本及处理状态可查询 |
| 事件与通知 | 公开事件无私有状态；SSE/导出按cursor续传；已启用Webhook验证签名及同delivery ID重试 |
| 存储与配置 | schema版本/校验和正确，账号权限和连接预算正确；文件/数据库/原密钥可共同恢复 |

观察数据使用已有公开Session/Turn状态、command_status、工具任务/Worker健康、SSE游标、
Webhook delivery记录，以及数据库/连接池和进程资源指标。记录queued积压、事件延迟、
权限刷新故障、unknown工具及重试状态；设置具体告警阈值前先测量实际负载。当前代表性
进程与小规模双副本验证不提供生产吞吐或尾延迟承诺。

## 切新流量与排空旧环境

1. 固定候选版本和资源映射，停止旧入口创建新Session与新Turn，同时保留旧结果读取、
   取消/人工答复和工具结果回传。关闭旧Cron/Channel等主动来源，防止继续产生任务。
   实际来源身份迁移按已确认规则处理，不能将Namespace owner当作人类运行身份。
2. 将明确的新会话发送到新Gateway。现有业务Session仍使用记录的旧地址；不要用新
   客户端协议去解释旧数据，也不要重新POST已完成任务来做验收。
3. 排空不仅检查running，还检查queued、cancel_requested、requires_action、failed/
   interrupted待恢复、子执行、工具unknown、待上报结果和未交付Webhook。对需要人工
   处理的任务记录负责人及选择，未确认外部效果的操作不能自动重跑。
4. 待旧执行停止与结果回传确认后，停止旧Worker/执行进程，保留文件与结果日志。旧
   Sandbox按其原绑定与租约处理；新Worker不接管旧任务或使用同一个Worker日志目录。
5. 观察新环境的状态、公开结果及用量对账，在具体保留期内留住旧环境的只读访问和
   可恢复备份。退役数据库、文件或供应商实例作为后续明确操作处理。

不能仅关闭SSE当作停止任务，也不能仅看到cancel_requested就终止保留执行结果的
Worker。平台默认5分钟空闲回收不等于清除文件归档。

## 回退条件与动作

- 新请求受理、权限、状态/事件或工具闭环出现确定问题时，先停止新环境的新准入和
  主动来源，保留其查询及结果回传；必要时按公开取消流程停止已受理的工作。
- 回退只改变后续新会话的入口。已进入新环境的Session由相同新协议版本处理或排空，
  不能交给旧执行器。回退前确认旧资源/身份配置仍有效，使用对应的旧客户端与旧地址。
- 数据问题在独立新库及匹配文件/密钥中恢复，先做只读核对，再处理未完成工作。未知
  模型或工具副作用进入显式核验，不能通过恢复数据库盲目重复外部操作。
- 当前迁移校验会拒绝未来版本；新增schema上不得重新启动不认识该版本的旧制品。
  若需要版本回退，使用该版本的独立兼容数据空间/完整备份并确认排空情况；镜像回退
  不能代替状态兼容验证。保留已经产生的新环境业务结果及对账记录。

## 本轮尚待验收

容器镜像实际安装、Kubernetes/RWX、生产负载曲线、资源批量导入工具、所有运行后端
及每个故障点的覆盖继续登记。Session绑定PATCH的Environment更换规则和无人值守
来源身份按待决策项推进；本手册不表示这些功能已完成或生产已切换。
