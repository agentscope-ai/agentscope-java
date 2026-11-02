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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.ai.model.prompt.PromptVariable;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies prompt selection, rendering, callback ordering, and subscription lifecycle contracts. */
class NacosPromptCompatibilityTest {
    private final AiService service = mock(AiService.class);
    private final NacosPromptListener prompts = new NacosPromptListener(service);

    @Test
    void preservesVariableDefaultsAndLiteralReplacement() throws Exception {
        Prompt prompt = new Prompt("greeting", "1.0.0", "{{name}} {{value}} {{missing}}");
        PromptVariable variable = new PromptVariable();
        variable.setName("name");
        variable.setDefaultValue("default");
        PromptVariable required = new PromptVariable();
        required.setName("missing");
        prompt.setVariables(List.of(variable, required));
        when(service.subscribePrompt(eq("greeting"), any(), any(), any())).thenReturn(prompt);
        assertEquals(
                "default $1\\path {{missing}}",
                prompts.getPrompt("greeting", Map.of("value", "$1\\path")));
        Map<String, String> args = new HashMap<>();
        args.put("name", null);
        args.put("value", "override");
        assertEquals(" override {{missing}}", prompts.getPrompt("greeting", args));
    }

    @Test
    void keepsSameNameVersionsAndLabelsIndependent() throws Exception {
        when(service.subscribePrompt(eq("shared"), any(), any(), any()))
                .thenAnswer(
                        call ->
                                new Prompt(
                                        "shared",
                                        call.getArgument(1),
                                        "target:"
                                                + call.getArgument(1)
                                                + ":"
                                                + call.getArgument(2)));
        assertEquals("target:1.0.0:null", prompts.getPrompt("shared", "1.0.0", null, null, null));
        assertEquals("target:2.0.0:null", prompts.getPrompt("shared", "2.0.0", null, null, null));
        assertEquals("target:null:prod", prompts.getPrompt("shared", null, "prod", null, null));
        assertEquals("target:null:null", prompts.getPrompt("shared"));
        verify(service, times(4)).subscribePrompt(eq("shared"), any(), any(), any());
    }

    @Test
    void callbackUpdatesOnlyItsSubscription() throws Exception {
        List<AbstractNacosPromptListener> callbacks = new ArrayList<>();
        when(service.subscribePrompt(eq("shared"), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            callbacks.add(call.getArgument(3));
                            return new Prompt("shared", "1.0.0", call.getArgument(2));
                        });
        assertEquals("prod", prompts.getPrompt("shared", null, "prod", null, null));
        assertEquals("test", prompts.getPrompt("shared", null, "test", null, null));
        callbacks
                .get(0)
                .onEvent(new NacosPromptEvent("shared", new Prompt("shared", "2.0.0", "new prod")));
        assertEquals("new prod", prompts.getPrompt("shared", null, "prod", null, null));
        assertEquals("test", prompts.getPrompt("shared", null, "test", null, null));
    }

    @Test
    void deletionUsesFallbackAndRecreationIsDelivered() throws Exception {
        AtomicReference<AbstractNacosPromptListener> callback = new AtomicReference<>();
        when(service.subscribePrompt(eq("deleted"), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            callback.set(call.getArgument(3));
                            return new Prompt("deleted", "1.0.0", "old");
                        });
        assertEquals("old", prompts.getPrompt("deleted"));
        callback.get().onEvent(new NacosPromptEvent("deleted", null));
        assertEquals("fallback", prompts.getPrompt("deleted", null, "fallback"));
        callback.get()
                .onEvent(new NacosPromptEvent("deleted", new Prompt("deleted", "2.0.0", "new")));
        assertEquals("new", prompts.getPrompt("deleted"));
        verify(service).subscribePrompt(eq("deleted"), any(), any(), any());
    }

    @Test
    void keepsCallbackDeliveredBeforeInitialLoadReturns() throws Exception {
        when(service.subscribePrompt(eq("race"), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            AbstractNacosPromptListener callback = call.getArgument(3);
                            callback.onEvent(new NacosPromptEvent("race", null));
                            return new Prompt("race", "1.0.0", "stale");
                        });
        assertEquals("fallback", prompts.getPrompt("race", null, "fallback"));
    }

    @Test
    void validatesSelectorsEvenAfterCacheHit() throws Exception {
        when(service.subscribePrompt(eq("key"), any(), any(), any()))
                .thenReturn(new Prompt("key", "1.0.0", "cached"));
        prompts.getPrompt("key");
        assertThrows(
                IllegalArgumentException.class,
                () -> prompts.getPrompt("key", "1.0.0", "prod", null, null));
    }

