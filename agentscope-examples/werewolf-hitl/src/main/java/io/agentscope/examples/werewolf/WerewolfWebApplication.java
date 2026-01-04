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
package io.agentscope.examples.werewolf;

import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for Werewolf game web interface.
 */
@SpringBootApplication(scanBasePackages = "io.agentscope.examples.werewolf")
public class WerewolfWebApplication {

    public static void main(String[] args) throws IOException {
        // Check API key
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: DASHSCOPE_API_KEY environment variable not set.");
            System.err.println("Please set it before starting the game.");
            System.exit(1);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("          Werewolf Game - Web Interface");
        System.out.println("=".repeat(60));

        SpringApplication.run(WerewolfWebApplication.class, args);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  Server started! Open http://localhost:8080 in your browser");
        System.out.println("=".repeat(60) + "\n");
    }
}
