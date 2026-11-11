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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellSyntaxTest {

    @Test
    void of_reportsTheShellOfTheBackendThatExecutes() {
        AbstractSandboxFilesystem backend = mock(AbstractSandboxFilesystem.class);
        when(backend.shellSyntax()).thenReturn(ShellSyntax.CMD);

        assertEquals(ShellSyntax.CMD, ShellSyntax.of(backend));
    }

    @Test
    void of_followsAnOverlayToTheShellItDelegatesTo(@TempDir Path dir) {
        // OverlayFilesystem.of returns the shell-aware subtype when the upper layer has a shell.
        AbstractSandboxFilesystem backend = mock(AbstractSandboxFilesystem.class);
        when(backend.shellSyntax()).thenReturn(ShellSyntax.POWERSHELL);

        AbstractFilesystem overlay = OverlayFilesystem.of(backend, new LocalFilesystem(dir));

        assertEquals(ShellSyntax.POWERSHELL, ShellSyntax.of(overlay));
    }

    @Test
    void of_followsARoutedSandboxToItsPrimary() {
        AbstractSandboxFilesystem backend = mock(AbstractSandboxFilesystem.class);
        when(backend.shellSyntax()).thenReturn(ShellSyntax.CMD);

        RoutedSandboxFilesystem routed = new RoutedSandboxFilesystem(backend, Map.of());

        assertEquals(ShellSyntax.CMD, ShellSyntax.of(routed));
    }

    @Test
    void of_reportsPosixForAFilesystemThatCannotRunCommands(@TempDir Path dir) {
        assertEquals(ShellSyntax.POSIX, ShellSyntax.of(new LocalFilesystem(dir)));
    }
}
