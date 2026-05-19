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
package io.agentscope.examples.hitlchat.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Built-in tools for the HITL Chat example.
 *
 * <p>Provides basic utilities like getting current time, listing files, and generating random
 * numbers.
 */
public class BuiltinTools {

    private final Random random = new Random();

    /**
     * Get the current date and time.
     *
     * @return Current date and time
     */
    @Tool(name = "get_time", description = "Get the current date and time")
    public ToolResultBlock getTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ToolResultBlock.text("Current time: " + now.format(formatter));
    }

    /**
     * Generate a random number within a specified range.
     *
     * @param min Minimum value (inclusive)
     * @param max Maximum value (inclusive)
     * @return A random number
     */
    @Tool(
            name = "random_number",
            description = "Generate a random integer within a specified range")
    public ToolResultBlock randomNumber(
            @ToolParam(name = "min", description = "Minimum value (inclusive)") int min,
            @ToolParam(name = "max", description = "Maximum value (inclusive)") int max) {
        if (min > max) {
            return ToolResultBlock.error("min must be less than or equal to max");
        }
        int result = random.nextInt(max - min + 1) + min;
        return ToolResultBlock.text("Random number between " + min + " and " + max + ": " + result);
    }
}
