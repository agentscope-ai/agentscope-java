/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for WindowsCommandValidator.
 *
 * <p>Tests Windows-specific command validation logic including:
 * <ul>
 *   <li>Executable extraction with Windows paths</li>
 *   <li>Extension removal (.exe, .bat, .cmd)</li>
 *   <li>Multiple command detection (Windows-specific separators)</li>
 *   <li>Whitelist validation</li>
 * </ul>
 */
@DisplayName("WindowsCommandValidator Tests")
class WindowsCommandValidatorTest {

    private WindowsCommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WindowsCommandValidator();
    }

    // ==================== Executable Extraction Tests ====================

    @Test
    @DisplayName("Should extract simple command name")
    void testExtractSimpleCommand() {
        assertEquals("dir", validator.extractExecutable("dir"));
        assertEquals("echo", validator.extractExecutable("echo"));
        assertEquals("type", validator.extractExecutable("type"));
    }

    @Test
    @DisplayName("Should extract command with arguments")
    void testExtractCommandWithArguments() {
        assertEquals("dir", validator.extractExecutable("dir /s /b"));
        assertEquals("echo", validator.extractExecutable("echo Hello World"));
        assertEquals("type", validator.extractExecutable("type file.txt"));
    }

    @Test
    @DisplayName("Should remove .exe extension")
    void testRemoveExeExtension() {
        assertEquals("notepad", validator.extractExecutable("notepad.exe"));
        assertEquals("cmd", validator.extractExecutable("cmd.exe /c dir"));
        assertEquals("powershell", validator.extractExecutable("powershell.exe -Command ls"));
    }

    @Test
    @DisplayName("Should remove .bat extension")
    void testRemoveBatExtension() {
        assertEquals("deploy", validator.extractExecutable("deploy.bat"));
        assertEquals("script", validator.extractExecutable("script.bat arg1 arg2"));
    }

    @Test
    @DisplayName("Should remove .cmd extension")
    void testRemoveCmdExtension() {
        assertEquals("setup", validator.extractExecutable("setup.cmd"));
        assertEquals("build", validator.extractExecutable("build.cmd --verbose"));
    }

    @Test
    @DisplayName("Should handle mixed case extensions")
    void testMixedCaseExtensions() {
        assertEquals("notepad", validator.extractExecutable("notepad.EXE"));
        assertEquals("script", validator.extractExecutable("script.BAT"));
        assertEquals("setup", validator.extractExecutable("setup.Cmd"));
        assertEquals("app", validator.extractExecutable("APP.exe"));
    }

    @Test
    @DisplayName("Should extract command from Windows path with backslash")
    void testExtractFromWindowsPath() {
        assertEquals("cmd", validator.extractExecutable("C:\\Windows\\System32\\cmd.exe"));
        assertEquals("notepad", validator.extractExecutable("C:\\Windows\\notepad.exe"));
        assertEquals(
                "python", validator.extractExecutable("C:\\Program Files\\Python\\python.exe"));
    }

    @Test
    @DisplayName("Should extract command from path with forward slash")
    void testExtractFromForwardSlashPath() {
        // Windows sometimes uses forward slashes
        assertEquals("cmd", validator.extractExecutable("C:/Windows/System32/cmd.exe"));
        assertEquals("git", validator.extractExecutable("C:/Program Files/Git/bin/git.exe"));
    }

    @Test
    @DisplayName("Should extract command from mixed slash path")
    void testExtractFromMixedSlashPath() {
        assertEquals("app", validator.extractExecutable("C:\\Users\\test/bin/app.exe"));
        assertEquals("tool", validator.extractExecutable("D:/tools\\bin\\tool.exe"));
    }

    @Test
    @DisplayName("Should handle quoted commands")
    void testQuotedCommands() {
        assertEquals("notepad", validator.extractExecutable("\"notepad.exe\" file.txt"));
        assertEquals(
                "cmd", validator.extractExecutable("\"C:\\Windows\\System32\\cmd.exe\" /c dir"));
        assertEquals("app", validator.extractExecutable("\"C:\\Program Files\\MyApp\\app.exe\""));
    }

    @Test
    @DisplayName("Should handle commands with tabs")
    void testCommandsWithTabs() {
        assertEquals("dir", validator.extractExecutable("dir\t/s"));
        assertEquals("echo", validator.extractExecutable("echo\tHello"));
    }

    @Test
    @DisplayName("Should handle empty or null commands")
    void testEmptyOrNullCommands() {
        assertEquals("", validator.extractExecutable(null));
        assertEquals("", validator.extractExecutable(""));
        assertEquals("", validator.extractExecutable("   "));
    }

    @Test
    @DisplayName("Should handle commands without extension")
    void testCommandsWithoutExtension() {
        assertEquals("dir", validator.extractExecutable("dir /s"));
        assertEquals("cd", validator.extractExecutable("cd C:\\Users"));
        assertEquals("echo", validator.extractExecutable("echo test"));
    }

    @Test
    @DisplayName("Should not remove non-standard extensions")
    void testNonStandardExtensions() {
        assertEquals("script.ps1", validator.extractExecutable("script.ps1"));
        assertEquals("app.vbs", validator.extractExecutable("app.vbs"));
        assertEquals("tool.jar", validator.extractExecutable("tool.jar"));
    }

    // ==================== Multiple Command Detection Tests ====================

    @Test
    @DisplayName("Should detect & separator")
    void testDetectAmpersandSeparator() {
        assertTrue(validator.containsMultipleCommands("dir & echo done"));
        assertTrue(validator.containsMultipleCommands("cd C:\\Users & dir"));
    }

    @Test
    @DisplayName("Should detect && separator")
    void testDetectDoubleAmpersandSeparator() {
        assertTrue(validator.containsMultipleCommands("dir && echo success"));
        assertTrue(validator.containsMultipleCommands("mkdir test && cd test"));
    }

    @Test
    @DisplayName("Should detect | pipe separator")
    void testDetectPipeSeparator() {
        assertTrue(validator.containsMultipleCommands("dir | findstr txt"));
        assertTrue(validator.containsMultipleCommands("echo test | clip"));
    }

    @Test
    @DisplayName("Should detect || separator")
    void testDetectDoublePipeSeparator() {
        assertTrue(validator.containsMultipleCommands("dir || echo failed"));
        assertTrue(validator.containsMultipleCommands("test.exe || exit"));
    }

    @Test
    @DisplayName("Should detect newline separator")
    void testDetectNewlineSeparator() {
        assertTrue(validator.containsMultipleCommands("dir\necho done"));
        assertTrue(validator.containsMultipleCommands("cd C:\\Users\ndir"));
    }

    @Test
    @DisplayName("Should NOT detect semicolon as separator in Windows")
    void testSemicolonNotDetected() {
        // Important: Windows cmd.exe does NOT use semicolon as command separator
        assertFalse(validator.containsMultipleCommands("echo test;echo done"));
        assertFalse(validator.containsMultipleCommands("dir;type file.txt"));
    }

    @Test
    @DisplayName("Should not detect single commands")
    void testSingleCommands() {
        assertFalse(validator.containsMultipleCommands("dir /s /b"));
        assertFalse(validator.containsMultipleCommands("echo Hello World"));
        assertFalse(validator.containsMultipleCommands("notepad.exe file.txt"));
    }

    @Test
    @DisplayName("Should handle null command in multiple command check")
    void testMultipleCommandsWithNull() {
        assertFalse(validator.containsMultipleCommands(null));
    }

    // ==================== Validation Tests ====================

    @Test
    @DisplayName("Should allow whitelisted command")
    void testAllowWhitelistedCommand() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("dir");
        whitelist.add("echo");

        CommandValidator.ValidationResult result = validator.validate("dir /s", whitelist);
        assertTrue(result.isAllowed());
        assertEquals("dir", result.getExecutable());
    }

    @Test
    @DisplayName("Should allow whitelisted command with .exe extension")
    void testAllowWhitelistedCommandWithExtension() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("notepad");

        // Both should work
        CommandValidator.ValidationResult result1 =
                validator.validate("notepad file.txt", whitelist);
        assertTrue(result1.isAllowed());

        CommandValidator.ValidationResult result2 =
                validator.validate("notepad.exe file.txt", whitelist);
        assertTrue(result2.isAllowed());
    }

    @Test
    @DisplayName("Should reject non-whitelisted command")
    void testRejectNonWhitelistedCommand() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("dir");

        CommandValidator.ValidationResult result = validator.validate("del file.txt", whitelist);
        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("not in the allowed whitelist"));
    }

    @Test
    @DisplayName("Should reject command with multiple separators")
    void testRejectMultipleCommands() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("dir");
        whitelist.add("echo");

        CommandValidator.ValidationResult result =
                validator.validate("dir && echo done", whitelist);
        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("multiple command separators"));
    }

    @Test
    @DisplayName("Should allow all commands when whitelist is null")
    void testAllowAllWithNullWhitelist() {
        CommandValidator.ValidationResult result = validator.validate("any_command", null);
        assertTrue(result.isAllowed());
    }

    @Test
    @DisplayName("Should allow all commands when whitelist is empty")
    void testAllowAllWithEmptyWhitelist() {
        Set<String> whitelist = new HashSet<>();
        CommandValidator.ValidationResult result = validator.validate("any_command", whitelist);
        assertTrue(result.isAllowed());
    }

    @Test
    @DisplayName("Should validate command with full path")
    void testValidateCommandWithFullPath() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("cmd");

        CommandValidator.ValidationResult result =
                validator.validate("C:\\Windows\\System32\\cmd.exe /c dir", whitelist);
        assertTrue(result.isAllowed());
        assertEquals("cmd", result.getExecutable());
    }

    @Test
    @DisplayName("Should validate quoted command")
    void testValidateQuotedCommand() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("notepad");

        CommandValidator.ValidationResult result =
                validator.validate(
                        "\"C:\\Program Files\\Notepad\\notepad.exe\" file.txt", whitelist);
        assertTrue(result.isAllowed());
    }

    // ==================== Windows-Specific Edge Cases ====================

    @Test
    @DisplayName("Should handle UNC paths")
    void testUNCPaths() {
        assertEquals("app", validator.extractExecutable("\\\\server\\share\\bin\\app.exe"));
        assertEquals("tool", validator.extractExecutable("\\\\192.168.1.1\\tools\\tool.bat"));
    }

    @Test
    @DisplayName("Should handle commands with special characters in path")
    void testSpecialCharactersInPath() {
        assertEquals("app", validator.extractExecutable("C:\\Program Files (x86)\\MyApp\\app.exe"));
        assertEquals("tool", validator.extractExecutable("C:\\Users\\user-name\\tools\\tool.cmd"));
    }

    @Test
    @DisplayName("Should handle very long paths")
    void testVeryLongPaths() {
        String longPath =
                "C:\\Very\\Long\\Path\\With\\Many\\Directories\\And\\Subdirectories\\app.exe";
        assertEquals("app", validator.extractExecutable(longPath));
    }

    @Test
    @DisplayName("Should handle relative paths")
    void testRelativePaths() {
        assertEquals("script", validator.extractExecutable(".\\script.bat"));
        assertEquals("app", validator.extractExecutable("..\\bin\\app.exe"));
        assertEquals("tool", validator.extractExecutable("tools\\tool.cmd"));
    }

    @Test
    @DisplayName("Should handle commands with environment variables")
    void testEnvironmentVariables() {
        // Note: This tests the extraction, not variable expansion
        assertEquals(
                "%WINDIR%\\System32\\cmd.exe",
                validator.extractExecutable("%WINDIR%\\System32\\cmd.exe /c dir"));
    }

    @Test
    @DisplayName("Should validate case-insensitive extensions")
    void testCaseInsensitiveExtensions() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("notepad");

        assertTrue(validator.validate("notepad.exe", whitelist).isAllowed());
        assertTrue(validator.validate("notepad.EXE", whitelist).isAllowed());
        assertTrue(validator.validate("notepad.Exe", whitelist).isAllowed());
        assertTrue(validator.validate("NOTEPAD.EXE", whitelist).isAllowed());
    }

    @Test
    @DisplayName("Should handle PowerShell commands")
    void testPowerShellCommands() {
        assertEquals(
                "powershell", validator.extractExecutable("powershell.exe -Command Get-Process"));
        assertEquals("pwsh", validator.extractExecutable("pwsh.exe -File script.ps1"));
    }

    @Test
    @DisplayName("Should handle batch file with arguments")
    void testBatchFileWithArguments() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("deploy");

        CommandValidator.ValidationResult result =
                validator.validate("deploy.bat --env production --verbose", whitelist);
        assertTrue(result.isAllowed());
        assertEquals("deploy", result.getExecutable());
    }

    @Test
    @DisplayName("Should reject piped commands even if first is whitelisted")
    void testRejectPipedCommands() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("dir");
        whitelist.add("findstr");

        CommandValidator.ValidationResult result =
                validator.validate("dir | findstr txt", whitelist);
        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("multiple command separators"));
    }

    @Test
    @DisplayName("Should handle commands with redirection operators")
    void testRedirectionOperators() {
        // Redirection operators should not be detected as command separators
        assertFalse(validator.containsMultipleCommands("dir > output.txt"));
        assertFalse(validator.containsMultipleCommands("type < input.txt"));
        assertFalse(validator.containsMultipleCommands("echo test >> log.txt"));
        assertFalse(validator.containsMultipleCommands("command 2> error.txt"));
    }
}
