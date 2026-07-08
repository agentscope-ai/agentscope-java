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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SandboxLifecycleMiddlewareTest {

    @Mock SandboxManager sandboxManager;
    @Mock SandboxBackedFilesystem filesystemProxy;
    @Mock Sandbox sandbox;
    @Mock SandboxLease lease;

    SandboxLifecycleMiddleware middleware;

    @BeforeEach
    void setUp() {
        middleware = new SandboxLifecycleMiddleware(sandboxManager, filesystemProxy);
    }

    @Test
    void acquireForCall_nullContext_returnsWithoutError() {
        middleware.acquireForCall(null);
        // no exception expected
    }

    @Test
    void acquireForCall_nullSandboxContext_returnsWithoutError() {
        RuntimeContext ctx = RuntimeContext.builder().build();
        middleware.acquireForCall(ctx);
        // no exception expected
    }

    @Test
    void acquireForCall_startFailure_rollsBackAndThrows() throws Exception {
        SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox, lease);
        when(sandboxManager.acquire(any(), any())).thenReturn(result);
        doThrow(new RuntimeException("start failed")).when(sandbox).start();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .put(SandboxContext.class, SandboxContext.builder().build())
                        .build();

        assertThrows(RuntimeException.class, () -> middleware.acquireForCall(ctx));

        verify(sandboxManager).release(any(), any());
        verify(lease).close();
        verify(filesystemProxy).setSandbox(null);
    }
}
