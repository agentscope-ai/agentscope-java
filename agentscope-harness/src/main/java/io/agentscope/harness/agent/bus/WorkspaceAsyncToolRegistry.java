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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * {@link AsyncToolRegistry} backed by {@link AbstractFilesystem}.
 *
 * <p>Each async tool record is persisted as a JSON file under
 * {@code {registryRoot}/async-tools/{sessionId}/{recordId}.json}. This ensures records survive
 * process crashes — {@link io.agentscope.harness.agent.middleware.InboxMiddleware} can detect
 * stale RUNNING records on session recovery and notify the LLM.
 *
 * <p>Works with any filesystem backend (local, remote, sandbox).
 */
public class WorkspaceAsyncToolRegistry implements AsyncToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAsyncToolRegistry.class);
    private static final RuntimeContext RC = RuntimeContext.empty();

    private final AbstractFilesystem fs;
    private final String registryRoot;

    /**
     * @param filesystem   any {@link AbstractFilesystem} implementation
     * @param registryRoot absolute path within the filesystem (e.g. {@code "/bus/async-tools"})
     */
    public WorkspaceAsyncToolRegistry(AbstractFilesystem filesystem, String registryRoot) {
        this.fs = filesystem;
        this.registryRoot =
                registryRoot.endsWith("/")
                        ? registryRoot.substring(0, registryRoot.length() - 1)
                        : registryRoot;
    }

    @Override
    public Mono<Void> register(AsyncToolRecord record) {
        return Mono.fromRunnable(
                () -> {
                    String path = recordPath(record.sessionId(), record.id());
                    ensureDir(sessionDir(record.sessionId()));
                    Map<String, Object> data =
                            Map.of(
                                    "id", record.id(),
                                    "sessionId", record.sessionId(),
                                    "toolName", record.toolName(),
                                    "toolCallId", record.toolCallId(),
                                    "status", record.status(),
                                    "createdAt", record.createdAt().toString());
                    fs.write(RC, path, JsonUtils.getJsonCodec().toJson(data));
                });
    }

    @Override
    public Mono<Void> complete(String id, String result) {
        return updateStatus(id, AsyncToolRecord.COMPLETED);
    }

    @Override
    public Mono<Void> fail(String id, String error) {
        return updateStatus(id, AsyncToolRecord.FAILED);
    }

    @Override
    public Mono<Void> markTimeout(String id) {
        return updateStatus(id, AsyncToolRecord.TIMEOUT);
    }

    @Override
    public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
        return Mono.fromCallable(
                () -> {
                    String dir = sessionDir(sessionId);
                    if (!fs.exists(RC, dir)) {
                        return List.<AsyncToolRecord>of();
                    }
                    LsResult ls = fs.ls(RC, dir);
                    if (!ls.isSuccess() || ls.entries() == null) {
                        return List.<AsyncToolRecord>of();
                    }
                    Instant cutoff = Instant.now().minus(ttl);
                    List<AsyncToolRecord> stale = new ArrayList<>();
                    for (FileInfo fi : ls.entries()) {
                        if (fi.isDirectory() || !fi.path().endsWith(".json")) {
                            continue;
                        }
                        AsyncToolRecord rec = readRecord(fi.path());
                        if (rec != null
                                && AsyncToolRecord.RUNNING.equals(rec.status())
                                && rec.createdAt().isBefore(cutoff)) {
                            stale.add(rec);
                        }
                    }
                    return stale;
                });
    }

    private Mono<Void> updateStatus(String id, String newStatus) {
        return Mono.fromRunnable(
                () -> {
                    String path = findRecordPath(id);
                    if (path == null) {
                        return;
                    }
                    AsyncToolRecord rec = readRecord(path);
                    if (rec == null) {
                        return;
                    }
                    fs.delete(RC, path);
                    Map<String, Object> data =
                            Map.of(
                                    "id", rec.id(),
                                    "sessionId", rec.sessionId(),
                                    "toolName", rec.toolName(),
                                    "toolCallId", rec.toolCallId(),
                                    "status", newStatus,
                                    "createdAt", rec.createdAt().toString());
                    fs.write(RC, path, JsonUtils.getJsonCodec().toJson(data));
                });
    }

    private AsyncToolRecord readRecord(String path) {
        ReadResult rr = fs.read(RC, path, 0, Integer.MAX_VALUE);
        if (!rr.isSuccess() || rr.fileData() == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data =
                    JsonUtils.getJsonCodec().fromJson(rr.fileData().content(), Map.class);
            return new AsyncToolRecord(
                    (String) data.get("id"),
                    (String) data.get("sessionId"),
                    (String) data.get("toolName"),
                    (String) data.get("toolCallId"),
                    (String) data.get("status"),
                    Instant.parse((String) data.get("createdAt")));
        } catch (Exception e) {
            log.warn("Failed to parse async tool record at {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String findRecordPath(String id) {
        LsResult rootLs = fs.ls(RC, registryRoot);
        if (!rootLs.isSuccess() || rootLs.entries() == null) {
            return null;
        }
        for (FileInfo sessionDir : rootLs.entries()) {
            if (!sessionDir.isDirectory()) {
                continue;
            }
            String candidate = sessionDir.path() + "/" + id + ".json";
            if (fs.exists(RC, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String sessionDir(String sessionId) {
        return registryRoot + "/" + sanitize(sessionId);
    }

    private String recordPath(String sessionId, String id) {
        return sessionDir(sessionId) + "/" + id + ".json";
    }

    private void ensureDir(String dir) {
        if (!fs.exists(RC, dir)) {
            fs.write(RC, dir + "/.keep", "");
        }
    }

    private static String sanitize(String s) {
        return s != null ? s.replaceAll("[^a-zA-Z0-9._-]", "_") : "default";
    }
}
