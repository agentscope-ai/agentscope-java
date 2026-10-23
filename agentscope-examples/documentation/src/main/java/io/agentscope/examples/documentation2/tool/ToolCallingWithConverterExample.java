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
package io.agentscope.examples.documentation2.tool;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.DefaultToolResultConverter;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 * ToolCallingWithConverterExample - Demonstrates customizing {@link DefaultToolResultConverter}.
 *
 * <p>Shows two converter patterns:
 * <ol>
 *   <li>Sensitive Data Masking Converter — masks password, API key, and credit-card fields.</li>
 *   <li>Schema Enhancement Converter — appends the return-type JSON Schema to the tool result.</li>
 * </ol>
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Removed {@code .memory(new InMemoryMemory())} — not required in 2.0.</li>
 * </ul>
 */
public class ToolCallingWithConverterExample {

    /**
     * Runs the converter example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Tool Calling Custom ToolResultConverter Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates custom ToolResultConverters:\n"
                    + "  - get_user_info: Masks sensitive fields (password, apiKey, creditCard)\n"
                    + "  - list_orders:   Appends the JSON Schema of the return type");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to tools. "
                                        + "Use tools when needed to answer questions accurately.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    /** Simple tools used in this example. */
    public static class SimpleTools {

        /**
         * Returns user information for the given user ID, with sensitive fields masked.
         *
         * @param userId the user ID
         * @return user information
         */
        @Tool(
                name = "get_user_info",
                description = "Retrieve user information by user ID",
                converter = SensitiveDataMaskingConverter.class)
        public UserInfo getUserInfo(
                @ToolParam(name = "userId", description = "User ID") String userId) {
            return new UserInfo(
                    userId,
                    "John Doe",
                    "john@example.com",
                    "MySecretPassword123",
                    "sk-1234567890abcdef",
                    "4567-1234-8888-6666");
        }

        /**
         * Returns a list of orders for the given user ID, with JSON Schema appended.
         *
         * @param userId the user ID
         * @return list of orders
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
                            "Handle with care, waterproof and shockproof packaging required",
                            "2025-01-15 10:30:00"),
                    new Order(
                            "ORD002",
                            userId,
                            "Splendid Monitor",
                            3,
                            4999.99,
                            "Hangzhou City, Zhejiang Province",
                            1,
                            "Handle with care, waterproof and shockproof packaging required",
                            "2025-01-15 10:30:00"));
        }
    }

    // ==================== Custom Converters ====================

    /**
     * Masks sensitive fields (password, apiKey, creditCard, etc.) in the tool result.
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
                Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(returnType);
                String schemaJson = JsonUtils.getJsonCodec().toJson(schema);
                return ToolResultBlock.of(
                        List.of(
                                TextBlock.builder()
                                        .text("Sensitive data has been masked\n\n" + json)
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
     * Appends the JSON Schema of the return type to the tool result.
     */
    public static class SchemaEnhancementConverter extends DefaultToolResultConverter {

