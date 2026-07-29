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
package io.agentscope.builder.web.managed;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Hands work-queue facade. Durability is provided by {@link CoordinationStore} so Brain replicas
 * and out-of-process workers share the same queued/starting/active rows.
 */
@Component
public class EnvironmentWorkQueue {

    public enum Status {
        queued,
        starting,
        active,
        stopping,
        stopped
    }

    public static final class WorkItem {
        private final String leaseId;
        private final String sessionId;
        private final String environmentId;
        private final String ownerId;
        private final long createdAt;
        private final long updatedAt;
        private final Status status;
        private final String claimedBy;
        private final String workDir;
        private final Map<String, Object> metadata;

        WorkItem(
                String leaseId,
                String sessionId,
                String environmentId,
                String ownerId,
                long createdAt,
                long updatedAt,
                Status status,
                String claimedBy,
                String workDir,
                Map<String, Object> metadata) {
            this.leaseId = leaseId;
            this.sessionId = sessionId;
            this.environmentId = environmentId;
            this.ownerId = ownerId;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.status = status;
            this.claimedBy = claimedBy;
            this.workDir = workDir;
            this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        static WorkItem from(CoordinationStore.WorkItemRecord r) {
            return new WorkItem(
                    r.leaseId(),
                    r.sessionId(),
                    r.environmentId(),
                    r.ownerId(),
                    r.createdAt(),
                    r.updatedAt(),
                    Status.valueOf(r.status()),
                    r.claimedBy(),
                    r.workDir(),
                    Map.of());
        }

        /** Returns a copy with session staging metadata attached for the worker. */
        public WorkItem withMetadata(Map<String, Object> metadata) {
            return new WorkItem(
                    leaseId,
                    sessionId,
                    environmentId,
                    ownerId,
                    createdAt,
                    updatedAt,
                    status,
                    claimedBy,
                    workDir,
                    metadata);
        }

        @JsonProperty("id")
        public String id() {
            return leaseId;
        }

        @JsonProperty("workId")
        public String workId() {
            return leaseId;
        }

        @JsonProperty("leaseId")
        public String leaseId() {
            return leaseId;
        }

        @JsonProperty("sessionId")
        public String sessionId() {
            return sessionId;
        }

        @JsonProperty("environmentId")
        public String environmentId() {
            return environmentId;
        }

        @JsonProperty("ownerId")
        public String ownerId() {
            return ownerId;
        }

        @JsonProperty("createdAt")
        public long createdAt() {
            return createdAt;
        }

        @JsonProperty("updatedAt")
        public long updatedAt() {
            return updatedAt;
        }

        @JsonProperty("status")
        public Status status() {
            return status;
        }

        @JsonProperty("claimedBy")
        public String claimedBy() {
            return claimedBy;
        }

        @JsonProperty("workDir")
        public String workDir() {
            return workDir;
        }

        /** Session staging hints ({@code input_file} / {@code files} / {@code resources}). */
        @JsonProperty("metadata")
        public Map<String, Object> metadata() {
            return metadata;
        }
    }

    private final CoordinationStore coordinationStore;

    public EnvironmentWorkQueue(CoordinationStore coordinationStore) {
        this.coordinationStore = coordinationStore;
    }

    public WorkItem enqueue(String sessionId, String environmentId, String ownerId) {
        return WorkItem.from(coordinationStore.enqueueWork(sessionId, environmentId, ownerId));
    }

    public Optional<WorkItem> poll(String environmentId, String workerId, long timeoutMs)
            throws InterruptedException {
        return coordinationStore.claimWork(environmentId, workerId, timeoutMs).map(WorkItem::from);
    }

    public void ack(String leaseId, String workerId, String workDir) {
        coordinationStore.ackWork(leaseId, workerId, workDir);
    }

    public void stop(String leaseId) {
        coordinationStore.stopWork(leaseId);
    }

    public void heartbeat(String leaseId) {
        coordinationStore.heartbeatWork(leaseId);
    }

    public Optional<WorkItem> get(String leaseId) {
        return coordinationStore.getWork(leaseId).map(WorkItem::from);
    }

    public List<WorkItem> list(String environmentId, String statusFilter) {
        return coordinationStore.listWork(environmentId, statusFilter).stream()
                .map(WorkItem::from)
                .toList();
    }

    public CoordinationStore.WorkStats stats(String environmentId) {
        return coordinationStore.workStats(environmentId);
    }

    public void registerWorker(String workerId, Object capabilities) {
        String json = null;
        if (capabilities != null) {
            try {
                json = JsonUtils.getJsonCodec().toJson(capabilities);
            } catch (Exception ignored) {
                json = String.valueOf(capabilities);
            }
        }
        coordinationStore.workerHeartbeat(workerId, json);
    }

    public Map<String, Long> workerHeartbeats() {
        return coordinationStore.workerHeartbeats();
    }

    public int pendingCount() {
        return coordinationStore.pendingWorkCount();
    }
}
