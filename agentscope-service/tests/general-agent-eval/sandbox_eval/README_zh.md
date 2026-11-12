# Terminal/SWE 沙箱适配准备

[English](README.md) · [验收与选题](../../../../docs/acceptance/native-context-eval-20261103/README.md)

本包将已部署的 Managed Agents Session 接入隔离 Harbor 任务 Pod，不替代 Harness 内核。Terminal 的 15 次冒烟首轮已全部终结归档，补评分单列；三路正在运行已验收的前五道正式题，其余 Terminal 与 SWE 环境仍需验收。

- `harbor_environment.py` 复用 Harbor 0.25 的 ACK 环境，限定固定 digest 的 CPU 单容器镜像。Task Pod 不挂载 ServiceAccount token，不启用特权模式、宿主卷或控制器凭据。发现同名 Pod 时停止并检查，不使用上游默认的删除重建行为。
- `exec_transport.py` 要求收到真实 Kubernetes 执行退出回执。连接中断、缺少回执及传输输出超限保留为未知。
- `terminal_process.py` 以 Python 标准库在题目 Pod 内运行，支持命令、持久交互式 PTY、stdin、取消、真实退出码及分页 stdout/stderr。稳定操作 ID 保留命令和输入回执；守护进程中断后不会隐式重放命令。进程日志由控制器最终导出。文件读取、写入和编辑通过同一文件系统内的 shell 命令完成。
- `terminal_bridge.py` 为三个 Harness 提供等价的四个工具：启动、轮询、输入、取消；在题目 Pod 外记录 RPC 请求与结果。
- `terminal_mcp.py` 校验工具密钥与审计网关传来的准确 trial 身份。命令结果未知时封闭对应 trial 的终端访问。

审计网关新增的可选 `--terminal` 后端默认关闭。新代码不会覆盖正在执行 AppWorld 的冻结环境。终端后端需固定集群 Service 地址、独立 trial 绑定，并沿用现有模型审计路由。在支持更精确的审计路由前，每个 Harness 保持一个活跃 trial。

在控制器的 Python 3.12+ 独立虚拟环境安装 `requirements.txt`。被测 Agent 执行期间，控制器凭据、数据集参考解和评分测试均留在题目容器外。参考解验收使用独立的一次性 Pod。准备环境的 PVC 和 Kubernetes 控制器凭据不得交给被测模型。

从上级目录运行 `test_terminal_process.py`、`test_sandbox_exec_transport.py`、`test_terminal_mcp.py` 和审计网关测试；MCP 测试也在固定版本的集群环境运行。合成任务集群验收已验证退出码 7、stderr、文件持久化、PTY 状态和重复输入的回执查询。这些仅证明传输与执行基础能力，不计入 benchmark 评分，也不能证明已经触发上下文压缩。

`session_driver.py` 与 `harbor_agent.py` 调用部署中的 Session API；`run_terminal.py` 只接受已固定、哈希验证且通过参考解验收的题单。评分测试在模型执行结束后才上传；用量未结算时不评分，失败保留 Pod/租约供检查。超时遵循官方题目声明，续跑只处理经核对尚未尝试的项。


冒烟首轮均已终结，正式题按已验收的固定顺序分批运行。
`run_swe.py` 复用 Session 控制器，但要求 SWE 专用的已验收题单和预算；核对干净 base commit，
用独立 Git index 导出包含新增与二进制文件的模型补丁，在另一干净 Pod 应用后才上传官方测试。
`grading.py` 要求实际 CTRF/SWE 解析证据；缺少测试依赖时即使原始脚本写出 0，也保留为未评分。
`preparation.py` 记录 APT/PyPI 分发镜像与可选的 planarity 0.6 兼容约束；正在运行的批次继续使用
原不可变代码与计划。SWE 参考解与干净评分 Pod 验收已通过，Session 试题仍在执行。


`artifact_transfer.py` 用 8 KiB 分块读取不可变快照，在补丁应用或解包前校验整份 SHA256；失败保留部分文件和源快照。`swe_patch.prepare_repository` 只在新 Pod 干净、路径和 blob 内容与 base commit 完全相同时归一化镜像准备提交的权限；该准备在模型执行前及独立评分 Pod 各进行一次。解析器使用隔离的 Python 3.13.9 与 uv 0.7.13，不替换 conda/testbed Python。官方 GitHub 依赖已通过限定域名的本机中转验收，SWE 模型题正在执行。

SWE 镜像可能含上游构建时的依赖配置提交。显式固定的 `setup_commit` 在模型 Pod 与干净评分 Pod 中同时保留（Astropy 将 setuptools 固定为 68.0.0）；模型补丁基于同一 setup commit 导出，官方 `base_commit` 另行记录。未声明的内容变更及非干净初始仓库仍被拒绝。缺少证书库的最小 Ubuntu 镜像使用单独声明、保留 APT 签名验证的 CA/curl 引导，再恢复 HTTPS。这些准备只在新参考解 Pod 验收，运行中的 v13 模型批次保持原代码。


五道 SWE 参考解与干净评分 Pod 验收已通过，开发 Session 试题正在执行。限定 GitHub 域名的本机 CONNECT 中转保留 TLS，需保持可用。Pod 就绪等待现按官方环境准备预算执行，与模型/评分超时分开。后续 Terminal 仅在 pMARS 采用明确声明的 Debian 分发代理 profile；其他 profile 与已冻结控制器保持不变。
