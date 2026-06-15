/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.planskillcombo;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * ReAct 循环完整事件流示例 —— 基于 {@code agent.stream()} 内置 {@code StreamingHook}。
 *
 * <p>与 {@link PlanSkillComboExample} 共享相同的 plan + skill + tool 业务逻辑，但无需自定义
 * {@code ToolNotificationHook} / {@code ToolEventBus}：直接通过 {@link StreamOptions} 同时开启
 * {@code REASONING} + {@code TOOL_RESULT}，从 {@link Event} 拆解出各类 {@link ContentBlock}
 * 并转为 {@link ChatResp} JSONL 输出。
 *
 * <h2>每轮 ReAct 输出的业务对象</h2>
 *
 * <pre>
 * REASONING(isLast=true) 中的 Msg 可能包含:
 *   ThinkingBlock  → {"id":"msg_1","data":{"type":"thinking","id":"msg_1","thinking":"..."}}
 *   ToolUseBlock   → {"id":"msg_1","data":{"type":"toolCall","id":"call_1","toolName":"...","input":{...}}}
 *   TextBlock      → {"id":"msg_1","data":{"type":"text","content":"..."}}
 *
 * TOOL_RESULT(isLast=true) 中的 Msg 包含:
 *   ToolResultBlock → {"id":"msg_2","data":{"type":"toolResult","id":"call_1","toolName":"...","output":"..."}}
 * </pre>
 */
public class ReActRoundCompleteExample {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    // ==================== 模拟数据存储 ====================

    private static final Map<String, Map<String, Object>> metricStore = new HashMap<>();
    private static final Map<String, List<String>> logStore = new HashMap<>();
    private static final Map<String, String> serviceHealth = new HashMap<>();
    private static final Map<String, Map<String, String>> serviceConfig = new HashMap<>();

    static {
        metricStore.put(
                "web-server-01",
                new HashMap<>(
                        Map.of(
                                "cpu_percent", 95.3,
                                "memory_percent", 62.1,
                                "disk_percent", 45.0,
                                "network_mbps", 120.5)));
        metricStore.put(
                "web-server-02",
                new HashMap<>(
                        Map.of(
                                "cpu_percent", 23.1,
                                "memory_percent", 48.5,
                                "disk_percent", 32.0,
                                "network_mbps", 45.2)));
        logStore.put(
                "payment-service",
                List.of(
                        "2026-06-07 10:23:01 ERROR ConnectionPool - 连接池耗尽, active=20/20,"
                                + " pending=15",
                        "2026-06-07 10:23:05 WARN  PaymentService - 事务超时, 超过30秒未响应",
                        "2026-06-07 10:23:10 ERROR PaymentService - 处理订单 #ORD-88291 失败",
                        "2026-06-07 10:23:15 ERROR ConnectionPool - 连接池耗尽, active=20/20,"
                                + " pending=22",
                        "2026-06-07 10:23:20 INFO  HealthCheck - /health 返回 status=503"));
        logStore.put(
                "order-service",
                List.of(
                        "2026-06-07 10:20:01 INFO  OrderService - 订单处理正常, TPS=150",
                        "2026-06-07 10:21:01 INFO  OrderService - 订单处理正常, TPS=148",
                        "2026-06-07 10:22:01 INFO  OrderService - 订单处理正常, TPS=152"));
        serviceHealth.put("payment-service", "DEGRADED");
        serviceHealth.put("order-service", "UP");
        serviceConfig.put(
                "payment-service", new HashMap<>(Map.of("pool.size", "20", "timeout.ms", "30000")));
        serviceConfig.put(
                "order-service", new HashMap<>(Map.of("pool.size", "30", "timeout.ms", "30000")));
    }

    // ==================== 工具 ====================

