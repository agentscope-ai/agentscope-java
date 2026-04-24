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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredOutputSchemaWrappingTest {

    static class OrderInfo {
        public String orderId;
        public Address billingAddress;
        public Address shippingAddress;
        public List<OrderItem> items;
    }

    static class Address {
        public String city;
        public String street;
        public String recipientName;
    }

    static class OrderItem {
        public String productName;
        public Integer quantity;
    }

    @Test
    void shouldHoistDefsToRootWhenWrappingStructuredOutputSchema() {
        Map<String, Object> responseSchema =
                JsonSchemaUtils.generateSchemaFromClass(OrderInfo.class);
        Map<String, Object> wrappedSchema =
                StructuredOutputCapableAgent.wrapStructuredOutputSchema(responseSchema);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) wrappedSchema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> wrappedResponse = (Map<String, Object>) properties.get("response");
        @SuppressWarnings("unchecked")
        Map<String, Object> defs = (Map<String, Object>) wrappedSchema.get("$defs");

        assertNotNull(defs, "Wrapped schema should expose shared defs at root level");
        assertTrue(defs.containsKey("Address"), "Address should be hoisted to root $defs");
        assertFalse(
                wrappedResponse.containsKey("$defs"),
                "Nested response schema should not keep $defs");

        Map<String, Object> validInput =
                Map.of(
                        "response",
                        Map.of(
                                "orderId",
                                "ORD-001",
                                "billingAddress",
                                Map.of(
                                        "city", "Beijing",
                                        "street", "Jianguo Road",
                                        "recipientName", "Zhang San"),
                                "shippingAddress",
                                Map.of(
                                        "city", "Shanghai",
                                        "street", "Century Avenue",
                                        "recipientName", "Li Si"),
                                "items",
                                List.of(Map.of("productName", "Phone", "quantity", 2))));

        String validationError =
                ToolValidator.validateInput(
                        JsonUtils.getJsonCodec().toJson(validInput), wrappedSchema);
        assertNull(
                validationError, "Wrapped structured-output schema should validate reused objects");
    }

    @Test
    void shouldSupportStructuredOutputWithReusedNestedObjects() {
        Toolkit toolkit = new Toolkit();
        Memory memory = new InMemoryMemory();
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "orderId",
                                "ORD-001",
                                "billingAddress",
                                Map.of(
                                        "city", "Beijing",
                                        "street", "Jianguo Road",
                                        "recipientName", "Zhang San"),
                                "shippingAddress",
                                Map.of(
                                        "city", "Shanghai",
                                        "street", "Century Avenue",
                                        "recipientName", "Li Si"),
                                "items",
                                List.of(
                                        Map.of("productName", "Phone", "quantity", 2),
                                        Map.of("productName", "Headset", "quantity", 1))));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
                            if (!hasToolResults) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_structured_1")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .id("msg_2")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text(
                                                                            "Structured response"
                                                                                    + " generated")
                                                                    .build()))
                                            .usage(new ChatUsage(5, 10, 15))
                                            .build());
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("order-agent")
                        .sysPrompt("You extract order information")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Extract the order information.").build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, OrderInfo.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getMetadata());

        OrderInfo result = responseMsg.getStructuredData(OrderInfo.class);
        assertNotNull(result);
        assertEquals("ORD-001", result.orderId);
        assertNotNull(result.billingAddress);
        assertNotNull(result.shippingAddress);
        assertEquals("Beijing", result.billingAddress.city);
        assertEquals("Shanghai", result.shippingAddress.city);
        assertNotNull(result.items);
        assertEquals(2, result.items.size());
        assertEquals("Phone", result.items.get(0).productName);
    }
}
