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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "MANAGED_REDIS_TEST_URI", matches = ".+")
@Timeout(20)
class ManagedToolResultDeliveryIntegrationTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path blobs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope =
            new ManagedScope("result-test", "n", UUID.randomUUID().toString());

    @Test
    void separateWaitersReadOneImmutableLargeResultAndWrongScopeCannotReadIt() throws Exception {
        try (var publisher = redis();
                var waiter = redis()) {
            var toolWorker = new RedisManagedToolResultDelivery(publisher);
            var harnessRuntime = new RedisManagedToolResultDelivery(waiter);
            var codec = new ManagedBatchCodec(mapper, blobs);
            var result =
                    new ManagedToolResult(
                            scope,
                            "task",
                            "task",
                            "succeeded",
                            mapper.writeValueAsString(
                                    Map.of("output", "x".repeat(2 * 1024 * 1024))));
            var message = codec.encode(result);
            assertTrue(message.body().length < 128 * 1024);
            toolWorker.publish(scope, "task", message);
            toolWorker.publish(scope, "task", message);
            assertEquals(
                    result,
                    codec.decodeToolResult(
                            harnessRuntime.await(scope, "task", Duration.ZERO).orElseThrow()));
            assertEquals(
                    result,
                    codec.decodeToolResult(
                            toolWorker.await(scope, "task", Duration.ZERO).orElseThrow()));
            String key = ManagedToolResultDelivery.key(scope, "task");
            assertEquals(1L, publisher.busCommands().xlen(key));
            var other = new ManagedScope("other", "n", scope.sessionId());
            assertTrue(harnessRuntime.await(other, "task", Duration.ZERO).isEmpty());
            assertThrows(
                    ManagedStateException.class,
                    () ->
                            toolWorker.publish(
                                    scope,
                                    "task",
                                    codec.encode(
                                            new ManagedToolResult(
                                                    scope,
                                                    "task",
                                                    "task",
                                                    "succeeded",
                                                    "different"))));
            assertTrue(publisher.busCommands().pttl(key) > 0);
            publisher.busCommands().del(key, key + ":identity");
        }
    }

    @Test
    void durableOutcomeRepairsMissingStreamAndFullMailboxLossWithoutReexecutingTool()
            throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:result-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = ProjectionTestSupport.admission(source, mapper);
        var journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "actor", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "token");
        String task = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        var checkpoint =
                new SessionEvent(
                        1,
                        "checkpoint",
                        1,
                        System.currentTimeMillis(),
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        mapper.writeValueAsString(
                                Map.of("stateJson", AgentState.builder().build().toJson())));
        AgentScopePreparation.commit(
                journal,
                new SessionCommitTransactions(journal),
                AgentScopeCheckpoints.prepare(
                        journal.objects(),
                        new ManagedRuntimeBatch(
                                scope,
                                turn,
                                1,
                                "token",
                                "writer",
                                "intent",
                                0,
                                List.of(checkpoint),
                                List.of(new ToolIntent("call", "sandbox.exec", "{}")),
                                List.of(),
                                null)));
        journal.claimTool(scope, task, "worker");
        var result =
                new ManagedToolResult(scope, task, task, "succeeded", "{\"output\":\"confirmed\"}");
        ManagedPushTestSupport.commitToolResult(journal, result);
        try (var redis = redis()) {
            var codec = new ManagedBatchCodec(mapper, blobs);
            var delivery = new RedisManagedToolResultDelivery(redis);
            var relay = new ManagedToolResultRelay(journal, codec, delivery);
            String key = ManagedToolResultDelivery.key(scope, task);
            assertTrue(delivery.await(scope, task, Duration.ZERO).isEmpty());
            assertEquals(1, relay.repair(64));
            assertEquals(
                    result,
                    codec.decodeToolResult(
                            delivery.await(scope, task, Duration.ZERO).orElseThrow()));
            redis.busCommands().del(key);
            assertTrue(delivery.await(scope, task, Duration.ZERO).isEmpty());
            assertEquals(1, relay.repair(64));
            redis.busCommands().del(key, key + ":identity");
            assertEquals(1, relay.repair(64));
            assertEquals(
                    result,
                    codec.decodeToolResult(
                            delivery.await(scope, task, Duration.ZERO).orElseThrow()));
            assertEquals(task, journal.tool(scope, task).orElseThrow().executionId());
            assertEquals("succeeded", journal.tool(scope, task).orElseThrow().status());
            admission.closeInputs(scope, turn, 1);
            AgentScopePreparation.commit(
                    journal,
                    new SessionCommitTransactions(journal),
                    AgentScopeCheckpoints.prepare(
                            journal.objects(),
                            new ManagedRuntimeBatch(
                                    scope,
                                    turn,
                                    1,
                                    "token",
                                    "writer",
                                    "done",
                                    1,
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    "succeeded")));
            assertEquals(0, relay.repair(64));
            redis.busCommands().del(key, key + ":identity");
            assertFalse(
                    journal.resultsForDelivery("", "", 64).stream()
                            .anyMatch(value -> value.scope().equals(scope)));
        }
    }

    private RedisManagedInfrastructure redis() {
        return new RedisManagedInfrastructure(System.getenv("MANAGED_REDIS_TEST_URI"), 4);
    }
}
