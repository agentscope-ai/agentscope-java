/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;

/**
 * 演示 ToolExecutionContext 的所有配置方式及框架类型自动注入。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>① ReActAgent 级别 — toolExecutionContext() 设置用户上下文</li>
 *   <li>② Toolkit 级别 — ToolkitConfig.defaultContext() 设置数据源共享对象</li>
 *   <li>③ 工具方法自动注入 — Agent / ToolExecutionContext / ToolEmitter / 自定义 POJO</li>
 * </ul>
 */
@SuppressWarnings("unused")
public class ToolContextExample {

    // ========== 自定义业务 POJO（演示注入） ==========

    /** 用户上下文 — 通过 Agent 级别 ToolExecutionContext 注入。 */
    public static class UserContext {
        private final String userId;
        private final String tenant;

        public UserContext(String userId, String tenant) {
            this.userId = userId;
            this.tenant = tenant;
        }

        public String getUserId() {
            return userId;
        }

        public String getTenant() {
            return tenant;
        }

        @Override
        public String toString() {
            return "UserContext{userId='" + userId + "', tenant='" + tenant + "'}";
        }
    }

    /** 数据源 — 通过 Toolkit 级别 ToolExecutionContext 注入，所有工具共享。 */
    public static class DataSource {
        private final String dbUrl;

        public DataSource(String dbUrl) {
            this.dbUrl = dbUrl;
        }

        public String getDbUrl() {
            return dbUrl;
        }

        @Override
        public String toString() {
            return "DataSource{dbUrl='" + dbUrl + "'}";
        }
    }

    // ========== 演示工具类，通过参数声明所需注入对象 ==========

    public static class ContextDemoTools {

        /**
         * 演示所有自动注入类型：
         * <ul>
         *   <li>@ToolParam 参数 — 从 LLM 的 tool call arguments 解析</li>
         *   <li>Agent — 框架按类型自动注入当前 Agent 实例</li>
         *   <li>ToolExecutionContext — 框架按类型自动注入上下文容器</li>
         *   <li>UserContext — 从 ToolExecutionContext 中按类型解析（Agent 级别配置）</li>
         *   <li>DataSource — 从 ToolExecutionContext 中按类型解析（Toolkit 级别配置）</li>
         *   <li>ToolEmitter — 框架按类型自动注入，用于推送中间进度</li>
         * </ul>
         */
        @Tool(name = "context_demo", description = "演示所有自动注入类型")
        public String contextDemo(
                /* @ToolParam — 从 LLM 传入 */ @ToolParam(name = "query", description = "查询内容")
                        String query,

                /* 框架注入 — 当前 Agent 实例 */ Agent agent,

                /* 框架注入 — 上下文容器本身 */ ToolExecutionContext context,

                /* 业务 POJO — 从 Agent 级 context 中按类型解析 */ UserContext userCtx,

                /* 业务 POJO — 从 Toolkit 级 context 中按类型解析 */ DataSource dataSource,

                /* 框架注入 — 流式进度推送 */ ToolEmitter emitter) {

            // 模拟进度推送
            emitter.emit(ToolResultBlock.text("正在处理查询: " + query));

            StringBuilder sb = new StringBuilder();
            sb.append("=== 自动注入结果 ===\n");
            sb.append("Agent 名称: ").append(agent.getName()).append("\n");
            sb.append("ToolExecutionContext: ")
                    .append(context != null ? "已注入" : "null")
                    .append("\n");
            sb.append("UserContext: ").append(userCtx).append("\n");
            sb.append("DataSource: ").append(dataSource).append("\n");
            sb.append("ToolEmitter: ").append(emitter != null ? "已注入" : "null").append("\n");

            emitter.emit(ToolResultBlock.text("注入检查完成"));
            return sb.toString();
        }

        /**
         * 只注入需要的对象，不声明的不注入。
         * 这里只声明了 UserContext，不会注入 DataSource。
         */
        @Tool(name = "user_info", description = "查询当前用户信息")
        public String userInfo(
                @ToolParam(name = "field", description = "查询字段") String field,
                UserContext userCtx) {
            return "字段: " + field + ", 用户: " + userCtx.getUserId() + ", 租户: " + userCtx.getTenant();
        }
    }

    // ========== 主程序 ==========

    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "ToolExecutionContext Example", "演示 ToolExecutionContext 的三种配置方式及框架自动注入。");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        // ==================== ① Toolkit 级别配置 ====================
        // 注册 DataSource 到共享上下文，该 Toolkit 下所有工具都能拿到
        DataSource sharedDs = new DataSource("jdbc:mysql://prod-db:3306/mydb");

        ToolkitConfig toolkitConfig =
                ToolkitConfig.builder()
                        .defaultContext(
                                ToolExecutionContext.builder()
                                        .register(DataSource.class, sharedDs)
                                        .build())
                        .build();

        Toolkit toolkit = new Toolkit(toolkitConfig);
        toolkit.registerTool(new ContextDemoTools());

        System.out.println("✓ Toolkit 级别上下文: 已注入 DataSource(" + sharedDs.getDbUrl() + ")");

        // ==================== ② Agent 级别配置 ====================
        // 注册 UserContext 到 Agent 上下文，会覆盖 Toolkit 中的同名类型
        UserContext user = new UserContext("user-9527", "acme-corp");

        // ==================== ③ 组装 Agent 并测试 ====================
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ContextAgent")
                        .sysPrompt(
                                "你是一个演示 ToolExecutionContext 的助手。用户说「测试」或「test」时，"
                                        + "请调用 context_demo 工具。用户说「用户信息」时，调用 user_info 工具。"
                                        + "其他情况直接简短回复。")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(false)
                                        .build())
                        .toolkit(toolkit)
                        .toolExecutionContext(
                                ToolExecutionContext.builder()
                                        .register(UserContext.class, user)
                                        .build())
                        .memory(new InMemoryMemory())
                        .build();

        System.out.println("✓ Agent 级别上下文: 已注入 UserContext(" + user + ")");

        // ==================== ④ 运行演示 ====================
        System.out.println("\n开始测试工具上下文注入...\n");

        // 演示 1: 触发 context_demo 工具（展示所有注入效果）
        System.out.println(">>> 演示 1: 触发 context_demo 工具");
        String response1 =
                agent.call(Msg.builder().role(MsgRole.USER).textContent("测试").build())
                        .block()
                        .getTextContent();
        System.out.println("Agent 回复:\n" + response1);
        System.out.println();

        // 演示 2: 触发 user_info 工具（按需注入）
        System.out.println(">>> 演示 2: 触发 user_info 工具");
        String response2 =
                agent.call(Msg.builder().role(MsgRole.USER).textContent("用户信息").build())
                        .block()
                        .getTextContent();
        System.out.println("Agent 回复:\n" + response2);
        System.out.println();

        System.out.println("=== ToolExecutionContext 演示完成 ===");
        System.exit(0);
    }
}
