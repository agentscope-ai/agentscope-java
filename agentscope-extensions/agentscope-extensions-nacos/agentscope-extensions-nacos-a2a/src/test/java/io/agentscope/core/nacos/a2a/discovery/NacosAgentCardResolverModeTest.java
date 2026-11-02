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

package io.agentscope.core.nacos.a2a.discovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ability.constant.AbilityStatus;
import com.alibaba.nacos.api.ai.AgentService;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentCardListener;
import com.alibaba.nacos.api.ai.listener.AbstractNacosAgentDiscoveryListener;
import com.alibaba.nacos.api.ai.listener.NacosAgentCardEvent;
import com.alibaba.nacos.api.ai.listener.NacosAgentDiscoveryEvent;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.agent.AgentCallInterface;
import com.alibaba.nacos.api.ai.model.agent.AgentDiscoveryFilter;
import com.alibaba.nacos.api.ai.model.agent.AgentDiscoveryResult;
import com.alibaba.nacos.api.ai.model.agent.AgentReference;
import com.alibaba.nacos.api.ai.model.agent.Endpoint;
import com.alibaba.nacos.api.ai.model.agent.EndpointSet;
import com.alibaba.nacos.api.ai.model.agent.EndpointSource;
import com.alibaba.nacos.api.ai.model.agent.RuntimeVersionBinding;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** Tests the independent A2A and RAD discovery contracts through the public resolver. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NacosAgentCardResolverModeTest {
    private static final String MODE = "agentscope.nacos.a2a.discovery.mode";
    private final AiService client = mock(AiService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AtomicReference<AbstractNacosAgentDiscoveryListener> events =
            new AtomicReference<>();
    private String previousMode;
    private NacosAgentCardResolver resolver;

    @BeforeEach
    void rememberMode() throws Exception {
        previousMode = System.getProperty(MODE);
        System.clearProperty(MODE);
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.NOT_SUPPORTED);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (resolver != null) resolver.close();
        } finally {
            if (previousMode == null) System.clearProperty(MODE);
            else System.setProperty(MODE, previousMode);
        }
    }

    @Test
    void unsupportedCapabilityUsesOnlyA2aAndRetainsCacheUntilNextCardPush() throws Exception {
        AtomicReference<AbstractNacosAgentCardListener> cards = new AtomicReference<>();
        when(client.subscribeAgentCard(eq("demo"), any()))
                .thenAnswer(
                        call -> {
                            cards.set(call.getArgument(1));
                            return card("1.0.0");
                        });
        resolver = new NacosAgentCardResolver(client);
        assertEquals("1.0.0", resolver.getAgentCard("demo").version());
        // Failed SDK refreshes produce no legacy Card callback: the last Card remains usable.
        assertEquals("1.0.0", resolver.getAgentCard("demo").version());
        cards.get().onEvent(new NacosAgentCardEvent(card("2.0.0")));
        assertEquals("2.0.0", resolver.getAgentCard("demo").version());
        verify(client).subscribeAgentCard(eq("demo"), any());
        verify(client, never()).agent();
    }

    @Test
    void modeIsFixedWhenResolverIsCreated() throws Exception {
        System.setProperty(MODE, "a2a");
        resolver = new NacosAgentCardResolver(client);
        System.setProperty(MODE, "rad");
        when(client.subscribeAgentCard(eq("demo"), any())).thenReturn(card("1.0.0"));
        assertEquals("1.0.0", resolver.getAgentCard("demo").version());
        verify(client, never()).agent();
    }

    @Test
    void a2aIgnoresUnrelatedAndLateCallbacks() throws Exception {
        AtomicReference<AbstractNacosAgentCardListener> cards = new AtomicReference<>();
        when(client.subscribeAgentCard(eq("demo"), any()))
                .thenAnswer(
                        call -> {
                            cards.set(call.getArgument(1));
                            return card("1.0.0");
                        });
        resolver = new NacosAgentCardResolver(client);
        AgentCard cached = resolver.getAgentCard("demo");
        cards.get().onEvent(null);
        AgentCardDetailInfo other = card("2.0.0");
        other.setName("other");
        cards.get().onEvent(new NacosAgentCardEvent(other));
        assertSame(cached, resolver.getAgentCard("demo"));
        resolver.close();
        cards.get().onEvent(new NacosAgentCardEvent(card("3.0.0")));
        assertThrows(IllegalStateException.class, () -> resolver.getAgentCard("demo"));
    }

    @Test
    void radIgnoresEmptyAndUnrelatedSnapshots() throws Exception {
        useRad(snapshot("1.0.0"));
        AgentCard cached = resolver.getAgentCard("demo");
        events.get().onEvent(null);
        events.get().onEvent(mock(NacosAgentDiscoveryEvent.class));
        AgentDiscoveryResult other = snapshot("2.0.0");
        other.setAgentName("other");
        events.get().onEvent(new NacosAgentDiscoveryEvent(other));
        assertSame(cached, resolver.getAgentCard("demo"));
    }

    @Test
    void invalidModeFailsBeforeCreatingOwnedClient() {
        System.setProperty(MODE, "typo");
        try (var factory = mockStatic(AiFactory.class)) {
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new NacosAgentCardResolver(new Properties()));
            assertTrue(failure.getMessage().contains(MODE));
            factory.verifyNoInteractions();
        }
    }

    @Test
    void radUsesOnlyNativeSubscriptionAndPreservesDescriptorFields() throws Exception {
        AgentDiscoveryResult initial = snapshot("1.0.0");
        useRad(initial);
        AgentCard result = resolver.getAgentCard("demo");
        assertEquals("demo", result.name());
        assertEquals("1.0.0", result.version());
        assertEquals("0.3.0", result.supportedInterfaces().get(0).protocolVersion());
        assertEquals("Translate text", result.description());
        assertEquals("https://example.org/docs", result.documentationUrl());
        assertEquals("https://example.org/icon.png", result.iconUrl());
        assertEquals("Example", result.provider().organization());
        assertTrue(result.capabilities().streaming());
        assertTrue(result.capabilities().extendedAgentCard());
        assertEquals("translate", result.skills().get(0).id());
        assertEquals(List.of("text"), result.defaultInputModes());
        assertEquals(List.of("text"), result.defaultOutputModes());
        assertEquals(
                List.of(Map.of("apiKey", List.of())),
                result.securityRequirements().stream()
                        .map(org.a2aproject.sdk.spec.SecurityRequirement::schemes)
                        .toList());
        assertNotNull(result.securitySchemes().get("apiKey"));
        assertEquals("http://runtime:8080/agent", result.supportedInterfaces().get(0).url());
        assertEquals("JSONRPC", result.supportedInterfaces().get(0).protocolBinding());
        assertEquals(1, result.supportedInterfaces().size());
        assertSame(result, resolver.getAgentCard("demo"));
        ArgumentCaptor<AgentReference> reference = ArgumentCaptor.forClass(AgentReference.class);
        ArgumentCaptor<AgentDiscoveryFilter> filter =
                ArgumentCaptor.forClass(AgentDiscoveryFilter.class);
        verify(agentService).subscribeAgent(reference.capture(), filter.capture(), any());
        assertEquals("demo", reference.getValue().getAgentName());
        assertEquals("latest", reference.getValue().getLabel());
        assertNull(reference.getValue().getVersion());
        assertEquals(List.of("a2a"), filter.getValue().getProtocols());
        verify(client, never()).subscribeAgentCard(anyString(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> descriptor =
                (Map<String, Object>) initial.getCallInterfaces().get(0).getNativeDescriptor();
        assertEquals("http://declared/agent", descriptor.get("url"));
    }

    @Test
    void radUnavailableClearsCacheAndRecoversThroughSameSubscription() throws Exception {
        useRad(snapshot("1.0.0"));
        assertNotNull(resolver.getAgentCard("demo"));
        events.get().onEvent(NacosAgentDiscoveryEvent.unavailable(404, "not discoverable"));
        assertNull(resolver.getAgentCard("demo"));
        assertNull(resolver.getAgentCard("demo"));
        events.get().onEvent(new NacosAgentDiscoveryEvent(snapshot("2.0.0")));
        assertEquals("2.0.0", resolver.getAgentCard("demo").version());
        verify(agentService).subscribeAgent(any(), any(), any());
    }

    @Test
    void initiallyMissingRadTargetAcceptsLaterSnapshot() throws Exception {
        useRad(null);
        assertNull(resolver.getAgentCard("demo"));
        assertNull(resolver.getAgentCard("demo"));
        events.get().onEvent(new NacosAgentDiscoveryEvent(snapshot("1.0.0")));
        assertNotNull(resolver.getAgentCard("demo"));
        verify(agentService).subscribeAgent(any(), any(), any());
    }

    @Test
    void radSnapshotMayArriveBeforeSubscribeReturns() throws Exception {
        useRad(snapshot("1.0.0"));
        doAnswer(
                        call -> {
                            AbstractNacosAgentDiscoveryListener listener = call.getArgument(2);
                            listener.onEvent(new NacosAgentDiscoveryEvent(snapshot("2.0.0")));
                            return null;
                        })
                .when(agentService)
                .subscribeAgent(any(), any(), any());
        assertEquals("2.0.0", resolver.getAgentCard("demo").version());
    }

    @Test
    void radUnavailableMayArriveBeforeSubscribeReturns() throws Exception {
        useRad(snapshot("1.0.0"));
        doAnswer(
                        call -> {
                            AbstractNacosAgentDiscoveryListener listener = call.getArgument(2);
                            listener.onEvent(NacosAgentDiscoveryEvent.unavailable(404, "removed"));
                            return null;
                        })
                .when(agentService)
                .subscribeAgent(any(), any(), any());
        assertNull(resolver.getAgentCard("demo"));
    }

    @Test
    void unsupportedRadPropagatesErrorWithoutA2aFallback() throws Exception {
        useRad(null);
        NacosException unsupported = new NacosException(501, "RAD is unsupported");
        doThrow(unsupported).when(agentService).subscribeAgent(any(), any(), any());
        NacosRuntimeException failure =
                assertThrows(NacosRuntimeException.class, () -> resolver.getAgentCard("demo"));
        assertEquals(501, failure.getErrCode());
        assertSame(unsupported, failure.getCause());
        verify(client, never()).subscribeAgentCard(anyString(), any());
    }

    @Test
    void radCloseUnsubscribesOnlyNativeListenerAndIgnoresLateEvents() throws Exception {
        useRad(snapshot("1.0.0"));
        resolver.getAgentCard("demo");
        resolver.close();
        events.get().onEvent(new NacosAgentDiscoveryEvent(snapshot("2.0.0")));
        resolver.close();
        ArgumentCaptor<AgentReference> reference = ArgumentCaptor.forClass(AgentReference.class);
        ArgumentCaptor<AgentDiscoveryFilter> filter =
                ArgumentCaptor.forClass(AgentDiscoveryFilter.class);
        verify(agentService)
                .subscribeAgent(reference.capture(), filter.capture(), eq(events.get()));
        verify(agentService)
                .unsubscribeAgent(
                        org.mockito.ArgumentMatchers.same(reference.getValue()),
                        org.mockito.ArgumentMatchers.same(filter.getValue()),
                        eq(events.get()));
        verify(client, never()).unsubscribeAgentCard(anyString(), any());
        verify(client, never()).shutdown();
        assertThrows(IllegalStateException.class, () -> resolver.getAgentCard("demo"));
    }

    @Test
    void radEndpointChangesUpdateCardWithoutChangingVersion() throws Exception {
        useRad(snapshot("1.0.0"));
        assertEquals(
                "http://runtime:8080/agent",
                resolver.getAgentCard("demo").supportedInterfaces().get(0).url());
        AgentDiscoveryResult updated = snapshot("1.0.0");
        updated.getCallInterfaces()
                .get(0)
                .getEndpointSets()
                .get(0)
                .getEndpoints()
                .get(0)
                .setUri("http://new-runtime:8080/agent");
        events.get().onEvent(new NacosAgentDiscoveryEvent(updated));
        assertEquals(
                "http://new-runtime:8080/agent",
                resolver.getAgentCard("demo").supportedInterfaces().get(0).url());
        verify(agentService).subscribeAgent(any(), any(), any());
    }

    @Test
    void radSnapshotWithoutA2aReplacesPreviouslyCachedCard() throws Exception {
        useRad(snapshot("1.0.0"));
        assertNotNull(resolver.getAgentCard("demo"));
        AgentDiscoveryResult updated = snapshot("2.0.0");
        updated.getCallInterfaces().get(0).setProtocol("other");
        assertDoesNotThrow(() -> events.get().onEvent(new NacosAgentDiscoveryEvent(updated)));
        assertThrows(IllegalArgumentException.class, () -> resolver.getAgentCard("demo"));
        events.get().onEvent(new NacosAgentDiscoveryEvent(snapshot("3.0.0")));
        assertEquals("3.0.0", resolver.getAgentCard("demo").version());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a2a", "rad"})
    void concurrentFirstReadsShareOneSubscription(String mode) throws Exception {
        if ("rad".equals(mode)) {
            useRad(snapshot("1.0.0"));
        } else {
            when(client.subscribeAgentCard(eq("demo"), any())).thenReturn(card("1.0.0"));
            resolver = new NacosAgentCardResolver(client);
        }
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<AgentCard> lookup =
                () -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return resolver.getAgentCard("demo");
                };
        try {
            var first = executor.submit(lookup);
            var second = executor.submit(lookup);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        if ("rad".equals(mode)) verify(agentService).subscribeAgent(any(), any(), any());
        else verify(client).subscribeAgentCard(eq("demo"), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a2a", "rad"})
    void unsubscribeFailureStillClosesOwnedClientAndOtherSubscriptions(String mode)
            throws Exception {
        System.setProperty(MODE, mode);
        NacosException unsubscribeFailure = new NacosException(500, "unsubscribe failed");
        NacosException shutdownFailure = new NacosException(503, "shutdown failed");
        if ("rad".equals(mode)) {
            when(client.agent()).thenReturn(agentService);
            doAnswer(
                            call -> {
                                AbstractNacosAgentDiscoveryListener listener = call.getArgument(2);
                                listener.onEvent(
                                        NacosAgentDiscoveryEvent.unavailable(404, "missing"));
                                return null;
                            })
                    .when(agentService)
                    .subscribeAgent(any(), any(), any());
            doThrow(unsubscribeFailure).when(agentService).unsubscribeAgent(any(), any(), any());
        } else {
            doThrow(unsubscribeFailure).when(client).unsubscribeAgentCard(anyString(), any());
        }
        doThrow(shutdownFailure).when(client).shutdown();
        Properties properties = new Properties();
        try (var factory = mockStatic(AiFactory.class)) {
            factory.when(() -> AiFactory.createAiService(properties)).thenReturn(client);
            resolver = new NacosAgentCardResolver(properties);
        }
        assertNull(resolver.getAgentCard("one"));
        assertNull(resolver.getAgentCard("two"));
        NacosException failure = assertThrows(NacosException.class, resolver::close);
        assertSame(unsubscribeFailure, failure);
        assertEquals(List.of(shutdownFailure), List.of(failure.getSuppressed()));
        if ("rad".equals(mode)) {
            verify(agentService, org.mockito.Mockito.times(2))
                    .unsubscribeAgent(any(), any(), any());
        } else {
            verify(client).unsubscribeAgentCard(eq("one"), any());
            verify(client).unsubscribeAgentCard(eq("two"), any());
        }
        verify(client).shutdown();
    }

    @Test
    void supportedCapabilityAutomaticallyUsesRadAndDoesNotRequery() throws Exception {
        useRad(snapshot("1.0.0"));
        System.clearProperty(MODE);
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.SUPPORTED);
        resolver = new NacosAgentCardResolver(client);
        assertEquals("1.0.0", resolver.getAgentCard("demo").version());
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.NOT_SUPPORTED);
        assertEquals("1.0.0", resolver.getAgentCard("demo").version());
        verify(client).getAgentDiscoveryCapability();
        verify(client, never()).subscribeAgentCard(anyString(), any());
    }

    @Test
    void unknownCapabilityDoesNotFallBackAndCanBeRetried() throws Exception {
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.UNKNOWN);
        resolver = new NacosAgentCardResolver(client);
        assertThrows(IllegalStateException.class, () -> resolver.getAgentCard("demo"));
        verify(client, never()).agent();
        verify(client, never()).subscribeAgentCard(anyString(), any());
        when(client.getAgentDiscoveryCapability()).thenReturn(AbilityStatus.NOT_SUPPORTED);
        when(client.subscribeAgentCard(eq("demo"), any())).thenReturn(card("1.0.0"));
        assertNotNull(resolver.getAgentCard("demo"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 503, 500})
    void capabilityFailurePreservesCauseWithoutSelectingAPath(int code) throws Exception {
        NacosException cause = new NacosException(code, "capability probe failed");
        when(client.getAgentDiscoveryCapability()).thenThrow(cause);
        resolver = new NacosAgentCardResolver(client);
        NacosRuntimeException error =
                assertThrows(NacosRuntimeException.class, () -> resolver.getAgentCard("demo"));
        assertSame(cause, error.getCause());
        assertEquals(code, error.getErrCode());
        verify(client, never()).agent();
        verify(client, never()).subscribeAgentCard(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a2a", "rad", " RAD "})
    void explicitModeDoesNotRequireCapabilityQuery(String mode) throws Exception {
        useRad(snapshot("1.0.0"));
        System.setProperty(MODE, mode);
        resolver = new NacosAgentCardResolver(client);
        resolver.getAgentCard("demo");
        verify(client, never()).getAgentDiscoveryCapability();
    }

    @Test
    void delayedInitialCallbackIsAwaitedWithoutAnotherSubscription() throws Exception {
        useRad(null);
        CountDownLatch subscribed = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            events.set(call.getArgument(2));
                            subscribed.countDown();
                            return null;
                        })
                .when(agentService)
                .subscribeAgent(any(), any(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> resolver.getAgentCard("demo"));
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            events.get().onEvent(new NacosAgentDiscoveryEvent(snapshot("1.0.0")));
            assertEquals("1.0.0", result.get(5, TimeUnit.SECONDS).version());
            verify(agentService).subscribeAgent(any(), any(), any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutKeepsSubscriptionForALaterInitialCallback() throws Exception {
        when(client.agent()).thenReturn(agentService);
        doAnswer(
                        call -> {
                            events.set(call.getArgument(2));
                            return null;
                        })
                .when(agentService)
                .subscribeAgent(any(), any(), any());
        var delegate = new RadAgentCardResolver(client, Duration.ZERO);
        try {
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> delegate.getAgentCard("demo"));
            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
            events.get().onEvent(new NacosAgentDiscoveryEvent(snapshot("1.0.0")));
            assertNotNull(delegate.getAgentCard("demo"));
            verify(agentService).subscribeAgent(any(), any(), any());
        } finally {
            delegate.close();
        }
    }

    @Test
    void interruptedInitialWaitPreservesInterruptAndSubscription() throws Exception {
        when(client.agent()).thenReturn(agentService);
        var delegate = new RadAgentCardResolver(client, Duration.ofSeconds(10));
        Thread.currentThread().interrupt();
        try {
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> delegate.getAgentCard("demo"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(error.getCause() instanceof InterruptedException);
        } finally {
            Thread.interrupted();
            delegate.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"interfaces", "endpoints", "descriptor"})
    void initialConversionFailureIsReportedToReaderAndLaterStateRecovers(String missing)
            throws Exception {
        AgentDiscoveryResult invalid = snapshot("1.0.0");
        if ("interfaces".equals(missing)) invalid.setCallInterfaces(List.of());
        else if ("endpoints".equals(missing)) {
            invalid.getCallInterfaces()
                    .get(0)
                    .getEndpointSets()
                    .forEach(set -> set.setEndpoints(List.of()));
        } else invalid.getCallInterfaces().get(0).setNativeDescriptor(List.of("invalid"));
        useRad(invalid);
        IllegalArgumentException first =
                assertThrows(IllegalArgumentException.class, () -> resolver.getAgentCard("demo"));
        assertSame(
                first,
                assertThrows(IllegalArgumentException.class, () -> resolver.getAgentCard("demo")));
        events.get().onEvent(NacosAgentDiscoveryEvent.unavailable(404, "missing"));
        assertNull(resolver.getAgentCard("demo"));
        events.get().onEvent(new NacosAgentDiscoveryEvent(snapshot("2.0.0")));
        assertEquals("2.0.0", resolver.getAgentCard("demo").version());
        verify(agentService).subscribeAgent(any(), any(), any());
    }

    @Test
    void cachedReadsDoNotConsumeTheCallersInterrupt() throws Exception {
        useRad(snapshot("1.0.0"));
        AgentCard cached = resolver.getAgentCard("demo");
        Thread.currentThread().interrupt();
        try {
            assertSame(cached, resolver.getAgentCard("demo"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a2a", "rad"})
    void slowSubscriptionDoesNotBlockOtherCachedCards(String mode) throws Exception {
        System.setProperty(MODE, mode);
        when(client.agent()).thenReturn(agentService);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        if ("a2a".equals(mode)) {
            when(client.subscribeAgentCard(anyString(), any()))
                    .thenAnswer(
                            call -> {
                                if ("slow".equals(call.getArgument(0))) {
                                    entered.countDown();
                                    assertTrue(release.await(10, TimeUnit.SECONDS));
                                }
                                return card("1.0.0");
                            });
        } else {
            doAnswer(
                            call -> {
                                AgentReference reference = call.getArgument(0);
                                if ("slow".equals(reference.getAgentName())) {
                                    entered.countDown();
                                    assertTrue(release.await(10, TimeUnit.SECONDS));
                                }
                                AbstractNacosAgentDiscoveryListener callback = call.getArgument(2);
                                callback.onEvent(
                                        new NacosAgentDiscoveryEvent(
                                                namedSnapshot(reference.getAgentName())));
                                return null;
                            })
                    .when(agentService)
                    .subscribeAgent(any(), any(), any());
        }
        resolver = new NacosAgentCardResolver(client);
        AgentCard cached = resolver.getAgentCard("demo");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var slow = pool.submit(() -> resolver.getAgentCard("slow"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var fast = pool.submit(() -> resolver.getAgentCard("demo"));
            assertSame(cached, fast.get(2, TimeUnit.SECONDS));
            assertFalse(slow.isDone());
            release.countDown();
            assertNotNull(slow.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void waitingForInitialRadCallbackDoesNotBlockOtherCachedCards() throws Exception {
        useRad(snapshot("1.0.0"));
        AgentCard cached = resolver.getAgentCard("demo");
        CountDownLatch subscribed = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            events.set(call.getArgument(2));
                            subscribed.countDown();
                            return null;
                        })
                .when(agentService)
                .subscribeAgent(any(), any(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var slow = pool.submit(() -> resolver.getAgentCard("slow"));
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            assertSame(
                    cached,
                    pool.submit(() -> resolver.getAgentCard("demo")).get(2, TimeUnit.SECONDS));
            assertFalse(slow.isDone());
            events.get().onEvent(new NacosAgentDiscoveryEvent(namedSnapshot("slow")));
            assertNotNull(slow.get(5, TimeUnit.SECONDS));
        } finally {
            if (events.get() != null)
                events.get().onEvent(NacosAgentDiscoveryEvent.unavailable(404, "missing"));
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a2a", "rad"})
    void concurrentFirstReadsShareSubscriptionAndAllowWorkerCallbacks(String mode)
            throws Exception {
        System.setProperty(MODE, mode);
        when(client.agent()).thenReturn(agentService);
        var callbacks = Executors.newSingleThreadExecutor();
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        if ("a2a".equals(mode)) {
            when(client.subscribeAgentCard(eq("demo"), any()))
                    .thenAnswer(
                            call -> {
                                entered.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                                AbstractNacosAgentCardListener callback = call.getArgument(1);
                                callbacks
                                        .submit(
                                                () ->
                                                        callback.onEvent(
                                                                new NacosAgentCardEvent(
                                                                        card("2.0.0"))))
                                        .get(5, TimeUnit.SECONDS);
                                return card("1.0.0");
                            });
        } else {
            doAnswer(
                            call -> {
                                entered.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                                AbstractNacosAgentDiscoveryListener callback = call.getArgument(2);
                                callbacks
                                        .submit(
                                                () ->
                                                        callback.onEvent(
                                                                new NacosAgentDiscoveryEvent(
                                                                        snapshot("2.0.0"))))
                                        .get(5, TimeUnit.SECONDS);
                                return null;
                            })
                    .when(agentService)
                    .subscribeAgent(any(), any(), any());
        }
        resolver = new NacosAgentCardResolver(client);
        try {
            List<java.util.concurrent.Future<AgentCard>> reads = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                reads.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return resolver.getAgentCard("demo");
                                }));
            start.countDown();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (var read : reads) assertEquals("2.0.0", read.get(5, TimeUnit.SECONDS).version());
            if ("a2a".equals(mode)) verify(client).subscribeAgentCard(eq("demo"), any());
            else verify(agentService).subscribeAgent(any(), any(), any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            callbacks.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a2a", "rad"})
    void closeWaitsForInFlightSubscriptionAndThenCancelsIt(String mode) throws Exception {
        System.setProperty(MODE, mode);
        when(client.agent()).thenReturn(agentService);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        if ("a2a".equals(mode)) {
            when(client.subscribeAgentCard(eq("demo"), any()))
                    .thenAnswer(
                            call -> {
                                entered.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                                return card("1.0.0");
                            });
        } else {
            doAnswer(
                            call -> {
                                entered.countDown();
                                assertTrue(release.await(10, TimeUnit.SECONDS));
                                AbstractNacosAgentDiscoveryListener callback = call.getArgument(2);
                                callback.onEvent(new NacosAgentDiscoveryEvent(snapshot("1.0.0")));
                                return null;
                            })
                    .when(agentService)
                    .subscribeAgent(any(), any(), any());
        }
        resolver = new NacosAgentCardResolver(client);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch closing = new CountDownLatch(1);
        try {
            var reading = pool.submit(() -> resolver.getAgentCard("demo"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var close =
                    pool.submit(
                            () -> {
                                closing.countDown();
                                resolver.close();
                                return null;
                            });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> close.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertNotNull(reading.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            if ("a2a".equals(mode)) verify(client).unsubscribeAgentCard(eq("demo"), any());
            else verify(agentService).unsubscribeAgent(any(), any(), any());
            verify(client, never()).shutdown();
            assertThrows(IllegalStateException.class, () -> resolver.getAgentCard("demo"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static AgentDiscoveryResult namedSnapshot(String name) {
        AgentDiscoveryResult result = snapshot("1.0.0");
        result.setAgentName(name);
        Map<String, Object> descriptor =
                new LinkedHashMap<>(
                        (Map<String, Object>)
                                result.getCallInterfaces().get(0).getNativeDescriptor());
        descriptor.put("name", name);
        result.getCallInterfaces().get(0).setNativeDescriptor(descriptor);
        return result;
    }

    private void useRad(AgentDiscoveryResult initial) throws NacosException {
        System.setProperty(MODE, "rad");
        when(client.agent()).thenReturn(agentService);
        doAnswer(
                        call -> {
                            events.set(call.getArgument(2));
                            events.get()
                                    .onEvent(
                                            initial == null
                                                    ? NacosAgentDiscoveryEvent.unavailable(
                                                            404, "missing")
                                                    : new NacosAgentDiscoveryEvent(initial));
                            return null;
                        })
                .when(agentService)
                .subscribeAgent(any(), any(), any());
        resolver = new NacosAgentCardResolver(client);
    }

    static AgentCardDetailInfo card(String version) {
        AgentCardDetailInfo card = new AgentCardDetailInfo();
        card.setName("demo");
        card.setVersion(version);
        card.setDescription("Translate text");
        card.setUrl("http://declared/agent");
        card.setProtocolVersion("0.3.0");
        card.setPreferredTransport("JSONRPC");
        card.setSupportsAuthenticatedExtendedCard(false);
        card.setDefaultInputModes(List.of("text"));
        card.setDefaultOutputModes(List.of("text"));
        return card;
    }

    static AgentDiscoveryResult snapshot(String version) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", "demo");
        descriptor.put("version", version);
        descriptor.put("protocolVersion", "0.3.0");
        descriptor.put("description", "Translate text");
        descriptor.put("url", "http://declared/agent");
        descriptor.put("preferredTransport", "JSONRPC");
        descriptor.put("documentationUrl", "https://example.org/docs");
        descriptor.put("iconUrl", "https://example.org/icon.png");
        descriptor.put("provider", Map.of("organization", "Example", "url", "https://example.org"));
        descriptor.put("capabilities", Map.of("streaming", true));
        descriptor.put("supportsAuthenticatedExtendedCard", true);
        descriptor.put(
                "skills",
                List.of(
                        Map.of(
                                "id",
                                "translate",
                                "name",
                                "Translate",
                                "description",
                                "Translate text",
                                "tags",
                                List.of("language"))));
        descriptor.put("defaultInputModes", List.of("text"));
        descriptor.put("defaultOutputModes", List.of("text"));
        descriptor.put("security", List.of(Map.of("apiKey", List.of())));
        descriptor.put(
                "securitySchemes",
                Map.of("apiKey", Map.of("type", "apiKey", "in", "header", "name", "X-API-Key")));
        AgentCallInterface call = new AgentCallInterface();
        call.setProtocol("a2a");
        call.setProtocolVersion("0.3.0");
        call.setDescriptorMediaType("application/json");
        call.setNativeDescriptor(descriptor);
        Endpoint endpoint = new Endpoint();
        endpoint.setUri("http://runtime:8080/agent");
        endpoint.setTransport("JSONRPC");
        EndpointSet runtime = new EndpointSet();
        runtime.setSource(EndpointSource.RUNTIME);
        runtime.setSourceRevision("murmur3-x64-128-v1:" + "0".repeat(32));
        RuntimeVersionBinding binding = new RuntimeVersionBinding();
        binding.setRuntimeVersion(version);
        binding.setVersionRange("[" + version + "]");
        endpoint.setBindings(List.of(binding));
        runtime.setEndpoints(List.of(endpoint));
        EndpointSet declared = new EndpointSet();
        declared.setSource(EndpointSource.DECLARED);
        declared.setSourceRevision("sha256:" + "0".repeat(64));
        declared.setEndpoints(List.of());
        call.setEndpointSets(new ArrayList<>(List.of(runtime, declared)));
        AgentDiscoveryResult result = new AgentDiscoveryResult();
        result.setAgentName("demo");
        result.setContentDigest("sha256:" + "0".repeat(64));
        result.setVersion(version);
        result.setNamespaceId("public");
        result.setCallInterfaces(List.of(call));
        return result;
    }
}
