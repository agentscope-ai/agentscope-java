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
 * Unit tests for UnixCommandValidator.
 *
 * <p>Tests Unix-specific command validation logic including:
 * <ul>
 *   <li>Executable extraction with Unix paths</li>
 *   <li>Multiple command detection (Unix-specific separators)</li>
 *   <li>Quote handling (single and double quotes)</li>
 *   <li>Whitelist validation</li>
 * </ul>
 */
@DisplayName("UnixCommandValidator Tests")
class UnixCommandValidatorTest {

    private UnixCommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UnixCommandValidator();
    }

    // ==================== Executable Extraction Tests ====================

    @Test
    @DisplayName("Should extract simple command")
    void testExtractSimpleCommand() {
        assertEquals("ls", validator.extractExecutable("ls"));
        assertEquals("cat", validator.extractExecutable("cat"));
        assertEquals("grep", validator.extractExecutable("grep"));
    }

    @Test
    @DisplayName("Should extract command with arguments")
    void testExtractCommandWithArguments() {
        assertEquals("ls", validator.extractExecutable("ls -la /home"));
        assertEquals("grep", validator.extractExecutable("grep pattern file.txt"));
        assertEquals("find", validator.extractExecutable("find . -name '*.java'"));
    }

    @Test
    @DisplayName("Should extract command from absolute path")
    void testExtractFromAbsolutePath() {
        assertEquals("python", validator.extractExecutable("/usr/bin/python"));
        assertEquals("node", validator.extractExecutable("/usr/local/bin/node"));
        assertEquals("java", validator.extractExecutable("/opt/java/bin/java"));
    }

    @Test
    @DisplayName("Should extract command from relative path")
    void testExtractFromRelativePath() {
        assertEquals("script.sh", validator.extractExecutable("./script.sh"));
        assertEquals("app", validator.extractExecutable("../bin/app"));
        assertEquals("tool", validator.extractExecutable("tools/tool"));
    }

    @Test
    @DisplayName("Should handle single-quoted commands")
    void testSingleQuotedCommands() {
        assertEquals("ls", validator.extractExecutable("'ls' -la"));
        assertEquals("echo", validator.extractExecutable("'echo' test"));
    }

    @Test
    @DisplayName("Should handle double-quoted commands")
    void testDoubleQuotedCommands() {
        assertEquals("ls", validator.extractExecutable("\"ls\" -la"));
        assertEquals("echo", validator.extractExecutable("\"echo\" test"));
    }

    // ==================== Multiple Command Detection Tests ====================

    @Test
    @DisplayName("Should detect ampersand separator")
    void testDetectAmpersandSeparator() {
        assertTrue(validator.containsMultipleCommands("ls & pwd"));
        assertTrue(validator.containsMultipleCommands("command1 & command2"));
    }

    @Test
    @DisplayName("Should detect double ampersand separator")
    void testDetectDoubleAmpersandSeparator() {
        assertTrue(validator.containsMultipleCommands("ls && pwd"));
        assertTrue(validator.containsMultipleCommands("make && make install"));
    }

    @Test
    @DisplayName("Should detect pipe separator")
    void testDetectPipeSeparator() {
        assertTrue(validator.containsMultipleCommands("ls | grep txt"));
        assertTrue(validator.containsMultipleCommands("cat file | wc -l"));
    }

    @Test
    @DisplayName("Should detect double pipe separator")
    void testDetectDoublePipeSeparator() {
        assertTrue(validator.containsMultipleCommands("command1 || command2"));
        assertTrue(validator.containsMultipleCommands("test -f file || echo not found"));
    }

    @Test
    @DisplayName("Should detect semicolon separator")
    void testDetectSemicolonSeparator() {
        assertTrue(validator.containsMultipleCommands("ls; pwd"));
        assertTrue(validator.containsMultipleCommands("cd /tmp; ls"));
    }

    @Test
    @DisplayName("Should detect newline separator")
    void testDetectNewlineSeparator() {
        assertTrue(validator.containsMultipleCommands("ls\npwd"));
        assertTrue(validator.containsMultipleCommands("echo test\necho more"));
    }

    @Test
    @DisplayName("Should NOT detect separators in single commands")
    void testSingleCommands() {
        assertFalse(validator.containsMultipleCommands("ls -la"));
        assertFalse(validator.containsMultipleCommands("grep pattern file.txt"));
        assertFalse(validator.containsMultipleCommands("find . -name '*.java'"));
    }

    // ==================== Quote Handling Tests ====================

    @Test
    @DisplayName("Should NOT detect separators inside double quotes - URL with ampersand")
    void testUrlWithAmpersandInDoubleQuotes() {
        // This should NOT be detected as multiple commands
        assertFalse(validator.containsMultipleCommands("curl \"http://example.com?foo=1&bar=2\""));
        assertFalse(
                validator.containsMultipleCommands(
                        "wget \"https://api.example.com/data?id=123&token=abc\""));
    }

    @Test
    @DisplayName("Should NOT detect separators inside single quotes - URL with ampersand")
    void testUrlWithAmpersandInSingleQuotes() {
        // This should NOT be detected as multiple commands
        assertFalse(validator.containsMultipleCommands("curl 'http://example.com?foo=1&bar=2'"));
        assertFalse(
                validator.containsMultipleCommands(
                        "wget 'https://api.example.com/data?id=123&token=abc'"));
    }

    @Test
    @DisplayName("Should NOT detect pipe inside double quotes")
    void testPipeInDoubleQuotes() {
        // This should NOT be detected as multiple commands
        assertFalse(validator.containsMultipleCommands("echo \"a|b|c\""));
        assertFalse(validator.containsMultipleCommands("grep \"pattern|other\" file.txt"));
    }

    @Test
    @DisplayName("Should NOT detect pipe inside single quotes")
    void testPipeInSingleQuotes() {
        // This should NOT be detected as multiple commands
        assertFalse(validator.containsMultipleCommands("echo 'a|b|c'"));
        assertFalse(validator.containsMultipleCommands("grep 'pattern|other' file.txt"));
    }

    @Test
    @DisplayName("Should NOT detect semicolon inside double quotes")
    void testSemicolonInDoubleQuotes() {
        // This should NOT be detected as multiple commands
        assertFalse(validator.containsMultipleCommands("echo \"a;b;c\""));
        assertFalse(validator.containsMultipleCommands("awk \"BEGIN {print; exit}\""));
    }

    @Test
    @DisplayName("Should NOT detect semicolon inside single quotes")
    void testSemicolonInSingleQuotes() {
        // This should NOT be detected as multiple commands
        assertFalse(validator.containsMultipleCommands("echo 'a;b;c'"));
        assertFalse(validator.containsMultipleCommands("grep 'test;more' file.txt"));
    }

    @Test
    @DisplayName("Should detect separators OUTSIDE quotes")
    void testSeparatorsOutsideQuotes() {
        // These SHOULD be detected as multiple commands
        assertTrue(validator.containsMultipleCommands("echo \"test\" & ls"));
        assertTrue(validator.containsMultipleCommands("ls | grep \"pattern\""));
        assertTrue(validator.containsMultipleCommands("echo 'a' ; echo 'b'"));
        assertTrue(validator.containsMultipleCommands("echo \"test\" && pwd"));
    }

    @Test
    @DisplayName("Should handle mixed quoted and unquoted content")
    void testMixedQuotedContent() {
        // Separator inside quotes should be ignored
        assertFalse(validator.containsMultipleCommands("curl -H \"User-Agent: Bot&Crawler\" url"));
        assertFalse(validator.containsMultipleCommands("echo 'a|b' test"));

        // Separator outside quotes should be detected
        assertTrue(validator.containsMultipleCommands("curl \"http://example.com\" & echo done"));
        assertTrue(validator.containsMultipleCommands("echo 'test' | cat"));
    }

    @Test
    @DisplayName("Should handle escaped characters")
    void testEscapedCharacters() {
        // Backslash is the escape character in Unix shells
        assertFalse(validator.containsMultipleCommands("echo test\\&more"));
        assertFalse(validator.containsMultipleCommands("echo test\\|more"));
        assertFalse(validator.containsMultipleCommands("echo test\\;more"));
    }

    @Test
    @DisplayName("Should handle escaped quotes")
    void testEscapedQuotes() {
        // Escaped quotes should not affect quote state
        assertFalse(validator.containsMultipleCommands("echo \"test\\\"quoted\" value"));
        assertFalse(validator.containsMultipleCommands("echo 'test\\'quoted' value"));
    }

    @Test
    @DisplayName("Should handle nested quotes")
    void testNestedQuotes() {
        // Single quotes inside double quotes (and vice versa) are literal
        assertFalse(validator.containsMultipleCommands("echo \"it's a test & more\""));
        assertFalse(validator.containsMultipleCommands("echo 'he said \"hello|world\"'"));
    }

    @Test
    @DisplayName("Should handle unclosed quotes gracefully")
    void testUnclosedQuotes() {
        // Unclosed quote - everything after is considered quoted
        assertFalse(validator.containsMultipleCommands("echo \"test & more"));
        assertFalse(validator.containsMultipleCommands("echo 'test | more"));
    }

    // ==================== Whitelist Validation Tests ====================

    @Test
    @DisplayName("Should allow whitelisted command")
    void testAllowWhitelistedCommand() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("ls");
        whitelist.add("cat");

        CommandValidator.ValidationResult result = validator.validate("ls -la", whitelist);
        assertTrue(result.isAllowed());
        assertEquals("ls", result.getExecutable());
    }

    @Test
    @DisplayName("Should reject non-whitelisted command")
    void testRejectNonWhitelistedCommand() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("ls");
        whitelist.add("cat");

        CommandValidator.ValidationResult result = validator.validate("rm -rf /", whitelist);
        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("not in the allowed whitelist"));
    }

    @Test
    @DisplayName("Should allow all commands with null whitelist")
    void testAllowAllWithNullWhitelist() {
        CommandValidator.ValidationResult result = validator.validate("any-command", null);
        assertTrue(result.isAllowed());
    }

    @Test
    @DisplayName("Should allow all commands with empty whitelist")
    void testAllowAllWithEmptyWhitelist() {
        Set<String> whitelist = new HashSet<>();
        CommandValidator.ValidationResult result = validator.validate("any-command", whitelist);
        assertTrue(result.isAllowed());
    }

    @Test
    @DisplayName("Should validate whitelisted command with URL in quotes")
    void testWhitelistWithQuotedUrl() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("curl");

        // This should be allowed - the & is inside quotes
        CommandValidator.ValidationResult result =
                validator.validate("curl \"http://example.com?foo=1&bar=2\"", whitelist);
        assertTrue(result.isAllowed());
        assertEquals("curl", result.getExecutable());
    }

    @Test
    @DisplayName("Should reject command with separator outside quotes even if whitelisted")
    void testRejectSeparatorOutsideQuotes() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("echo");
        whitelist.add("ls");

        // This should be rejected - the & is outside quotes
        CommandValidator.ValidationResult result =
                validator.validate("echo \"test\" & ls", whitelist);
        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("multiple command separators"));
    }

    @Test
    @DisplayName("Should reject multiple commands even if both are whitelisted")
    void testRejectMultipleWhitelistedCommands() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("ls");
        whitelist.add("pwd");

        CommandValidator.ValidationResult result = validator.validate("ls && pwd", whitelist);
        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("multiple command separators"));
    }

    @Test
    @DisplayName("Should handle command with path in whitelist")
    void testCommandWithPath() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add("python");

        CommandValidator.ValidationResult result =
                validator.validate("/usr/bin/python script.py", whitelist);
        assertTrue(result.isAllowed());
        assertEquals("python", result.getExecutable());
    }
}
