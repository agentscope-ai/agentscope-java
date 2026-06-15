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

import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * HarnessAgent 版本的 Plan + Skill + Tool 组合示例。
 *
 * <p>演示能力：
 *
 * <ul>
 *   <li><b>HarnessAgent</b>：替代 ReActAgent，内置 WorkspaceSession 持久化、对话压缩
 *   <li><b>4 层 Skill 加载</b>：project-global → marketplace → workspace-shared → per-user
 *   <li><b>流式输出 JSONL</b>：Flux.merge 合并 LLM 文本流 + ToolEventBus 工具事件流
 *   <li><b>自定义 Hook</b>：planVizHook 可视化计划状态 + ToolNotificationHook 发布工具事件
 *   <li><b>计划持久化</b>：PlanNotebook 通过 WorkspaceSession 自动持久化
 * </ul>
 *
 * <h2>Skill 分层映射</h2>
 *
 * <pre>
 * Layer 1 (最低优先级) projectGlobalSkillsDir  → 运维诊断
 * Layer 2                     skillRepositories  → 运维修复 (InMemory)
 * Layer 3                     workspaceSkillsDir → 服务部署
 * Layer 4 (最高优先级)         per-user fs        → (本示例未使用)
 * </pre>
 */
public class HarnessPlanSkillExample {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));
    private static final Path WORKSPACE = Paths.get("/opt/agentscope/workspace");
    private static final Path PROJECT_GLOBAL_SKILLS =
            Paths.get("/opt/agentscope/project-global-skills");

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
                        "2026-06-07 10:23:20 INFO  HealthCheck - /health 返回 status=503"));
        logStore.put(
                "order-service",
                List.of(
                        "2026-06-07 10:20:01 INFO  OrderService - 订单处理正常, TPS=150",
                        "2026-06-07 10:21:01 INFO  OrderService - 订单处理正常, TPS=148"));
        serviceHealth.put("payment-service", "DEGRADED");
        serviceHealth.put("order-service", "UP");
        serviceConfig.put(
                "payment-service", new HashMap<>(Map.of("pool.size", "20", "timeout.ms", "30000")));
        serviceConfig.put(
                "order-service", new HashMap<>(Map.of("pool.size", "30", "timeout.ms", "30000")));
    }

    // ==================== 诊断工具（仅诊断 Skill 可用）====================

    public static class DiagnosisTools {
        @Tool(name = "query_metrics", description = "查询服务器监控指标（CPU使用率、内存使用率、磁盘使用率、网络流量）")
        public Mono<String> queryMetrics(
                @ToolParam(name = "server_name", description = "服务器主机名") String serverName) {
            Map<String, Object> metrics = metricStore.get(serverName);
            if (metrics == null) {
                return Mono.just(
                        "服务器 '" + serverName + "' 在监控系统中未找到。已知服务器: " + metricStore.keySet());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("服务器: ").append(serverName).append("\n");
            sb.append("采集时间: ").append(java.time.Instant.now()).append("\n");
            metrics.forEach(
                    (k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
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
    }

    // ==================== 修复工具（仅修复 Skill 可用）====================

    public static class RemediationTools {
        @Tool(name = "check_service_health", description = "检查服务健康状态（返回 UP / DEGRADED / DOWN）")
        public Mono<String> checkServiceHealth(
                @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
            String health = serviceHealth.getOrDefault(serviceName, "UNKNOWN");
            return Mono.just(
                    "服务: "
                            + serviceName
                            + "\n状态: "
                            + health
                            + "\n检查时间: "
                            + java.time.Instant.now());
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
                    "服务重启完成:\n  服务: "
                            + serviceName
                            + "\n  状态: UP\n  重启时间: "
                            + java.time.Instant.now());
        }
    }

    // ==================== 部署工具（仅部署 Skill 可用）====================

    public static class DeploymentTools {
        @Tool(name = "query_metrics", description = "查询服务器监控指标（CPU使用率、内存使用率、磁盘使用率、网络流量）")
        public Mono<String> queryMetrics(
                @ToolParam(name = "server_name", description = "服务器主机名") String serverName) {
            Map<String, Object> metrics = metricStore.get(serverName);
            if (metrics == null) {
                return Mono.just(
                        "服务器 '" + serverName + "' 在监控系统中未找到。已知服务器: " + metricStore.keySet());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("服务器: ").append(serverName).append("\n");
            sb.append("采集时间: ").append(java.time.Instant.now()).append("\n");
            metrics.forEach(
                    (k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
            return Mono.just(sb.toString());
        }

        @Tool(name = "check_service_health", description = "检查服务健康状态（返回 UP / DEGRADED / DOWN）")
        public Mono<String> checkServiceHealth(
                @ToolParam(name = "service_name", description = "服务名称") String serviceName) {
            String health = serviceHealth.getOrDefault(serviceName, "UNKNOWN");
            return Mono.just(
                    "服务: "
                            + serviceName
                            + "\n状态: "
                            + health
                            + "\n检查时间: "
                            + java.time.Instant.now());
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
    }

    // ==================== 主程序 ====================

    public static void main(String[] args) throws Exception {
        printBanner();
        String apiKey = getApiKey();

        // ─── 1. 初始化各层 Skill 目录 ───
        initWorkspace(WORKSPACE);
        initProjectGlobalSkills(PROJECT_GLOBAL_SKILLS);

        // ─── 2. Toolkit ───
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new DiagnosisTools()).apply();
        toolkit.registration().tool(new RemediationTools()).apply();
        toolkit.registration().tool(new DeploymentTools()).apply();
        System.out.println(
                "✅ Toolkit: query_metrics, fetch_logs, check_service_health,"
                        + " update_config, restart_service, deploy_service");

        // ─── 3. InMemorySkillRepository: 仅运维修复（Layer 2）───
        AgentSkillRepository skillRepo =
                new InMemorySkillRepository("custom", List.of(createRemediationSkill()));

        // ─── 4. PlanNotebook ───
        PlanNotebook planNotebook = PlanNotebook.builder().needUserConfirm(true).build();
        System.out.println("✅ PlanNotebook 已初始化");

        // ─── 5a. 自定义 Hook: 计划状态可视化 ───
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

        // ─── 5b. 工具事件通知（ToolEventBus + ToolNotificationHook）───
        String sessionKey = "main";
        ToolEventBus toolEventBus = new ToolEventBus();
        Hook toolNotificationHook = new ToolNotificationHook(toolEventBus, sessionKey, true);

        // ─── 6. HarnessAgent ───
        String sysPrompt =
                """
                你是一个智能运维助手，擅长通过 PlanNotebook 制定计划并加载 Skill 来执行任务。

                工作流程：
                1. 简单查询/修复 → 直接调用工具，一步完成
                2. 复杂任务（多步骤、需先诊断再修复）→ create_plan 后立即开始执行，无需等待用户确认
                3. 按步骤执行：update_subtask_state → load_skill_through_path → 按技能指导调用工具 → finish_subtask
                4. 计划完成后调用 finish_plan，向用户总结结果

                ⚠️ 重要：计划创建后直接开始执行第一个子任务，不要问用户"是否继续"或等待确认。

                注意：
                - 使用用户消息中提到的具体服务器名和服务名
                - 用中文回复用户，简洁明了
                - 同一工具对同一目标只调用一次
                - 不要在完成一个子任务后停下来等待，继续执行下一个
                """;

        HarnessAgent agent =
                HarnessAgent.builder()
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
                        .toolkit(toolkit)
                        .planNotebook(planNotebook)
                        .workspace(WORKSPACE)
                        .projectGlobalSkillsDir(PROJECT_GLOBAL_SKILLS)
                        .skillRepository(skillRepo)
                        .hook(planVizHook)
                        .hook(toolNotificationHook)
                        .maxIters(200)
                        .build();
        System.out.println("✅ 运维助手已就绪 (HarnessAgent + 流式输出 + 自定义 Hook)");
        System.out.println("   Workspace: " + WORKSPACE.toAbsolutePath());
        System.out.println("   ProjectGlobalSkills: " + PROJECT_GLOBAL_SKILLS.toAbsolutePath());
        System.out.println("   Hooks: planVizHook, toolNotificationHook");

        // ─── 7. 多轮对话（流式输出 JSONL）───
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId("sesion:1").userId("leo").build();
        startChatLoop(agent, planNotebook, toolEventBus, sessionKey, ctx);
    }

    // ==================== 流式多轮对话 ====================

    private static void startChatLoop(
            HarnessAgent agent,
            PlanNotebook notebook,
            ToolEventBus bus,
            String sessionKey,
            RuntimeContext ctx)
            throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("💬 多轮对话已启动 (输入 'exit' 退出, 'help' 查看帮助)");
        System.out.println("   输出格式: JSONL (每行一个 ChatResp JSON)");
        System.out.println("=".repeat(60) + "\n");

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

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(input.trim()).build())
                            .build();

            try {
                System.out.println("\n" + "-".repeat(60));
                System.out.println("🤖 运维助手: ");
                System.out.flush();

                // ── Flux.merge + Sinks.One done 模式 ──
                Sinks.One<Boolean> done = Sinks.one();

                // 工具事件流：ToolNotificationHook → ToolEventBus → ChatResp（实时）
                Flux<ChatResp> toolStream = bus.subscribe(sessionKey).takeUntilOther(done.asMono());

                // LLM 文本流：agent.stream() 增量 chunk → ChatResp
                Flux<ChatResp> textStream =
                        agent.stream(List.of(userMsg), streamOptions, ctx)
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
                                    System.err.println("   ⚠️ 计划状态已持久化到 workspace，重启后可继续");
                                    e.printStackTrace();
                                })
                        .blockLast();

                System.out.println("\n" + "-".repeat(60) + "\n");
            } catch (Exception e) {
                System.err.println("\n❌ 执行出错: " + e.getMessage());
                System.out.println("   ⚠️ 计划状态已持久化到 workspace，重启后可继续");
                e.printStackTrace();
                System.out.println();
            }
        }
    }

    // ==================== 计划可视化 ====================

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

    // ==================== Skill 定义 ====================

    private static AgentSkill createRemediationSkill() {
        String skillMd =
                """
                ---
                name: 运维修复
                description: >
                  运维修复操作流程。修复服务故障、变更配置或重启服务。
                  可用工具：check_service_health、update_config、restart_service
                ---
                # 运维修复操作

                你是一名资深 SRE 工程师，正在执行修复操作。

                ## 可用工具
                - `check_service_health`：检查服务健康状态
                - `update_config`：更新服务运行时配置
                - `restart_service`：优雅重启指定服务

                ## 修复步骤

                ### 第一步：确认当前健康状态
                调用 `check_service_health` 确认受影响服务的当前状态。

                ### 第二步：执行配置变更
                调用 `update_config` 修复配置。连接池耗尽 → 增大 pool.size。

                ### 第三步：重启服务
                调用 `restart_service` 优雅重启使新配置生效。

                ### 第四步：验证恢复结果
                再次调用 `check_service_health` 确认服务恢复为 UP。
                """;
        return SkillUtil.createFrom(skillMd, null);
    }

    // ==================== Workspace 初始化 ====================

    /** 初始化 workspace：AGENTS.md + Layer 3 skill（服务部署）。 */
    private static void initWorkspace(Path workspace) throws IOException {
        Files.createDirectories(workspace);

        Path agentsMd = workspace.resolve("AGENTS.md");
        if (!Files.exists(agentsMd)) {
            Files.writeString(
                    agentsMd,
                    """
                    # 智能运维助手

                    你是一个智能运维助手，擅长通过 PlanNotebook 制定计划并加载 Skill 来执行任务。

                    ## 可用技能
                    - 运维诊断：排查服务器故障（project-global）
                    - 运维修复：修复服务故障（marketplace）
                    - 服务部署：部署服务到新服务器（workspace）

                    ## 行为约定
                    - 复杂任务先规划再执行
                    - 按步骤加载对应 Skill 并严格遵循 Skill 指导
                    - 用中文回复，简洁明了
                    """);
        }

        // Layer 3: 仅 服务部署 写入 workspace/skills/
        writeSkillToDir(workspace.resolve("skills"), "服务部署", createDeploymentSkillMd());
    }

    /** 初始化 project-global-skills 目录：Layer 1 skill（运维诊断）。 */
    private static void initProjectGlobalSkills(Path projectGlobalSkills) throws IOException {
        Files.createDirectories(projectGlobalSkills);
        writeSkillToDir(projectGlobalSkills, "运维诊断", createDiagnosisSkillMd());
        System.out.println(
                "✅ [Layer1] projectGlobalSkillsDir 初始化: " + projectGlobalSkills.toAbsolutePath());
    }

    private static void writeSkillToDir(Path skillsDir, String name, String content)
            throws IOException {
        Path skillDir = skillsDir.resolve(name);
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            Files.createDirectories(skillDir);
            Files.writeString(skillFile, content);
        }
    }

    private static String createDiagnosisSkillMd() {
        return """
        ---
        name: 运维诊断
        description: >-
          运维故障诊断流程（project-global skill）
        ---
        # 运维故障诊断
        排查服务器异常、性能问题或服务降级。
        可用工具：query_metrics、fetch_logs
        """;
    }

    private static String createDeploymentSkillMd() {
        return """
        ---
        name: 服务部署
        description: >-
          服务部署流程（workspace skill）
        ---
        # 服务部署
        将服务部署到新服务器或执行扩容操作。
        可用工具：query_metrics、check_service_health、deploy_service
        """;
    }

    // ==================== 内存 SkillRepository ====================

    /** source 固定的内存 SkillRepository，保证 skillId 可预测。 */
    private static class InMemorySkillRepository implements AgentSkillRepository {

        private final String source;
        private final Map<String, AgentSkill> skills;
        private boolean writeable;

        InMemorySkillRepository(String source, List<AgentSkill> skillList) {
            this.source = source;
            this.skills = new HashMap<>();
            for (AgentSkill s : skillList) {
                this.skills.put(s.getName(), s);
            }
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.get(name);
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.copyOf(skills.keySet());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            if (skills == null || skills.isEmpty()) {
                return false;
            }
            for (AgentSkill s : skills) {
                if (!force && this.skills.containsKey(s.getName())) {
                    return false;
                }
                this.skills.put(s.getName(), s);
            }
            return true;
        }

        @Override
        public boolean delete(String skillName) {
            return skills.remove(skillName) != null;
        }

        @Override
        public boolean skillExists(String skillName) {
            return skills.containsKey(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("memory", source, writeable);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {
            this.writeable = writeable;
        }

        @Override
        public boolean isWriteable() {
            return writeable;
        }

        @Override
        public void close() {}
    }

    // ==================== UI ====================

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  HarnessAgent + 4层Skill + 流式JSONL + 自定义Hook    ║");
        System.out.println("║                                                      ║");
        System.out.println("║  Layer 1: projectGlobalSkillsDir → 运维诊断           ║");
        System.out.println("║  Layer 2: InMemorySkillRepository → 运维修复          ║");
        System.out.println("║  Layer 3: workspaceSkillsDir → 服务部署              ║");
        System.out.println("║  Layer 4: per-user fs (本示例未使用)                  ║");
        System.out.println("║                                                      ║");
        System.out.println("║  ● 流式输出: Flux.merge(LLM + ToolEventBus)          ║");
        System.out.println("║  ● 自定义Hook: planVizHook + ToolNotificationHook    ║");
        System.out.println("║  ● 计划持久化: workspace session 自动存储             ║");
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
