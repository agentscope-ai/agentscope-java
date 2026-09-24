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
package io.agentscope.extensions.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

/**
 * Runtime paths the loopback fixture does not reach: agent-initiated delivery, inbound messages
 * without a provider id, peer throttling, and runtime-listener isolation.
 */
@Timeout(60)
class WeixinChannelRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EMPTY_BATCH =
            "{\"ret\":0,\"msgs\":[],\"get_updates_buf\":\"cursor-empty\"}";

    private HttpServer server;
    private WeixinChannel channel;
    private final AtomicInteger providerStarts = new AtomicInteger();
    private final AtomicInteger notifyStartFailures = new AtomicInteger();
    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicInteger dispatches = new AtomicInteger();
    private final AtomicInteger rejections = new AtomicInteger();
    private final AtomicReference<JsonNode> lastSend = new AtomicReference<>();
    private final List<String> transientFailures = Collections.synchronizedList(new ArrayList<>());
    private volatile Supplier<String> updates = () -> EMPTY_BATCH;
    private volatile String sendResponse = "{\"message_id\":\"receipt-1\"}";
    private volatile Supplier<Mono<Msg>> agentReply = () -> Mono.just(assistant("reply"));
    private volatile String fixedAccountId;
    private int maxBackoffMs = 30000;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    String body = "{\"ret\":0}";
                    int status = 200;
                    if (path.endsWith("getupdates")) {
                        JSON.readTree(exchange.getRequestBody());
                        polls.incrementAndGet();
                        // A real getupdates blocks; answering instantly would spin the consumer
                        // loop as fast as the CPU allows and starve the rest of the suite.
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        body = updates.get();
                    } else if (path.endsWith("sendmessage")) {
                        sends.incrementAndGet();
                        lastSend.set(JSON.readTree(exchange.getRequestBody()));
                        body = sendResponse;
                    } else {
                        exchange.getRequestBody().readAllBytes();
                        if (path.endsWith("notifystart")) {
                            providerStarts.incrementAndGet();
                            if (notifyStartFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                                status = 500;
                                body = "{\"ret\":1,\"errcode\":1}";
                            }
                        }
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        if (channel != null) channel.stop();
        if (server != null) server.stop(0);
    }

    @Test
    void deliversWithTheContextTokenStoredForThePeer() throws Exception {
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doReturn("ctx-9").when(store).loadContextToken(anyString(), anyString());
        startChannel(store, WeixinRuntimeListener.noOp());
        awaitProviderStart();

        channel.deliver(
                OutboundAddress.direct("channel", "weixin:DIRECT:peer-a"),
                List.of(assistant("hello")));

        assertTrue(waitFor(() -> sends.get() == 1), "deliver never reached sendmessage");
        JsonNode msg = lastSend.get().path("msg");
        assertEquals("peer-a", msg.path("to_user_id").asText(), "delivery target");
        assertEquals("ctx-9", msg.path("context_token").asText(), "stored context token");
        assertEquals(
                "hello", msg.path("item_list").path(0).path("text_item").path("text").asText());
    }

    @Test
    void rejectsDeliveryWithoutAnActiveConsumer() {
        WeixinChannel idle = newIdleChannel();
        assertThrows(
                IllegalStateException.class,
                () ->
                        idle.deliver(
                                OutboundAddress.direct("channel", "weixin:DIRECT:peer-a"),
                                List.of(assistant("hello"))));
    }

    @Test
    void reportsCredentialRejectionFromDeliveryAndStopsTheChannel() throws Exception {
        sendResponse = "{\"ret\":-14}";
        startChannel(
                WeixinStateStore.inMemory(),
                new WeixinRuntimeListener() {
                    @Override
                    public void onCredentialRejected(String accountId, String reason) {
                        rejections.incrementAndGet();
                    }
                });
        awaitProviderStart();

        channel.deliver(
                OutboundAddress.direct("channel", "weixin:DIRECT:peer-a"),
                List.of(assistant("hello")));

        assertTrue(waitFor(() -> rejections.get() == 1), "rejection was not reported to the host");
        assertThrows(
                IllegalStateException.class,
                () ->
                        channel.deliver(
                                OutboundAddress.direct("channel", "weixin:DIRECT:peer-a"),
                                List.of(assistant("hello"))),
                "a rejected credential must stop the channel");
    }

    @Test
    void dispatchesMessagesWithoutAProviderIdExactlyOnce() throws Exception {
        updates = () -> polls.get() <= 2 ? idlessBatch() : EMPTY_BATCH;
        startChannel(WeixinStateStore.inMemory(), WeixinRuntimeListener.noOp());

        assertTrue(
                waitFor(() -> dispatches.get() == 1),
                "a message without message_id was not dispatched");
        assertTrue(waitFor(() -> polls.get() >= 3), "provider was not polled again");
        assertEquals(1, dispatches.get(), "the repeated idless payload was dispatched twice");
        assertEquals(1, sends.get(), "replies");
    }

    @Test
    void peerThrottleStopsDispatchAndSurfacesATransientFailure() throws Exception {
        updates = () -> batch(21, "peer-a");
        AtomicInteger failed = new AtomicInteger();
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doAnswer(
                        invocation -> {
                            failed.incrementAndGet();
                            return invocation.callRealMethod();
                        })
                .when(store)
                .failMessage(anyString(), any(WeixinLease.class), any(WeixinInboxClaim.class));
        startChannel(
                store,
                new WeixinRuntimeListener() {
                    @Override
                    public void onTransientFailure(String accountId, String reason) {
                        transientFailures.add(reason);
                    }
                });

        assertTrue(waitFor(() -> failed.get() > 0), "the throttled message was never failed");
        // Wait for the report before stopping: a stop that lands first makes the poll loop skip
        // the notification, which is correct shutdown behaviour but not what this test asserts.
        assertTrue(
                waitFor(() -> !transientFailures.isEmpty()),
                "throttling must surface a transient failure");
        channel.stop();
        assertEquals(20, dispatches.get(), "the peer throttle must stop before the 21st event");
        assertTrue(
                transientFailures.stream().noneMatch(reason -> reason.contains("hello")),
                "failure reasons must stay payload-free: " + transientFailures);
    }

    @Test
    void pollingCredentialRejectionStopsTheChannel() throws Exception {
        updates = () -> "{\"ret\":-14}";
        startChannel(
                WeixinStateStore.inMemory(),
                new WeixinRuntimeListener() {
                    @Override
                    public void onCredentialRejected(String accountId, String reason) {
                        rejections.incrementAndGet();
                    }
                });

        assertTrue(waitFor(() -> rejections.get() == 1), "polling rejection was not reported");
        assertThrows(
                IllegalStateException.class,
                () ->
                        channel.deliver(
                                OutboundAddress.direct("channel", "weixin:DIRECT:peer-a"),
                                List.of(assistant("hello"))),
                "a rejected credential must stop the channel");
    }

    @Test
    void pollingFailuresAreRetriedAndReported() throws Exception {
        updates = () -> "{\"ret\":7,\"errcode\":7}";
        startChannel(
                WeixinStateStore.inMemory(),
                new WeixinRuntimeListener() {
                    @Override
                    public void onTransientFailure(String accountId, String reason) {
                        transientFailures.add(reason);
                    }
                });

        assertTrue(waitFor(() -> !transientFailures.isEmpty()), "polling failure was not reported");
        assertTrue(waitFor(() -> polls.get() >= 2), "the poll loop must keep retrying");
    }

    @Test
    void fencesMessagesWhenContextBookkeepingLosesTheLease() throws Exception {
        updates = () -> batch(1, "peer-a");
        AtomicInteger failed = new AtomicInteger();
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doReturn(false)
                .when(store)
                .saveContextToken(anyString(), any(WeixinLease.class), anyString(), anyString());
        doAnswer(
                        invocation -> {
                            failed.incrementAndGet();
                            return invocation.callRealMethod();
                        })
                .when(store)
                .failMessage(anyString(), any(WeixinLease.class), any(WeixinInboxClaim.class));
        startChannel(store, WeixinRuntimeListener.noOp());

        assertTrue(waitFor(() -> failed.get() > 0), "a fenced context update was not failed");
        channel.stop();
        assertEquals(0, dispatches.get(), "a fenced context update must not reach the agent");
    }

    @Test
    void providerSessionStartIsRetriedAfterATransientFailure() throws Exception {
        notifyStartFailures.set(2);
        startChannel(
                WeixinStateStore.inMemory(),
                new WeixinRuntimeListener() {
                    @Override
                    public void onTransientFailure(String accountId, String reason) {
                        transientFailures.add(reason);
                    }
                });

        assertTrue(
                waitFor(() -> providerStarts.get() >= 3),
                "notifystart was not retried: attempts=" + providerStarts.get());
        assertFalse(transientFailures.isEmpty(), "the failed startup must be reported");
    }

    /** Inline delivery has no host queue: a refused reply must leave the message retryable. */
    @Test
    void inlineReplyFailureIsRetriedBeforeCompletingTheMessage() throws Exception {
        updates = () -> polls.get() <= 2 ? batch(1, "peer-sendfail") : EMPTY_BATCH;
        sendResponse = "{\"ret\":-2,\"errcode\":0,\"errmsg\":\"prepare failed\"}";
        List<String> deliveryFailures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completed = new AtomicInteger();
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doAnswer(
                        invocation -> {
                            Object result = invocation.callRealMethod();
                            if (Boolean.TRUE.equals(result)) completed.incrementAndGet();
                            return result;
                        })
                .when(store)
                .completeMessage(anyString(), any(WeixinLease.class), any(WeixinInboxClaim.class));

        startChannel(
                store,
                new WeixinRuntimeListener() {
                    @Override
                    public void onDeliveryFailed(String accountId, String reason) {
                        deliveryFailures.add(reason);
                        sendResponse = "{\"message_id\":\"receipt-recovered\"}";
                    }
                });

        assertTrue(
                waitFor(() -> !deliveryFailures.isEmpty()),
                "a refused reply was never reported as a delivery failure");
        assertTrue(waitFor(() -> completed.get() == 1), "the recovered message never completed");
        assertEquals(2, sends.get(), "a refused reply was completed without a successful retry");
        assertEquals(2, dispatches.get(), "standalone dispatch uses at-least-once processing");
        assertTrue(
                deliveryFailures.stream().anyMatch(reason -> reason.contains("ret=-2")),
                "the structured provider outcome must reach the host: " + deliveryFailures);
    }

    /**
     * A provider that echoes a credential in its error text must not get that text into a host
     * report. The client keeps only the structured outcome; a leaked token cannot be replayed.
     */
    @Test
    void providerErrorTextNeverReachesTheHost() throws Exception {
        updates = () -> polls.get() <= 2 ? batch(1, "peer-leak") : EMPTY_BATCH;
        sendResponse =
                "{\"ret\":-2,\"errcode\":0,\"errmsg\":\"rejected token sk-live-SECRET-1234\"}";
        List<String> deliveryFailures = Collections.synchronizedList(new ArrayList<>());
        startChannel(
                WeixinStateStore.inMemory(),
                new WeixinRuntimeListener() {
                    @Override
                    public void onDeliveryFailed(String accountId, String reason) {
                        deliveryFailures.add(reason);
                    }
                });

        assertTrue(waitFor(() -> !deliveryFailures.isEmpty()), "the refusal was never reported");
        assertTrue(
                deliveryFailures.stream().anyMatch(reason -> reason.contains("ret=-2")),
                "the structured outcome must still be reported: " + deliveryFailures);
        assertTrue(
                deliveryFailures.stream().noneMatch(reason -> reason.contains("SECRET")),
                "provider text must not be reported: " + deliveryFailures);
    }

    /**
     * A provider outage must not freeze messages this consumer already accepted: the poll and the
     * consume phases fail independently, so a dead long poll still lets the backlog drain.
     */
    @Test
    void pollFailuresDoNotStarveAcceptedMessages() throws Exception {
        String account = "account-pending";
        fixedAccountId = account;
        WeixinStateStore store = WeixinStateStore.inMemory();
        WeixinLease seeded = store.acquireLease(account, "previous-owner", 60_000).orElseThrow();
        store.acceptBatch(
                account,
                seeded,
                "cursor",
                List.of(new WeixinInboxMessage("m-1", pendingPayload())));
        store.releaseLease(account, seeded);
        updates = () -> "{\"ret\":1,\"errcode\":1}";

        startChannel(store, WeixinRuntimeListener.noOp());

        assertTrue(
                waitFor(() -> dispatches.get() == 1),
                "an already accepted message was starved by a failing poll");
    }

    /** A dispatch that keeps failing is abandoned instead of retried forever. */
    @Test
    void dispatchFailuresAreAbandonedAndReported() throws Exception {
        updates = () -> batch(1, "peer-down");
        agentReply = () -> Mono.error(new IllegalStateException("data plane token=TEST-SECRET"));
        List<String> dispatchFailures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger abandoned = new AtomicInteger();
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doAnswer(
                        invocation -> {
                            Object result = invocation.callRealMethod();
                            if (Boolean.TRUE.equals(result)) abandoned.incrementAndGet();
                            return result;
                        })
                .when(store)
                .abandonMessage(anyString(), any(WeixinLease.class), any(WeixinInboxClaim.class));

        startChannel(
                store,
                new WeixinRuntimeListener() {
                    @Override
                    public void onDispatchFailed(String accountId, String reason) {
                        dispatchFailures.add(reason);
                    }
                });

        assertTrue(
                waitFor(() -> !dispatchFailures.isEmpty()),
                "an abandoned message was never reported as a dispatch failure");
        assertEquals(3, dispatches.get(), "the dispatch retry budget changed");
        assertEquals(1, abandoned.get(), "the message was not abandoned");
        assertEquals(
                List.of("IllegalStateException"),
                dispatchFailures,
                "a host exception may contain credentials and must be reported by type only");
    }

    @Test
    void credentialRejectionDoesNotExhaustTheDispatchBudget() throws Exception {
        fixedAccountId = "credential-recovery";
        updates = () -> batch(1, "peer-credential");
        agentReply =
                () ->
                        dispatches.get() <= 2
                                ? Mono.error(new IllegalStateException("temporary agent failure"))
                                : Mono.just(assistant("reply"));
        sendResponse = "{\"ret\":-14}";
        AtomicInteger abandoned = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doAnswer(
                        invocation -> {
                            abandoned.incrementAndGet();
                            return invocation.callRealMethod();
                        })
                .when(store)
                .abandonMessage(anyString(), any(WeixinLease.class), any(WeixinInboxClaim.class));
        startChannel(
                store,
                new WeixinRuntimeListener() {
                    @Override
                    public void onCredentialRejected(String accountId, String reason) {
                        rejections.incrementAndGet();
                    }

                    @Override
                    public void onStopped(String accountId) {
                        stopped.incrementAndGet();
                    }
                });

        assertTrue(waitFor(() -> stopped.get() == 1), "credential rejection did not stop polling");
        assertEquals(1, rejections.get());
        assertEquals(
                0, abandoned.get(), "reauthorization must still be able to recover the message");
        WeixinLease replacement =
                store.acquireLease(fixedAccountId, "reauthorized", 20000).orElseThrow();
        assertEquals(
                1,
                store.claimMessages(fixedAccountId, replacement, 1, 1000).size(),
                "credential rejection must return the claim to pending");
    }

    @Test
    void dispatchBudgetRestartsWhenTheLeaseChanges() throws Exception {
        updates = () -> batch(1, "peer-lease");
        agentReply = () -> Mono.error(new IllegalStateException("agent unavailable"));
        maxBackoffMs = 100;
        AtomicReference<WeixinLease> lease = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger abandoned = new AtomicInteger();
        WeixinStateStore store = WeixinStateStore.inMemory();
        startChannel(
                store,
                new WeixinRuntimeListener() {
                    @Override
                    public void onLeaseAcquired(String accountId, WeixinLease acquired) {
                        lease.set(acquired);
                    }

                    @Override
                    public void onTransientFailure(String accountId, String reason) {
                        if (failures.incrementAndGet() == 2) {
                            store.releaseLease(accountId, lease.get());
                        }
                    }

                    @Override
                    public void onDispatchFailed(String accountId, String reason) {
                        abandoned.incrementAndGet();
                    }
                });

        assertTrue(waitFor(() -> abandoned.get() == 1), "the poison message was never abandoned");
        assertEquals(5, dispatches.get(), "the old lease's two attempts leaked into its successor");
    }

    @Test
    void thirdPartyIllegalArgumentMessagesNeverReachTheHost() throws Exception {
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doAnswer(
                        invocation -> {
                            throw new RuntimeException(
                                    new IllegalArgumentException(
                                            "https://provider.invalid/?token=TEST-SECRET"));
                        })
                .when(store)
                .loadCursor(anyString());
        startChannel(
                store,
                new WeixinRuntimeListener() {
                    @Override
                    public void onTransientFailure(String accountId, String reason) {
                        transientFailures.add(reason);
                    }
                });

        assertTrue(waitFor(() -> !transientFailures.isEmpty()), "store failure was never reported");
        assertEquals("IllegalArgumentException", transientFailures.get(0));
    }

    @Test
    void hostOwnedDeliveryCompletesWithoutAnInlineSend() throws Exception {
        updates = () -> batch(1, "peer-managed");
        agentReply = Mono::empty;
        AtomicInteger completed = new AtomicInteger();
        WeixinStateStore store = spy(WeixinStateStore.inMemory());
        doAnswer(
                        invocation -> {
                            Object result = invocation.callRealMethod();
                            if (Boolean.TRUE.equals(result)) completed.incrementAndGet();
                            return result;
                        })
                .when(store)
                .completeMessage(anyString(), any(WeixinLease.class), any(WeixinInboxClaim.class));
        startChannel(store, WeixinRuntimeListener.noOp());

        assertTrue(waitFor(() -> completed.get() == 1));
        assertTrue(waitFor(() -> polls.get() >= 3));
        assertEquals(1, dispatches.get());
        assertEquals(0, sends.get(), "the host already owns delivery of its persisted reply");
    }

    @Test
    void byteIdenticalMessagesWithoutIdsAreBothDispatched() throws Exception {
        updates = () -> polls.get() <= 1 ? idlessDuplicateBatch() : EMPTY_BATCH;
        startChannel(WeixinStateStore.inMemory(), WeixinRuntimeListener.noOp());

        assertTrue(
                waitFor(() -> dispatches.get() == 2),
                "one of two identical id-less messages was dropped: " + dispatches.get());
        assertTrue(waitFor(() -> sends.get() == 2), "replies");
        assertEquals(2, dispatches.get());
    }

    @Test
    void listenerFailuresDoNotStopDispatch() throws Exception {
        updates = () -> batch(1, "peer-a");
        startChannel(
                WeixinStateStore.inMemory(),
                new WeixinRuntimeListener() {
                    @Override
                    public void onLeaseAcquired(String accountId, WeixinLease lease) {
                        throw new IllegalStateException("listener boom");
                    }
                });

        assertTrue(waitFor(() -> dispatches.get() == 1), "a listener failure stopped dispatch");
        assertTrue(waitFor(() -> sends.get() == 1), "reply was not sent");
    }

    @Test
    void stoppedChannelCannotRestart() {
        startChannel(WeixinStateStore.inMemory(), WeixinRuntimeListener.noOp());
        assertEquals("channel", channel.channelId());
        assertEquals("main", channel.config().defaultAgentId());
        channel.stop();
        assertThrows(IllegalStateException.class, channel::start);
    }

    private void startChannel(WeixinStateStore store, WeixinRuntimeListener listener) {
        channel =
                WeixinChannel.create(
                        "channel",
                        ChannelConfig.of("channel", "main"),
                        WeixinChannelProperties.from(
                                "channel",
                                Map.of(
                                        "accountId",
                                        fixedAccountId == null
                                                ? "account-" + UUID.randomUUID()
                                                : fixedAccountId,
                                        "baseUrl",
                                        "http://127.0.0.1:" + server.getAddress().getPort(),
                                        "ilinkUserId",
                                        "self-bot",
                                        "leaseMs",
                                        20000,
                                        "dispatchTimeoutMs",
                                        5000,
                                        "maxBackoffMs",
                                        maxBackoffMs)),
                        WeixinCredentialProvider.fixed("test-token"),
                        store,
                        listener);
        channel.init(
                new Gateway() {
                    @Override
                    public void bindMainAgent(HarnessAgent agent) {}

                    @Override
                    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
                        dispatches.incrementAndGet();
                        return agentReply.get();
                    }
                });
        channel.start();
    }

    private WeixinChannel newIdleChannel() {
        return WeixinChannel.create(
                "channel",
                ChannelConfig.of("channel", "main"),
                WeixinChannelProperties.from(
                        "channel",
                        Map.of(
                                "accountId",
                                "idle-" + UUID.randomUUID(),
                                "baseUrl",
                                "http://127.0.0.1:" + server.getAddress().getPort(),
                                "ilinkUserId",
                                "self-bot")),
                WeixinCredentialProvider.fixed("test-token"),
                WeixinStateStore.inMemory(),
                WeixinRuntimeListener.noOp());
    }

    private void awaitProviderStart() throws InterruptedException {
        assertTrue(
                waitFor(() -> providerStarts.get() > 0),
                "the channel never started a provider session");
    }

    private static Msg assistant(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    /** The payload of one inbound text message, as the consumer stores it. */
    private static String pendingPayload() {
        return "{\"message_type\":1,\"message_id\":\"m-1\",\"from_user_id\":\"peer-pending\","
                + "\"context_token\":\"ctx-pending\",\"item_list\":[{\"type\":1,"
                + "\"text_item\":{\"text\":\"hello pending\"}}]}";
    }

    private static String batch(int count, String peer) {
        StringBuilder json =
                new StringBuilder("{\"ret\":0,\"get_updates_buf\":\"cursor-batch\",\"msgs\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) json.append(',');
            json.append("{\"message_type\":1,\"message_id\":\"m-")
                    .append(i)
                    .append("\",\"from_user_id\":\"")
                    .append(peer)
                    .append("\",\"context_token\":\"ctx-")
                    .append(i)
                    .append("\",\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"hello ")
                    .append(i)
                    .append("\"}}]}");
        }
        return json.append("]}").toString();
    }

    /** Two byte-identical messages, neither carrying a provider message_id. */
    private static String idlessDuplicateBatch() {
        String message =
                "{\"message_type\":1,\"from_user_id\":\"peer-a\",\"context_token\":\"ctx-1\","
                        + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"same\"}}]}";
        return "{\"ret\":0,\"get_updates_buf\":\"cursor-dupe\",\"msgs\":["
                + message
                + ","
                + message
                + "]}";
    }

    private static String idlessBatch() {
        return "{\"ret\":0,\"get_updates_buf\":\"cursor-idless\",\"msgs\":["
                + "{\"message_type\":1,\"from_user_id\":\"peer-a\",\"context_token\":\"ctx-1\","
                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"no id\"}}]}]}";
    }

    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return false;
    }
}
