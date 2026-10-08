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
package io.agentscope.examples.copilotkit.model;

import java.util.List;
import java.util.Map;

/**
 * CopilotKit Runtime / thread API models (immutable records).
 *
 *
 */
public final class CopilotKitModels {

    private CopilotKitModels() {}

    public record InfoResponse(
            String version,
            Map<String, AgentInfo> agents,
            boolean audioFileTranscriptionEnabled,
            String mode,
            ThreadEndpoints threadEndpoints,
            boolean suggestions,
            Intelligence intelligence,
            boolean a2uiEnabled,
            boolean openGenerativeUIEnabled,
            String licenseStatus,
            boolean telemetryDisabled) {}

    public record AgentInfo(
            String id,
            String name,
            String description,
            Map<String, Boolean> capabilities,
            String className) {

        public AgentInfo withIdentity(String name, String description, String className) {
            return new AgentInfo(id, name, description, capabilities, className);
        }
    }

    public record ThreadEndpoints(
            boolean list, boolean inspect, boolean mutations, boolean realtimeMetadata) {}

    public record Intelligence(String wsUrl) {}

    public record ThreadsResponse(List<ThreadInfo> threads, String nextCursor, String joinCode) {}

    public record ThreadInfo(
            String id,
            String name,
            String agentId,
            boolean archived,
            String createdAt,
            String updatedAt,
            String lastRunAt) {

        public ThreadInfo withName(String name) {
            return new ThreadInfo(id, name, agentId, archived, createdAt, updatedAt, lastRunAt);
        }

        public ThreadInfo withArchived(boolean archived) {
            return new ThreadInfo(id, name, agentId, archived, createdAt, updatedAt, lastRunAt);
        }

        public ThreadInfo withUpdatedAt(String updatedAt) {
            return new ThreadInfo(id, name, agentId, archived, createdAt, updatedAt, lastRunAt);
        }
    }

    public record ThreadMutationRequest(String agentId, String name, Boolean archived) {}
}
