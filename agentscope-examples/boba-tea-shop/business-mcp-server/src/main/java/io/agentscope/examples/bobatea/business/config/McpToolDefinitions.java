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

package io.agentscope.examples.bobatea.business.config;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared MCP Tool Definitions.
 * Contains all tool metadata (name, description, schema) used by both
 * McpServerConfig and McpServerRegistrar.
 *
 */
public class McpToolDefinitions {

    /**
     * Tool definition record containing name, description, and schema.
     */
    public record ToolDefinition(String name, String description, Map<String, Object> schema) {

        /**
         * Get JsonSchema for MCP SDK 0.17.0+.
         */
        public JsonSchema jsonSchema() {
            String type = (String) schema.getOrDefault("type", "object");

            Map<String, Object> properties;
            Object rawProperties = schema.get("properties");
            if (rawProperties instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castProperties = (Map<String, Object>) rawProperties;
                properties = castProperties;
            } else {
                properties = new HashMap<>();
            }

            List<String> required = new ArrayList<>();
            Object rawRequired = schema.get("required");
            if (rawRequired instanceof List) {
                List<?> rawList = (List<?>) rawRequired;
                for (Object value : rawList) {
                    if (value instanceof String) {
                        required.add((String) value);
                    }
                }
            }
            return new JsonSchema(type, properties, required, null, null, null);
        }
    }

    // ===================== Order Tools =====================

    public static final ToolDefinition ORDER_CREATE_ORDER_WITH_USER =
            new ToolDefinition(
                    "order-create-order-with-user",
                    "Create a new boba tea order for user. Supports all products from Cloud Edge"
                            + " Boba Tea Shop.",
                    createOrderWithUserSchema());

    public static final ToolDefinition ORDER_GET_ORDER =
            new ToolDefinition(
                    "order-get-order",
                    "Query order details by order ID, including product name, sweetness, ice level,"
                            + " quantity, price and creation time.",
                    createGetOrderSchema());

    public static final ToolDefinition ORDER_GET_ORDER_BY_USER =
            new ToolDefinition(
                    "order-get-order-by-user",
                    "Query order details by user ID and order ID.",
                    createGetOrderByUserSchema());

    public static final ToolDefinition ORDER_CHECK_STOCK =
            new ToolDefinition(
                    "order-check-stock",
                    "Check if the specified product has sufficient stock.",
                    createCheckStockSchema());

    public static final ToolDefinition ORDER_GET_ORDERS =
            new ToolDefinition(
                    "order-get-orders",
                    "Get a list of all orders in the system.",
                    createEmptySchema());

    public static final ToolDefinition ORDER_GET_ORDERS_BY_USER =
            new ToolDefinition(
                    "order-get-orders-by-user",
                    "Get all orders for a user by user ID.",
                    createUserIdSchema());

    public static final ToolDefinition ORDER_QUERY_ORDERS =
            new ToolDefinition(
                    "order-query-orders",
                    "Query user orders by multiple conditions.",
                    createQueryOrdersSchema());

    public static final ToolDefinition ORDER_DELETE_ORDER =
            new ToolDefinition(
                    "order-delete-order",
                    "Delete an order by user ID and order ID.",
                    createDeleteOrderSchema());

    public static final ToolDefinition ORDER_UPDATE_REMARK =
            new ToolDefinition(
                    "order-update-remark",
                    "Update order remark by user ID and order ID.",
                    createUpdateRemarkSchema());

    public static final ToolDefinition ORDER_VALIDATE_PRODUCT =
            new ToolDefinition(
                    "order-validate-product",
                    "Validate if the specified product exists and is available.",
                    createProductNameSchema());

    // ===================== Feedback Tools =====================

    public static final ToolDefinition FEEDBACK_CREATE_FEEDBACK =
            new ToolDefinition(
                    "feedback-create-feedback",
                    "Create user feedback record, userId is required",
                    createFeedbackSchema());

    public static final ToolDefinition FEEDBACK_GET_FEEDBACK_BY_USER =
            new ToolDefinition(
                    "feedback-get-feedback-by-user",
                    "Query feedback records by user ID",
                    createUserIdSchema());

    public static final ToolDefinition FEEDBACK_GET_FEEDBACK_BY_ORDER =
            new ToolDefinition(
                    "feedback-get-feedback-by-order",
                    "Query feedback records by order ID",
                    createOrderIdSchema());

    public static final ToolDefinition FEEDBACK_UPDATE_SOLUTION =
            new ToolDefinition(
                    "feedback-update-solution",
                    "Update feedback solution",
                    createUpdateSolutionSchema());

    // ===================== Tool Lists =====================

