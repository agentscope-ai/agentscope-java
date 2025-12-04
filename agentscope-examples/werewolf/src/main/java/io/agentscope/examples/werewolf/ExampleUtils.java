/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.werewolf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Utility class providing common functionality for examples.
 */
public class ExampleUtils {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    /**
     * Get DashScope API key from environment variable or interactive input.
     *
     * @return API key
     * @throws IOException if input fails
     */
    public static String getDashScopeApiKey() throws IOException {
        return getApiKey(
                "DASHSCOPE_API_KEY", "DashScope", "https://dashscope.console.aliyun.com/apiKey");
    }

    /**
     * Get API key from environment variable or interactive input.
     *
     * @param envVarName environment variable name
     * @param serviceName service name for display
     * @param helpUrl URL to get API key
     * @return API key
     * @throws IOException if input fails
     */
    public static String getApiKey(String envVarName, String serviceName, String helpUrl)
            throws IOException {

        // 1. Try environment variable
        String apiKey = System.getenv(envVarName);

        if (apiKey != null && !apiKey.isEmpty()) {
            System.out.println("✓ Using API key from environment variable " + envVarName + "\n");
            return apiKey;
        }

        // 2. Interactive input
        System.out.println(envVarName + " environment variable not found.\n");
        System.out.println("Please enter your " + serviceName + " API Key:");
        System.out.println("(Get one at: " + helpUrl + ")");
        System.out.print("\nAPI Key: ");

        apiKey = reader.readLine().trim();

        if (apiKey.isEmpty()) {
            System.err.println("Error: API Key cannot be empty");
            System.exit(1);
        }

        System.out.println("\n✓ API Key configured");
        System.out.println("Tip: Set environment variable to skip this step:");
        System.out.println("  export " + envVarName + "=" + maskApiKey(apiKey) + "\n");

        return apiKey;
    }

    /**
     * Mask API key for display (show first 4 and last 4 characters).
     *
     * @param apiKey API key to mask
     * @return masked API key
     */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * Print a welcome banner.
     *
     * @param title example title
     * @param description example description
     */
    public static void printWelcome(String title, String description) {
        System.out.println("=== " + title + " ===\n");
        System.out.println(description);
        System.out.println();
    }
}
