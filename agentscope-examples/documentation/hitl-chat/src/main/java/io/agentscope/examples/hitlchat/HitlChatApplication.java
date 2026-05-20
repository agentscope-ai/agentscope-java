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
package io.agentscope.examples.hitlchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HITL Chat Application - Human-in-the-Loop chat example.
 *
 * <p>This application demonstrates:
 * <ul>
 *   <li>Dynamic MCP tool configuration</li>
 *   <li>Agent interruption during conversation</li>
 *   <li>Dangerous tool confirmation before execution</li>
 *   <li>Built-in tools (time, file, random number)</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <ol>
 *   <li>Set the DASHSCOPE_API_KEY environment variable</li>
 *   <li>Run this application</li>
 *   <li>Open http://localhost:8080 in a browser</li>
 * </ol>
 */
@SpringBootApplication
public class HitlChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(HitlChatApplication.class, args);
        printStartupInfo();
    }

    private static void printStartupInfo() {
        System.out.println("\n=== HITL Chat Application Started ===");
        System.out.println("Open: http://localhost:8080");
        System.out.println("\nFeatures:");
        System.out.println("  - Dynamic MCP tool configuration");
        System.out.println("  - Agent interruption support");
        System.out.println("  - Dangerous tool confirmation");
        System.out.println("  - Built-in tools: get_time, list_files, random_number");
        System.out.println("\nPress Ctrl+C to stop.");
    }
}
