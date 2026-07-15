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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SandboxLifecycleMiddlewareRuntimeContextTest {

    @Test
    void bindsRuntimeContextBeforeStartingSandbox() throws Exception {
        SandboxManager sandboxManager = mock(SandboxManager.class);
        SandboxBackedFilesystem filesystem = mock(SandboxBackedFilesystem.class);
        Sandbox sandbox = mock(Sandbox.class);
        SandboxContext sandboxContext = SandboxContext.builder().build();
        RuntimeContext runtimeContext =
                RuntimeContext.builder()
                        .userId("scheduled-job-author")
                        .sessionId("job-42")
                        .put(SandboxContext.class, sandboxContext)
                        .build();

        when(sandboxManager.acquire(sandboxContext, runtimeContext))
                .thenReturn(SandboxAcquireResult.userManaged(sandbox));

        new SandboxLifecycleMiddleware(sandboxManager, filesystem).acquireForCall(runtimeContext);

        InOrder lifecycle = inOrder(sandbox);
        lifecycle.verify(sandbox).bindRuntimeContext(runtimeContext);
        lifecycle.verify(sandbox).start();
    }

    @Test
    void releasesSandboxAndLeaseWhenRuntimeContextBindingFails() throws Exception {
        SandboxManager sandboxManager = mock(SandboxManager.class);
        SandboxBackedFilesystem filesystem = mock(SandboxBackedFilesystem.class);
        Sandbox sandbox = mock(Sandbox.class);
        SandboxLease lease = mock(SandboxLease.class);
        SandboxContext sandboxContext = SandboxContext.builder().build();
        RuntimeContext runtimeContext =
                RuntimeContext.builder()
                        .userId("scheduled-job-author")
                        .sessionId("job-42")
                        .put(SandboxContext.class, sandboxContext)
                        .build();
        SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox, lease);

        when(sandboxManager.acquire(sandboxContext, runtimeContext)).thenReturn(result);
        doThrow(new IllegalStateException("invalid runtime identity"))
                .when(sandbox)
                .bindRuntimeContext(runtimeContext);

        assertThrows(
                RuntimeException.class,
                () ->
                        new SandboxLifecycleMiddleware(sandboxManager, filesystem)
                                .acquireForCall(runtimeContext));

        verify(sandbox, never()).start();
        verify(filesystem).setSandbox(null);
        verify(sandboxManager).release(result);
        verify(lease).close();
    }
}