    @Test
    void concurrentLoadsShareOneSubscription() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        when(service.subscribePrompt(eq("concurrent"), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            Thread.sleep(100);
                            return new Prompt("concurrent", "1.0.0", "value");
                        });
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return prompts.getPrompt("concurrent");
                                }));
            start.countDown();
            for (var result : results) assertEquals("value", result.get(5, TimeUnit.SECONDS));
            verify(service).subscribePrompt(eq("concurrent"), any(), any(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-name", "other-label", "other-version"})
    void slowSubscriptionDoesNotBlockCachedSelections(String selection) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.subscribePrompt(any(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            if ("slow".equals(call.getArgument(0))
                                    || "slow".equals(call.getArgument(1))
                                    || "slow".equals(call.getArgument(2))) {
                                entered.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                            }
                            return new Prompt(call.getArgument(0), "1.0.0", "cached {{name}}");
                        });
        assertEquals(
                "cached user",
                prompts.getPrompt("shared", null, "prod", Map.of("name", "user"), null));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var slow =
                    pool.submit(
                            () ->
                                    prompts.getPrompt(
                                            "other-name".equals(selection) ? "slow" : "shared",
                                            "other-version".equals(selection) ? "slow" : null,
                                            "other-version".equals(selection) ? null : "slow",
                                            null,
                                            null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var cached =
                    pool.submit(
                            () ->
                                    prompts.getPrompt(
                                            "shared", null, "prod", Map.of("name", "user"), null));
            assertEquals("cached user", cached.get(2, TimeUnit.SECONDS));
            assertFalse(slow.isDone());
            release.countDown();
            slow.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            prompts.close();
        }
    }

    @Test
    void workerCallbackCanRunBeforeSubscriptionReturns() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        when(service.subscribePrompt(eq("race"), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            AbstractNacosPromptListener callback = call.getArgument(3);
                            pool.submit(
                                            () ->
                                                    callback.onEvent(
                                                            new NacosPromptEvent(
                                                                    "race",
                                                                    new Prompt(
                                                                            "race", "2.0.0",
                                                                            "updated"))))
                                    .get(5, TimeUnit.SECONDS);
                            return new Prompt("race", "1.0.0", "stale");
                        });
        try {
            assertEquals("updated", prompts.getPrompt("race"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            prompts.close();
        }
    }

    @Test
    void closeWaitsForSubscriptionAndCancelsItBeforeReturning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        when(service.subscribePrompt(eq("race"), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                            return new Prompt("race", "1.0.0", "value");
                        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var read = pool.submit(() -> prompts.getPrompt("race"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var close =
                    pool.submit(
                            () -> {
                                closing.countDown();
                                prompts.close();
                                return null;
                            });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> close.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("value", read.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            verify(service).unsubscribePrompt(eq("race"), any(), any(), any());
            verify(service, org.mockito.Mockito.never()).shutdown();
            assertThrows(IllegalStateException.class, () -> prompts.getPrompt("race"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            prompts.close();
        }
    }

    @Test
    void closesEverySelectorWithoutClosingSharedClient() throws Exception {
        prompts.getPrompt("shared", "1.0.0", null, null, null);
        prompts.getPrompt("shared", null, "prod", null, null);
        prompts.close();
        prompts.close();
        verify(service).unsubscribePrompt(eq("shared"), eq("1.0.0"), eq(null), any());
        verify(service).unsubscribePrompt(eq("shared"), eq(null), eq("prod"), any());
        verify(service, org.mockito.Mockito.never()).shutdown();
        assertThrows(IllegalStateException.class, () -> prompts.getPrompt("shared"));
    }

    @Test
    void failedLoadCanRetryWithoutCachingFallback() throws Exception {
        when(service.subscribePrompt(eq("retry"), any(), any(), any()))
                .thenThrow(new com.alibaba.nacos.api.exception.NacosException(503, "unavailable"))
                .thenReturn(new Prompt("retry", "1.0.0", "recovered"));
        assertEquals("fallback", prompts.getPrompt("retry", null, "fallback"));
        assertEquals("recovered", prompts.getPrompt("retry"));
    }

    @Test
    void failedCloseAttemptsEverySubscriptionAndRetriesOnlyFailures() throws Exception {
        prompts.getPrompt("one");
        prompts.getPrompt("two");
        prompts.getPrompt("success");
        NacosException first = new NacosException(503, "one");
        NacosException second = new NacosException(503, "two");
        doThrow(first).doNothing().when(service).unsubscribePrompt(eq("one"), any(), any(), any());
        doThrow(second).doNothing().when(service).unsubscribePrompt(eq("two"), any(), any(), any());
        NacosException failure = assertThrows(NacosException.class, prompts::close);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(
                java.util.Set.of(first, second),
                java.util.Set.of(failure, failure.getSuppressed()[0]));
        verify(service).unsubscribePrompt(eq("success"), any(), any(), any());
        prompts.close();
        verify(service, times(2)).unsubscribePrompt(eq("one"), any(), any(), any());
        verify(service, times(2)).unsubscribePrompt(eq("two"), any(), any(), any());
        verify(service).unsubscribePrompt(eq("success"), any(), any(), any());
    }

    @Test
    void blankSelectorsNormalizeToTheSameSubscription() throws Exception {
        assertEquals("", prompts.getPrompt("key", " ", "\t", null, null));
        assertEquals("", prompts.getPrompt("key"));
        verify(service).subscribePrompt(eq("key"), eq(null), eq(null), any());
        assertThrows(IllegalArgumentException.class, () -> prompts.getPrompt(null));
        assertThrows(IllegalArgumentException.class, () -> prompts.getPrompt(" "));
    }

    @Test
    void lateAndUnrelatedCallbacksDoNotChangeSelectedTemplate() throws Exception {
        AtomicReference<AbstractNacosPromptListener> callback = new AtomicReference<>();
        when(service.subscribePrompt(eq("key"), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            callback.set(call.getArgument(3));
                            return new Prompt("key", "1.0.0", "current");
                        });
        assertEquals("current", prompts.getPrompt("key"));
        callback.get().onEvent(null);
        callback.get()
                .onEvent(new NacosPromptEvent("other", new Prompt("other", "1.0.0", "wrong")));
        assertEquals("current", prompts.getPrompt("key"));
        prompts.close();
        callback.get().onEvent(new NacosPromptEvent("key", new Prompt("key", "2.0.0", "late")));
        assertThrows(IllegalStateException.class, () -> prompts.getPrompt("key"));
    }
}
