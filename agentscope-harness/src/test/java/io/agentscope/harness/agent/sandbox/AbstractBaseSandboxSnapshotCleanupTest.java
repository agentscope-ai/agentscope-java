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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class AbstractBaseSandboxSnapshotCleanupTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesSnapshotAfterSuccessfulHydration(boolean previouslyReady) throws Exception {
        InputStream archive = mock(InputStream.class);
        AbstractBaseSandbox sandbox = sandboxForRestore(previouslyReady, archive);

        sandbox.start();

        InOrder order = inOrder(sandbox, archive);
        order.verify(sandbox).doSetupWorkspace();
        order.verify(sandbox).doHydrateWorkspace(archive);
        order.verify(archive).close();
        assertTrue(sandbox.isRunning());
        assertTrue(sandbox.getState().isWorkspaceRootReady());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesOpenedSnapshotWhenWorkspaceSetupFails(boolean previouslyReady) throws Exception {
        InputStream archive = mock(InputStream.class);
        AbstractBaseSandbox sandbox = sandboxForRestore(previouslyReady, archive);
        IOException setupFailure = new IOException("workspace setup failed");
        doThrow(setupFailure).when(sandbox).doSetupWorkspace();

        SandboxException.WorkspaceStartException failure =
                assertThrows(SandboxException.WorkspaceStartException.class, sandbox::start);

        assertSame(setupFailure, failure.getCause());
        assertFalse(sandbox.isRunning());
        assertFalse(sandbox.getState().isWorkspaceRootReady());
        verify(sandbox, never()).doHydrateWorkspace(any());
        verify(archive).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSetupFailureWhenClosingSnapshotAlsoFails(boolean previouslyReady)
            throws Exception {
        InputStream archive = mock(InputStream.class);
        IOException closeFailure = new IOException("archive close failed");
        doThrow(closeFailure).when(archive).close();
        AbstractBaseSandbox sandbox = sandboxForRestore(previouslyReady, archive);
        IOException setupFailure = new IOException("workspace setup failed");
        doThrow(setupFailure).when(sandbox).doSetupWorkspace();

        SandboxException.WorkspaceStartException failure =
                assertThrows(SandboxException.WorkspaceStartException.class, sandbox::start);

        assertSame(setupFailure, failure.getCause());
        verify(archive).close();
        assertArrayEquals(new Throwable[] {closeFailure}, setupFailure.getSuppressed());
    }

    private AbstractBaseSandbox sandboxForRestore(boolean previouslyReady, InputStream archive)
            throws Exception {
        SandboxSnapshot snapshot = mock(SandboxSnapshot.class);
        when(snapshot.isRestorable()).thenReturn(true);
        when(snapshot.restore()).thenReturn(archive);
        SandboxState state = new SandboxState() {};
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setWorkspaceRootReady(previouslyReady);
        state.setSnapshot(snapshot);
        AbstractBaseSandbox sandbox =
                mock(
                        AbstractBaseSandbox.class,
                        withSettings().useConstructor(state).defaultAnswer(CALLS_REAL_METHODS));
        doReturn(false).when(sandbox).probeWorkspaceRootForPreservedResume();
        return sandbox;
    }
}