    /**
     * Get all order tool definitions.
     */
    public static List<ToolDefinition> getOrderTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(ORDER_CREATE_ORDER_WITH_USER);
        tools.add(ORDER_GET_ORDER);
        tools.add(ORDER_GET_ORDER_BY_USER);
        tools.add(ORDER_CHECK_STOCK);
        tools.add(ORDER_GET_ORDERS);
        tools.add(ORDER_GET_ORDERS_BY_USER);
        tools.add(ORDER_QUERY_ORDERS);
        tools.add(ORDER_DELETE_ORDER);
        tools.add(ORDER_UPDATE_REMARK);
        tools.add(ORDER_VALIDATE_PRODUCT);
        return tools;
    }

    /**
     * Get all feedback tool definitions.
     */
    public static List<ToolDefinition> getFeedbackTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(FEEDBACK_CREATE_FEEDBACK);
        tools.add(FEEDBACK_GET_FEEDBACK_BY_USER);
        tools.add(FEEDBACK_GET_FEEDBACK_BY_ORDER);
        tools.add(FEEDBACK_UPDATE_SOLUTION);
        return tools;
    }

    /**
     * Get all tool definitions.
     */
    public static List<ToolDefinition> getAllTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.addAll(getOrderTools());
        tools.addAll(getFeedbackTools());
        return tools;
    }

    // ===================== Schema Definitions =====================

    private static Map<String, Object> createOrderWithUserSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "userId",
                Map.of("type", "integer", "description", "User ID, must be a positive integer"));
        properties.put(
                "productName",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Product name, must be an existing product at Cloud Edge Boba Tea Shop"));
        properties.put(
                "sweetness",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Sweetness requirement: Regular Sugar, Less Sugar, Half Sugar, Light Sugar,"
                                + " No Sugar"));
        properties.put(
                "iceLevel",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Ice level requirement: Regular Ice, Less Ice, No Ice, Warm, Hot"));
        properties.put(
                "quantity",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Purchase quantity, must be a positive integer, default is 1"));
        properties.put("remark", Map.of("type", "string", "description", "Order remark, optional"));
        schema.put("properties", properties);

        schema.put(
                "required", List.of("userId", "productName", "sweetness", "iceLevel", "quantity"));
        return schema;
    }

    private static Map<String, Object> createGetOrderSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "orderId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Order ID, unique identifier starting with ORDER_"));
        schema.put("properties", properties);

        schema.put("required", List.of("orderId"));
        return schema;
    }

    private static Map<String, Object> createGetOrderByUserSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "userId",
                Map.of("type", "integer", "description", "User ID, must be a positive integer"));
        properties.put(
                "orderId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Order ID, unique identifier starting with ORDER_"));
        schema.put("properties", properties);

        schema.put("required", List.of("userId", "orderId"));
        return schema;
    }

    private static Map<String, Object> createCheckStockSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("productName", Map.of("type", "string", "description", "Product name"));
        properties.put("quantity", Map.of("type", "integer", "description", "Quantity to check"));
        schema.put("properties", properties);

        schema.put("required", List.of("productName", "quantity"));
        return schema;
    }

    private static Map<String, Object> createEmptySchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        return schema;
    }

    private static Map<String, Object> createUserIdSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "userId",
                Map.of("type", "integer", "description", "User ID, must be a positive integer"));
        schema.put("properties", properties);

        schema.put("required", List.of("userId"));
        return schema;
    }

    private static Map<String, Object> createQueryOrdersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "userId",
                Map.of("type", "integer", "description", "User ID, must be a positive integer"));
        properties.put(
                "productName",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Product name, optional, supports fuzzy matching"));
        properties.put(
                "sweetness",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Sweetness: 1-No Sugar, 2-Light Sugar, 3-Half Sugar, 4-Less Sugar,"
                                + " 5-Regular Sugar"));
        properties.put(
                "iceLevel",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Ice level: 1-Hot, 2-Warm, 3-No Ice, 4-Less Ice, 5-Regular Ice"));
        properties.put(
                "startTime",
                Map.of("type", "string", "description", "Start time, format: yyyy-MM-dd HH:mm:ss"));
        properties.put(
                "endTime",
                Map.of("type", "string", "description", "End time, format: yyyy-MM-dd HH:mm:ss"));
        schema.put("properties", properties);

        schema.put("required", List.of("userId"));
        return schema;
    }

    private static Map<String, Object> createDeleteOrderSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "userId",
                Map.of("type", "integer", "description", "User ID, must be a positive integer"));
        properties.put(
                "orderId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Order ID, unique identifier starting with ORDER_"));
        schema.put("properties", properties);

        schema.put("required", List.of("userId", "orderId"));
        return schema;
    }

    private static Map<String, Object> createUpdateRemarkSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "userId",
                Map.of("type", "integer", "description", "User ID, must be a positive integer"));
        properties.put(
                "orderId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Order ID, unique identifier starting with ORDER_"));
        properties.put("remark", Map.of("type", "string", "description", "New remark content"));
        schema.put("properties", properties);

        schema.put("required", List.of("userId", "orderId", "remark"));
        return schema;
    }

    private static Map<String, Object> createProductNameSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("productName", Map.of("type", "string", "description", "Product name"));
        schema.put("properties", properties);

        schema.put("required", List.of("productName"));
        return schema;
    }

    private static Map<String, Object> createFeedbackSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("userId", Map.of("type", "integer", "description", "User ID, required"));
        properties.put(
                "feedbackType",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Feedback type: 1-Product Feedback, 2-Service Feedback, 3-Complaint,"
                                + " 4-Suggestion"));
        properties.put("content", Map.of("type", "string", "description", "Feedback content"));
        properties.put(
                "orderId",
                Map.of("type", "string", "description", "Associated order ID, optional"));
        properties.put(
                "rating", Map.of("type", "integer", "description", "Rating 1-5 stars, optional"));
        schema.put("properties", properties);

        schema.put("required", List.of("userId", "feedbackType", "content"));
        return schema;
    }

    private static Map<String, Object> createOrderIdSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("orderId", Map.of("type", "string", "description", "Order ID"));
        schema.put("properties", properties);

        schema.put("required", List.of("orderId"));
        return schema;
    }

    private static Map<String, Object> createUpdateSolutionSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("feedbackId", Map.of("type", "integer", "description", "Feedback ID"));
        properties.put("solution", Map.of("type", "string", "description", "Solution"));
        schema.put("properties", properties);

        schema.put("required", List.of("feedbackId", "solution"));
        return schema;
    }
}
