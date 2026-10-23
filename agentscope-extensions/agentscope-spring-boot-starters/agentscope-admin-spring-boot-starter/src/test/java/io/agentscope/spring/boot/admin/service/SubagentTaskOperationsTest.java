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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.test.StepVerifier;

class SubagentTaskOperationsTest {

    @Test
    void noopWhenNoRepoBean() {
        SubagentTaskOperations ops = new SubagentTaskOperations(emptyProvider());
        assertThat(ops.isConfigured()).isFalse();
        StepVerifier.create(ops.list("sess", null))
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
        StepVerifier.create(ops.cancel("sess", "task-1")).expectNext(false).verifyComplete();
    }

    @Test
    void parsesStatusFilter() {
        TaskRepository repo = mock(TaskRepository.class);
        when(repo.listTasks(any(), eq("sess"), eq(TaskStatus.COMPLETED))).thenReturn(List.of());

        SubagentTaskOperations ops = new SubagentTaskOperations(provider(repo));
        StepVerifier.create(ops.list("sess", "completed"))
                .assertNext(l -> assertThat(l).isEmpty())
                .verifyComplete();
        verify(repo).listTasks(any(), eq("sess"), eq(TaskStatus.COMPLETED));
    }

    @Test
    void mapsBackgroundTaskFields() {
        BackgroundTask task = mock(BackgroundTask.class);
        when(task.getTaskId()).thenReturn("t-1");
        when(task.getAgentId()).thenReturn("code-reviewer");
        when(task.getTaskStatus()).thenReturn(TaskStatus.RUNNING);
        when(task.isCompleted()).thenReturn(false);

        TaskRepository repo = mock(TaskRepository.class);
        when(repo.listTasks(any(), eq("sess"), eq(null))).thenReturn(List.of(task));

        SubagentTaskOperations ops = new SubagentTaskOperations(provider(repo));
        StepVerifier.create(ops.list("sess", null))
                .assertNext(
                        list -> {
                            assertThat(list).hasSize(1);
                            assertThat(list.get(0).taskId()).isEqualTo("t-1");
                            assertThat(list.get(0).subagentId()).isEqualTo("code-reviewer");
                            assertThat(list.get(0).status()).isEqualTo("RUNNING");
                            assertThat(list.get(0).completed()).isFalse();
                        })
                .verifyComplete();
    }

    @Test
    void cancelDelegates() {
        TaskRepository repo = mock(TaskRepository.class);
        when(repo.cancelTask(any(), eq("sess"), eq("t-1"))).thenReturn(true);

        SubagentTaskOperations ops = new SubagentTaskOperations(provider(repo));
        StepVerifier.create(ops.cancel("sess", "t-1")).expectNext(true).verifyComplete();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TaskRepository> provider(TaskRepository repo) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("repo", repo);
        return bf.getBeanProvider(TaskRepository.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TaskRepository> emptyProvider() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        return bf.getBeanProvider(TaskRepository.class);
    }
}
