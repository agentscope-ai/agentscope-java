# AG-UI 示例

在项目根目录构建并运行：

```bash
mvn -pl agentscope-examples/documentation -am install -DskipTests
export DASHSCOPE_API_KEY=your-key
mvn -pl agentscope-examples/documentation spring-boot:run \
  -Dspring-boot.run.mainClass=io.agentscope.examples.documentation2.agui.AguiExampleApplication
```

打开 http://localhost:8080。页面与脚本来自 `src/main/resources/agui/static`，配置来自 `src/main/resources/agui/application.yml`。

可使用 `-Dspring-boot.run.arguments="--server.port=8081"` 覆盖端口。接口仍为 `/agui/run`，支持多 Agent 路由、状态同步和 HITL；发送聊天请求会调用真实模型。
