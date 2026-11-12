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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Signal;
import io.agentcore.managed.server.runtime.managed.ManagedInputInbox.Type;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ManagedInfrastructureTest {
    private final AtomicLong clock = new AtomicLong();
    private final Duration ttl = Duration.ofSeconds(60);
    private final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");

    @Test
    void scopeIncludesTenantNamespaceAndUnambiguousFieldBoundaries() {
        assertTrue(scope.messageGroup().getBytes(StandardCharsets.UTF_8).length < 64);
        assertNotEquals(
                scope.messageGroup(),
                new ManagedScope("other", "namespace", "session").messageGroup());
        assertNotEquals(
                scope.messageGroup(),
                new ManagedScope("tenant", "other", "session").messageGroup());
        assertNotEquals(
                new ManagedScope("ab", "c", "d").messageGroup(),
                new ManagedScope("a", "bc", "d").messageGroup());
        assertTrue(scope.key("execution").contains("{" + scope.messageGroup() + "}"));
    }

    @Test
    void heldPushCallbackBlocksOnlyItsSessionAndSuccessfulReturnReleasesSuccessor()
            throws Exception {
        try (var bus = new InMemoryManagedMessageBus(clock::get, 3);
                var turns = new ManagedPushTestSupport.Probe(bus, Channel.TURNS, 4);
                var events = new ManagedPushTestSupport.Probe(bus, Channel.TOOL_EVENTS, 1)) {
            bus.publish(Channel.TURNS, message("t1", scope));
            bus.publish(Channel.TURNS, message("t2", scope));
            bus.publish(
                    Channel.TURNS,
                    message("other", new ManagedScope("tenant", "namespace", "other")));
            var a = turns.next();
            var b = turns.next();
            var first = "t1".equals(a.delivery().message().id()) ? a : b;
            var other = first == a ? b : a;
            assertEquals("t1", first.delivery().message().id());
            assertEquals("other", other.delivery().message().id());
            assertEquals(null, turns.poll(Duration.ofMillis(50)));
            bus.publish(Channel.TOOL_EVENTS, message("event", scope));
            var event = events.next();
            assertEquals("event", event.delivery().message().id());
            event.succeed();
            other.succeed();
            clock.set(70_000); // Beyond the former application's MQ visibility interval.
            assertTrue(bus.active(first.delivery()));
            assertEquals(null, turns.poll(Duration.ofMillis(50)));
            first.succeed();
            var next = turns.next();
            assertEquals("t2", next.delivery().message().id());
            next.succeed();
        }
    }

    @Test
    void callbackFailureRetriesSameHeadAndDeadLetterDoesNotProvePredecessorCompleted()
            throws Exception {
        try (var bus = new InMemoryManagedMessageBus(clock::get, 2);
                var turns = new ManagedPushTestSupport.Probe(bus, Channel.TURNS, 2)) {
            bus.publish(Channel.TURNS, message("lost", scope));
            bus.publish(Channel.TURNS, message("later", scope));
            var first = turns.next();
            first.fail();
            clock.addAndGet(20);
            // Move the explicit fake clock after callback finally has installed its retry deadline.
            for (int i = 0; i < 100 && bus.active(first.delivery()); i++) Thread.sleep(1);
            clock.addAndGet(20);
            var retry = turns.next();
            assertEquals("lost", retry.delivery().message().id());
            assertEquals(2, retry.delivery().attempt());
            retry.fail();
            for (int i = 0; i < 100 && bus.active(retry.delivery()); i++) Thread.sleep(1);
            clock.addAndGet(20);
            var later = turns.next();
            assertEquals("later", later.delivery().message().id());
            assertEquals("lost", bus.deadLetters().get(0).id());
            later.succeed();
        }
    }

    @Test
    void callbackExpiryFencesOldInvocationAndLogicalPublicationIsNotExactlyOnce() throws Exception {
        try (var bus = new InMemoryManagedMessageBus(clock::get, 3);
                var turns = new ManagedPushTestSupport.Probe(bus, Channel.TURNS, 3)) {
            var firstPublication = bus.publish(Channel.TURNS, message("t1", scope));
            var duplicate = bus.publish(Channel.TURNS, message("t1", scope));
            assertNotEquals(firstPublication.brokerMessageId(), duplicate.brokerMessageId());
            var first = turns.next();
            clock.set(Duration.ofHours(3).toMillis());
            var replacement = turns.next();
            assertFalse(bus.active(first.delivery()));
            assertEquals(2, replacement.delivery().attempt());
            first.succeed();
            assertEquals(null, turns.poll(Duration.ofMillis(50)));
            replacement.succeed();
            var duplicateDelivery = turns.next();
            assertEquals("t1", duplicateDelivery.delivery().message().id());
            assertEquals(
                    duplicate.brokerMessageId(), duplicateDelivery.delivery().brokerMessageId());
            duplicateDelivery.succeed();
        }
    }

    @Test
    void replacedLeaseCannotRenewOrReleaseItsSuccessor() {
        var store = new InMemoryManagedLeaseStore(clock::get);
        var first = store.acquire(scope.key("execution"), ttl).orElseThrow();
        assertTrue(store.acquire(scope.key("execution"), ttl).isEmpty());
        clock.set(60_000);
        var replacement = store.acquire(scope.key("execution"), ttl).orElseThrow();
        assertFalse(store.renew(first, ttl));
        assertFalse(store.release(first));
        assertTrue(store.owns(replacement));
        assertTrue(store.release(replacement));
    }

    @Test
    void inputCursorFollowsTransportArrivalAndReplayIsIdempotent() {
        var inbox = new InMemoryManagedInputInbox(clock::get);
        var second = new Signal("input2", 2, Type.INJECT, null, "input2");
        var first = new Signal("input1", 1, Type.STEER, null, "input1");
        String cursor = inbox.append(scope, "turn", second);
        assertEquals(cursor, inbox.append(scope, "turn", second));
        inbox.append(scope, "turn", first);
        var items = inbox.read(scope, "turn", "0-0", 10, Duration.ZERO);
        assertEquals(2, items.size());
        assertEquals(2, items.get(0).signal().sequence());
        assertEquals(
                1, inbox.read(scope, "turn", cursor, 10, Duration.ZERO).get(0).signal().sequence());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        inbox.append(
                                scope,
                                "turn",
                                new Signal("input2", 2, Type.CANCEL, null, "input2")));
    }

    @Test
    void cancelIsRepeatableScopedAndInputSurvivesUntilTerminalExpiry() {
        var inbox = new InMemoryManagedInputInbox(clock::get);
        inbox.append(scope, "turn", new Signal("cancel", 1, Type.CANCEL, null, "cancel"));
        assertTrue(inbox.cancelled(scope, "turn"));
        assertTrue(inbox.cancelled(scope, "turn"));
        assertFalse(inbox.cancelled(scope, "other-turn"));
        clock.set(100_000_000);
        assertTrue(inbox.cancelled(scope, "turn"));
        inbox.expire(scope, "turn", ttl);
        clock.addAndGet(60_000);
        assertFalse(inbox.cancelled(scope, "turn"));
        assertTrue(inbox.read(scope, "turn", "0-0", 10, Duration.ZERO).isEmpty());
    }

    @Test
    void messageBodyIsImmutableAndOversizedPayloadMustUseReference() {
        byte[] body = {1};
        var message = new Message("id", scope.messageGroup(), body);
        body[0] = 2;
        message.body()[0] = 3;
        assertEquals(1, message.body()[0]);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Message(
                                "id",
                                scope.messageGroup(),
                                new byte[ManagedMessageBus.MAX_BODY_BYTES + 1]));
    }

    private static Message message(String id, ManagedScope scope) {
        return new Message(id, scope.messageGroup(), id.getBytes(StandardCharsets.UTF_8));
    }
}
