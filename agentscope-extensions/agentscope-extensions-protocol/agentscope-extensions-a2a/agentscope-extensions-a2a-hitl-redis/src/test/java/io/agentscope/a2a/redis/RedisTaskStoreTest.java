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
package io.agentscope.a2a.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.hitl.SanitizingTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStoreException;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;

class RedisTaskStoreTest {

    private static RedisServerSupport redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisServerSupport.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
            assertThat(redis.processAlive()).isFalse();
        }
    }

    @Test
    void roundTripsOverwritesAndAtomicallyMaintainsIndex() {
        String namespace = "a2a:test:task:atomic:";
        RedisTaskStore store = new RedisTaskStore(redis.client(), namespace, Duration.ofDays(1));
        Task original = task("task-1", "ctx", "tenant-a", TaskState.TASK_STATE_WORKING, at(12));
        Task replacement =
                Task.builder(original)
                        .status(
                                new TaskStatus(
                                        TaskState.TASK_STATE_COMPLETED,
                                        original.history().get(1),
                                        at(13)))
                        .build();

        store.save(original, false);
        store.save(replacement, true);

        Task restored = store.get("task-1");
        assertThat(restored.id()).isEqualTo(replacement.id());
        assertThat(restored.contextId()).isEqualTo(replacement.contextId());
        assertThat(restored.status().state()).isEqualTo(replacement.status().state());
        assertThat(restored.status().timestamp()).isEqualTo(replacement.status().timestamp());
        assertThat(restored.metadata()).isEqualTo(replacement.metadata());
        assertThat(restored.status().message().parts().get(0)).isInstanceOf(DataPart.class);
        assertThat(restored.history()).hasSize(2);
        assertThat(restored.artifacts()).hasSize(1);
        assertThat(redis.client().getSet(store.indexKey(), StringCodec.INSTANCE).readAll())
                .containsExactly("task-1");
        assertThat(store.taskKey("task-1")).contains("{agentscope-a2a-hitl}");
        assertThat(store.indexKey()).contains("{agentscope-a2a-hitl}");
        assertThat(RedisTaskStore.SAVE_SCRIPT).contains("'set'", "'sadd'");
        assertThat(RedisTaskStore.DELETE_SCRIPT).contains("'del'", "'srem'");

        store.delete("task-1");
        store.delete("task-1");
        assertThat(store.get("task-1")).isNull();
        assertThat(redis.client().getKeys().countExists(store.taskKey("task-1"))).isZero();
        assertThat(redis.client().getSet(store.indexKey(), StringCodec.INSTANCE).readAll())
                .isEmpty();
    }

    @Test
    void concurrentSaveDeleteNeverExposesTaskIndexDivergence() throws Exception {
        RedisTaskStore store =
                new RedisTaskStore(redis.client(), "a2a:test:task:race:", Duration.ofDays(1));
        Task task = task("race", "ctx", null, TaskState.TASK_STATE_WORKING, at(12));
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean mismatch = new AtomicBoolean();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writer =
                    pool.submit(
                            () -> {
                                start.await();
                                for (int i = 0; i < 500; i++) {
                                    store.save(task, false);
                                    store.delete(task.id());
                                }
                                return null;
                            });
            var observer =
                    pool.submit(
                            () -> {
                                start.await();
                                String invariant =
                                        "local exists=redis.call('exists',KEYS[1]); local"
                                            + " indexed=redis.call('sismember',KEYS[2],ARGV[1]); if"
                                            + " exists ~= indexed then return 0 else return 1 end";
                                for (int i = 0; i < 1000 && !mismatch.get(); i++) {
                                    Long consistent =
                                            redis.client()
                                                    .getScript(StringCodec.INSTANCE)
                                                    .eval(
                                                            RScript.Mode.READ_ONLY,
                                                            invariant,
                                                            RScript.ReturnType.LONG,
                                                            List.of(
                                                                    store.taskKey(task.id()),
                                                                    store.indexKey()),
                                                            task.id());
                                    if (consistent == null || consistent != 1L) {
                                        mismatch.set(true);
                                    }
                                }
                                return null;
                            });
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            observer.get(10, TimeUnit.SECONDS);
            assertThat(mismatch).isFalse();
        } finally {
            pool.shutdownNow();
            store.delete(task.id());
        }
    }

    @Test
    void filtersOrdersPaginatesAndProjectsUsingSdkContract() {
        RedisTaskStore store =
                new RedisTaskStore(redis.client(), "a2a:test:task:list:", Duration.ofDays(1));
        store.save(task("b", "wanted", "tenant-a", TaskState.TASK_STATE_COMPLETED, at(12)), false);
        store.save(task("a", "wanted", "tenant-a", TaskState.TASK_STATE_COMPLETED, at(12)), false);
        store.save(task("aa", "wanted", "tenant-a", TaskState.TASK_STATE_COMPLETED, at(12)), false);
        store.save(task("c", "wanted", "tenant-a", TaskState.TASK_STATE_WORKING, at(11)), false);
        store.save(task("d", "other", "tenant-a", TaskState.TASK_STATE_COMPLETED, at(10)), false);
        store.save(task("e", "wanted", "tenant-b", TaskState.TASK_STATE_COMPLETED, at(9)), false);

        ListTasksParams firstParams =
                ListTasksParams.builder()
                        .contextId("wanted")
                        .status(TaskState.TASK_STATE_COMPLETED)
                        .tenant("tenant-a")
                        .statusTimestampAfter(Instant.parse("2026-07-14T11:00:00Z"))
                        .pageSize(1)
                        .historyLength(1)
                        .includeArtifacts(false)
                        .build();
        ListTasksResult first = store.list(firstParams);
        ListTasksResult second =
                store.list(
                        ListTasksParams.builder()
                                .contextId("wanted")
                                .status(TaskState.TASK_STATE_COMPLETED)
                                .tenant("tenant-a")
                                .statusTimestampAfter(Instant.parse("2026-07-14T11:00:00Z"))
                                .pageSize(1)
                                .pageToken(first.nextPageToken())
                                .historyLength(0)
                                .includeArtifacts(true)
                                .build());
        ListTasksResult third =
                store.list(
                        ListTasksParams.builder()
                                .contextId("wanted")
                                .status(TaskState.TASK_STATE_COMPLETED)
                                .tenant("tenant-a")
                                .statusTimestampAfter(Instant.parse("2026-07-14T11:00:00Z"))
                                .pageSize(1)
                                .pageToken(second.nextPageToken())
                                .build());

        assertThat(first.tasks()).extracting(Task::id).containsExactly("a");
        assertThat(first.totalSize()).isEqualTo(3);
        assertThat(first.pageSize()).isEqualTo(1);
        assertThat(first.tasks().get(0).history()).hasSize(1);
        assertThat(first.tasks().get(0).artifacts()).isEmpty();
        assertThat(first.nextPageToken()).isNotBlank();
        assertThat(second.tasks()).extracting(Task::id).containsExactly("aa");
        assertThat(second.tasks().get(0).history()).isEmpty();
        assertThat(second.tasks().get(0).artifacts()).hasSize(1);
        assertThat(second.nextPageToken()).isNotBlank();
        assertThat(third.tasks()).extracting(Task::id).containsExactly("b");
        assertThat(third.nextPageToken()).isNull();

        store.delete("aa");
        ListTasksResult continuedAfterAnchorDeletion =
                store.list(
                        ListTasksParams.builder()
                                .contextId("wanted")
                                .status(TaskState.TASK_STATE_COMPLETED)
                                .tenant("tenant-a")
                                .statusTimestampAfter(Instant.parse("2026-07-14T11:00:00Z"))
                                .pageSize(1)
                                .pageToken(second.nextPageToken())
                                .build());
        assertThat(continuedAfterAnchorDeletion.tasks()).extracting(Task::id).containsExactly("b");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                store.list(
                                        ListTasksParams.builder()
                                                .pageToken("not-a-page-token")
                                                .build()))
                .isInstanceOf(TaskStoreException.class);
    }

    @Test
    void tenantFilterUsesOnlySdkTenantAndExpiredEntriesAreCleaned() throws Exception {
        String namespace = "a2a:test:task:expiry:";
        RedisTaskStore store =
                new RedisTaskStore(redis.client(), namespace, Duration.ofMillis(180));
        store.save(task("sdk", "ctx", "tenant-a", TaskState.TASK_STATE_COMPLETED, at(12)), false);
        store.save(
                Task.builder(task("legacy", "ctx", null, TaskState.TASK_STATE_COMPLETED, at(11)))
                        .metadata(Map.of("_agentscope_tenant", "tenant-a"))
                        .build(),
                false);
        assertThat(store.list(ListTasksParams.builder().tenant("tenant-a").build()).tasks())
                .extracting(Task::id)
                .containsExactly("sdk");
        assertThat(store.list(ListTasksParams.builder().tenant("null").build()).tasks()).isEmpty();

        Thread.sleep(300);
        assertThat(store.get("sdk")).isNull();
        assertThat(store.list(new ListTasksParams()).tasks()).isEmpty();
        assertThat(redis.client().getSet(store.indexKey(), StringCodec.INSTANCE).readAll())
                .isEmpty();
    }

    @Test
    void sanitizingStoreLeavesNoPlaintextTokenAnywhereInNamespace() {
        String namespace = "a2a:test:task:secret:";
        RedisTaskStore raw = new RedisTaskStore(redis.client(), namespace, Duration.ofDays(1));
        SanitizingTaskStore store = new SanitizingTaskStore(raw);
        String secret = "resume-secret-that-must-not-be-stored";
        store.save(
                Task.builder(
                                task(
                                        "secret",
                                        "ctx",
                                        null,
                                        TaskState.TASK_STATE_INPUT_REQUIRED,
                                        at(12)))
                        .metadata(Map.of(MessageConstants.RESUME_TOKEN_METADATA_KEY, secret))
                        .build(),
                false);

        RedisNamespaceAssertions.containsNone(
                redis.client(), namespace, secret, "authenticated", "AgentState");
    }

    private static Task task(
            String id, String context, String tenant, TaskState state, OffsetDateTime timestamp) {
        Message first =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .messageId(id + "-message-1")
                        .parts(new TextPart("first", Map.of("safe", true)))
                        .build();
        Message second =
                Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .messageId(id + "-message-2")
                        .parts(new DataPart(Map.of("answer", 42)))
                        .build();
        Artifact artifact =
                Artifact.builder()
                        .artifactId(id + "-artifact")
                        .parts(new TextPart("artifact"))
                        .build();
        Map<String, Object> metadata = tenant == null ? Map.of() : Map.of("tenant", tenant);
        return Task.builder()
                .id(id)
                .contextId(context)
                .status(new TaskStatus(state, second, timestamp))
                .history(List.of(first, second))
                .artifacts(List.of(artifact))
                .metadata(metadata)
                .build();
    }

    private static OffsetDateTime at(int hour) {
        return OffsetDateTime.parse(String.format("2026-07-14T%02d:00:00Z", hour));
    }
}
