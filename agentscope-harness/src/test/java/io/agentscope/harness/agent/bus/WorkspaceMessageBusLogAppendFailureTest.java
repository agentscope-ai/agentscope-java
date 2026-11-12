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
package io.agentscope.harness.agent.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkspaceMessageBusLogAppendFailureTest {
    @TempDir Path tempDir;

    static Stream<Arguments> writeFailures() {
        return Stream.of(0, 1)
                .flatMap(
                        maxLen ->
                                Stream.of(Arguments.of(maxLen, false), Arguments.of(maxLen, true)));
    }

    @ParameterizedTest
    @MethodSource("writeFailures")
    void appendSignalsBackendFailureInsteadOfReturningACursor(int maxLen, boolean conflict) {
        FailingLogFilesystem fs = new FailingLogFilesystem(tempDir);
        fs.failWrites = true;
        fs.conflict = conflict;
        WorkspaceMessageBus bus = new WorkspaceMessageBus(fs, "/bus");

        StepVerifier.create(bus.logAppend("failed", Map.of("value", "lost"), maxLen))
                .expectErrorSatisfies(
                        error -> {
                            assertInstanceOf(IllegalStateException.class, error);
                            assertTrue(
                                    error.getMessage()
                                            .contains(
                                                    conflict
                                                            ? "already exists"
                                                            : "permission denied"));
                        })
                .verify();

        assertTrue(bus.logRead("failed", null, 10).block().isEmpty());
    }

    @Test
    void failedAppendPreservesExistingEntriesAndLaterWritesCanRecover() {
        FailingLogFilesystem fs = new FailingLogFilesystem(tempDir);
        WorkspaceMessageBus bus = new WorkspaceMessageBus(fs, "/bus");
        String first = bus.logAppend("recover", Map.of("value", "first"), 0).block();
        fs.failWrites = true;

        StepVerifier.create(bus.logAppend("recover", Map.of("value", "lost"), 1))
                .expectError(IllegalStateException.class)
                .verify();
        List<BusEntry> preserved = bus.logRead("recover", null, 10).block();
        assertEquals(List.of(new BusEntry(first, Map.of("value", "first"))), preserved);

        fs.failWrites = false;
        String second = bus.logAppend("recover", Map.of("value", "second"), 0).block();
        assertEquals(
                List.of(
                        new BusEntry(first, Map.of("value", "first")),
                        new BusEntry(second, Map.of("value", "second"))),
                bus.logRead("recover", null, 10).block());
    }

    @Test
    void successfulAppendReturnsThePersistedEntriesCursor() {
        WorkspaceMessageBus bus =
                new WorkspaceMessageBus(new FailingLogFilesystem(tempDir), "/bus");
        Map<String, Object> payload = Map.of("value", "stored");

        String id = bus.logAppend("success", payload, 1).block();

        assertNotNull(id);
        assertEquals(List.of(new BusEntry(id, payload)), bus.logRead("success", null, 10).block());
    }

    @Test
    void sessionEventFailureDoesNotAcknowledgeOrFanOutAnUnstoredEvent() {
        FailingLogFilesystem fs = new FailingLogFilesystem(tempDir);
        fs.failWrites = true;
        AtomicBoolean published = new AtomicBoolean();
        WorkspaceMessageBus bus =
                new WorkspaceMessageBus(fs, "/bus") {
                    @Override
                    public Mono<Void> publish(String key, Map<String, Object> payload) {
                        published.set(true);
                        return Mono.empty();
                    }
                };

        StepVerifier.create(bus.sessionPublishEvent("session", Map.of("type", "delta")))
                .expectError(IllegalStateException.class)
                .verify();

        assertFalse(published.get());
        assertTrue(bus.sessionReadEvents("session", null, 10).block().isEmpty());
    }

    @Test
    void uncertainWriteDoesNotDeleteOrRetryAStoredEntry() {
        FailingLogFilesystem fs = new FailingLogFilesystem(tempDir);
        WorkspaceMessageBus bus = new WorkspaceMessageBus(fs, "/bus");
        String prior = bus.logAppend("uncertain", Map.of("value", "prior"), 0).block();
        fs.failWrites = true;
        fs.commitThenFail = true;

        StepVerifier.create(bus.logAppend("uncertain", Map.of("value", "uncertain"), 1))
                .expectError(IllegalStateException.class)
                .verify();

        List<BusEntry> entries = bus.logRead("uncertain", null, 10).block();
        assertEquals(
                2, entries.size(), "failed acknowledgement must not trigger capping or cleanup");
        assertEquals(new BusEntry(prior, Map.of("value", "prior")), entries.get(0));
        assertEquals(Map.of("value", "uncertain"), entries.get(1).payload());
    }

    private static final class FailingLogFilesystem extends LocalFilesystem {
        private boolean failWrites;
        private boolean conflict;
        private boolean commitThenFail;

        private FailingLogFilesystem(Path root) {
            super(root, true, 10);
        }

        @Override
        public WriteResult write(RuntimeContext context, String filePath, String content) {
            if (failWrites && filePath.contains("/logs/") && filePath.endsWith(".json")) {
                if (commitThenFail) {
                    WriteResult written = super.write(context, filePath, content);
                    assertTrue(written.isSuccess());
                    return WriteResult.fail("acknowledgement lost");
                }
                return conflict
                        ? WriteResult.alreadyExists(filePath)
                        : WriteResult.fail("permission denied");
            }
            return super.write(context, filePath, content);
        }
    }
}
