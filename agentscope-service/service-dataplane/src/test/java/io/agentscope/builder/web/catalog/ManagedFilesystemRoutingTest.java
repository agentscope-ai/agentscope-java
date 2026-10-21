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
package io.agentscope.builder.web.catalog;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.runtime.managed.ManagedToolRequests;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedFilesystemRoutingTest {
    @Test
    void memoryRoutePreservesShellCapabilityAndDelegatesExecutionToHands() {
        var requests = mock(ManagedToolRequests.class);
        var memory = mock(AbstractFilesystem.class);
        var context = RuntimeContext.empty();
        var read = mock(ReadResult.class);
        var execution = new ExecuteResponse("hands-result", 0, false);
        when(memory.read(context, "/note.md", 0, 0)).thenReturn(read);
        when(requests.request(
                        context,
                        "sandbox.exec",
                        Map.of("command", "echo check", "timeout_seconds", 30),
                        ExecuteResponse.class))
                .thenReturn(execution);
        var filesystem =
                HarnessAgentBuildService.managedFilesystem(
                        requests, Map.of("memory-stores/Knowledge/", memory));
        assertSame(read, filesystem.read(context, "/memory-stores/Knowledge/note.md", 0, 0));
        assertSame(execution, filesystem.execute(context, "echo check", 30));
        verify(requests)
                .request(
                        context,
                        "sandbox.exec",
                        Map.of("command", "echo check", "timeout_seconds", 30),
                        ExecuteResponse.class);
    }
}
