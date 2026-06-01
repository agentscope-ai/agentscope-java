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
package io.agentscope.spring.boot.admin.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.spring.boot.admin.dto.SubagentTaskView;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Read/cancel operations over a {@link TaskRepository}-managed pool of subagent background tasks.
 *
 * <p>The {@code TaskRepository} bean is optional. When absent (no user wired one into Spring),
 * every method returns an empty result without raising — the endpoint is still reachable but
 * inert, which is the same fail-soft behaviour as the rest of the inventory.
 */
public final class SubagentTaskOperations {

    private final ObjectProvider<TaskRepository> repoProvider;

    public SubagentTaskOperations(ObjectProvider<TaskRepository> repoProvider) {
        this.repoProvider = repoProvider;
    }

    public boolean isConfigured() {
        return repoProvider.getIfAvailable() != null;
    }

    public Mono<List<SubagentTaskView>> list(String sessionId, String statusFilter) {
        TaskRepository repo = repoProvider.getIfAvailable();
        if (repo == null) return Mono.just(List.of());
        TaskStatus filter = parseStatus(statusFilter);
        return Mono.fromCallable(
                        () -> {
                            List<SubagentTaskView> out = new ArrayList<>();
                            for (BackgroundTask t :
                                    repo.listTasks(RuntimeContext.empty(), sessionId, filter)) {
                                SubagentTaskView v = SubagentTaskView.of(t);
                                if (v != null) out.add(v);
                            }
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<SubagentTaskView> get(String sessionId, String taskId) {
        TaskRepository repo = repoProvider.getIfAvailable();
        if (repo == null) return Mono.empty();
        return Mono.fromCallable(
                        () ->
                                SubagentTaskView.of(
                                        repo.getTask(RuntimeContext.empty(), sessionId, taskId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** @return true if the cancel request was accepted by the repository. */
    public Mono<Boolean> cancel(String sessionId, String taskId) {
        TaskRepository repo = repoProvider.getIfAvailable();
        if (repo == null) return Mono.just(false);
        return Mono.fromCallable(() -> repo.cancelTask(RuntimeContext.empty(), sessionId, taskId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static TaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
