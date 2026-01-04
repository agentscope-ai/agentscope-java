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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for WindowsCommandValidator.
 */
@DisplayName("WindowsCommandValidator")
class WindowsCommandValidatorTest {

    private WindowsCommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WindowsCommandValidator();
    }

    @Nested
    @DisplayName("Executable Extraction")
    class ExecutableExtractionTests {

        @Test
        @DisplayName("Should extract simple command")
        void extractSimpleCommand() {
            assertEquals("cmd", validator.extractExecutable("cmd"));
            assertEquals("dir", validator.extractExecutable("dir"));
        }

        @Test
        @DisplayName("Should extract command with arguments")
        void extractCommandWithArguments() {
            assertEquals("dir", validator.extractExecutable("dir /s /b"));
            assertEquals("findstr", validator.extractExecutable("findstr pattern file.txt"));
        }

        @Nested
        @DisplayName("Extension Handling")
        class ExtensionHandlingTests {

            @Test
            @DisplayName("Should remove common extensions")
            void removeCommonExtensions() {
                assertEquals("notepad", validator.extractExecutable("notepad.exe"));
                assertEquals("deploy", validator.extractExecutable("deploy.bat"));
                assertEquals("setup", validator.extractExecutable("setup.cmd"));
            }

            @Test
            @DisplayName("Should handle case-insensitive extensions")
            void handleCaseInsensitiveExtensions() {
                assertEquals("app", validator.extractExecutable("APP.EXE"));
                assertEquals("script", validator.extractExecutable("Script.Bat"));
            }

            @Test
            @DisplayName("Should not remove non-standard extensions")
            void notRemoveNonStandardExtensions() {
                assertEquals("file.txt", validator.extractExecutable("file.txt"));
                assertEquals("script.ps1", validator.extractExecutable("script.ps1"));
            }
        }
    }

    @Nested
    @DisplayName("Multiple Command Detection")
    class MultipleCommandDetectionTests {

        @Test
        @DisplayName("Should detect standard separators")
        void detectStandardSeparators() {
            assertTrue(validator.containsMultipleCommands("dir & type file.txt"));
            assertTrue(validator.containsMultipleCommands("dir && type file.txt"));
            assertTrue(validator.containsMultipleCommands("dir | findstr txt"));
            assertTrue(validator.containsMultipleCommands("dir || echo failed"));
            assertTrue(validator.containsMultipleCommands("dir\ntype file.txt"));
        }

        @Test
        @DisplayName("Should NOT detect semicolon as separator")
        void notDetectSemicolon() {
            assertFalse(validator.containsMultipleCommands("echo test;more"));
            assertFalse(validator.containsMultipleCommands("cmd1;cmd2"));
        }

        @Test
        @DisplayName("Should NOT detect separators in single commands")
        void notDetectInSingleCommands() {
            assertFalse(validator.containsMultipleCommands("dir /s /b"));
            assertFalse(validator.containsMultipleCommands("findstr pattern file.txt"));
        }

        @Test
        @DisplayName("Should NOT detect redirection operators")
        void notDetectRedirectionOperators() {
            assertFalse(validator.containsMultipleCommands("dir > output.txt"));
            assertFalse(validator.containsMultipleCommands("type < input.txt"));
        }

        @Nested
        @DisplayName("Commands Without Spaces")
        class CommandsWithoutSpacesTests {

            @Test
            @DisplayName("Should detect commands chained without spaces")
            void detectCommandsWithoutSpaces() {
                assertTrue(validator.containsMultipleCommands("dir&type file.txt"));
                assertTrue(validator.containsMultipleCommands("dir|findstr txt"));
                assertTrue(validator.containsMultipleCommands("echo test&more"));
            }

            @Test
            @DisplayName("Should NOT detect semicolon without spaces")
            void notDetectSemicolonWithoutSpaces() {
                assertFalse(validator.containsMultipleCommands("echo test;more"));
            }
        }
    }

    @Nested
    @DisplayName("Quote Handling")
    class QuoteHandlingTests {

        @Test
        @DisplayName("Should NOT detect separators inside quotes")
        void notDetectSeparatorsInsideQuotes() {
            assertFalse(
                    validator.containsMultipleCommands("curl \"http://example.com?foo=1&bar=2\""));
            assertFalse(validator.containsMultipleCommands("echo \"a|b|c\""));
            assertFalse(validator.containsMultipleCommands("findstr \"pattern|other\" file.txt"));
        }

        @Test
        @DisplayName("Should detect separators outside quotes")
        void detectSeparatorsOutsideQuotes() {
            assertTrue(validator.containsMultipleCommands("echo \"test\" & dir"));
            assertTrue(validator.containsMultipleCommands("type \"file\" | findstr pattern"));
            assertTrue(validator.containsMultipleCommands("echo \"test\"&dir"));
        }

        @Test
        @DisplayName("Should handle escaped characters")
        void handleEscapedCharacters() {
            assertFalse(validator.containsMultipleCommands("echo test^&more"));
            assertFalse(validator.containsMultipleCommands("echo test^|more"));
        }

        @Test
        @DisplayName("Should handle unclosed quotes")
        void handleUnclosedQuotes() {
            assertFalse(validator.containsMultipleCommands("echo \"test & more"));
        }
    }

    @Nested
    @DisplayName("Whitelist Validation")
    class WhitelistValidationTests {

        @Test
        @DisplayName("Should allow whitelisted commands")
        void allowWhitelistedCommands() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("dir");
            whitelist.add("type");

            CommandValidator.ValidationResult result = validator.validate("dir /s", whitelist);
            assertTrue(result.isAllowed());
            assertEquals("dir", result.getExecutable());
        }

        @Test
        @DisplayName("Should allow whitelisted commands with extensions")
        void allowWhitelistedCommandsWithExtensions() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("deploy");

            CommandValidator.ValidationResult result =
                    validator.validate("deploy.bat --env production", whitelist);
            assertTrue(result.isAllowed());
            assertEquals("deploy", result.getExecutable());
        }

        @Test
        @DisplayName("Should reject non-whitelisted commands")
        void rejectNonWhitelistedCommands() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("dir");

            CommandValidator.ValidationResult result =
                    validator.validate("del important.txt", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("not in the allowed whitelist"));
        }

        @Test
        @DisplayName("Should reject multiple commands even if whitelisted")
        void rejectMultipleCommandsEvenIfWhitelisted() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("dir");
            whitelist.add("findstr");

            CommandValidator.ValidationResult result =
                    validator.validate("dir | findstr txt", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("multiple command separators"));
        }

        @Test
        @DisplayName("Should allow all commands with null or empty whitelist")
        void allowAllWithNullOrEmptyWhitelist() {
            assertTrue(validator.validate("any-command", null).isAllowed());
            assertTrue(validator.validate("any-command", new HashSet<>()).isAllowed());
        }

        @Test
        @DisplayName("Should handle case-insensitive matching")
        void handleCaseInsensitiveMatching() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("cmd");

            CommandValidator.ValidationResult result1 = validator.validate("CMD", whitelist);
            assertTrue(result1.isAllowed());

            CommandValidator.ValidationResult result2 = validator.validate("Cmd.exe", whitelist);
            assertTrue(result2.isAllowed());
        }
    }

    @Nested
    @DisplayName("Relative Path Security")
    class RelativePathSecurityTests {

        @Test
        @DisplayName("Should allow safe relative paths")
        void allowSafeRelativePaths() {
            // Unix-style paths
            assertTrue(validator.isPathWithinCurrentDirectory("./script.bat"));
            assertTrue(validator.isPathWithinCurrentDirectory("./subdir/script.bat"));

            // Windows-style paths
            assertTrue(validator.isPathWithinCurrentDirectory(".\\script.bat"));
            assertTrue(validator.isPathWithinCurrentDirectory(".\\subdir\\script.bat"));

            // Mixed-style paths
            assertTrue(validator.isPathWithinCurrentDirectory(".\\subdir/script.bat"));
            assertTrue(validator.isPathWithinCurrentDirectory("./subdir\\script.bat"));
        }

        @Test
        @DisplayName("Should reject escaping relative paths")
        void rejectEscapingPaths() {
            // Unix-style
            assertFalse(validator.isPathWithinCurrentDirectory("./../script.bat"));
            assertFalse(validator.isPathWithinCurrentDirectory("./../../script.bat"));

            // Windows-style
            assertFalse(validator.isPathWithinCurrentDirectory(".\\..\\script.bat"));
            assertFalse(validator.isPathWithinCurrentDirectory(".\\..\\..\\script.bat"));

            // Mixed-style
            assertFalse(validator.isPathWithinCurrentDirectory("./..\\script.bat"));
            assertFalse(validator.isPathWithinCurrentDirectory(".\\../script.bat"));
        }

        @Test
        @DisplayName("Should reject escaping path in validation")
        void rejectEscapingPathInValidation() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("activate check");
            CommandValidator.ValidationResult result =
                    validator.validate(".\\..\\script.bat", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("escapes"));
        }
    }

    @Nested
    @DisplayName("Security Tests")
    class SecurityTests {

        @Test
        @DisplayName("Should detect unquoted URLs as potential injection")
        void detectUnquotedUrls() {
            assertTrue(validator.containsMultipleCommands("curl http://example.com?a=1&b=2"));
        }

        @Test
        @DisplayName("Should reject unquoted URL even if command is whitelisted")
        void rejectUnquotedUrlWithWhitelist() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("curl");

            CommandValidator.ValidationResult result =
                    validator.validate("curl http://example.com?a=1&b=2", whitelist);
            assertFalse(result.isAllowed());
        }

        @Test
        @DisplayName("Should accept properly quoted URLs")
        void acceptQuotedUrls() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("curl");

            CommandValidator.ValidationResult result =
                    validator.validate("curl \"http://example.com?a=1&b=2\"", whitelist);
            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("Should prevent command injection via URL")
        void preventCommandInjectionViaUrl() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("curl");

            CommandValidator.ValidationResult result =
                    validator.validate("curl http://safe.com&del /f /q important.txt", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("multiple command separators"));
        }

        @Test
        @DisplayName("Should prevent command injection via no-space chaining")
        void preventCommandInjectionViaNoSpaceChaining() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");

            CommandValidator.ValidationResult result =
                    validator.validate("echo test&malicious_command", whitelist);
            assertFalse(result.isAllowed());
        }
    }
}
