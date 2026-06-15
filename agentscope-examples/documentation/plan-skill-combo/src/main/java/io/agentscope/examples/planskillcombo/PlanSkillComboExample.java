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
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
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
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * PlanNotebook + Skill 组合示例 —— 多轮对话版。
 *
 * <h2>业务场景: 智能运维助手</h2>
 *
 * <pre>
 * 用户: "web-server-01 CPU 告警，支付服务变慢，请排查并修复"
 *   → Agent 自动规划 2 步: [诊断问题] → [执行修复]
 *   → 每步加载对应 Skill，按 Skill 指导调用工具
 *   → 完成后汇报结果
 *
 * 用户: "检查下 web-server-02 的状态"
 *   → Agent 直接查指标（无需规划，简单任务）
 *
 * 用户: "现在需要扩容，把订单服务也部署到 web-server-02"
 *   → Agent 规划新计划、加载部署 skill
 * </pre>
 */
public class PlanSkillComboExample {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    // ==================== 模拟数据存储 ====================

    private static final Map<String, Map<String, Object>> metricStore = new HashMap<>();
    private static final Map<String, List<String>> logStore = new HashMap<>();
    private static final Map<String, String> serviceHealth = new HashMap<>();
    private static final Map<String, Map<String, String>> serviceConfig = new HashMap<>();

    static {
        // web-server-01: 指标异常（CPU飙高）
        metricStore.put(
                "web-server-01",
                new HashMap<>(
                        Map.of(
                                "cpu_percent", 95.3,
                                "memory_percent", 62.1,
                                "disk_percent", 45.0,
                                "network_mbps", 120.5)));

        // web-server-02: 正常指标
        metricStore.put(
                "web-server-02",
                new HashMap<>(
                        Map.of(
                                "cpu_percent", 23.1,
                                "memory_percent", 48.5,
                                "disk_percent", 32.0,
                                "network_mbps", 45.2)));

        // payment-service 日志: 连接池耗尽
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

        // order-service 日志: 正常
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

    // ==================== Mock 工具 ====================

    @Tool(name = "query_metrics", description = "查询服务器监控指标（CPU使用率、内存使用率、磁盘使用率、网络流量）")
    public Mono<String> queryMetrics(
            @ToolParam(name = "server_name", description = "服务器主机名") String serverName) {
        System.out.println("  📊 [query_metrics] 服务器=" + serverName);

        Map<String, Object> metrics = metricStore.get(serverName);
        if (metrics == null) {
            return Mono.just(
                    "服务器 '" + serverName + "' 在监控系统中未找到。" + " 已知服务器: " + metricStore.keySet());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("服务器: ").append(serverName).append("\n");
        sb.append("采集时间: ").append(java.time.Instant.now()).append("\n");
        sb.append("指标数据:\n");
        metrics.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));

        System.out.println("    → CPU=" + metrics.get("cpu_percent") + "%");
        return Mono.just(sb.toString());
    }