    @Tool(name = "query_metrics", description = "查询服务器监控指标（CPU使用率、内存使用率、磁盘使用率、网络流量）")
    public Mono<String> queryMetrics(
            @ToolParam(name = "server_name", description = "服务器主机名") String serverName) {
        Map<String, Object> metrics = metricStore.get(serverName);
        if (metrics == null) {
            return Mono.just("服务器 '" + serverName + "' 在监控系统中未找到。已知服务器: " + metricStore.keySet());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("服务器: ").append(serverName).append("\n");
        sb.append("采集时间: ").append(java.time.Instant.now()).append("\n");
        sb.append("指标数据:\n");
        metrics.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
        return Mono.just(sb.toString());
    }

    @Tool(name = "fetch_logs", description = "获取指定服务的最近应用日志")
    public Mono<String> fetchLogs(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
        List<String> logs = logStore.get(serviceName);
        if (logs == null || logs.isEmpty()) {
            return Mono.just("服务 '" + serviceName + "' 暂无日志。已知服务: " + logStore.keySet());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("服务 ").append(serviceName).append(" 的最近日志:\n");
        for (String log : logs) {
            sb.append("  ").append(log).append("\n");
        }
        return Mono.just(sb.toString());
    }

    @Tool(name = "check_service_health", description = "检查服务健康状态（返回 UP / DEGRADED / DOWN）")
    public Mono<String> checkServiceHealth(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
        String health = serviceHealth.getOrDefault(serviceName, "UNKNOWN");
        return Mono.just(
                "服务: " + serviceName + "\n状态: " + health + "\n检查时间: " + java.time.Instant.now());
    }

    @Tool(name = "update_config", description = "更新服务运行时配置参数")
    public Mono<String> updateConfig(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName,
            @ToolParam(name = "key", description = "配置项名称") String key,
            @ToolParam(name = "value", description = "新的配置值") String value) {
        Map<String, String> config = serviceConfig.get(serviceName);
        if (config == null) {
            config = new HashMap<>();
            serviceConfig.put(serviceName, config);
        }
        String oldValue = config.getOrDefault(key, "(未设置)");
        config.put(key, value);
        return Mono.just(
                "配置更新成功:\n  服务: "
                        + serviceName
                        + "\n  配置项: "
                        + key
                        + "\n  旧值: "
                        + oldValue
                        + "\n  新值: "
                        + value);
    }

    @Tool(name = "restart_service", description = "优雅重启指定服务")
    public Mono<String> restartService(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
        serviceHealth.put(serviceName, "UP");
        for (Map.Entry<String, Map<String, Object>> entry : metricStore.entrySet()) {
            Map<String, Object> metrics = entry.getValue();
            double cpu = ((Number) metrics.get("cpu_percent")).doubleValue();
            if (cpu > 80) {
                metrics.put("cpu_percent", cpu - 55.0);
            }
        }
        return Mono.just(
                "服务重启完成:\n  服务: " + serviceName + "\n  状态: UP\n  重启时间: " + java.time.Instant.now());
    }

    @Tool(name = "deploy_service", description = "将服务部署到指定服务器")
    public Mono<String> deployService(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName,
            @ToolParam(name = "target_server", description = "目标服务器主机名") String targetServer) {
        serviceHealth.put(serviceName + "-" + targetServer, "UP");
        return Mono.just(
                "部署成功:\n  服务: "
                        + serviceName
                        + "\n  目标服务器: "
                        + targetServer
                        + "\n  部署时间: "
                        + java.time.Instant.now());
    }

    // ==================== Skill 定义 ====================

    private static AgentSkill createDiagnosisSkill() {
        String skillMd =
                """
                ---
                name: 运维诊断
                description: >
                  运维故障诊断流程。当你需要排查服务器异常、性能问题或服务降级时使用此技能。
                ---
                # 运维故障诊断
                你是一名资深 SRE 工程师，正在执行故障诊断。

                ## 诊断步骤
                ### 第一步：采集服务器指标
                调用 `query_metrics` 查询每一台受影响服务器的监控指标。

                ### 第二步：分析应用日志
                调用 `fetch_logs` 拉取受影响服务的最近日志，寻找错误关键词。

                ### 第三步：汇报诊断结论
                汇报指标异常、根因分析和诊断总结。
                """;
        return SkillUtil.createFrom(skillMd, null);
    }

    private static AgentSkill createRemediationSkill() {
        String skillMd =
                """
                ---
                name: 运维修复
                description: >
                  运维修复操作流程。当你需要修复服务故障、变更配置或重启服务时使用此技能。
                ---
                # 运维修复操作
                你是一名资深 SRE 工程师，正在执行修复操作。

                ## 修复步骤
                ### 第一步：确认当前健康状态
                调用 `check_service_health` 确认受影响服务在变更前的健康状态。

                ### 第二步：执行配置变更
                根据诊断结论，调用 `update_config` 修复配置问题。

                ### 第三步：重启服务使配置生效
                调用 `restart_service` 优雅重启服务。

                ### 第四步：验证恢复结果
                再次调用 `check_service_health` 确认服务已恢复为 UP 状态。
                """;
        return SkillUtil.createFrom(skillMd, null);
    }

    private static AgentSkill createDeploymentSkill() {
        String skillMd =
                """
                ---
                name: 服务部署
                description: >
                  服务部署流程。当你需要将服务部署到新服务器或执行扩容操作时使用此技能。
                ---
                # 服务部署
                你是一名资深 SRE 工程师，正在执行服务部署。

                ## 部署步骤
                ### 第一步：检查目标服务器状态
                调用 `query_metrics` 确认目标服务器资源充足。

                ### 第二步：执行部署
                调用 `deploy_service` 将服务部署到目标服务器。

                ### 第三步：验证部署结果
                调用 `check_service_health` 确认新部署的服务实例状态为 UP。
                """;
        return SkillUtil.createFrom(skillMd, null);
    }

    // ==================== 主程序 ====================

    public static void main(String[] args) throws Exception {
        printBanner();
        String apiKey = getApiKey();

        // ─── Toolkit ───
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReActRoundCompleteExample());
        System.out.println("✅ 已注册 6 个运维工具");

        // ─── SkillBox ───
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.setExposeAllSkillMetadata(true);
        skillBox.registerSkill(createDiagnosisSkill());
        skillBox.registerSkill(createRemediationSkill());
        skillBox.registerSkill(createDeploymentSkill());
        System.out.println("✅ 已注册 3 个技能: 运维诊断, 运维修复, 服务部署");

        // ─── PlanNotebook ───
        PlanNotebook planNotebook = PlanNotebook.builder().needUserConfirm(false).build();
        System.out.println("✅ PlanNotebook 已初始化");

        // ─── Agent ───
        String sysPrompt =
                """
                你是一个智能运维助手，擅长通过 PlanNotebook 制定计划并加载 Skill 来执行任务。

                工作流程：
                1. 复杂任务（多步骤、需先诊断再修复）→ 先规划再执行
                2. 简单查询/修复 → 直接调用工具
                3. 按步骤执行：update_subtask_state → load_skill_through_path → 按技能指导调用工具 → finish_subtask
                4. 计划完成后调用 finish_plan，向用户总结结果

                注意：
                - 使用用户消息中提到的具体服务器名和服务名
                - 用中文回复用户，简洁明了
                - 同一工具对同一目标只调用一次
                """;

        ReActAgent agent =
                ReActAgent.builder()
                        .name("运维助手")
                        .sysPrompt(sysPrompt)
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(toolkit)
                        .maxIters(200)
                        .planNotebook(planNotebook)
                        .skillBox(skillBox)
                        .enableMetaTool(true)
                        .build();
        System.out.println("✅ 运维助手已就绪");

        // ─── 流式配置：同时监听 REASONING + TOOL_RESULT ───
        // includeReasoningChunk(false) → 只收完整推理结果，不收增量 chunk
        // includeActingChunk(false)    → 只收最终工具结果，不收中间 chunk
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .eventTypes(
                                EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                        //                        .incremental(false)
                        //                        .includeReasoningChunk(false)
                        .includeActingChunk(false)
                        .build();

        // ─── 多轮对话 ───
        startChatLoop(agent, streamOptions);
    }

    // ==================== 流式对话 ====================

    private static void startChatLoop(ReActAgent agent, StreamOptions streamOptions)
            throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("💬 多轮对话已启动 (输入 'exit' 退出)");
        System.out.println("   每轮输出完整的 ReAct 事件: thinking → toolCall → toolResult → text");
        System.out.println("=".repeat(60) + "\n");

        while (true) {
            System.out.print("👤 你: ");
            String input = reader.readLine();

            if (input == null || "exit".equalsIgnoreCase(input.trim())) {
                System.out.println("\n👋 再见!");
                break;
            }
            if (input.trim().isEmpty()) {
                continue;
            }

            System.out.println();
            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(input.trim()).build())
                            .build();

            try {
                System.out.println("-".repeat(60));
                System.out.println("🤖 ReAct 循环事件流:");
                System.out.flush();

                agent.stream(userMsg, streamOptions)
                        //                        .filter(Event::isLast)
                        .flatMapIterable(event -> eventToChatResps(event))
                        .doOnNext(
                                resp -> {
                                    System.out.println(resp.toString());
                                })
                        .doOnError(
                                e -> {
                                    System.err.println("\n❌ 执行出错: " + e.getMessage());
                                    e.printStackTrace();
                                })
                        .blockLast();

                System.out.println("\n" + "-".repeat(60) + "\n");
            } catch (Exception e) {
                System.err.println("\n❌ 执行出错: " + e.getMessage());
                e.printStackTrace();
                System.out.println();
            }
        }
    }

