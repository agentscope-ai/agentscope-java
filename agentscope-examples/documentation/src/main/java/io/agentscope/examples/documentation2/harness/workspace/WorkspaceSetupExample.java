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
package io.agentscope.examples.documentation2.harness.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WorkspaceSetupExample — Demonstrates the workspace directory layout and how each file is
 * loaded into the agent's system prompt.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>Workspace layout</b> — creates a full workspace with {@code AGENTS.md},
 *       {@code knowledge/KNOWLEDGE.md}, {@code subagents/researcher.md}, and a custom
 *       {@code PREFERENCES.md}.</li>
 *   <li><b>{@code AGENTS.md} injection</b> — the persona file is injected into the system
 *       prompt every reasoning step.</li>
 *   <li><b>Knowledge directory</b> — {@code KNOWLEDGE.md} is injected in full; other files
 *       under {@code knowledge/} are listed by path and read on demand.</li>
 *   <li><b>{@code additionalContextFile}</b> — custom files injected alongside the standard
 *       workspace content.</li>
 *   <li><b>Subagent spec files</b> — dropping a {@code .md} in {@code subagents/} makes it
 *       available via {@code agent_spawn}.</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSetupExample
 * </pre>
 */
public class WorkspaceSetupExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Workspace — Directory Layout & Content Injection");
        System.out.println("=".repeat(60) + "\n");

        // ── Create a workspace with all key files ───────────────────────────

        Path workspace = Files.createTempDirectory("agentscope-workspace-example");

        // AGENTS.md — persona and behavior rules
        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # Project Assistant

                You are a project management assistant for the "Phoenix" project.

                ## Behavior
                - Always greet the user by referencing the Phoenix project.
                - Use the knowledge base to answer domain questions.
                - Delegate research tasks to the researcher subagent.
                - Refer to PREFERENCES.md for response formatting rules.
                """);

        // knowledge/KNOWLEDGE.md — domain knowledge entry point
        Path knowledgeDir = Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(
                knowledgeDir.resolve("KNOWLEDGE.md"),
                """
                # Phoenix Project Knowledge Base

                ## Overview
                Phoenix is a cloud-native microservices platform built with Java 17+
                and Spring Boot 3.x. It consists of 5 core services:
                - gateway-service (API routing)
                - auth-service (OAuth 2.0 + JWT)
                - user-service (user management)
                - order-service (order processing)
                - notification-service (email + push)

                ## Tech Stack
                - Runtime: JDK 21, Spring Boot 3.3
                - Database: PostgreSQL 16 + Redis 7
                - Messaging: Apache Kafka 3.7
                - Deployment: Kubernetes 1.29 on AWS EKS
                """);

        // knowledge/api-conventions.md — additional reference (listed but not injected)
        Files.writeString(
                knowledgeDir.resolve("api-conventions.md"),
                """
                # API Conventions

                - All endpoints use kebab-case: `/api/v1/user-profiles`
                - Pagination via `?page=0&size=20`
                - Error responses follow RFC 7807 Problem Details
                - Rate limiting: 100 req/min per API key
                """);

        // subagents/researcher.md — subagent declaration
        Path subagentsDir = Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                subagentsDir.resolve("researcher.md"),
                """
                ---
                description: Research specialist. Use when the user needs investigation on a topic.
                ---

                You are a research subagent for the Phoenix project.
                Investigate the given topic and provide a concise summary.
                """);

        // PREFERENCES.md — additional context file
        Files.writeString(
                workspace.resolve("PREFERENCES.md"),
                """
                # Response Preferences

                - Use bullet points for lists, not numbered lists.
                - Keep responses under 200 words.
                - Always end with a "Next steps" section.
                """);

        System.out.println("Workspace created at: " + workspace);
        System.out.println("Contents:");
        Files.walk(workspace)
                .filter(p -> !p.equals(workspace))
                .forEach(
                        p -> {
                            String rel = workspace.relativize(p).toString();
                            System.out.println("  " + (Files.isDirectory(p) ? rel + "/" : rel));
                        });

        // ── Build the agent with the workspace ──────────────────────────────

        System.out.println("\n── Building HarnessAgent with workspace ──\n");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("phoenix-assistant")
                        .sysPrompt("You are the Phoenix project assistant.")
                        .model("qwen-plus")
                        .workspace(workspace)
                        .additionalContextFile("PREFERENCES.md")
                        .maxContextTokens(4000)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("workspace-demo").build();

        // ── Ask questions that exercise workspace content ────────────────────

        System.out.println("── Question 1: Does the agent know the project persona? ──\n");
        System.out.print("Agent: ");
        agent.streamEvents(new UserMessage("Introduce yourself and the project you support."), ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            }
                        })
                .blockLast();

        System.out.println("\n\n── Question 2: Does the agent know the knowledge base? ──\n");
        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage("What services does the Phoenix platform consist of?"), ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            }
                        })
                .blockLast();

        System.out.println("\n\n── Question 3: Can the agent read knowledge reference files? ──\n");
        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage(
                                "What are the API conventions for the project? Check the knowledge"
                                        + " base files for details."),
                        ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n[TOOL] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        System.out.println("\n\n" + "=".repeat(60));
        System.out.println("Done. Workspace at: " + workspace);
        System.out.println("=".repeat(60));
    }
}
