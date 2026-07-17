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

import io.agentscope.core.a2a.server.hitl.HitlDurableStorageComponent;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.server.tasks.TaskStoreException;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.util.PageToken;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** Redis-backed implementation of the complete A2A SDK {@link TaskStore} contract. */
public final class RedisTaskStore implements TaskStore, HitlDurableStorageComponent {

    private static final String HASH_TAG = "{agentscope-a2a-hitl}:";
    static final String SAVE_SCRIPT =
            "redis.call('set',KEYS[1],ARGV[1],'PX',ARGV[2]); "
                    + "redis.call('sadd',KEYS[2],ARGV[3]); return 1";
    static final String DELETE_SCRIPT =
            "redis.call('del',KEYS[1]); redis.call('srem',KEYS[2],ARGV[1]); return 1";
    private static final String READ_AND_CLEAN_SCRIPT =
            "local value=redis.call('get',KEYS[1]); "
                    + "if not value then redis.call('srem',KEYS[2],ARGV[1]) end; return value";

    private final RedissonClient redissonClient;
    private final String namespace;
    private final Duration taskTtl;

    public RedisTaskStore(RedissonClient redissonClient, String namespace, Duration taskTtl) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.namespace = normalizeNamespace(namespace);
        this.taskTtl = requirePositiveMillis(taskTtl, "taskTtl");
    }

    @Override
    public void save(Task task, boolean replicated) {
        Objects.requireNonNull(task, "task");
        if (task.id() == null || task.id().isBlank()) {
            throw new IllegalArgumentException("task id must not be blank");
        }
        try {
            script().eval(
                            RScript.Mode.READ_WRITE,
                            SAVE_SCRIPT,
                            RScript.ReturnType.LONG,
                            List.of(taskKey(task.id()), indexKey()),
                            JsonUtil.toJson(task),
                            String.valueOf(taskTtl.toMillis()),
                            task.id());
        } catch (Exception exception) {
            throw new TaskStoreException("Failed to save Redis task", task.id(), exception);
        }
    }

    @Override
    public Task get(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        try {
            String json =
                    script().eval(
                                    RScript.Mode.READ_WRITE,
                                    READ_AND_CLEAN_SCRIPT,
                                    RScript.ReturnType.VALUE,
                                    List.of(taskKey(taskId), indexKey()),
                                    taskId);
            return json == null ? null : JsonUtil.fromJson(json, Task.class);
        } catch (Exception exception) {
            throw new TaskStoreException("Failed to read Redis task", taskId, exception);
        }
    }

    @Override
    public void delete(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            script().eval(
                            RScript.Mode.READ_WRITE,
                            DELETE_SCRIPT,
                            RScript.ReturnType.LONG,
                            List.of(taskKey(taskId), indexKey()),
                            taskId);
        } catch (RuntimeException exception) {
            throw new TaskStoreException("Failed to delete Redis task", taskId, exception);
        }
    }

    @Override
    public ListTasksResult list(ListTasksParams parameters) {
        ListTasksParams params = parameters == null ? new ListTasksParams() : parameters;
        try {
            List<Task> filtered =
                    redissonClient
                            .<String>getSet(indexKey(), StringCodec.INSTANCE)
                            .readAll()
                            .stream()
                            .map(this::get)
                            .filter(Objects::nonNull)
                            .filter(
                                    task ->
                                            params.contextId() == null
                                                    || params.contextId().equals(task.contextId()))
                            .filter(
                                    task ->
                                            params.status() == null
                                                    || params.status()
                                                            .equals(task.status().state()))
                            .filter(
                                    task ->
                                            params.statusTimestampAfter() == null
                                                    || statusInstant(task)
                                                            .isAfter(params.statusTimestampAfter()))
                            .filter(task -> matchesTenant(task, params.tenant()))
                            .sorted(taskComparator())
                            .toList();
            int totalSize = filtered.size();
            int start = findStart(filtered, params.pageToken());
            int end = Math.min(start + params.getEffectivePageSize(), filtered.size());
            List<Task> page = filtered.subList(start, end);
            String nextPageToken = nextPageToken(filtered, end);
            int historyLength = params.getEffectiveHistoryLength();
            boolean includeArtifacts = params.shouldIncludeArtifacts();
            List<Task> projected =
                    page.stream()
                            .map(task -> project(task, historyLength, includeArtifacts))
                            .toList();
            return new ListTasksResult(projected, totalSize, projected.size(), nextPageToken);
        } catch (TaskStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TaskStoreException("Failed to list Redis tasks", exception);
        }
    }

    public RedissonClient redissonClient() {
        return redissonClient;
    }

    public String namespace() {
        return namespace;
    }

    @Override
    public Object storageClientIdentity() {
        return redissonClient;
    }

    @Override
    public String logicalStoreId() {
        return namespace;
    }

    String taskKey(String taskId) {
        return namespace + HASH_TAG + "task:" + taskId;
    }

    String indexKey() {
        return namespace + HASH_TAG + "tasks";
    }

    private RScript script() {
        return redissonClient.getScript(StringCodec.INSTANCE);
    }

    private static Comparator<Task> taskComparator() {
        return Comparator.comparing(RedisTaskStore::statusInstant, Comparator.reverseOrder())
                .thenComparing(Task::id);
    }

    private static Instant statusInstant(Task task) {
        return task.status().timestamp().toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static int findStart(List<Task> tasks, String encodedToken) {
        PageToken token = PageToken.fromString(encodedToken);
        if (token == null) {
            return 0;
        }
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            Instant timestamp = statusInstant(task);
            if (timestamp.isBefore(token.timestamp())
                    || (timestamp.equals(token.timestamp())
                            && task.id().compareTo(token.id()) > 0)) {
                return index;
            }
        }
        return tasks.size();
    }

    private static String nextPageToken(List<Task> tasks, int end) {
        if (end == 0 || end >= tasks.size()) {
            return null;
        }
        Task last = tasks.get(end - 1);
        return new PageToken(statusInstant(last), last.id()).toString();
    }

    private static Task project(Task task, int historyLength, boolean includeArtifacts) {
        List<Message> history = task.history();
        if (historyLength == 0) {
            history = List.of();
        } else if (historyLength > 0 && history.size() > historyLength) {
            history = history.subList(history.size() - historyLength, history.size());
        }
        List<Artifact> artifacts = includeArtifacts ? task.artifacts() : List.of();
        return Task.builder(task).history(history).artifacts(artifacts).build();
    }

    private static boolean matchesTenant(Task task, String tenant) {
        if (tenant == null) {
            return true;
        }
        Map<String, Object> metadata = task.metadata();
        if (metadata == null || !metadata.containsKey("tenant")) {
            return false;
        }
        Object storedTenant = metadata.get("tenant");
        return storedTenant != null && tenant.equals(String.valueOf(storedTenant));
    }

    static String normalizeNamespace(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Redis namespace must not be blank");
        }
        return normalized.endsWith(":") ? normalized : normalized + ':';
    }

    static Duration requirePositiveMillis(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero() || value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }
}
