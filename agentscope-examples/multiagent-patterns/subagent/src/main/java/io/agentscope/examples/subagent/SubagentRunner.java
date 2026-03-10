/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.subagent;

import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Interactive chat runner for the Tech Due Diligence Assistant (orchestrator invoked via graph).
 * Runs when {@code subagent.run-interactive=true}.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "subagent.run-interactive", havingValue = "true")
public class SubagentRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    private final OrchestratorService orchestratorService;

    public SubagentRunner(OrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Tech Due Diligence Assistant ready. Type your request (or 'quit' to exit).");
        log.info(
                "Example: Analyze this codebase for Spring usage and research Spring AI"
                        + " alternatives.");
        log.info("");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nUSER: ");
                String input = scanner.nextLine();
                if (input == null || input.isBlank()) {
                    continue;
                }
                if ("quit".equalsIgnoreCase(input.trim())
                        || "exit".equalsIgnoreCase(input.trim())) {
                    log.info("Goodbye.");
                    break;
                }

                try {
                    String response = orchestratorService.run(input);
                    log.info("ASSISTANT: {}", response != null ? response : "(no response)");
                } catch (Exception e) {
                    log.error("Error: {}", e.getMessage());
                }
            }
        }
    }
}