        @Override
        protected ToolResultBlock serialize(Object result, Type returnType) {
            try {
                String json = JsonUtils.getJsonCodec().toJson(result);
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

    /** User information returned by {@code get_user_info}. */
    public static class UserInfo {

        @JsonPropertyDescription("User ID")
        private String userId;

        @JsonPropertyDescription("Username")
        private String username;

        @JsonPropertyDescription("Email address")
        private String email;

        @JsonPropertyDescription("Password (sensitive)")
        private String password;

        @JsonPropertyDescription("API key (sensitive)")
        private String apiKey;

        @JsonPropertyDescription("Credit card number (sensitive)")
        private String creditCard;

        @JsonPropertyDescription("Registration time, format: yyyy-MM-dd HH:mm:ss")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime = LocalDateTime.now();

        /** Default constructor required for Jackson. */
        public UserInfo() {}

        /**
         * Creates a new UserInfo instance.
         *
         * @param userId     user ID
         * @param username   username
         * @param email      email address
         * @param password   password (will be masked by converter)
         * @param apiKey     API key (will be masked)
         * @param creditCard credit card number (will be masked)
         */
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

        /** Returns the user ID. */
        public String getUserId() {
            return userId;
        }

        /** Sets the user ID. */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /** Returns the username. */
        public String getUsername() {
            return username;
        }

        /** Sets the username. */
        public void setUsername(String username) {
            this.username = username;
        }

        /** Returns the email address. */
        public String getEmail() {
            return email;
        }

        /** Sets the email address. */
        public void setEmail(String email) {
            this.email = email;
        }

        /** Returns the password. */
        public String getPassword() {
            return password;
        }

        /** Sets the password. */
        public void setPassword(String password) {
            this.password = password;
        }

        /** Returns the API key. */
        public String getApiKey() {
            return apiKey;
        }

        /** Sets the API key. */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /** Returns the credit card number. */
        public String getCreditCard() {
            return creditCard;
        }

        /** Sets the credit card number. */
        public void setCreditCard(String creditCard) {
            this.creditCard = creditCard;
        }

        /** Returns the registration time. */
        public LocalDateTime getCreateTime() {
            return createTime;
        }

        /** Sets the registration time. */
        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }
    }

    /** Order data returned by {@code list_orders}. */
    public static class Order {

        @JsonPropertyDescription("Order ID, format: ORD + 3 digits")
        private String id;

        @JsonPropertyDescription("User ID")
        private String userId;

        @JsonPropertyDescription("Product name")
        private String product;

        @JsonPropertyDescription(
                "Status: 0=Pending, 1=Paid, 2=Processing, 3=Shipped, 4=In Transit,"
                        + " 5=Delivered, 6=Completed, 7=Cancelled")
        private Integer status;

        @JsonPropertyDescription("Total price in CNY")
        private Double price;

        @JsonPropertyDescription("Product origin")
        private String address;

        @JsonPropertyDescription("Quantity")
        private Integer quantity;

        @JsonPropertyDescription("Order remarks for special delivery requirements")
        private String description;

        @JsonPropertyDescription("Order creation time, format: yyyy-MM-dd HH:mm:ss")
        private String createTime;

        /** Default constructor required for Jackson. */
        public Order() {}

        /**
         * Creates a new Order instance.
         *
         * @param id          order ID
         * @param userId      user ID
         * @param product     product name
         * @param status      order status code
         * @param price       total price
         * @param address     product origin
         * @param quantity    quantity
         * @param description remarks
         * @param createTime  creation time string
         */
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

        /** Returns the order ID. */
        public String getId() {
            return id;
        }

        /** Sets the order ID. */
        public void setId(String id) {
            this.id = id;
        }

        /** Returns the user ID. */
        public String getUserId() {
            return userId;
        }

        /** Sets the user ID. */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /** Returns the product name. */
        public String getProduct() {
            return product;
        }

        /** Sets the product name. */
        public void setProduct(String product) {
            this.product = product;
        }

        /** Returns the status code. */
        public Integer getStatus() {
            return status;
        }

        /** Sets the status code. */
        public void setStatus(Integer status) {
            this.status = status;
        }

        /** Returns the total price. */
        public Double getPrice() {
            return price;
        }

        /** Sets the total price. */
        public void setPrice(Double price) {
            this.price = price;
        }

        /** Returns the product origin. */
        public String getAddress() {
            return address;
        }

        /** Sets the product origin. */
        public void setAddress(String address) {
            this.address = address;
        }

        /** Returns the quantity. */
        public Integer getQuantity() {
            return quantity;
        }

        /** Sets the quantity. */
        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        /** Returns the order remarks. */
        public String getDescription() {
            return description;
        }

        /** Sets the order remarks. */
        public void setDescription(String description) {
            this.description = description;
        }

        /** Returns the creation time string. */
        public String getCreateTime() {
            return createTime;
        }

        /** Sets the creation time string. */
        public void setCreateTime(String createTime) {
            this.createTime = createTime;
        }
    }
}
