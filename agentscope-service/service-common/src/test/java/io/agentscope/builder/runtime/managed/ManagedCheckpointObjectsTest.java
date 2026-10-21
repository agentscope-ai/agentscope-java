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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedCheckpointObjectsTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    private ManagedCheckpointObjects objects;

    @BeforeEach
    void start() {
        objects = new ManagedCheckpointObjects(mapper, root);
    }

    private String state(String message) {
        return AgentState.builder()
                .sessionId(scope.sessionId())
                .curIter(3)
                .replyId("reply")
                .summary("saved summary")
                .addMessage(new UserMessage(message))
                .build()
                .toJson();
    }

    private ManagedRuntimeBatch batch(String state) throws Exception {
        var event =
                new SessionEvent(
                        1,
                        "capture",
                        1,
                        123,
                        "state/checkpoint",
                        "run",
                        "turn",
                        true,
                        mapper.writeValueAsString(Map.of("stateJson", state, "reason", "test")));
        return new ManagedRuntimeBatch(
                scope,
                "turn",
                7,
                "lease",
                "writer",
                "batch",
                0,
                List.of(event),
                List.of(),
                List.of(),
                null);
    }

    private ManagedCheckpointObjects.Reference reference(ManagedRuntimeBatch wire) {
        return objects.reference(scope, wire.events().get(0));
    }

    @Test
    void fullStateAndContinuationStayPrivateAndAnotherInstanceRestoresExactBytes()
            throws Exception {
        String state = state("private memory and tool context");
        var original = batch(state);
        var context =
                Map.<String, Object>of(
                        "toolOperations",
                        List.of(
                                Map.of(
                                        "taskId",
                                        "task",
                                        "executionId",
                                        "task",
                                        "arguments",
                                        "private arguments",
                                        "resultJson",
                                        "partial result",
                                        "applied",
                                        false)),
                        "appliedInputSeq",
                        9);
        var wire = objects.prepare(original, context);
        assertFalse(mapper.writeValueAsString(wire).contains("stateJson"));
        assertFalse(mapper.writeValueAsString(wire).contains("private memory"));
        assertFalse(mapper.writeValueAsString(wire).contains("private arguments"));
        var other = new ManagedCheckpointObjects(mapper, root);
        assertEquals(original.events(), List.of(other.hydrate(scope, wire.events().get(0))));
        var image = other.read(reference(wire));
        assertEquals(state, image.stateJson());
        assertEquals(9, ((Number) image.continuation().get("appliedInputSeq")).intValue());
        assertEquals(context.get("toolOperations"), image.continuation().get("toolOperations"));
        assertEquals(3, AgentState.fromJsonString(image.stateJson()).getCurIter());
        objects.verify(wire);
    }

    @Test
    void retryIsImmutableAndAChangedCaptureCannotReuseItsId() throws Exception {
        var original = batch(state("original"));
        assertEquals(objects.prepare(original), objects.prepare(original));
        assertThrows(ManagedStateException.class, () -> objects.prepare(batch(state("changed"))));
        assertEquals(
                original.events(),
                List.of(objects.hydrate(scope, objects.prepare(original).events().get(0))));
    }

    @Test
    void concurrentWritersCannotReplaceTheWinningSnapshot() throws Exception {
        var one = batch(state("one"));
        var two = batch(state("two"));
        var gate = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        Callable<ManagedRuntimeBatch> writeOne =
                () -> {
                    gate.await();
                    try {
                        return objects.prepare(one);
                    } catch (ManagedStateException refused) {
                        return null;
                    }
                };
        Callable<ManagedRuntimeBatch> writeTwo =
                () -> {
                    gate.await();
                    try {
                        return objects.prepare(two);
                    } catch (ManagedStateException refused) {
                        return null;
                    }
                };
        try {
            var a = pool.submit(writeOne);
            var b = pool.submit(writeTwo);
            var first = a.get();
            var second = b.get();
            assertNotEquals(first == null, second == null);
            var winner = first == null ? second : first;
            assertEquals(
                    first == null ? two.events() : one.events(),
                    List.of(objects.hydrate(scope, winner.events().get(0))));
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrCorruptedObjectsNeverBecomeRecoverableCheckpoints(boolean missing)
            throws Exception {
        var wire = objects.prepare(batch(state("original")));
        Path file = root.resolve(reference(wire).storageRef());
        if (missing) Files.delete(file);
        else Files.writeString(file, "damaged");
        assertThrows(ManagedStateException.class, () -> objects.verify(wire));
        assertThrows(
                ManagedStateException.class, () -> objects.hydrate(scope, wire.events().get(0)));
    }

    @Test
    void copiedScopeOrSourceIdentityCannotReadAnExistingObject() throws Exception {
        var wire = objects.prepare(batch(state("original")));
        var other = new ManagedScope("other tenant", scope.namespace(), scope.sessionId());
        assertThrows(
                ManagedStateException.class, () -> objects.hydrate(other, wire.events().get(0)));
        var event = wire.events().get(0);
        var changed =
                new SessionEvent(
                        event.schemaVersion(),
                        "another capture",
                        event.seq(),
                        event.occurredAt(),
                        event.type(),
                        event.executionRunId(),
                        event.turnId(),
                        event.required(),
                        event.payloadJson());
        assertThrows(ManagedStateException.class, () -> objects.hydrate(scope, changed));
        assertThrows(
                ManagedStateException.class,
                () -> objects.decodeReference(other, objects.encodeReference(reference(wire))));
    }

    @Test
    void inlineStateCannotEnterBrainCommit() throws Exception {
        var raw = batch(state("original"));
        assertThrows(ManagedStateException.class, () -> objects.verify(raw));
    }

    @Test
    void aNamespaceSymlinkCannotRedirectAnObjectWrite() throws Exception {
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Files.createSymbolicLink(root.resolve(scope.messageGroup()), elsewhere);
        assertThrows(ManagedStateException.class, () -> objects.prepare(batch(state("original"))));
        try (var files = Files.list(elsewhere)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void epochMismatchCannotCommitAValidObjectUnderAnotherAttempt() throws Exception {
        var wire = objects.prepare(batch(state("original")));
        var wrong =
                new ManagedRuntimeBatch(
                        scope,
                        "turn",
                        8,
                        "new lease",
                        "writer",
                        "batch",
                        0,
                        wire.events(),
                        List.of(),
                        List.of(),
                        null);
        assertThrows(ManagedStateException.class, () -> objects.verify(wrong));
    }
}
