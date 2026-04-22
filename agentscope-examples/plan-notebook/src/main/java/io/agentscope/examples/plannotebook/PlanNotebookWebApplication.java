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
package io.agentscope.examples.plannotebook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PlanNotebook Web Application - Spring Boot application with SSE streaming for
 * Agent chat and real-time plan notebook management.
 *
 * <p>Features:
 * <ul>
 *   <li>SSE streaming for agent responses</li>
 *   <li>Real-time plan state updates via SSE</li>
 *   <li>REST API for plan management operations</li>
 *   <li>Interactive web UI for chat and plan visualization</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * # Set API key
 * export DASHSCOPE_API_KEY=your_api_key
 *
 * # Run the application
 * cd agentscope-examples/multi-component/plan-notebook
 * mvn spring-boot:run
 *
 * # Open browser
 * http://localhost:8080
 * </pre>
 */
@SpringBootApplication
public class PlanNotebookWebApplication {

    public static void main(String[] args) {
        printBanner();
        SpringApplication.run(PlanNotebookWebApplication.class, args);
        printStartupInfo();
    }

    private static void printBanner() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  PlanNotebook Web Application");
        System.out.println("  AgentScope Java SDK Example");
        System.out.println("=".repeat(60) + "\n");
    }

    private static void printStartupInfo() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  Server started successfully!");
        System.out.println("=".repeat(60));
        System.out.println("\n  Open your browser and visit:");
        System.out.println("    http://localhost:8080");
        System.out.println("\n  Chat API:");
        System.out.println("    GET  /api/chat?message=...        - Chat with agent (SSE)");
        System.out.println("    GET  /api/health                  - Health check");
        System.out.println("    POST /api/reset                   - Reset agent and plans");
        System.out.println("\n  Plan API:");
        System.out.println("    GET    /api/plan/stream           - Plan state stream (SSE)");
        System.out.println("    GET    /api/plan                  - Get current plan");
        System.out.println("    PUT    /api/plan                  - Update plan info");
        System.out.println("    POST   /api/plan/subtasks         - Add subtask");
        System.out.println("    PUT    /api/plan/subtasks/{idx}   - Revise subtask");
        System.out.println("    DELETE /api/plan/subtasks/{idx}   - Delete subtask");
        System.out.println("    PATCH  /api/plan/subtasks/{idx}/state  - Update subtask state");
        System.out.println("    POST   /api/plan/subtasks/{idx}/finish - Finish subtask");
        System.out.println("    POST   /api/plan/finish           - Finish plan");
        System.out.println("\n  Press Ctrl+C to stop.\n");
    }
}
