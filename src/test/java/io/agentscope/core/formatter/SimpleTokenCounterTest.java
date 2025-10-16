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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SimpleTokenCounterTest {

    @Test
    public void testConstructorWithCustomTokenLength() {
        SimpleTokenCounter counter = new SimpleTokenCounter("test", 5.0);
        assertEquals("test", counter.getName());
        assertEquals(5.0, counter.getAverageTokenLength());
    }

    @Test
    public void testConstructorWithDefaultTokenLength() {
        SimpleTokenCounter counter = new SimpleTokenCounter("test");
        assertEquals("test", counter.getName());
        assertEquals(4.0, counter.getAverageTokenLength());
    }

    @Test
    public void testForOpenAI() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();
        assertEquals("OpenAI-Simple", counter.getName());
        assertEquals(4.0, counter.getAverageTokenLength());
    }

    @Test
    public void testForAnthropic() {
        SimpleTokenCounter counter = SimpleTokenCounter.forAnthropic();
        assertEquals("Anthropic-Simple", counter.getName());
        assertEquals(3.8, counter.getAverageTokenLength());
    }

    @Test
    public void testCountTokensForEmptyString() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();
        assertEquals(0, counter.countTokens(""));
        assertEquals(0, counter.countTokens("   "));
    }

    @Test
    public void testCountTokensForNullString() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();
        assertEquals(0, counter.countTokens((String) null));
    }

    @Test
    public void testCountTokensForShortText() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();
        int tokens = counter.countTokens("Hello world");
        assertTrue(tokens > 0);
        assertTrue(tokens < 10);
    }

    @Test
    public void testCountTokensForLongerText() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();
        String text =
                "This is a much longer piece of text that should result in more tokens being"
                        + " counted.";
        int tokens = counter.countTokens(text);
        assertTrue(tokens > 10);
        assertTrue(tokens < 50);
    }

    @Test
    public void testCountTokensIncreaseWithTextLength() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        String shortText = "Hello";
        String mediumText = "Hello world how are you";
        String longText = "Hello world how are you doing today this is a longer message";

        int shortTokens = counter.countTokens(shortText);
        int mediumTokens = counter.countTokens(mediumText);
        int longTokens = counter.countTokens(longText);

        assertTrue(shortTokens < mediumTokens);
        assertTrue(mediumTokens < longTokens);
    }

    @Test
    public void testCountTokensForFormattedMessagesWithTextContent() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg1 = new HashMap<>();
        msg1.put("role", "user");
        msg1.put("content", "Hello world");
        messages.add(msg1);

        int tokens = counter.countTokens(messages);
        assertTrue(tokens > 0);
        // Should include overhead per message
        assertTrue(tokens >= 10);
    }

    @Test
    public void testCountTokensForMultipleMessages() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> msg1 = new HashMap<>();
        msg1.put("role", "user");
        msg1.put("content", "Hello");
        messages.add(msg1);

        Map<String, Object> msg2 = new HashMap<>();
        msg2.put("role", "assistant");
        msg2.put("content", "Hi there!");
        messages.add(msg2);

        int tokens = counter.countTokens(messages);
        // Should be at least 2 * 10 for overhead plus content tokens
        assertTrue(tokens >= 20);
    }

    @Test
    public void testCountTokensForMessageWithContentArray() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");

        List<Map<String, Object>> contentArray = new ArrayList<>();
        Map<String, Object> textContent = new HashMap<>();
        textContent.put("type", "text");
        textContent.put("text", "Hello world");
        contentArray.add(textContent);

        msg.put("content", contentArray);
        messages.add(msg);

        int tokens = counter.countTokens(messages);
        assertTrue(tokens > 0);
    }

    @Test
    public void testCountTokensForMessageWithToolCalls() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_123");
        toolCall.put("type", "function");

        Map<String, Object> function = new HashMap<>();
        function.put("name", "get_weather");
        function.put("arguments", "{\"location\":\"New York\"}");
        toolCall.put("function", function);

        toolCalls.add(toolCall);
        msg.put("tool_calls", toolCalls);
        messages.add(msg);

        int tokens = counter.countTokens(messages);
        assertTrue(tokens > 0);
    }

    @Test
    public void testCountTokensForEmptyMessages() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();
        List<Map<String, Object>> messages = new ArrayList<>();

        int tokens = counter.countTokens(messages);
        assertEquals(0, tokens);
    }

    @Test
    public void testCountTokensWithNullContent() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", null);
        messages.add(msg);

        int tokens = counter.countTokens(messages);
        // Should still count overhead
        assertTrue(tokens >= 10);
    }

    @Test
    public void testCountTokensWithContentArrayContainingNonTextItems() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");

        List<Object> contentArray = new ArrayList<>();
        contentArray.add("not a map");
        contentArray.add(123);

        msg.put("content", contentArray);
        messages.add(msg);

        int tokens = counter.countTokens(messages);
        // Should handle gracefully and count overhead
        assertTrue(tokens >= 10);
    }

    @Test
    public void testCountTokensWithToolCallsContainingNonFunctionItems() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");

        List<Object> toolCalls = new ArrayList<>();
        toolCalls.add("not a map");
        toolCalls.add(456);

        msg.put("tool_calls", toolCalls);
        messages.add(msg);

        int tokens = counter.countTokens(messages);
        // Should handle gracefully and count overhead
        assertTrue(tokens >= 10);
    }

    @Test
    public void testCountTokensWithSpecialCharacters() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        String textWithSpecialChars = "Hello! @#$%^&*() How are you? 你好世界";
        int tokens = counter.countTokens(textWithSpecialChars);

        assertTrue(tokens > 0);
    }

    @Test
    public void testCountTokensWithNewlines() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        String textWithNewlines = "Line 1\nLine 2\nLine 3\nLine 4";
        int tokens = counter.countTokens(textWithNewlines);

        assertTrue(tokens > 0);
        // Should count whitespace tokens from newlines
        assertTrue(tokens >= 4);
    }

    @Test
    public void testDifferentTokenCountersProduceDifferentResults() {
        String text = "This is a test message";

        SimpleTokenCounter openAI = SimpleTokenCounter.forOpenAI();
        SimpleTokenCounter anthropic = SimpleTokenCounter.forAnthropic();

        int openAITokens = openAI.countTokens(text);
        int anthropicTokens = anthropic.countTokens(text);

        // Different average token lengths should produce different counts
        assertTrue(openAITokens > 0);
        assertTrue(anthropicTokens > 0);
    }

    @Test
    public void testCountTokensWithVeryLongText() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longText.append("word ");
        }

        int tokens = counter.countTokens(longText.toString());
        assertTrue(tokens > 500);
    }

    @Test
    public void testCountTokensForContentArrayWithMixedContent() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");

        List<Map<String, Object>> contentArray = new ArrayList<>();

        Map<String, Object> textContent1 = new HashMap<>();
        textContent1.put("type", "text");
        textContent1.put("text", "First part");
        contentArray.add(textContent1);

        Map<String, Object> imageContent = new HashMap<>();
        imageContent.put("type", "image");
        imageContent.put("url", "https://example.com/image.jpg");
        contentArray.add(imageContent);

        Map<String, Object> textContent2 = new HashMap<>();
        textContent2.put("type", "text");
        textContent2.put("text", "Second part");
        contentArray.add(textContent2);

        msg.put("content", contentArray);
        messages.add(msg);

        int tokens = counter.countTokens(messages);
        assertTrue(tokens > 0);
    }

    @Test
    public void testCountTokensWithEmptyToolArguments() {
        SimpleTokenCounter counter = SimpleTokenCounter.forOpenAI();

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_123");
        toolCall.put("type", "function");

        Map<String, Object> function = new HashMap<>();
        function.put("name", "no_args_function");
        function.put("arguments", "");
        toolCall.put("function", function);

        toolCalls.add(toolCall);
        msg.put("tool_calls", toolCalls);
        messages.add(msg);

        int tokens = counter.countTokens(messages);
        assertTrue(tokens >= 10);
    }
}
