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

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Client for AgentTeams coordination (Claude-parity task board + mailbox).
 *
 * <p>Implementations: {@link LocalTeamClient} (BaseStore CAS) and control-plane HTTP.
 */
public interface TeamClient {

    /** Lists all tasks on the team board. */
    Mono<List<TeamTask>> listTasks(String namespace, String teamName);

    /** Creates a pending task; {@code owner} may be blank (unassigned). */
    Mono<TeamTask> createTask(
            String namespace,
            String teamName,
            String subject,
            String description,
            List<String> blockedBy,
            String owner);

    /** Lead-assigns a pending task to {@code owner} (stays pending). */
    Mono<TeamTask> assignTask(
            String namespace, String teamName, String taskId, String owner, long expectedVersion);

    /**
     * Claims a pending unblocked task into in_progress when owner is empty (self-claim) or already
     * equals {@code claimedBy} (assignee starts).
     *
     * <p>{@code expectedVersion <= 0} means "claim at the current store version" (models often omit
     * the optimistic lock token).
     */
    Mono<TeamTask> claimTask(
            String namespace,
            String teamName,
            String taskId,
            String claimedBy,
            long expectedVersion);

    /** Completes an in-progress task. Notifies the lead with the result. */
    Mono<TeamTask> completeTask(String namespace, String teamName, String taskId, String result);

    /** Marks a non-terminal task failed. Notifies the lead with the reason. */
    Mono<TeamTask> failTask(String namespace, String teamName, String taskId, String reason);

    /** Unclaims an in-progress task back to pending. */
    Mono<TeamTask> unclaimTask(
            String namespace, String teamName, String taskId, long expectedVersion);

    /**
     * Returns unblocked pending tasks the caller can start: unassigned open-board tasks, plus (when
     * {@code forMember} is non-blank) pending tasks already assigned to that member.
     */
    Mono<List<TeamTask>> listClaimableTasks(String namespace, String teamName, String forMember);

    /** Open-board claimable tasks only ({@code forMember} blank). */
    default Mono<List<TeamTask>> listClaimableTasks(String namespace, String teamName) {
        return listClaimableTasks(namespace, teamName, null);
    }

    /** Point-to-point team mailbox message. */
    Mono<TeamMessage> sendMessage(
            String namespace, String teamName, String from, String to, String content);

    /** Broadcast mailbox message to all other members ({@code to} empty on CP). */
    Mono<List<TeamMessage>> broadcastMessage(
            String namespace, String teamName, String from, String content);

    /** Lists recent team messages. */
    Mono<List<TeamMessage>> listMessages(String namespace, String teamName, int limit);

    /** Lists team members. */
    Mono<List<TeamMemberInfo>> listMembers(String namespace, String teamName);

    /** Dynamically spawn a teammate (lead). */
    Mono<Void> spawnMember(
            String namespace, String teamName, String name, String agentRef, String prompt);

    /** Shutdown / remove a teammate (lead). */
    Mono<Void> shutdownMember(String namespace, String teamName, String memberName);

    /** Worker submits a plan for lead approval. */
    Mono<Void> submitPlan(String namespace, String teamName, String memberName, String planText);

    /** Lead approves a member plan. */
    Mono<Void> approvePlan(String namespace, String teamName, String memberName);

    /** Lead rejects a member plan. */
    Mono<Void> rejectPlan(String namespace, String teamName, String memberName);

    /** Creates a store-backed team and seed members. */
    Mono<TeamInfo> createTeam(TeamCreateSpec spec);

    /** Marks the team completed (retention TTL; not immediate delete). */
    Mono<Void> completeTeam(String namespace, String teamName);
}
