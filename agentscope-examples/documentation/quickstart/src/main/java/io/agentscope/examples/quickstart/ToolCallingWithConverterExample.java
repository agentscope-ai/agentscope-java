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
package io.agentscope.examples.quickstart;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.DefaultToolResultConverter;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ToolCallingWithConverterExample - Demonstrates how to customize ToolResultConverter.
 *
 * <p>This example shows how to customize result conversion logic by extending DefaultToolResultConverter:
 * <ol>
 *   <li>Sensitive Data Masking Converter - Automatically masks sensitive fields like password, API Key, etc.</li>
 *   <li>Schema Enhancement Converter - Adds detailed JSON Schema information, referring to DefaultToolResultConverter2</li>
 * </ol>
 */
public class ToolCallingWithConverterExample {

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Tool Calling Custom ToolResultConverter Example",
                "This example demonstrates how to equip an Agent with tools and"
                        + " ToolResultConverters.\n"
                        + "The agent has access to: get_user_info, list_orders.");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create and register tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        System.out.println("Registered tools:");
        System.out.println("  - get_user_info: Get user info by user id");
        System.out.println("  - list_orders: Get order list by user id\n");

        // Create Agent with tools
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to tools. "
                                        + "Use tools when needed to answer questions accurately. "
                                        + "Always explain what you're doing when using tools.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }

    /**
     * Simple tools for demonstration.
     *
     * <p>Each method annotated with @Tool becomes a callable tool for the agent.
     */
    public static class SimpleTools {

        /**
         * Retrieve user information by user ID.
         * @param userId
         * @return
         */
        @Tool(
                name = "get_user_info",
                description = "Retrieve user information by user ID",
                converter = SensitiveDataMaskingConverter.class)
        public UserInfo getUserInfo(
                @ToolParam(name = "userId", description = "User ID") String userId) {

            // Creates user data containing sensitive information
            return new UserInfo(
                    userId,
                    "John Doe",
                    "john@example.com",
                    "MySecretPassword123",
                    "sk-1234567890abcdef",
                    "4567-1234-8888-6666");
        }

        /**
         * Retrieve a list of orders based on user ID.
         *
         * @param userId User ID
         * @return List of orders
         */
        @Tool(
                name = "list_orders",
                description = "Retrieve a list of orders by user ID",
                converter = SchemaEnhancementConverter.class)
        public List<Order> listOrders(
                @ToolParam(name = "userId", description = "User ID") String userId) {
            return List.of(
                    new Order(
                            "ORD001",
                            userId,
                            "Luxurious Laptop",
                            3,
                            5999.99,
                            "Hangzhou City, Zhejiang Province",
                            1,
                            "Handle with care, prevent collision, waterproof and shockproof"
                                    + " packaging required",
                            "2025-01-15 10:30:00"),
                    new Order(
                            "ORD002",
                            userId,
                            "Splendid Monitor",
                            3,
                            4999.99,
                            "Hangzhou City, Zhejiang Province",
                            1,
                            "Handle with care, prevent collision, waterproof and shockproof"
                                    + " packaging required",
                            "2025-01-15 10:30:00"));
        }
    }

    // ==================== Custom Converter Implementations ====================

    /**
     * Sensitive Data Masking Converter
     *
     * <p>Automatically masks sensitive fields in results, such as password, apiKey, creditCard, etc.
     */
    public static class SensitiveDataMaskingConverter extends DefaultToolResultConverter {

        private static final Set<String> SENSITIVE_FIELDS =
                new HashSet<>(
                        Arrays.asList(
                                "password",
                                "apikey",
                                "api_key",
                                "token",
                                "secret",
                                "creditcard",
                                "credit_card",
                                "ssn"));

        private static final Pattern CREDIT_CARD_PATTERN =
                Pattern.compile("\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}");

        @Override
        protected ToolResultBlock serialize(Object result, Type returnType) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                JsonNode node = mapper.valueToTree(result);
                JsonNode masked = maskSensitiveData(node);
                String json = JsonUtils.getJsonCodec().toJson(masked);

                // Generate Schema
                Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(returnType);
                String schemaJson = JsonUtils.getJsonCodec().toJson(schema);

                return ToolResultBlock.of(
                        List.of(
                                TextBlock.builder()
                                        .text("⚠️  Sensitive data has been masked\n\n" + json)
                                        .build(),
                                TextBlock.builder()
                                        .text("\nResult JSON Schema:\n" + schemaJson)
                                        .build()));
            } catch (Exception e) {
                return super.serialize(result, returnType);
            }
        }

        private JsonNode maskSensitiveData(JsonNode node) {
            if (node.isObject()) {
                ObjectNode result = ((ObjectNode) node).deepCopy();
                Iterator<Map.Entry<String, JsonNode>> fields = result.fields();

                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String fieldName = entry.getKey().toLowerCase();

                    if (isSensitiveField(fieldName)) {
                        result.put(entry.getKey(), "***MASKED***");
                    } else if (entry.getValue().isTextual()) {
                        String value = entry.getValue().asText();
                        if (CREDIT_CARD_PATTERN.matcher(value).matches()) {
                            result.put(entry.getKey(), maskCreditCard(value));
                        }
                    }
                }
                return result;
            }
            return node;
        }

        private boolean isSensitiveField(String fieldName) {
            for (String sensitive : SENSITIVE_FIELDS) {
                if (fieldName.contains(sensitive)) {
                    return true;
                }
            }
            return false;
        }

        private String maskCreditCard(String card) {
            String digits = card.replaceAll("[^0-9]", "");
            if (digits.length() >= 4) {
                return "****-****-****-" + digits.substring(digits.length() - 4);
            }
            return "***MASKED***";
        }
    }

    /**
     * Schema Enhancement Converter
     *
     * <p>Adds detailed JSON Schema information, referring to DefaultToolResultConverter2.
     */
    public static class SchemaEnhancementConverter extends DefaultToolResultConverter {

        @Override
        protected ToolResultBlock serialize(Object result, Type returnType) {
            try {
                String json = JsonUtils.getJsonCodec().toJson(result);

                // Generate Schema
                Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(returnType);
                String schemaJson = JsonUtils.getJsonCodec().toJson(schema);

                return ToolResultBlock.of(
                        List.of(
                                TextBlock.builder().text("Result Data:\n" + json).build(),
                                TextBlock.builder()
                                        .text("\nResult JSON Schema:\n" + schemaJson)
                                        .build()));
            } catch (Exception e) {
                return super.serialize(result, returnType);
            }
        }
    }

    // ==================== Data Classes ====================

    /**
     * User Information Class
     */
    public static class UserInfo {
        @JsonPropertyDescription("User ID")
        private String userId;

        @JsonPropertyDescription("Username")
        private String username;

        @JsonPropertyDescription("Email address")
        private String email;

        @JsonPropertyDescription("Password (sensitive information)")
        private String password;

        @JsonPropertyDescription("API key (sensitive information)")
        private String apiKey;

        @JsonPropertyDescription("Credit card number (sensitive information)")
        private String creditCard;

        @JsonPropertyDescription("User register time, format: yyyy-MM-dd HH:mm:ss")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime = LocalDateTime.now();

        public UserInfo() {}

        public UserInfo(
                String userId,
                String username,
                String email,
                String password,
                String apiKey,
                String creditCard) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.password = password;
            this.apiKey = apiKey;
            this.creditCard = creditCard;
        }

        // Getters and setters
        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCreditCard() {
            return creditCard;
        }

        public void setCreditCard(String creditCard) {
            this.creditCard = creditCard;
        }

        public LocalDateTime getCreateTime() {
            return createTime;
        }

        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }
    }

    /**
     * Order Class
     */
    public static class Order {
        @JsonPropertyDescription("Order unique identifier, format: ORD + 3 digits, e.g., ORD001")
        private String id;

        @JsonPropertyDescription("User ID")
        private String userId;

        @JsonPropertyDescription("Product name, including brand and model information")
        private String product;

        @JsonPropertyDescription(
                "Order current status, possible values: 0=Pending Payment, 1=Paid, 2=Pending"
                    + " Shipment, 3=Shipped, 4=In Transit, 5=Delivered, 6=Completed, 7=Cancelled,"
                    + " 8=Refunding, 9=Refunded")
        private Integer status;

        @JsonPropertyDescription(
                "Order total price in CNY (RMB), including all product prices and shipping fees,"
                        + " excluding tax")
        private Double price;

        /**
         * Address
         * You can try adjusting the comment description of this field to help the model recognize its different meanings
         * #JsonPropertyDescription("Delivery address, including province, city, district, and detailed street address for product delivery")
         */
        @JsonPropertyDescription("Product origin, This refers to the product's country of origin.")
        private String address;

        @JsonPropertyDescription(
                "Quantity of products purchased, indicating the number of units of this product in"
                        + " the order")
        private Integer quantity;

        @JsonPropertyDescription(
                "Order remarks description, filled by users for special delivery requirements or"
                    + " product instructions, such as: handle with care, store at room temperature,"
                    + " waterproof and sunproof, etc.")
        private String description;

        @JsonPropertyDescription("Order creation time, standard time format: yyyy-MM-dd HH:mm:ss")
        private String createTime;

        public Order() {}

        public Order(String id, String product, Integer status, Double price) {
            this.id = id;
            this.product = product;
            this.status = status;
            this.price = price;
        }

        public Order(
                String id,
                String userId,
                String product,
                Integer status,
                Double price,
                String address,
                Integer quantity,
                String description,
                String createTime) {
            this.id = id;
            this.userId = userId;
            this.product = product;
            this.status = status;
            this.price = price;
            this.address = address;
            this.quantity = quantity;
            this.description = description;
            this.createTime = createTime;
        }

        // Getters and setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getProduct() {
            return product;
        }

        public void setProduct(String product) {
            this.product = product;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public Double getPrice() {
            return price;
        }

        public void setPrice(Double price) {
            this.price = price;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCreateTime() {
            return createTime;
        }

        public void setCreateTime(String createTime) {
            this.createTime = createTime;
        }
    }
}
