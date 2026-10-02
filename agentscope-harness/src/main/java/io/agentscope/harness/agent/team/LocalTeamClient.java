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

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link TeamClient} backed by {@link BaseStore} CAS (Claude file-lock equivalent). Mailbox wakeups
 * are delegated to {@link TeamWakeups}, which the hosting middleware registers.
 */
public final class LocalTeamClient implements TeamClient {

    private static final String LEAD = "lead";

    private final BaseStore store;
    private final AtomicLong messageSeq = new AtomicLong();

    public LocalTeamClient(BaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Mono<List<TeamTask>> listTasks(String namespace, String teamName) {
        return Mono.fromCallable(() -> readAllTasks(namespace, teamName))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> createTask(
            String namespace,
            String teamName,
            String subject,
            String description,
            List<String> blockedBy,
            String owner) {
        return Mono.fromCallable(
                        () -> {
                            List<TeamTask> existing = readAllTasks(namespace, teamName);
                            long next =
                                    existing.stream()
                                                    .map(TeamTask::taskId)
                                                    .mapToLong(LocalTeamClient::taskSeq)
                                                    .max()
                                                    .orElse(0L)
                                            + 1;
                            String taskId = "task-" + next;
                            TeamTask task =
                                    new TeamTask(
                                            taskId,
                                            teamName,
                                            namespace,
                                            subject,
                                            description == null ? "" : description,
                                            TeamTask.PENDING,
                                            owner == null ? "" : owner,
                                            blockedBy == null ? List.of() : List.copyOf(blockedBy),
                                            "",
                                            1L);
                            if (!store.putIfVersion(
                                    taskNs(namespace, teamName), taskId, task.toMap(), 0L)) {
                                throw new TeamConflictException("create cas failed for " + taskId);
                            }
                            notifyMemberTaskAssigned(task);
                            return task;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> assignTask(
            String namespace, String teamName, String taskId, String owner, long expectedVersion) {
        return Mono.fromCallable(
                        () -> {
                            if (owner == null || owner.isBlank()) {
                                throw new IllegalArgumentException("owner required");
                            }
                            VersionedTask cur = requireVersioned(namespace, teamName, taskId);
                            if (cur.storeVersion() != expectedVersion
                                    || !TeamTask.PENDING.equals(cur.task().state())) {
                                throw new TeamConflictException("assign conflict for " + taskId);
                            }
                            TeamTask next =
                                    new TeamTask(
                                            cur.task().taskId(),
                                            cur.task().teamName(),
                                            cur.task().namespace(),
                                            cur.task().subject(),
                                            cur.task().description(),
                                            TeamTask.PENDING,
                                            owner,
                                            cur.task().blockedBy(),
                                            cur.task().result(),
                                            cur.storeVersion() + 1);
                            if (!cas(namespace, teamName, next, expectedVersion)) {
                                throw new TeamConflictException("assign cas failed for " + taskId);
                            }
                            notifyMemberTaskAssigned(next);
                            return next;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> claimTask(
            String namespace,
            String teamName,
            String taskId,
            String claimedBy,
            long expectedVersion) {
        return Mono.fromCallable(
                        () -> {
                            VersionedTask cur = requireVersioned(namespace, teamName, taskId);
                            // Idempotent: already started by this member.
                            if (TeamTask.IN_PROGRESS.equals(cur.task().state())
                                    && claimedBy != null
                                    && claimedBy.equals(cur.task().owner())) {
                                return cur.task();
                            }
                            long version =
                                    expectedVersion <= 0 ? cur.storeVersion() : expectedVersion;
                            if (cur.storeVersion() != version
                                    || !TeamTask.PENDING.equals(cur.task().state())) {
                                throw new TeamConflictException("claim conflict for " + taskId);
                            }
                            if (cur.task().owner() != null
                                    && !cur.task().owner().isBlank()
                                    && !cur.task().owner().equals(claimedBy)) {
                                throw new TeamConflictException(
                                        "task owned by " + cur.task().owner());
                            }
                            if (isBlocked(namespace, teamName, cur.task())) {
                                throw new TeamConflictException("task still blocked");
                            }
                            TeamTask next =
                                    new TeamTask(
                                            cur.task().taskId(),
                                            cur.task().teamName(),
                                            cur.task().namespace(),
                                            cur.task().subject(),
                                            cur.task().description(),
                                            TeamTask.IN_PROGRESS,
                                            claimedBy,
                                            cur.task().blockedBy(),
                                            cur.task().result(),
                                            cur.storeVersion() + 1);
                            if (!cas(namespace, teamName, next, version)) {
                                throw new TeamConflictException("claim cas failed for " + taskId);
                            }
                            return next;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> completeTask(
            String namespace, String teamName, String taskId, String result) {
        return Mono.fromCallable(
                        () -> {
                            VersionedTask cur = requireVersioned(namespace, teamName, taskId);
                            if (!TeamTask.IN_PROGRESS.equals(cur.task().state())) {
                                throw new IllegalStateException(
                                        "task not in progress: " + cur.task().state());
                            }
                            TeamTask next =
                                    new TeamTask(
                                            cur.task().taskId(),
                                            cur.task().teamName(),
                                            cur.task().namespace(),
                                            cur.task().subject(),
                                            cur.task().description(),
                                            TeamTask.COMPLETED,
                                            cur.task().owner(),
                                            cur.task().blockedBy(),
                                            result == null ? "" : result,
                                            cur.storeVersion() + 1);
                            if (!cas(namespace, teamName, next, cur.storeVersion())) {
                                throw new TeamConflictException(
                                        "complete cas failed for " + taskId);
                            }
                            notifyLeadTaskSettled(next, "completed", result);
                            return next;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamTask> failTask(
            String namespace, String teamName, String taskId, String reason) {
        return Mono.fromCallable(
                        () -> {
                            VersionedTask cur = requireVersioned(namespace, teamName, taskId);
                            if (TeamTask.isTerminal(cur.task().state())) {
                                throw new TeamConflictException(
                                        "task already terminal: " + cur.task().state());
                            }
                            TeamTask next =
                                    new TeamTask(
                                            cur.task().taskId(),
                                            cur.task().teamName(),
                                            cur.task().namespace(),
                                            cur.task().subject(),
                                            cur.task().description(),
                                            TeamTask.FAILED,
                                            cur.task().owner(),
                                            cur.task().blockedBy(),
                                            reason == null ? "" : reason,
                                            cur.storeVersion() + 1);
                            if (!cas(namespace, teamName, next, cur.storeVersion())) {
                                throw new TeamConflictException("fail cas failed for " + taskId);
                            }
                            notifyLeadTaskSettled(next, "failed", reason);
                            return next;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Pushes newly assigned work into the owner's mailbox so the member is woken with it. A worker
     * that probed an empty board and ended its turn has no other way to learn the task exists.
     */
    private void notifyMemberTaskAssigned(TeamTask task) {
        String owner = task.owner();
        if (owner == null || owner.isBlank() || LEAD.equals(owner)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[team] Task ")
                .append(task.taskId())
                .append(" (")
                .append(task.subject())
                .append(") was assigned to you.\n");
        if (task.description() != null && !task.description().isBlank()) {
            sb.append("\nDescription:\n").append(task.description()).append("\n");
        }
        sb.append("\nCall claimTask with task_id=")
                .append(task.taskId())
                .append(" to start, then completeTask with a result summary, or failTask with a")
                .append(" reason.");
        deliverNotification(task.namespace(), task.teamName(), LEAD, owner, sb.toString());
    }

    /**
     * Pushes a terminal task transition into the lead's mailbox so the lead is woken with the
     * outcome. Without this the board would change silently and an idle lead would never react.
     */
    private void notifyLeadTaskSettled(TeamTask task, String verb, String detail) {
        String owner = task.owner() == null || task.owner().isBlank() ? "unknown" : task.owner();
        if (LEAD.equals(owner)) {
            return;
        }
        String body = detail == null || detail.isBlank() ? "(no detail provided)" : detail;
        String label = "failed".equals(verb) ? "Reason" : "Result";
        String content =
                "[team] Task "
                        + task.taskId()
                        + " ("
                        + task.subject()
                        + ") "
                        + verb
                        + " by "
                        + owner
                        + ".\n\n"
                        + label
                        + ":\n"
                        + body;
        deliverNotification(task.namespace(), task.teamName(), owner, LEAD, content);
    }

    /**
     * Persists a notification synchronously. These calls run inside {@code Mono.fromCallable}
     * blocks on boundedElastic threads, so blocking here guarantees the message is visible in the
     * store before the triggering operation (createTask/failTask/...) completes. A fire-and-forget
     * subscription raced with callers reading the inbox immediately afterwards.
     */
    private void deliverNotification(
            String namespace, String teamName, String from, String to, String content) {
        try {
            sendMessage(namespace, teamName, from, to, content).block();
        } catch (RuntimeException ex) {
            // Notification failures never break the task transition itself.
        }
    }

    @Override
    public Mono<TeamTask> unclaimTask(
            String namespace, String teamName, String taskId, long expectedVersion) {
        return Mono.fromCallable(
                        () -> {
                            VersionedTask cur = requireVersioned(namespace, teamName, taskId);
                            if (cur.storeVersion() != expectedVersion
                                    || !TeamTask.IN_PROGRESS.equals(cur.task().state())) {
                                throw new TeamConflictException("unclaim conflict for " + taskId);
                            }
                            TeamTask next =
                                    new TeamTask(
                                            cur.task().taskId(),
                                            cur.task().teamName(),
                                            cur.task().namespace(),
                                            cur.task().subject(),
                                            cur.task().description(),
                                            TeamTask.PENDING,
                                            // Owner is cleared so the task returns to the
                                            // claimable pool, matching control-plane Unclaim.
                                            "",
                                            cur.task().blockedBy(),
                                            cur.task().result(),
                                            cur.storeVersion() + 1);
                            if (!cas(namespace, teamName, next, expectedVersion)) {
                                throw new TeamConflictException("unclaim cas failed for " + taskId);
                            }
                            return next;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TeamTask>> listClaimableTasks(
            String namespace, String teamName, String forMember) {
        return listTasks(namespace, teamName).map(tasks -> TeamTask.claimableOf(tasks, forMember));
    }

    @Override
    public Mono<TeamMessage> sendMessage(
            String namespace, String teamName, String from, String to, String content) {
        if (to != null && to.equals(from)) {
            // A self-addressed message only wakes its sender again, so the reply it
            // was meant for never reaches anyone.
            return Mono.error(
                    new IllegalArgumentException(
                            "cannot send a message to yourself; address another roster member"));
        }
        return Mono.fromCallable(
                        () -> {
                            long id = messageSeq.incrementAndGet();
                            TeamMessage msg = new TeamMessage(from, to, content, id);
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("id", id);
                            value.put("from", from);
                            value.put("to", to);
                            value.put("content", content);
                            store.put(msgNs(namespace, teamName), String.valueOf(id), value);
                            // Wake the recipient through the middleware, which owns the
                            // member -> runtime session mapping this client cannot resolve.
                            TeamWakeups.wake(teamName, to, content);
                            return msg;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TeamMessage>> broadcastMessage(
            String namespace, String teamName, String from, String content) {
        return listMembers(namespace, teamName)
                .flatMap(
                        members -> {
                            List<Mono<TeamMessage>> sends = new ArrayList<>();
                            for (TeamMemberInfo m : members) {
                                if (m.memberName() == null || m.memberName().equals(from)) {
                                    continue;
                                }
                                sends.add(
                                        sendMessage(
                                                namespace,
                                                teamName,
                                                from,
                                                m.memberName(),
                                                content));
                            }
                            if (sends.isEmpty()) {
                                return Mono.just(List.of());
                            }
                            return Mono.zip(
                                    sends,
                                    arr -> {
                                        List<TeamMessage> out = new ArrayList<>();
                                        for (Object o : arr) {
                                            out.add((TeamMessage) o);
                                        }
                                        return out;
                                    });
                        });
    }

    @Override
    public Mono<Void> spawnMember(
            String namespace, String teamName, String name, String agentRef, String prompt) {
        return Mono.error(
                new UnsupportedOperationException("spawnMember requires control-plane TeamClient"));
    }

    @Override
    public Mono<Void> shutdownMember(String namespace, String teamName, String memberName) {
        return Mono.error(
                new UnsupportedOperationException(
                        "shutdownMember requires control-plane TeamClient"));
    }

    @Override
    public Mono<Void> submitPlan(
            String namespace, String teamName, String memberName, String planText) {
        return Mono.error(
                new UnsupportedOperationException("submitPlan requires control-plane TeamClient"));
    }

    @Override
    public Mono<Void> approvePlan(String namespace, String teamName, String memberName) {
        return Mono.error(
                new UnsupportedOperationException("approvePlan requires control-plane TeamClient"));
    }

    @Override
    public Mono<Void> rejectPlan(String namespace, String teamName, String memberName) {
        return Mono.error(
                new UnsupportedOperationException("rejectPlan requires control-plane TeamClient"));
    }

    @Override
    public Mono<List<TeamMessage>> listMessages(String namespace, String teamName, int limit) {
        return Mono.fromCallable(
                        () -> {
                            List<StoreItem> items =
                                    store.search(
                                            msgNs(namespace, teamName), Math.max(limit, 50), 0);
                            List<TeamMessage> out = new ArrayList<>();
                            for (StoreItem item : items) {
                                if (item != null && item.value() != null) {
                                    out.add(TeamMessage.fromMap(item.value()));
                                }
                            }
                            if (out.size() > limit && limit > 0) {
                                return out.subList(out.size() - limit, out.size());
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TeamMemberInfo>> listMembers(String namespace, String teamName) {
        return Mono.fromCallable(
                        () -> {
                            List<StoreItem> items =
                                    store.search(memberNs(namespace, teamName), 100, 0);
                            List<TeamMemberInfo> out = new ArrayList<>();
                            for (StoreItem item : items) {
                                if (item != null && item.value() != null) {
                                    out.add(TeamMemberInfo.fromMap(item.value()));
                                }
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<TeamInfo> createTeam(TeamCreateSpec spec) {
        return Mono.fromCallable(
                        () -> {
                            Objects.requireNonNull(spec, "spec");
                            String ns = spec.namespace() == null ? "default" : spec.namespace();
                            Map<String, Object> meta = new LinkedHashMap<>();
                            meta.put("name", spec.name());
                            meta.put("namespace", ns);
                            meta.put("objective", spec.objective());
                            meta.put("phase", "Running");
                            meta.put("leadRef", spec.leadAgentRef());
                            if (!store.putIfVersion(teamNs(ns, spec.name()), "meta", meta, 0L)) {
                                StoreItem existing = store.get(teamNs(ns, spec.name()), "meta");
                                if (existing != null) {
                                    throw new TeamConflictException("team exists: " + spec.name());
                                }
                                store.put(teamNs(ns, spec.name()), "meta", meta);
                            }
                            store.put(
                                    memberNs(ns, spec.name()),
                                    "lead",
                                    new TeamMemberInfo(
                                                    "lead",
                                                    spec.leadAgentRef(),
                                                    "Working",
                                                    "",
                                                    "byo",
                                                    true)
                                            .toMap());
                            if (spec.members() != null) {
                                for (TeamMemberSpec m : spec.members()) {
                                    store.put(
                                            memberNs(ns, spec.name()),
                                            m.name(),
                                            new TeamMemberInfo(
                                                            m.name(),
                                                            m.agentRef(),
                                                            "Working",
                                                            "",
                                                            m.deployMode() == null
                                                                    ? "byo"
                                                                    : m.deployMode(),
                                                            false)
                                                    .toMap());
                                }
                            }
                            return new TeamInfo(
                                    spec.name(),
                                    ns,
                                    spec.objective(),
                                    "Running",
                                    spec.leadAgentRef());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> completeTeam(String namespace, String teamName) {
        return Mono.<Void>fromRunnable(
                        () -> {
                            StoreItem item = store.get(teamNs(namespace, teamName), "meta");
                            Map<String, Object> meta =
                                    item == null || item.value() == null
                                            ? new LinkedHashMap<>()
                                            : new LinkedHashMap<>(item.value());
                            meta.put("phase", "Completed");
                            long ver = item == null ? 0L : item.version();
                            if (!store.putIfVersion(
                                    teamNs(namespace, teamName), "meta", meta, ver)) {
                                store.put(teamNs(namespace, teamName), "meta", meta);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private boolean cas(String namespace, String teamName, TeamTask next, long expectedVersion) {
        return store.putIfVersion(
                taskNs(namespace, teamName), next.taskId(), next.toMap(), expectedVersion);
    }

    private record VersionedTask(TeamTask task, long storeVersion) {}

    private VersionedTask requireVersioned(String namespace, String teamName, String taskId) {
        StoreItem item = store.get(taskNs(namespace, teamName), taskId);
        if (item == null || item.value() == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        TeamTask task = TeamTask.fromMap(item.value());
        // Prefer BaseStore CAS version as the authority.
        return new VersionedTask(
                new TeamTask(
                        task.taskId(),
                        task.teamName(),
                        task.namespace(),
                        task.subject(),
                        task.description(),
                        task.state(),
                        task.owner(),
                        task.blockedBy(),
                        task.result(),
                        item.version()),
                item.version());
    }

    private List<TeamTask> readAllTasks(String namespace, String teamName) {
        List<StoreItem> items = store.search(taskNs(namespace, teamName), 500, 0);
        List<TeamTask> out = new ArrayList<>();
        for (StoreItem item : items) {
            if (item != null && item.value() != null) {
                TeamTask t = TeamTask.fromMap(item.value());
                out.add(
                        new TeamTask(
                                t.taskId(),
                                t.teamName(),
                                t.namespace(),
                                t.subject(),
                                t.description(),
                                t.state(),
                                t.owner(),
                                t.blockedBy(),
                                t.result(),
                                item.version()));
            }
        }
        return out;
    }

    private boolean isBlocked(String namespace, String teamName, TeamTask task) {
        if (task.blockedBy() == null || task.blockedBy().isEmpty()) {
            return false;
        }
        Map<String, Boolean> completed = new HashMap<>();
        for (TeamTask t : readAllTasks(namespace, teamName)) {
            if (TeamTask.COMPLETED.equals(t.state())) {
                completed.put(t.taskId(), true);
            }
        }
        for (String b : task.blockedBy()) {
            if (!Boolean.TRUE.equals(completed.get(b))) {
                return true;
            }
        }
        return false;
    }

    private static long taskSeq(String taskId) {
        if (taskId != null && taskId.startsWith("task-")) {
            try {
                return Long.parseLong(taskId.substring(5));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static List<String> teamNs(String namespace, String teamName) {
        return List.of("teams", namespace, teamName);
    }

    private static List<String> taskNs(String namespace, String teamName) {
        return List.of("teams", namespace, teamName, "tasks");
    }

    private static List<String> memberNs(String namespace, String teamName) {
        return List.of("teams", namespace, teamName, "members");
    }

    private static List<String> msgNs(String namespace, String teamName) {
        return List.of("teams", namespace, teamName, "messages");
    }
}
