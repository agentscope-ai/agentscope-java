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
package io.agentscope.examples.a2a.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Example tools for the A2A demo.
 *
 * <p>These tools demonstrate how to create tool functions that can be called
 * by the agent during conversation.
 */
public class ExampleTools {

    private final Random random = new Random();

    /**
     * Get weather information for a city.
     *
     * <p>Note: This is a mock implementation for demo purposes.
     *
     * @param city The city name to get weather for
     * @return Weather information
     */
    @Tool(name = "get_weather", description = "Get current weather information for a city")
    public ToolResultBlock getWeather(
            @ToolParam(name = "city", description = "The city name (e.g., 'Beijing', 'New York')")
                    String city) {
        // Mock weather data
        String[] conditions = {"Sunny", "Cloudy", "Partly Cloudy", "Rainy", "Overcast"};
        String condition = conditions[random.nextInt(conditions.length)];
        int temperature = random.nextInt(35) + 5; // 5-40 degrees
        int humidity = random.nextInt(60) + 30; // 30-90%

        String result =
                String.format(
                        "Weather in %s:\n- Condition: %s\n- Temperature: %d°C\n- Humidity: %d%%",
                        city, condition, temperature, humidity);
        return ToolResultBlock.text(result);
    }

    /**
     * Perform a simple calculation.
     *
     * @param expression The math expression to evaluate
     * @return The calculation result
     */
    @Tool(
            name = "calculate",
            description = "Perform a simple arithmetic calculation (supports +, -, *, /)")
    public ToolResultBlock calculate(
            @ToolParam(
                            name = "expression",
                            description =
                                    "The arithmetic expression to calculate (e.g., '2 + 3 * 4')")
                    String expression) {
        try {
            // Simple expression evaluator (for demo purposes)
            double result = evaluateExpression(expression);
            return ToolResultBlock.text("Result: " + formatNumber(result));
        } catch (Exception e) {
            return ToolResultBlock.error("Failed to calculate: " + e.getMessage());
        }
    }

    /**
     * Get the current date and time.
     *
     * @return Current date and time
     */
    @Tool(name = "get_current_time", description = "Get the current date and time")
    public ToolResultBlock getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ToolResultBlock.text("Current time: " + now.format(formatter));
    }

    /**
     * Simple expression evaluator.
     * Supports basic arithmetic: +, -, *, /
     *
     * @param expression The arithmetic expression to evaluate
     * @return The calculated result
     * @throws IllegalArgumentException if the expression contains invalid numeric values
     */
    private double evaluateExpression(String expression) {
        // Remove whitespace
        expression = expression.replaceAll("\\s+", "");

        // Handle addition and subtraction (lowest precedence)
        int lastPlusOrMinus = -1;
        int parenDepth = 0;
        for (int i = expression.length() - 1; i >= 0; i--) {
            char c = expression.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0 && (c == '+' || c == '-') && i > 0) {
                lastPlusOrMinus = i;
                break;
            }
        }

        if (lastPlusOrMinus > 0) {
            String left = expression.substring(0, lastPlusOrMinus);
            String right = expression.substring(lastPlusOrMinus + 1);
            char op = expression.charAt(lastPlusOrMinus);
            if (op == '+') {
                return evaluateExpression(left) + evaluateExpression(right);
            } else {
                return evaluateExpression(left) - evaluateExpression(right);
            }
        }

        // Handle multiplication and division
        int lastMultOrDiv = -1;
        parenDepth = 0;
        for (int i = expression.length() - 1; i >= 0; i--) {
            char c = expression.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0 && (c == '*' || c == '/')) {
                lastMultOrDiv = i;
                break;
            }
        }

        if (lastMultOrDiv > 0) {
            String left = expression.substring(0, lastMultOrDiv);
            String right = expression.substring(lastMultOrDiv + 1);
            char op = expression.charAt(lastMultOrDiv);
            if (op == '*') {
                return evaluateExpression(left) * evaluateExpression(right);
            } else {
                return evaluateExpression(left) / evaluateExpression(right);
            }
        }

        // Handle parentheses
        if (expression.startsWith("(") && expression.endsWith(")")) {
            return evaluateExpression(expression.substring(1, expression.length() - 1));
        }

        // Parse number
        return parseNumber(expression);
    }

    private double parseNumber(String expression) {
        try {
            return Double.parseDouble(expression);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid numeric value in expression: '" + expression + "'", e);
        }
    }

    private String formatNumber(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.format("%.4f", value);
    }
}