    @Tool(name = "fetch_logs", description = "获取指定服务的最近应用日志")
    public Mono<String> fetchLogs(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
        System.out.println("  📋 [fetch_logs] 服务=" + serviceName);

        List<String> logs = logStore.get(serviceName);
        if (logs == null || logs.isEmpty()) {
            return Mono.just("服务 '" + serviceName + "' 暂无日志。已知服务: " + logStore.keySet());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("服务 ").append(serviceName).append(" 的最近日志:\n");
        for (String log : logs) {
            sb.append("  ").append(log).append("\n");
        }

        System.out.println("    → " + logs.size() + " 条日志");
        return Mono.just(sb.toString());
    }

    @Tool(name = "check_service_health", description = "检查服务健康状态（返回 UP / DEGRADED / DOWN）")
    public Mono<String> checkServiceHealth(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
        System.out.println("  ❤️  [check_service_health] 服务=" + serviceName);

        String health = serviceHealth.getOrDefault(serviceName, "UNKNOWN");
        String result =
                "服务: "
                        + serviceName
                        + "\n"
                        + "状态: "
                        + health
                        + "\n"
                        + "检查时间: "
                        + java.time.Instant.now();

        System.out.println("    → 状态=" + health);
        return Mono.just(result);
    }

    @Tool(name = "update_config", description = "更新服务运行时配置参数")
    public Mono<String> updateConfig(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName,
            @ToolParam(name = "key", description = "配置项名称") String key,
            @ToolParam(name = "value", description = "新的配置值") String value) {
        System.out.println("  ⚙️  [update_config] " + serviceName + " " + key + "=" + value);

        Map<String, String> config = serviceConfig.get(serviceName);
        if (config == null) {
            config = new HashMap<>();
            serviceConfig.put(serviceName, config);
        }
        String oldValue = config.getOrDefault(key, "(未设置)");
        config.put(key, value);

        String result =
                "配置更新成功:\n"
                        + "  服务: "
                        + serviceName
                        + "\n"
                        + "  配置项: "
                        + key
                        + "\n"
                        + "  旧值: "
                        + oldValue
                        + "\n"
                        + "  新值: "
                        + value;

        System.out.println("    → " + key + ": " + oldValue + " → " + value);
        return Mono.just(result);
    }

    @Tool(name = "restart_service", description = "优雅重启指定服务")
    public Mono<String> restartService(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
        System.out.println("  🔄 [restart_service] 服务=" + serviceName);

        serviceHealth.put(serviceName, "UP");

        // 模拟修复效果：重启后相关服务器 CPU 降低
        for (Map.Entry<String, Map<String, Object>> entry : metricStore.entrySet()) {
            Map<String, Object> metrics = entry.getValue();
            double cpu = ((Number) metrics.get("cpu_percent")).doubleValue();
            if (cpu > 80) {
                metrics.put("cpu_percent", cpu - 55.0); // 修复后 CPU 降回正常
            }
        }

        String result =
                "服务重启完成:\n"
                        + "  服务: "
                        + serviceName
                        + "\n"
                        + "  状态: UP\n"
                        + "  重启时间: "
                        + java.time.Instant.now()
                        + "\n"
                        + "  步骤: 排空连接 → 优雅停止 → 启动 → 健康检查 ✓";

        System.out.println("    → 已重启, 健康状态=UP, 相关服务器CPU已恢复正常");
        return Mono.just(result);
    }

    @Tool(name = "deploy_service", description = "将服务部署到指定服务器")
    public Mono<String> deployService(
            @ToolParam(name = "service_name", description = "服务名称") String serviceName,
            @ToolParam(name = "target_server", description = "目标服务器主机名") String targetServer) {
        System.out.println("  🚀 [deploy_service] " + serviceName + " → " + targetServer);

        serviceHealth.put(serviceName + "-" + targetServer, "UP");

        String result =
                "部署成功:\n"
                        + "  服务: "
                        + serviceName
                        + "\n"
                        + "  目标服务器: "
                        + targetServer
                        + "\n"
                        + "  部署时间: "
                        + java.time.Instant.now()
                        + "\n"
                        + "  步骤: 拉取镜像 → 健康检查旧实例 → 滚动更新 → 流量切换 → 验证 ✓";

        System.out.println("    → 部署完成");
        return Mono.just(result);
    }

    // ==================== Skill 定义（中文） ====================

    /**
     * 运维诊断技能 —— 指导 LLM 系统化排查服务器故障。
     */
    private static AgentSkill createDiagnosisSkill() {
        String skillMd =
                """
                ---
                name: 运维诊断
                description: >
                  运维故障诊断流程。当你需要排查服务器异常、性能问题或服务降级时使用此技能。
                  本技能将指导你通过监控指标和日志分析，系统地定位问题根因。
                ---
                # 运维故障诊断

                你是一名资深 SRE 工程师，正在执行故障诊断。当用户报告服务器或服务异常时，
                请严格按照以下流程操作。

                ## 诊断步骤

                ### 第一步：采集服务器指标
                首先调用 `query_metrics` 工具，查询**每一台**受影响服务器的监控指标
                （CPU、内存、磁盘、网络）。
                - 重点关注异常指标：CPU > 80%、内存 > 90%、磁盘 > 85%
                - 记录用户消息中提到的服务器名称

                ### 第二步：分析应用日志
                然后调用 `fetch_logs` 工具，拉取受影响服务的最近日志。
                - 寻找错误关键词：连接池耗尽、超时、OOM、死锁
                - 提取关键错误信息和时间戳
                - 从日志分析中确定**根因**

                ### 第三步：汇报诊断结论
                数据采集完成后，你必须汇报：
                - 发现了哪些指标异常
                - 根因是什么（基于日志分析得出）
                - 清晰的诊断总结，供下一步修复使用

                此技能在**定位到根因后**才算完成。
                之后请加载「运维修复」技能来执行修复操作。
                """;

        return SkillUtil.createFrom(skillMd, null);
    }

    /**
     * 运维修复技能 —— 指导 LLM 安全执行修复操作。
     */
    private static AgentSkill createRemediationSkill() {
        String skillMd =
                """
                ---
                name: 运维修复
                description: >
                  运维修复操作流程。当你需要修复服务故障、变更配置或重启服务时使用此技能。
                  本技能将指导你安全地执行修复操作并验证修复效果。
                ---
                # 运维修复操作

                你是一名资深 SRE 工程师，正在执行修复操作。在诊断阶段已确定根因后，
                请严格按照以下流程操作。

                ## 修复步骤

                ### 第一步：确认当前健康状态
                首先调用 `check_service_health` 工具，确认受影响服务在变更前的健康状态。

                ### 第二步：执行配置变更
                根据诊断结论，调用 `update_config` 工具修复配置问题：
                - 连接池耗尽 → 增大 pool.size（建议增加到当前的 2~3 倍）
                - 超时问题 → 适当增大 timeout.ms
                - 每次变更都要说明理由

                ### 第三步：重启服务使配置生效
                配置变更后，调用 `restart_service` 工具优雅重启服务，使新配置生效。

                ### 第四步：验证恢复结果
                重启完成后，再次调用 `check_service_health` 工具确认服务已恢复为 UP 状态。

                此技能在**确认服务健康后**才算完成。
                之后请向用户汇报修复结果。
                """;

        return SkillUtil.createFrom(skillMd, null);
    }

    /**
     * 服务部署技能 —— 指导 LLM 执行部署操作。
     */
    private static AgentSkill createDeploymentSkill() {
        String skillMd =
                """
                ---
                name: 服务部署
                description: >
                  服务部署流程。当你需要将服务部署到新服务器或执行扩容操作时使用此技能。
                  本技能将指导你安全地完成部署操作。
                ---
                # 服务部署

                你是一名资深 SRE 工程师，正在执行服务部署。请严格按照以下流程操作。

                ## 部署步骤

                ### 第一步：检查目标服务器状态
                首先调用 `query_metrics` 工具，确认目标服务器的资源是否充足
                （CPU < 60%，内存 < 80% 方可部署）。

                ### 第二步：执行部署
                调用 `deploy_service` 工具，将服务部署到目标服务器。

                ### 第三步：验证部署结果
                部署完成后，调用 `check_service_health` 工具确认新部署的服务实例状态为 UP。

                此技能在**服务健康检查通过后**才算完成。
                之后请向用户汇报部署结果。
                """;

        return SkillUtil.createFrom(skillMd, null);
    }

    // ==================== 可视化辅助 ====================

    private static void printPlanState(PlanNotebook notebook, String event) {
        Plan currentPlan = notebook.getCurrentPlan();
        if (currentPlan == null) {
            return;
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("📋 计划状态 [" + event + "]");
        System.out.println("=".repeat(60));
        System.out.println("  计划名称: " + currentPlan.getName());
        System.out.println("  计划状态: " + currentPlan.getState());

        for (int i = 0; i < currentPlan.getSubtasks().size(); i++) {
            SubTask st = currentPlan.getSubtasks().get(i);
            String icon =
                    switch (st.getState()) {
                        case TODO -> "⏸";
                        case IN_PROGRESS -> "▶️";
                        case DONE -> "✅";
                        case ABANDONED -> "❌";
                    };
            System.out.printf("  %s [%d] %s - %s%n", icon, i, st.getName(), st.getState());
        }
        System.out.println("=".repeat(60));
    }

    // ==================== 主程序 ====================

    public static void main(String[] args) throws Exception {
        printBanner();
        String apiKey = getApiKey();

        // ─── 1. Toolkit ───
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PlanSkillComboExample());
        System.out.println("✅ 已注册 6 个运维工具:");
        System.out.println("   query_metrics, fetch_logs, check_service_health,");
        System.out.println("   update_config, restart_service, deploy_service");

        // ─── 2. SkillBox ───
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.setExposeAllSkillMetadata(true);
        skillBox.registerSkill(createDiagnosisSkill());
        skillBox.registerSkill(createRemediationSkill());
        skillBox.registerSkill(createDeploymentSkill());
        System.out.println("✅ 已注册 3 个技能: 运维诊断, 运维修复, 服务部署");

        // ─── 3. PlanNotebook ───
        PlanNotebook planNotebook = PlanNotebook.builder().needUserConfirm(false).build();
        System.out.println("✅ PlanNotebook 已初始化");

        // ─── 4a. 计划可视化 Hook ───
        Hook planVizHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent pa) {
                            String toolName = pa.getToolUse().getName();
                            printPlanState(planNotebook, "完成 " + toolName);
                        }
                        return Mono.just(event);
                    }
                };

        // ─── 4b. 工具事件通知（ToolEventBus + ToolNotificationHook）───
        // 完全参照 agentscope-builder 架构：
        //   ToolNotificationHook → ToolEventBus → subscribe(sessionKey) → takeUntilOther(done)
        // sessionKey 在此 standalone demo 中固定为 "main"
        String sessionKey = "main";
        ToolEventBus toolEventBus = new ToolEventBus();
        Hook toolNotificationHook = new ToolNotificationHook(toolEventBus, sessionKey, true);

        // ─── 5. Agent（中文系统提示词）───
        String sysPrompt =
                """
                你是一个智能运维助手，擅长通过 PlanNotebook 制定计划并加载 Skill 来执行任务。

                工作流程：
                1. **判断任务类型**：
                   - 简单查询（只查看状态/指标/日志）→ 直接调用工具，不用制定计划
                   - 简单修复（单个服务重启/单次配置变更）→ 直接调用工具
                   - 复杂任务（多步骤、需先诊断再修复）→ 先规划再执行
                   - 后续追问（上一轮已处理过相关任务）→ 直接查看当前状态

                2. **复杂任务 - 先规划**：
                   - 诊断类任务通常分两步：诊断 → 修复
                   - 部署类任务通常分两步：检查 → 部署
                   - 用 `create_plan` 创建计划，子任务用中文命名

                3. **按步骤执行**：
                   a. 用 `update_subtask_state` 将当前步骤标记为 IN_PROGRESS
                   b. 调用 `load_skill_through_path` 加载对应的技能：
                      - 诊断问题 → skillId="运维诊断", path="SKILL.md"
                      - 修复问题 → skillId="运维修复", path="SKILL.md"
                      - 部署服务 → skillId="服务部署", path="SKILL.md"
                   c. 严格按照技能里的步骤指导，调用相应工具
                   d. 工具执行完毕并分析结果后，调用 `finish_subtask` 标记完成
                   e. **不要重复调用已返回相同结果的工具**，根据已有数据得出结论

                4. **计划完成**：所有步骤完成后调用 `finish_plan`，然后向用户总结结果。

                5. **后续追问**：如果用户追问上一轮已处理的问题（如"现在怎么样了"），
                   直接调用 query_metrics 查看当前状态即可，不要重新创建计划。

                注意事项：
                - 使用用户消息中提到的具体服务器名和服务名
                - 用中文回复用户，简洁明了
                - 每个步骤完成后必须调用 `finish_subtask`
                - 同一工具对同一目标只调用一次，不要循环调用
                - max_iters 有限，高效利用每次迭代
                """;

        ReActAgent agent =
                ReActAgent.builder()
                        .name("运维助手")
                        .sysPrompt(sysPrompt)
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(toolkit)
                        .maxIters(200)
                        .hooks(List.of(planVizHook, toolNotificationHook))
                        .planNotebook(planNotebook)
                        .skillBox(skillBox)
                        .enableMetaTool(true)
                        .build();
        System.out.println("✅ 运维助手已就绪 (PlanNotebook + SkillBox 双引擎)");

        // ─── 6. 多轮对话 ───
        startChatLoop(agent, planNotebook, toolEventBus, sessionKey);
    }

    // ==================== 流式对话 ====================

    private static void startChatLoop(
            ReActAgent agent, PlanNotebook notebook, ToolEventBus bus, String sessionKey)
            throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("💬 多轮对话已启动 (输入 'exit' 退出, 'help" + "' 查看帮助)");
        System.out.println("=".repeat(60) + "\n");

        // 只订阅 REASONING 事件获取 LLM 增量文本
        // 工具调用/结果由 ToolNotificationHook → ToolEventBus 发布
        StreamOptions streamOptions =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
                        .incremental(true)
                        .includeReasoningChunk(false)
                        .includeReasoningResult(true)
                        .build();

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

            if ("help".equalsIgnoreCase(input.trim())) {
                printHelp();
                continue;
            }

            if ("plan".equalsIgnoreCase(input.trim())) {
                printPlanState(notebook, "手动查看");
                continue;
            }

            System.out.println();
            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(input.trim()).build())
                            .build();

            try {
                System.out.println("\n" + "-".repeat(60));
                System.out.println("🤖 运维助手: ");
                System.out.flush();

                // ── 参照 ChatController 的 Flux.merge + Sinks.One done 模式 ──
                Sinks.One<Boolean> done = Sinks.one();

                // 工具事件流：ToolNotificationHook → ToolEventBus → ChatResp（实时）
                Flux<ChatResp> toolStream = bus.subscribe(sessionKey).takeUntilOther(done.asMono());

                // LLM 文本流：agent.stream() 增量 chunk → ChatResp
                Flux<ChatResp> textStream =
                        agent.stream(userMsg, streamOptions)
                                .filter(e -> e.getType() == EventType.AGENT_RESULT)
                                .flatMapIterable(
                                        e -> {
                                            List<ChatResp> resps = new ArrayList<>();
                                            for (ContentBlock block : e.getMessage().getContent()) {
                                                if (block instanceof TextBlock tb) {
                                                    String text = tb.getText();
                                                    if (text != null && !text.isEmpty()) {
                                                        resps.add(
                                                                new ChatResp(
                                                                        e.getMessage(),
                                                                        new TextMessage(text)));
                                                    }
                                                }
                                            }
                                            return resps;
                                        })
                                .doOnComplete(() -> done.tryEmitValue(true));

                // 合并流，每行输出一个 JSON 对象（JSONL）
                Flux.merge(toolStream, textStream)
                        .doOnNext(msg -> System.out.println(msg.toString()))
                        .doOnError(
                                e -> {
                                    System.err.println(
                                            "\n"
                                                    + new ChatResp(
                                                            "error",
                                                            new TextMessage(
                                                                    "❌ 执行出错: " + e.getMessage())));
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

    private static void printHelp() {
        System.out.println(
                """
                ┌──────────────────────────────────────────────────────────┐
                │ 可用命令:                                                 │
                │  exit  - 退出对话                                         │
                │  help  - 显示此帮助                                       │
                │  plan  - 查看当前计划状态                                  │
                │                                                          │
                │ 试试这些场景:                                              │
                │  ● 生产环境告警！web-server-01 CPU 飙高，支付服务变慢，     │
                │    请帮我排查并修复                                        │
                │  ● 检查 web-server-02 的当前状态                           │
                │  ● 将订单服务部署到 web-server-02 上，需要扩容             │
                │  ● 支付服务日志有什么异常？                                 │
                └──────────────────────────────────────────────────────────┘
                """);
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║   PlanNotebook + SkillBox  智能运维助手 (多轮对话)    ║");
        System.out.println("║                                                      ║");
        System.out.println("║  ● PlanNotebook: 自动规划多步骤任务                   ║");
        System.out.println("║  ● SkillBox: 按需加载运维技能                         ║");
        System.out.println("║  ● 多轮对话: 持续交互，记忆上下文                     ║");
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
