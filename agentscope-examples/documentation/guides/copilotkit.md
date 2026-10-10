# AgentScope × CopilotKit

展示 **AgentScope Java + AG-UI + CopilotKit v2** 高级用法的端到端示例：多路由 Runtime、会话线程、共享状态、生成式 UI、前端工具与人机协作（HITL）。

## 能力矩阵

| 能力 | 前端 Hook / 组件 | 后端 |
|------|------------------|------|
| 多路由 Runtime | `CopilotKit useSingleEndpoint={false}` | `/agui/run/info` · `threads` · `connect` · `run` |
| 会话管理 | `useThreads` | `DemoThreadStore` + 事件回放 |
| 共享状态 | `useAgent` / `useAgentContext` | Agent state 同步 |
| 前端工具 | `useFrontendTool` | AG-UI 转发前端工具 schema |
| 生成式 UI | `useRenderTool` / `useComponent` / `useDefaultRenderTool` | 工具结果驱动渲染 |
| HITL | `useInterrupt` + `useHumanInTheLoop` | schema-only `requestHumanApproval` |
| 建议 / 附件 | `useSuggestions` / `useAttachments` | Runtime 能力声明 |

## 快速开始

### 1. 环境变量

```bash
export DASHSCOPE_API_KEY=your_key
```

### 2. 启动后端

在仓库根目录或本模块执行：

```bash
mvn -pl agentscope-examples/documentation -am install -DskipTests
mvn -pl agentscope-examples/documentation spring-boot:run \
  -Dspring-boot.run.mainClass=io.agentscope.examples.documentation2.copilotkit.CopilotKitExampleApplication
```

默认端口：`8080`，AG-UI 前缀：`/agui`。

### 3. 启动前端（开发）

```bash
cd agentscope-examples/documentation/frontend/copilotkit
npm ci
npm run dev
```

打开 [http://localhost:5173](http://localhost:5173)。Vite 会将 `/agui` 代理到后端。

构建并托管前端：在项目根目录运行 `mvn -pl agentscope-examples/documentation -Pcopilotkit package -DskipTests`，再使用上述后端命令启动。产物位于 `target/generated-resources/copilotkit/static/`，profile 将其复制到 classpath；不会覆盖 AG-UI 页面。普通构建不运行 npm。

## 界面结构

1. **Hero**：品牌与能力预览（暗黑 + 香槟金 + 极光微光）
2. **Capability Rail**：高级用法矩阵
3. **Workbench**
   - 左：会话线程（`useThreads`）
   - 中：`CopilotChat` + HITL 演示条
   - 右：共享状态 / Hooks / 可复制指令（Tabs）

## 推荐试玩指令

- 把演示主题改成“订单审核”，优先级设为高。
- 渲染订单卡片：订单 A1001，金额 199.9，状态待支付。
- 调用 `confirmDemoAction`，体验前端 HITL。
- 点击「开始测试」体验后端 interrupt 审批。

## 技术栈

- **Backend**：Spring WebFlux · AgentScope · `agentscope-extensions-agui` · DashScope
- **Frontend**：React 19 · Vite · CopilotKit v2 · Tailwind CSS 4 · shadcn/ui

## 可执行 JAR

启用 `copilotkit` profile 后，可在项目根目录直接启动：

```bash
java -jar agentscope-examples/documentation/target/documentation-*-copilotkit.jar
```

配置、日志和静态页面分别从 `copilotkit/` 下加载。AG-UI 示例使用独立的 `agui/` 资源，不会被前端构建覆盖。