    /**
     * 将一个 {@link Event} 拆解为多个 {@link ChatResp}。
     *
     * <ul>
     *   <li>REASONING (有 ToolUseBlock): thinking → toolCall → processText
     *   <li>REASONING (无 ToolUseBlock): text (最终回复)
     *   <li>TOOL_RESULT: toolResult
     * </ul>
     */
    private static List<ChatResp> eventToChatResps(Event event) {
        String msgId = event.getMessageId();
        Msg msg = event.getMessage();
        List<ChatResp> resps = new ArrayList<>();

        if (event.getType() == EventType.REASONING) {
            // 判断是中间过程（有 toolCall）还是最终回复（无 toolCall）
            //            boolean hasToolCalls =
            // !msg.getContentBlocks(ToolUseBlock.class).isEmpty();
            String processText = null;

            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ThinkingBlock tb) {
                    if (event.isLast()) {
                        continue;
                    }
                    String thinking = tb.getThinking();
                    if (thinking != null && !thinking.isEmpty()) {
                        resps.add(new ChatResp(msg, new ThinkingMessage(thinking)));
                    }
                }
                if (block instanceof ToolUseBlock tu) {
                    if (event.isLast()) {
                        Map<String, Object> input = new LinkedHashMap<>();
                        if (tu.getInput() != null) {
                            input.putAll(tu.getInput());
                        }
                        resps.add(
                                new ChatResp(
                                        msg, new ToolCallMessage(tu.getId(), tu.getName(), input)));
                    }
                }
                if (block instanceof TextBlock tb) {
                    if (event.isLast()) {
                        continue;
                    }
                    String text = tb.getText();
                    if (text != null && !text.isEmpty()) {
                        // 最终回复
                        resps.add(new ChatResp(msg, new TextMessage(text)));
                    }
                }
            }

        } else if (event.getType() == EventType.TOOL_RESULT) {
            for (ContentBlock block : event.getMessage().getContent()) {
                if (block instanceof ToolResultBlock tr) {
                    StringBuilder output = new StringBuilder();
                    if (tr.getOutput() != null) {
                        for (ContentBlock cb : tr.getOutput()) {
                            if (cb instanceof TextBlock tb
                                    && tb.getText() != null
                                    && !tb.getText().isEmpty()) {
                                output.append(tb.getText());
                            }
                        }
                    }
                    // 从 ToolResultBlock 推断 toolName: 需从 id/name 字段获取
                    resps.add(
                            new ChatResp(
                                    msg,
                                    new ToolResultMessage(
                                            tr.getId(), tr.getName(), output.toString())));
                }
            }
        }
        return resps;
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  ReAct 循环完整事件流 (基于内置 StreamingHook)        ║");
        System.out.println("║                                                      ║");
        System.out.println("║  直接使用 agent.stream() 输出:                        ║");
        System.out.println("║  thinking → toolCall → toolResult → text             ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static String getApiKey() throws IOException {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            System.out.println("✓ 使用 DASHSCOPE_API_KEY 环境变量\n");
            return apiKey;
        }
        System.out.print("请输入 DashScope API Key: ");
        String input = reader.readLine();
        if (input == null || input.trim().isEmpty()) {
            System.err.println("错误: API Key 不能为空");
            System.exit(1);
        }
        System.out.println();
        return input.trim();
    }
}
