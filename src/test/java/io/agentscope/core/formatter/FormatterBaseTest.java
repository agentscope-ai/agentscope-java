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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.FormattedMessageList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class FormatterBaseTest {

    private static class TestFormatter extends FormatterBase {
        @Override
        public FormattedMessageList format(List<Msg> msgs) {
            List<Map<String, Object>> formatted = new ArrayList<>();
            for (Msg msg : msgs) {
                formatted.add(
                        Map.of(
                                "role",
                                msg.getRole().name().toLowerCase(),
                                "content",
                                msg.getTextContent()));
            }
            return new FormattedMessageList(formatted);
        }

        @Override
        public FormatterCapabilities getCapabilities() {
            return FormatterCapabilities.builder()
                    .providerName("Test")
                    .supportToolsApi(false)
                    .supportMultiAgent(false)
                    .supportVision(false)
                    .build();
        }
    }

    @Test
    public void testFormatWithOptionsUsesDefaultImplementation() {
        TestFormatter formatter = new TestFormatter();
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build());

        FormatterOptions options = new FormatterOptions();
        FormattedMessageList result = formatter.format(messages, options);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void testAssertListOfMsgsWithNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    FormatterBase.assertListOfMsgs(null);
                });
    }

    @Test
    public void testAssertListOfMsgsWithNonMsgObjects() {
        List<Object> mixed = new ArrayList<>();
        mixed.add(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build());
        mixed.add("not a Msg");

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    FormatterBase.assertListOfMsgs((List<Msg>) (List<?>) mixed);
                });
    }

    @Test
    public void testAssertListOfMsgsWithValidList() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text("Hello").build())
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Hi").build())
                                .build());

        // Should not throw
        FormatterBase.assertListOfMsgs(messages);
    }

    @Test
    public void testConvertToolResultToStringWithNull() {
        TestFormatter formatter = new TestFormatter();
        String result = formatter.convertToolResultToString(null);
        assertEquals("", result);
    }

    @Test
    public void testConvertToolResultToStringWithEmptyList() {
        TestFormatter formatter = new TestFormatter();
        String result = formatter.convertToolResultToString(new ArrayList<>());
        assertEquals("", result);
    }

    @Test
    public void testConvertToolResultToStringWithSingleResult() {
        TestFormatter formatter = new TestFormatter();
        List<Object> toolResults = List.of("Result 1");
        String result = formatter.convertToolResultToString(toolResults);

        assertTrue(result.contains("Tool Result 1: Result 1"));
    }

    @Test
    public void testConvertToolResultToStringWithMultipleResults() {
        TestFormatter formatter = new TestFormatter();
        List<Object> toolResults = List.of("Result 1", "Result 2", "Result 3");
        String result = formatter.convertToolResultToString(toolResults);

        assertTrue(result.contains("Tool Result 1: Result 1"));
        assertTrue(result.contains("Tool Result 2: Result 2"));
        assertTrue(result.contains("Tool Result 3: Result 3"));
        assertTrue(result.contains("\n"));
    }

    @Test
    public void testConvertToolResultToStringWithNullResult() {
        TestFormatter formatter = new TestFormatter();
        List<Object> toolResults = Arrays.asList("Result 1", null, "Result 3");
        String result = formatter.convertToolResultToString(toolResults);

        assertTrue(result.contains("Tool Result 1: Result 1"));
        assertTrue(result.contains("Tool Result 2: null"));
        assertTrue(result.contains("Tool Result 3: Result 3"));
    }

    @Test
    public void testGetName() {
        TestFormatter formatter = new TestFormatter();
        assertEquals("TestFormatter", formatter.getName());
    }

    @Test
    public void testGetCapabilities() {
        TestFormatter formatter = new TestFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities);
        assertEquals("Test", capabilities.getProviderName());
    }
}
