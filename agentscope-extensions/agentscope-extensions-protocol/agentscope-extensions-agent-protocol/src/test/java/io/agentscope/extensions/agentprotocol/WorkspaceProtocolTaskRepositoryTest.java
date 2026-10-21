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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Control-plane ProtocolTaskRepository vs execution-agent WorkspaceManager. */
@DisplayName("ProtocolTaskRepository control-plane isolation")
class WorkspaceProtocolTaskRepositoryTest {

    @TempDir Path tempDir;

    private Path controlPlane;
    private Path agentWorkspace;
    private ProtocolTaskRepository taskRepository;

    @BeforeEach
    void setUp() throws Exception {
        controlPlane = tempDir.resolve("protocol-control");
        agentWorkspace = tempDir.resolve("agent-a");
        Files.createDirectories(controlPlane);
        Files.createDirectories(agentWorkspace);
        taskRepository = new WorkspaceProtocolTaskRepository(controlPlane);
    }

    @Test
    @DisplayName("save/find round-trip under PROTOCOL_* bucket")
    void saveFindRoundTrip() {
        TaskRecord record =
                new TaskRecord(
                        "task-1",
                        "worker",
                        AgentProtocolConstants.PROTOCOL_AGENT_ID,
                        AgentProtocolConstants.PROTOCOL_SESSION_ID,
                        null);
        record.setStatus(TaskStatus.COMPLETED);
        record.setResult("done");

        taskRepository.save(record);

        assertTrue(taskRepository.find("task-1").isPresent());
        assertEquals(TaskStatus.COMPLETED, taskRepository.find("task-1").get().getStatus());
        assertEquals("done", taskRepository.find("task-1").get().getResult());
    }

    @Test
    @DisplayName("TaskStore writes protocol records outside the execution agent workspace")
    void taskStoreUsesControlPlaneNotAgentWorkspace() throws Exception {
        HarnessAgent agent = mock(HarnessAgent.class);
        WorkspaceManager agentWm = new WorkspaceManager(agentWorkspace);
        when(agent.getWorkspaceManager()).thenReturn(agentWm);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new AgentStartEvent("sess", null, "worker"),
                                new AgentResultEvent(
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .textContent("ok")
                                                .build()),
                                new AgentEndEvent(null)));

        AgentProtocolTaskStore store =
                new AgentProtocolTaskStore(AgentFactory.fixed(agent), taskRepository);

        store.submit("t-ctrl", "worker", "hello");
        awaitStatus(store, "t-ctrl", "success");

        assertTrue(taskRepository.find("t-ctrl").isPresent());
        assertTrue(
                agentWm.readTaskRecord(
                                RuntimeContext.empty(),
                                AgentProtocolConstants.PROTOCOL_AGENT_ID,
                                AgentProtocolConstants.PROTOCOL_SESSION_ID,
                                "t-ctrl")
                        .isEmpty(),
                "execution agent workspace must not hold protocol TaskRecords");
        assertNotEquals(
                agentWm.getWorkspace().normalize(),
                ((WorkspaceProtocolTaskRepository) taskRepository)
                        .workspaceManager()
                        .getWorkspace()
                        .normalize());
    }

    private static void awaitStatus(AgentProtocolTaskStore store, String taskId, String status)
            throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (status.equals(store.snapshot(taskId).get("status"))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError(
                "timed out waiting for status=" + status + " got=" + store.snapshot(taskId));
    }
}
