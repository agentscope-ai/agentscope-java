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
package io.agentscope.core.formatter.openai.dto;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for Builder pattern ensuring independence of built objects.
 */
class OpenAIBuilderIndependenceTest {

    @Test
    void testOpenAIMessageBuilderDoesNotReuseInstances() {
        OpenAIMessage.Builder builder = new OpenAIMessage.Builder();

        // Build first message
        OpenAIMessage msg1 = builder.role("user").content("Hello").build();
        // Build second message with different content
        OpenAIMessage msg2 = builder.role("assistant").content("Hi").build();

        // Verify they are different objects
        assertNotSame(msg1, msg2, "Builder should create new instances");
        // Verify they have different values
        assertNotEquals(msg1.getRole(), msg2.getRole());
        assertNotEquals(msg1.getContent(), msg2.getContent());
    }

    @Test
    void testOpenAIRequestBuilderDoesNotReuseInstances() {
        OpenAIRequest.Builder builder = new OpenAIRequest.Builder();

        // Build first request
        OpenAIRequest req1 = builder.model("gpt-4").temperature(0.7).build();
        // Build second request with different parameters
        OpenAIRequest req2 = builder.model("gpt-3.5-turbo").temperature(0.5).build();

        // Verify they are different objects
        assertNotSame(req1, req2, "Builder should create new instances");
        // Verify they have different values
        assertNotEquals(req1.getModel(), req2.getModel());
        assertNotEquals(req1.getTemperature(), req2.getTemperature());
    }

    @Test
    void testOpenAIContentPartBuilderDoesNotReuseInstances() {
        OpenAIContentPart.Builder builder = new OpenAIContentPart.Builder();

        // Build first part
        OpenAIContentPart part1 = builder.type("text").text("First").build();
        // Build second part
        OpenAIContentPart part2 = builder.type("image").text("Second").build();

        // Verify they are different objects
        assertNotSame(part1, part2, "Builder should create new instances");
        // Verify they have different values
        assertNotEquals(part1.getType(), part2.getType());
        assertNotEquals(part1.getText(), part2.getText());
    }

    @Test
    void testOpenAIToolCallBuilderDoesNotReuseInstances() {
        OpenAIToolCall.Builder builder = new OpenAIToolCall.Builder();

        // Build first tool call
        OpenAIToolCall call1 = builder.id("call-1").type("function").index(0).build();
        // Build second tool call
        OpenAIToolCall call2 = builder.id("call-2").type("function").index(1).build();

        // Verify they are different objects
        assertNotSame(call1, call2, "Builder should create new instances");
        // Verify they have different values
        assertNotEquals(call1.getId(), call2.getId());
        assertNotEquals(call1.getIndex(), call2.getIndex());
    }

    @Test
    void testOpenAIToolFunctionBuilderDoesNotReuseInstances() {
        OpenAIToolFunction.Builder builder = new OpenAIToolFunction.Builder();

        // Build first function
        OpenAIToolFunction func1 = builder.name("search").description("Search tool").build();
        // Build second function
        OpenAIToolFunction func2 = builder.name("math").description("Math tool").build();

        // Verify they are different objects
        assertNotSame(func1, func2, "Builder should create new instances");
        // Verify they have different values
        assertNotEquals(func1.getName(), func2.getName());
        assertNotEquals(func1.getDescription(), func2.getDescription());
    }

    @Test
    void testOpenAIMessageBuilderWithComplexState() {
        OpenAIMessage.Builder builder = new OpenAIMessage.Builder();
        List<OpenAIContentPart> parts1 = new ArrayList<>();
        parts1.add(new OpenAIContentPart());

        // Build message with content list
        OpenAIMessage msg1 = builder.role("user").content(parts1).toolCallId("tc1").build();

        List<OpenAIContentPart> parts2 = new ArrayList<>();
        parts2.add(new OpenAIContentPart());
        parts2.add(new OpenAIContentPart());

        // Build another message with different content list
        OpenAIMessage msg2 = builder.role("assistant").content(parts2).toolCallId("tc2").build();

        // Verify independence
        assertNotSame(msg1, msg2);
        assertNotEquals(msg1.getToolCallId(), msg2.getToolCallId());
    }

    @Test
    void testOpenAIRequestBuilderWithComplexState() {
        OpenAIRequest.Builder builder = new OpenAIRequest.Builder();
        List<OpenAIMessage> msgs1 = new ArrayList<>();
        msgs1.add(new OpenAIMessage());

        // Build request with messages
        OpenAIRequest req1 = builder.model("gpt-4").messages(msgs1).build();

        List<OpenAIMessage> msgs2 = new ArrayList<>();
        msgs2.add(new OpenAIMessage());
        msgs2.add(new OpenAIMessage());

        // Build another request with different messages
        OpenAIRequest req2 = builder.model("gpt-3.5").messages(msgs2).build();

        // Verify independence
        assertNotSame(req1, req2);
        assertNotEquals(req1.getModel(), req2.getModel());
    }
}
