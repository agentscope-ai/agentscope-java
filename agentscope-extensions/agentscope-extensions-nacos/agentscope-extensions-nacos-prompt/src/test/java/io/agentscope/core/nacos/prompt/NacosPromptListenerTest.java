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

package io.agentscope.core.nacos.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NacosPromptListener}.
 */
@ExtendWith(MockitoExtension.class)
class NacosPromptListenerTest {

    @Mock private AiService aiService;

    private NacosPromptListener listener;

    @BeforeEach
    void setUp() {
        listener = new NacosPromptListener(aiService);
    }

    @Nested
    @DisplayName("getPrompt - basic loading")
    class GetPromptBasicTests {

        @Test
        @DisplayName("should load prompt from Nacos and return rendered template")
        void shouldLoadAndRenderPrompt() throws NacosException {
            Prompt prompt = new Prompt("test-agent", "1.0.0", "You are {{role}} in {{department}}");
            when(aiService.subscribePrompt(eq("test-agent"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            Map<String, String> args = Map.of("role", "AI Assistant", "department", "Engineering");
            String result = listener.getPrompt("test-agent", args);

            assertEquals("You are AI Assistant in Engineering", result);
        }

        @Test
        @DisplayName("should load prompt without variable rendering when args is null")
        void shouldLoadPromptWithoutArgs() throws NacosException {
            Prompt prompt = new Prompt("simple", "1.0.0", "You are a helpful assistant");
            when(aiService.subscribePrompt(eq("simple"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            String result = listener.getPrompt("simple");

            assertEquals("You are a helpful assistant", result);
        }

        @Test
        @DisplayName("should load prompt without variable rendering when args is empty")
        void shouldLoadPromptWithEmptyArgs() throws NacosException {
            Prompt prompt = new Prompt("simple", "1.0.0", "You are a helpful assistant");
            when(aiService.subscribePrompt(eq("simple"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            String result = listener.getPrompt("simple", Map.of());

            assertEquals("You are a helpful assistant", result);
        }

        @Test
        @DisplayName("should return empty string when prompt is not found in Nacos")
        void shouldReturnEmptyForMissingPrompt() throws NacosException {
            when(aiService.subscribePrompt(eq("missing"), isNull(), isNull(), any()))
                    .thenReturn(null);

            String result = listener.getPrompt("missing");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty string when prompt template is null")
        void shouldReturnEmptyForNullTemplate() throws NacosException {
            Prompt prompt = new Prompt("null-tpl", "1.0.0", null);
            when(aiService.subscribePrompt(eq("null-tpl"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            String result = listener.getPrompt("null-tpl");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty string when prompt template is empty string")
        void shouldReturnEmptyForEmptyTemplate() throws NacosException {
            Prompt prompt = new Prompt("empty-tpl", "1.0.0", "");
            when(aiService.subscribePrompt(eq("empty-tpl"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            String result = listener.getPrompt("empty-tpl");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should fallback to default when prompt template is empty string")
        void shouldFallbackWhenEmptyTemplate() throws NacosException {
            Prompt prompt = new Prompt("empty-tpl", "1.0.0", "");
            when(aiService.subscribePrompt(eq("empty-tpl"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            String result = listener.getPrompt("empty-tpl", null, "Default for empty");

            assertEquals("Default for empty", result);
        }
    }

    @Nested
    @DisplayName("getPrompt - version and label")
    class VersionAndLabelTests {

        @Test
        @DisplayName("should load prompt with specific version")
        void shouldLoadPromptByVersion() throws NacosException {
            Prompt prompt = new Prompt("versioned", "2.0.0", "Version 2 template");
            when(aiService.subscribePrompt(eq("versioned"), eq("2.0.0"), isNull(), any()))
                    .thenReturn(prompt);

            String result = listener.getPrompt("versioned", "2.0.0", null, null, null);

            assertEquals("Version 2 template", result);
        }

        @Test
        @DisplayName("should load prompt with specific label")
        void shouldLoadPromptByLabel() throws NacosException {
            Prompt prompt = new Prompt("labeled", "1.0.0", "Production template");
            when(aiService.subscribePrompt(eq("labeled"), isNull(), eq("prod"), any()))
                    .thenReturn(prompt);

            String result = listener.getPrompt("labeled", null, "prod", null, null);

            assertEquals("Production template", result);
        }
    }

    @Nested
    @DisplayName("getPrompt - default value fallback")
    class DefaultValueTests {

        @Test
        @DisplayName("should use default value when Nacos returns null prompt")
        void shouldFallbackToDefaultValue() throws NacosException {
            when(aiService.subscribePrompt(eq("missing"), isNull(), isNull(), any()))
                    .thenReturn(null);

            String result = listener.getPrompt("missing", null, "I am a fallback assistant");

            assertEquals("I am a fallback assistant", result);
        }

        @Test
        @DisplayName("should render default value with args")
        void shouldRenderDefaultValueWithArgs() throws NacosException {
            when(aiService.subscribePrompt(eq("missing"), isNull(), isNull(), any()))
                    .thenReturn(null);

            Map<String, String> args = Map.of("name", "Bob");
            String result = listener.getPrompt("missing", args, "Hello {{name}}");

            assertEquals("Hello Bob", result);
        }

        @Test
        @DisplayName("should return empty string when no default value and Nacos empty")
        void shouldReturnEmptyWhenNoDefaultAndNacosEmpty() throws NacosException {
            when(aiService.subscribePrompt(eq("missing"), isNull(), isNull(), any()))
                    .thenReturn(null);

            String result = listener.getPrompt("missing", null, null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should use Nacos value over default value when both available")
        void shouldPreferNacosOverDefault() throws NacosException {
            Prompt prompt = new Prompt("test-agent", "1.0.0", "You are {{role}} in {{department}}");
            when(aiService.subscribePrompt(eq("test-agent"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            String result =
                    listener.getPrompt(
                            "test-agent",
                            Map.of("role", "Helper", "department", "Sales"),
                            "This is the fallback");

            assertEquals("You are Helper in Sales", result);
        }

        @Test
        @DisplayName("should use default value when NacosException occurs during loading")
        void shouldFallbackOnNacosException() throws NacosException {
            when(aiService.subscribePrompt(eq("error"), isNull(), isNull(), any()))
                    .thenThrow(new NacosException(500, "Nacos server error"));

            String result = listener.getPrompt("error", null, "Fallback on error");

            assertEquals("Fallback on error", result);
        }

        @Test
        @DisplayName("should throw NacosException when no default value provided")
        void shouldThrowWhenNoDefaultAndNacosException() throws NacosException {
            when(aiService.subscribePrompt(eq("error"), isNull(), isNull(), any()))
                    .thenThrow(new NacosException(500, "Nacos server error"));

            assertThrows(NacosException.class, () -> listener.getPrompt("error"));
        }
    }

    @Nested
    @DisplayName("Template rendering")
    class TemplateRenderingTests {

        @Test
        @DisplayName("should replace multiple variables in template")
        void shouldReplaceMultipleVariables() throws NacosException {
            Prompt prompt =
                    new Prompt(
                            "multi", "1.0.0", "{{greeting}} I am {{name}}, working at {{company}}");
            when(aiService.subscribePrompt(eq("multi"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            Map<String, String> args = new HashMap<>();
            args.put("greeting", "Hello!");
            args.put("name", "Agent");
            args.put("company", "Alibaba");

            String result = listener.getPrompt("multi", args);

            assertEquals("Hello! I am Agent, working at Alibaba", result);
        }

        @Test
        @DisplayName("should leave unmatched placeholders as-is")
        void shouldLeaveUnmatchedPlaceholders() throws NacosException {
            Prompt prompt = new Prompt("partial", "1.0.0", "Hello {{name}}, your role is {{role}}");
            when(aiService.subscribePrompt(eq("partial"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            Map<String, String> args = Map.of("name", "Alice");
            String result = listener.getPrompt("partial", args);

            assertEquals("Hello Alice, your role is {{role}}", result);
        }

        @Test
        @DisplayName("should handle null value in args by replacing with empty string")
        void shouldHandleNullArgValue() throws NacosException {
            Prompt prompt = new Prompt("nullval", "1.0.0", "Hello {{name}}");
            when(aiService.subscribePrompt(eq("nullval"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            Map<String, String> args = new HashMap<>();
            args.put("name", null);
            String result = listener.getPrompt("nullval", args);

            assertEquals("Hello ", result);
        }
    }

    @Nested
    @DisplayName("Caching via computeIfAbsent")
    class CachingTests {

        @Test
        @DisplayName("should only call Nacos once for the same key (cache hit)")
        void shouldCachePromptAfterFirstLoad() throws NacosException {
            Prompt prompt = new Prompt("cached", "1.0.0", "You are a helpful assistant");
            when(aiService.subscribePrompt(eq("cached"), isNull(), isNull(), any()))
                    .thenReturn(prompt);

            listener.getPrompt("cached");
            listener.getPrompt("cached");
            listener.getPrompt("cached");

            verify(aiService, times(1)).subscribePrompt(eq("cached"), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("should call Nacos separately for different keys")
        void shouldLoadDifferentKeysIndependently() throws NacosException {
            Prompt promptA = new Prompt("key-a", "1.0.0", "Template A");
            Prompt promptB = new Prompt("key-b", "1.0.0", "Template B");
            when(aiService.subscribePrompt(eq("key-a"), isNull(), isNull(), any()))
                    .thenReturn(promptA);
            when(aiService.subscribePrompt(eq("key-b"), isNull(), isNull(), any()))
                    .thenReturn(promptB);

            assertEquals("Template A", listener.getPrompt("key-a"));
            assertEquals("Template B", listener.getPrompt("key-b"));

            verify(aiService, times(1)).subscribePrompt(eq("key-a"), isNull(), isNull(), any());
            verify(aiService, times(1)).subscribePrompt(eq("key-b"), isNull(), isNull(), any());
        }
    }

    @Nested
    @DisplayName("Listener callback - config update")
    class ListenerCallbackTests {

        @Test
        @DisplayName("should update cached prompt when listener receives new prompt")
        void shouldUpdateCacheOnListenerCallback() throws Exception {
            Prompt original = new Prompt("updatable", "1.0.0", "Original template");
            when(aiService.subscribePrompt(eq("updatable"), isNull(), isNull(), any()))
                    .thenReturn(original);

            assertEquals("Original template", listener.getPrompt("updatable"));

            // Capture the listener registered with aiService
            AbstractNacosPromptListener nacosListener = getInternalListener();

            // Simulate prompt update event
            Prompt updated = new Prompt("updatable", "2.0.0", "Updated template");
            nacosListener.onEvent(new NacosPromptEvent("updatable", updated));

            assertEquals("Updated template", listener.getPrompt("updatable"));
        }

        @Test
        @DisplayName("should not crash when listener receives event with null prompt")
        void shouldHandleNullPromptInCallback() throws Exception {
            Prompt original = new Prompt("stable", "1.0.0", "Stable template");
            when(aiService.subscribePrompt(eq("stable"), isNull(), isNull(), any()))
                    .thenReturn(original);

            assertEquals("Stable template", listener.getPrompt("stable"));

            AbstractNacosPromptListener nacosListener = getInternalListener();
            nacosListener.onEvent(new NacosPromptEvent("stable", null));

            assertEquals("Stable template", listener.getPrompt("stable"));
        }

        @Test
        @DisplayName("should ignore callback with null event")
        void shouldIgnoreNullEvent() throws Exception {
            Prompt original = new Prompt("safe", "1.0.0", "Safe template");
            when(aiService.subscribePrompt(eq("safe"), isNull(), isNull(), any()))
                    .thenReturn(original);

            assertEquals("Safe template", listener.getPrompt("safe"));

            AbstractNacosPromptListener nacosListener = getInternalListener();
            nacosListener.onEvent(null);

            assertEquals("Safe template", listener.getPrompt("safe"));
        }

        @Test
        @DisplayName("should ignore callback when promptKey is empty")
        void shouldIgnoreCallbackEmptyPromptKey() throws Exception {
            Prompt original = new Prompt("keep", "1.0.0", "Keep this");
            when(aiService.subscribePrompt(eq("keep"), isNull(), isNull(), any()))
                    .thenReturn(original);

            assertEquals("Keep this", listener.getPrompt("keep"));

            AbstractNacosPromptListener nacosListener = getInternalListener();
            Prompt rogue = new Prompt("", "1.0.0", "Should not be stored");
            nacosListener.onEvent(new NacosPromptEvent("", rogue));

            assertEquals("Keep this", listener.getPrompt("keep"));
        }

        @Test
        @DisplayName("should ignore callback when promptKey is null")
        void shouldIgnoreCallbackNullPromptKey() throws Exception {
            Prompt original = new Prompt("keep2", "1.0.0", "Keep this too");
            when(aiService.subscribePrompt(eq("keep2"), isNull(), isNull(), any()))
                    .thenReturn(original);

            assertEquals("Keep this too", listener.getPrompt("keep2"));

            AbstractNacosPromptListener nacosListener = getInternalListener();
            Prompt rogue = new Prompt(null, "1.0.0", "Should not be stored");
            nacosListener.onEvent(new NacosPromptEvent(null, rogue));

            assertEquals("Keep this too", listener.getPrompt("keep2"));
        }

        @Test
        @DisplayName("should ignore callback when prompt has null template")
        void shouldIgnoreCallbackNullTemplate() throws Exception {
            Prompt original = new Prompt("keep3", "1.0.0", "Original");
            when(aiService.subscribePrompt(eq("keep3"), isNull(), isNull(), any()))
                    .thenReturn(original);

            assertEquals("Original", listener.getPrompt("keep3"));

            AbstractNacosPromptListener nacosListener = getInternalListener();
            Prompt bad = new Prompt("keep3", "2.0.0", null);
            nacosListener.onEvent(new NacosPromptEvent("keep3", bad));

            assertEquals("Original", listener.getPrompt("keep3"));
        }

        private AbstractNacosPromptListener getInternalListener() throws Exception {
            Field field = NacosPromptListener.class.getDeclaredField("internalListener");
            field.setAccessible(true);
            return (AbstractNacosPromptListener) field.get(listener);
        }
    }
}
