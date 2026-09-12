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
package io.agentscope.examples.documentation2.hitl;

import io.agentscope.core.event.AskUserResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * AskUserHITLExample - the model proactively asks the user questions (HITL "ask" direction).
 *
 * <p>With the built-in {@code ask_user} tool enabled, the model can pause the run and ask the
 * user one or more structured questions (with options; free-text input is always accepted; a
 * question may be skipped). The run pauses with
 * {@code getGenerateReason() == GenerateReason.ASK_USER_ASKING} and the {@code ask_user} tool is
 * never executed; the caller renders the questions, then resumes with {@link AskUserResult}s
 * under {@code Msg.METADATA_ASK_USER_RESULTS}.
 *
 * <p><b>Flow:</b>
 * <ol>
 *   <li>The model decides it needs information only the user can provide and calls
 *       {@code ask_user}.</li>
 *   <li>The run pauses with {@code ASK_USER_ASKING}; the pending {@code ask_user} tool call's
 *       input carries {@code questions[]}.</li>
 *   <li>This console app renders the questions, reads the user's answers, and resumes the agent;
 *       the model sees the answers as the tool result and continues.</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.hitl.AskUserHITLExample
 * </pre>
 */
@SuppressWarnings("unchecked")
public class AskUserHITLExample {

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("AskUser HITL Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates model-initiated questions: the model may call ask_user\n"
                        + "when it needs information only the user can provide. Enable it with\n"
                        + "HarnessAgent.builder().enableAskUser().\n");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("AskUserAgent")
                        .sysPrompt(
                                "You are a helpful assistant that gathers requirements before"
                                    + " acting. When you genuinely need information from the user"
                                    + " (preferences, decisions, credentials), call the ask_user"
                                    + " tool with 1-2 precise questions and concrete options. Do"
                                    + " not ask about anything you can infer yourself.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .enableAskUser()
                        .build();

        Scanner scanner = new Scanner(System.in);
        System.out.print("You: ");

        while (scanner.hasNextLine()) {
            String userInput = scanner.nextLine().trim();
            if (userInput.isBlank() || "exit".equalsIgnoreCase(userInput)) {
                break;
            }

            Msg userMsg = new UserMessage("user", userInput);
            Msg result = agent.call(List.of(userMsg)).block();

            while (result != null && result.getGenerateReason() == GenerateReason.ASK_USER_ASKING) {
                // ── The model asked structured questions; render them to the user ─────────
                System.out.println("\n[AskUser] The agent needs some information:");

                List<ToolUseBlock> pending = result.getContentBlocks(ToolUseBlock.class);
                ToolUseBlock ask = pending.get(0);
                List<Object> questions = (List<Object>) ask.getInput().get("questions");
                Map<String, Object> answers = new HashMap<>();
                for (int i = 0; i < questions.size(); i++) {
                    Map<String, Object> q = (Map<String, Object>) questions.get(i);
                    String id = String.valueOf(q.getOrDefault("id", "q_" + (i + 1)));
                    Object type = q.getOrDefault("type", "single");
                    System.out.printf("%d) [%s] %s%n", i + 1, type, q.getOrDefault("question", ""));
                    List<Object> options = (List<Object>) q.get("options");
                    if (options != null && !options.isEmpty()) {
                        for (int o = 0; o < options.size(); o++) {
                            Map<String, Object> opt = (Map<String, Object>) options.get(o);
                            System.out.printf("    %d) %s%n", o + 1, opt.get("label"));
                        }
                    }
                    System.out.print("    Your answer (or 'skip'): ");
                    String answer = scanner.hasNextLine() ? scanner.nextLine().trim() : "skip";
                    if (answer.isEmpty() || "skip".equalsIgnoreCase(answer)) {
                        answers.put(id, Map.of("skipped", true));
                    } else {
                        answers.put(id, answer);
                    }
                }

                // ── Resume with the collected answers ─────────────────────────────────────
                Map<String, Object> meta = new HashMap<>();
                meta.put(
                        Msg.METADATA_ASK_USER_RESULTS,
                        List.of(new AskUserResult(ask.getId(), answers)));
                Msg resumeMsg =
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .textContent("[answers]")
                                .metadata(meta)
                                .build();
                result = agent.call(List.of(resumeMsg)).block();
            }

            System.out.println("\nAgent: " + (result != null ? result.getTextContent() : "(null)"));

            System.out.print("\nYou: ");
        }
    }
}
