# Documentation 示例

AG-UI 和 CopilotKit 示例现作为本模块内的独立 package：

| 示例 | Package | 资源 | 使用说明 |
|---|---|---|---|
| AG-UI | `io.agentscope.examples.documentation2.agui` | `src/main/resources/agui` | [AG-UI](guides/agui.md) |
| CopilotKit | `io.agentscope.examples.documentation2.copilotkit` | `src/main/resources/copilotkit` | [CopilotKit](guides/copilotkit.md) |

两个入口只扫描各自 package，分别加载各自的配置、日志和静态页面。默认端口均为 8080；同时运行时使用 `--server.port` 设置不同端口。其他 documentation 示例继续使用各自 main 类。

普通 Maven 构建不执行前端安装；`-Pcopilotkit` 显式构建 CopilotKit 前端及带 `copilotkit` classifier 的可执行 JAR。

迁移验证：后端及依赖模块构建、CopilotKit 前端构建、profile 可执行 JAR 打包通过；两个启动入口的首页与脚本 HTTP 冒烟检查通过（未调用模型）。正式文档链接检查通过。
