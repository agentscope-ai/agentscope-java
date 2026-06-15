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
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.quickstart.util.MsgUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * PlanNotebook 示例 —— 演示 Agent 如何制定并执行多步骤计划。
 *
 * <p><b>核心架构（两层设计）：</b>
 * <ol>
 *   <li><b>PlanNotebook 自身</b> — 数据结构 + 10 个 @Tool 方法 + Hint 生成器
 *     <ul>
 *       <li>数据结构: Plan → List&lt;SubTask&gt;，每个 SubTask 有状态 TODO → IN_PROGRESS → DONE/ABANDONED</li>
 *       <li>@Tool 方法: create_plan、finish_subtask、revise_current_plan、finish_plan 等，注册给 Agent 使用</li>
 *       <li>PlanToHint: 根据当前 plan 状态自动生成上下文提示</li>
 *     </ul>
 *   </li>
 *   <li><b>PlanHintHook（框架自动注册）</b> — 桥梁，将 PlanNotebook 的状态注入 LLM 上下文
 *     <ul>
 *       <li>拦截 PreReasoningEvent → 调用 planNotebook.getCurrentHint() → 追加到 LLM 输入消息</li>
 *       <li>每一轮 ReAct 循环，LLM 都会收到当前 plan 状态的提示</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p><b>完整执行流程：</b>
 * <pre>
 *   agent.call("计算面积，保存，验证")
 *   → PreReasoning Hook: "无 plan，请用 create_plan"
 *   → LLM: create_plan(3 个 subtask)
 *   → PreReasoning Hook: "subtask[0] TODO，请标记 IN_PROGRESS"
 *   → LLM: update_subtask_state(0, IN_PROGRESS)
 *   → LLM: calculate("10*5") → 50
 *   → LLM: finish_subtask(0, "面积=50")  // 自动激活 subtask[1]
 *   → ... 依次执行 write_file → read_file
 *   → LLM: finish_plan()
 * </pre>
 */
public class PlanNotebookExample {

    /** 模拟文件存储（内存中的 HashMap）。 */
    private static final Map<String, String> fileStorage = new HashMap<>();

    /**
     * 工具1: 写文件。
     * 将内容保存到模拟的文件存储中。
     */
    @Tool(name = "write_file", description = "Write content to a file")
    public Mono<String> writeFile(
            @ToolParam(name = "filename", description = "File name") String filename,
            @ToolParam(name = "content", description = "Content") String content) {
        System.out.println("\n📝 [write_file] " + filename + " (" + content.length() + " chars)");
        fileStorage.put(filename, content);
        return Mono.just("File saved: " + filename);
    }

    /**
     * 工具2: 读文件。
     * 从模拟的文件存储中读取内容。
     */
    @Tool(name = "read_file", description = "Read content from a file")
    public Mono<String> readFile(
            @ToolParam(name = "filename", description = "File name") String filename) {
        System.out.println("\n📖 [read_file] " + filename);
        if (!fileStorage.containsKey(filename)) {
            return Mono.just("Error: File not found");
        }
        return Mono.just(fileStorage.get(filename));
    }

    /**
     * 工具3: 计算器。
     * 支持 +、-、*、/ 四则运算。
     */
    @Tool(name = "calculate", description = "Basic math: +, -, *, /")
    public Mono<String> calculate(
            @ToolParam(name = "expression", description = "Math expression") String expression) {
        System.out.println("\n🔢 [calculate] " + expression);
        try {
            double result = evaluateExpression(expression);
            return Mono.just(expression + " = " + result);
        } catch (Exception e) {
            return Mono.just("Error: " + e.getMessage());
        }
    }

