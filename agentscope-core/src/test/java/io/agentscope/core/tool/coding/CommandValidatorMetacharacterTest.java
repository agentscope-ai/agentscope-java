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
package io.agentscope.core.tool.coding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the shell-metacharacter whitelist bypass (#3285): with {@code echo}
 * whitelisted, command substitution, variable expansion, and redirection must be rejected so
 * they route to the approval callback instead of executing directly under {@code sh -c}.
 */
class CommandValidatorMetacharacterTest {

    private final UnixCommandValidator unix = new UnixCommandValidator();
    private final WindowsCommandValidator windows = new WindowsCommandValidator();
    private final Set<String> whitelist = Set.of("echo");

    // ==================== Unix ====================

    @Test
    void unix_plainWhitelistedCommand_stillAllowed() {
        assertTrue(unix.validate("echo hello", whitelist).isAllowed());
        // Single-quoted content is literal in sh — no substitution happens.
        assertTrue(unix.validate("echo '$(safe)'", whitelist).isAllowed());
    }

    @Test
    void unix_commandSubstitution_rejected() {
        assertFalse(unix.validate("echo $(printf marker)", whitelist).isAllowed());
        // Double quotes do NOT prevent substitution in sh.
        assertFalse(unix.validate("echo \"$(printf marker)\"", whitelist).isAllowed());
        assertFalse(unix.validate("echo `printf marker`", whitelist).isAllowed());
    }

    @Test
    void unix_variableExpansion_rejected() {
        assertFalse(unix.validate("echo $HOME", whitelist).isAllowed());
        assertFalse(unix.validate("echo ${HOME}", whitelist).isAllowed());
    }

    @Test
    void unix_redirection_rejected() {
        assertFalse(unix.validate("echo marker > marker.txt", whitelist).isAllowed());
        assertFalse(unix.validate("echo marker < input.txt", whitelist).isAllowed());
    }

    // ==================== Windows ====================

    @Test
    void windows_plainWhitelistedCommand_stillAllowed() {
        assertTrue(windows.validate("echo hello", whitelist).isAllowed());
    }

    @Test
    void windows_variableExpansion_rejected() {
        assertFalse(windows.validate("echo %USERPROFILE%", whitelist).isAllowed());
    }

    @Test
    void windows_redirectionAndEscape_rejected() {
        assertFalse(windows.validate("echo marker > marker.txt", whitelist).isAllowed());
        assertFalse(windows.validate("echo mar^ker", whitelist).isAllowed());
    }
}
