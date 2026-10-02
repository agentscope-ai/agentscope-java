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
package io.agentscope.harness.agent.team;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Runtime context injected into a teammate session (mirrors Go TeamContext). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TeamContext(
        String teamName,
        String namespace,
        String objective,
        String myRole,
        boolean isLead,
        List<MemberSnapshot> members,
        List<String> availableActions,
        RecoveryContext recoveryContext) {

    public TeamContext(
            String teamName,
            String namespace,
            String objective,
            String myRole,
            boolean isLead,
            List<MemberSnapshot> members,
            List<String> availableActions) {
        this(teamName, namespace, objective, myRole, isLead, members, availableActions, null);
    }

    /** Roster snapshot embedded in TeamContext (not the full store member row). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemberSnapshot(String name, String agentRef, String status) {}

    /** Injected when a member session is recovering after a crash/reschedule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecoveryContext(
            String previousSessionId,
            int restartCount,
            List<CompletedTask> completedTasks,
            InterruptedTask interruptedTask,
            List<RecentMessage> recentMessages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompletedTask(String id, String subject, String result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterruptedTask(String id, String subject, String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecentMessage(String from, String content, String timestamp) {}

    public String resolvedNamespace() {
        return namespace == null || namespace.isBlank() ? "default" : namespace;
    }
}