    /**
     * 简易四则运算解析器。
     * 先处理乘除（从左到右），再处理加减。
     */
    private static double evaluateExpression(String expr) {
        expr = expr.replaceAll("\\s+", "");
        // 先处理乘除（* 和 /）
        while (expr.contains("*") || expr.contains("/")) {
            String[] parts = expr.split("(?=[*/])|(?<=[*/])");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("*") && i > 0 && i < parts.length - 1) {
                    double result =
                            Double.parseDouble(parts[i - 1]) * Double.parseDouble(parts[i + 1]);
                    expr =
                            expr.replaceFirst(
                                    parts[i - 1] + "\\*" + parts[i + 1], String.valueOf(result));
                    break;
                } else if (parts[i].equals("/") && i > 0 && i < parts.length - 1) {
                    double result =
                            Double.parseDouble(parts[i - 1]) / Double.parseDouble(parts[i + 1]);
                    expr =
                            expr.replaceFirst(
                                    parts[i - 1] + "/" + parts[i + 1], String.valueOf(result));
                    break;
                }
            }
        }
        // 再处理加减（+ 和 -）
        String[] terms = expr.split("(?=[+\\-])|(?<=[+\\-])");
        double result = 0;
        String operator = "+";
        for (String term : terms) {
            if (term.equals("+") || term.equals("-")) {
                operator = term;
            } else if (!term.isEmpty()) {
                double value = Double.parseDouble(term);
                result = operator.equals("+") ? result + value : result - value;
            }
        }
        return result;
    }

    /**
     * 打印当前 Plan 状态的可视化面板。
     * 通过 PostActingEvent Hook 触发，每次 plan 工具调用后展示当前进度。
     */
    private static void printPlanState(PlanNotebook notebook, String event) {
        Plan currentPlan = notebook.getCurrentPlan();
        if (currentPlan == null) {
            System.out.println("\n📋 [" + event + "] No active plan");
            return;
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("📋 PLAN STATE [" + event + "]");
        System.out.println("=".repeat(70));
        System.out.println("Plan: " + currentPlan.getName());
        System.out.println("State: " + currentPlan.getState());
        System.out.println("\nSubtasks:");

        for (int i = 0; i < currentPlan.getSubtasks().size(); i++) {
            SubTask subtask = currentPlan.getSubtasks().get(i);
            // 用图标区分四种 SubTask 状态
            String icon =
                    switch (subtask.getState()) {
                        case TODO -> "⏸️"; // 待办
                        case IN_PROGRESS -> "▶️"; // 进行中
                        case DONE -> "✅"; // 已完成
                        case ABANDONED -> "❌"; // 已放弃
                    };
            System.out.printf(
                    "  %s [%d] %s - %s%n", icon, i, subtask.getName(), subtask.getState());
        }
        System.out.println("=".repeat(70) + "\n");
    }

    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "PlanNotebook Example",
                "Watch how the agent creates and executes a plan step-by-step!");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        // ─── 1. 注册自定义工具 ───
        // 将 PlanNotebookExample 实例本身注册为工具提供者
        // （write_file、read_file、calculate 三个 @Tool 方法会被自动发现）
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PlanNotebookExample());

        // ─── 2. 创建 PlanNotebook ───
        // PlanNotebook 自身也包含 10 个 @Tool 方法（create_plan、finish_subtask 等）
        // 这些工具会在 configurePlan() 阶段自动注册到 toolkit
        PlanNotebook planNotebook = PlanNotebook.builder().needUserConfirm(false).build();

        // ─── 3. 创建可视化 Hook ───
        // 在每次工具调用完成后打印 Plan 状态面板
        // 这只是可视化辅助，核心的 PlanHintHook 是框架自动注册的
        Hook planVisualizationHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent postActing) {
                            String toolName = postActing.getToolUse().getName();
                            printPlanState(planNotebook, "After " + toolName);
                        }
                        return Mono.just(event);
                    }
                };

        // ─── 4. 创建 Agent ───
        // planNotebook(planNotebook) 会触发 configurePlan():
        //   - 在 toolkit 中注册 PlanNotebook 的 10 个 @Tool 方法
        //   - 自动注册 PlanHintHook（在 PreReasoning 阶段注入 plan 状态提示）
        ReActAgent agent =
                ReActAgent.builder()
                        .name("PlanAgent")
                        .sysPrompt(
                                "You are a systematic assistant. For multi-step tasks:\n"
                                        + "1. Create a plan with create_plan tool\n"
                                        + "2. Execute subtasks one by one\n"
                                        + "3. Use finish_subtask after completing each\n"
                                        + "4. Call finish_plan when all done")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(toolkit)
                        .maxIters(100) // 允许多轮迭代（plan 场景步骤多）
                        .hooks(List.of(planVisualizationHook))
                        .planNotebook(planNotebook) // ← 启用 PlanNotebook
                        .build();

        // ─── 5. 构造多步骤任务 ───
        System.out.println("\n" + "=".repeat(70));
        System.out.println("TASK");
        System.out.println("=".repeat(70));
        String userInput =
                "Calculate the area of a rectangle (length=10, width=5), then save the result to"
                        + " 'result.txt' and verify by reading it back. This is a multi-step task -"
                        + " please organize with a plan. 然后执行plan";
        System.out.println(userInput);
        System.out.println("=".repeat(70) + "\n");

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userInput).build())
                        .build();

        System.out.println("🚀 Starting execution...\n");

        // ─── 6. 执行 Agent ───
        // agent.call() 内部会:
        //   每一轮 PreReasoning → PlanHintHook 注入当前 plan 状态 hint
        //   → LLM 根据 hint 决定调用 create_plan / update_subtask_state / finish_subtask 等
        //   → 工具执行完后 PostActingEvent → 可视化 Hook 打印 plan 面板
        //   → 循环直到 finish_plan 或 maxIters 耗尽
        Msg response = agent.call(userMsg).block();

        // ─── 7. 展示最终结果 ───
        System.out.println("\n" + "=".repeat(70));
        System.out.println("FINAL RESPONSE");
        System.out.println("=".repeat(70));
        String finalText = MsgUtils.getTextContent(response);
        System.out.println(finalText != null ? finalText : "(No response)");
        System.out.println("=".repeat(70) + "\n");

        if (fileStorage.containsKey("result.txt")) {
            System.out.println("📄 Saved File Content:");
            System.out.println("  " + fileStorage.get("result.txt") + "\n");
        }
    }
}
