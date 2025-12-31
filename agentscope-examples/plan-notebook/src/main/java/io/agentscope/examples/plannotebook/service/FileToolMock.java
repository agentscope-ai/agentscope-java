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
package io.agentscope.examples.plannotebook.service;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

public class FileToolMock {

    private static final Map<String, String> fileStorage = new HashMap<>();

    /**
     * Clear all stored files.
     */
    public static void clearStorage() {
        fileStorage.clear();
    }

    @Tool(name = "write_file", description = "Write content to a file")
    public Mono<String> writeFile(
            @ToolParam(name = "filename", description = "File name") String filename,
            @ToolParam(name = "content", description = "Content") String content) {
        System.out.println("\n📝 [write_file] " + filename + " (" + content.length() + " chars)");
        fileStorage.put(filename, content);
        return Mono.just("File saved: " + filename);
    }

    @Tool(name = "read_file", description = "Read content from a file")
    public Mono<String> readFile(
            @ToolParam(name = "filename", description = "File name") String filename) {
        System.out.println("\n📖 [read_file] " + filename);
        if (!fileStorage.containsKey(filename)) {
            return Mono.just("Error: File not found");
        }
        return Mono.just(fileStorage.get(filename));
    }

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

    private static double evaluateExpression(String expr) {
        expr = expr.replaceAll("\\s+", "");
        // Handle * and /
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
        // Handle + and -
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
}
