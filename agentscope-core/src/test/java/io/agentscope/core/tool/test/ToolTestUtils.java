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
package io.agentscope.core.tool.test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility methods for Tool testing.
 *
 * <p>Provides helper methods for creating tool parameters, validating responses, and testing tool
 * execution.
 */
public class ToolTestUtils {

    /**
     * Create simple tool parameters.
     */
    public static Map<String, Object> createSimpleParams(String key, Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put(key, value);
        return params;
    }

    /**
     * Create complex tool parameters.
     */
    public static Map<String, Object> createComplexParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("string_param", "test value");
        params.put("int_param", 42);
        params.put("double_param", 3.14);
        params.put("boolean_param", true);
        params.put("array_param", new String[] {"a", "b", "c"});
        return params;
    }

    /**
     * Validate tool response.
     */
    public static boolean isValidToolResultBlock(ToolResultBlock response) {
        return response != null && response.getOutput() != null && !response.getOutput().isEmpty();
    }

    /**
     * Check if tool response contains error (by checking metadata).
     */
    public static boolean isErrorResponse(ToolResultBlock response) {
        if (response == null || response.getMetadata() == null) {
            return false;
        }
        Object error = response.getMetadata().get("error");
        return error != null && Boolean.TRUE.equals(error);
    }

    /**
     * Create success response with text content.
     */
    public static ToolResultBlock createSuccessResponse(String content) {
        return ToolResultBlock.text(content);
    }

    /**
     * Create error response with error message.
     */
    public static ToolResultBlock createErrorResponse(String errorMessage) {
        return ToolResultBlock.error(errorMessage);
    }

    /**
     * Extract content from response as string.
     */
    public static String extractContent(ToolResultBlock response) {
        if (response == null || response.getOutput() == null || response.getOutput().isEmpty()) {
            return null;
        }

        if (response.getOutput().get(0) instanceof TextBlock) {
            return ((TextBlock) response.getOutput().get(0)).getText();
        }

        return null;
    }

    private ToolTestUtils() {
        // Utility class
    }
}
