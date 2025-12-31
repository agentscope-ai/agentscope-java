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
 * Unit tests for UnixCommandValidator.
 */
@DisplayName("UnixCommandValidator")
class UnixCommandValidatorTest {

    private UnixCommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UnixCommandValidator();
    }

    @Nested
    @DisplayName("Executable Extraction")
    class ExecutableExtractionTests {

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
    }

    @Nested
    @DisplayName("Multiple Command Detection")
    class MultipleCommandDetectionTests {

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

        @Nested
        @DisplayName("Commands Without Spaces")
        class CommandsWithoutSpacesTests {

            @Test
            @DisplayName("Should detect commands chained without spaces - ampersand")
            void testDetectCommandsWithoutSpacesAmpersand() {
                assertTrue(validator.containsMultipleCommands("ls&pwd"));
                assertTrue(validator.containsMultipleCommands("echo test&more"));
                assertTrue(validator.containsMultipleCommands("cmd1&cmd2&cmd3"));
            }

            @Test
            @DisplayName("Should detect commands chained without spaces - pipe")
            void testDetectCommandsWithoutSpacesPipe() {
                assertTrue(validator.containsMultipleCommands("ls|grep txt"));
                assertTrue(validator.containsMultipleCommands("cat file|wc"));
            }

            @Test
            @DisplayName("Should detect commands chained without spaces - semicolon")
            void testDetectCommandsWithoutSpacesSemicolon() {
                assertTrue(validator.containsMultipleCommands("ls;pwd"));
                assertTrue(validator.containsMultipleCommands("cd /tmp;ls;pwd"));
            }

            @Test
            @DisplayName("Should detect separator in complex command without spaces")
            void testComplexCommandWithoutSpaces() {
                assertTrue(validator.containsMultipleCommands("echo hello&echo world"));
                assertTrue(validator.containsMultipleCommands("ls -la|grep test"));
                assertTrue(validator.containsMultipleCommands("cd /tmp;ls -la"));
            }

            @Test
            @DisplayName("Should handle commands with arguments and no-space separators")
            void testCommandsWithArgumentsNoSpaceSeparators() {
                assertTrue(validator.containsMultipleCommands("ls -la&pwd"));
                assertTrue(validator.containsMultipleCommands("grep pattern file.txt|wc -l"));
                assertTrue(validator.containsMultipleCommands("echo test;cat file"));
            }
        }
    }

    @Nested
    @DisplayName("Quote Handling")
    class QuoteHandlingTests {

        @Test
        @DisplayName("Should NOT detect separators inside double quotes - URL with ampersand")
        void testUrlWithAmpersandInDoubleQuotes() {
            assertFalse(
                    validator.containsMultipleCommands("curl \"http://example.com?foo=1&bar=2\""));
            assertFalse(
                    validator.containsMultipleCommands(
                            "wget \"https://api.example.com/data?id=123&token=abc\""));
        }

        @Test
        @DisplayName("Should NOT detect separators inside single quotes - URL with ampersand")
        void testUrlWithAmpersandInSingleQuotes() {
            assertFalse(
                    validator.containsMultipleCommands("curl 'http://example.com?foo=1&bar=2'"));
            assertFalse(
                    validator.containsMultipleCommands(
                            "wget 'https://api.example.com/data?id=123&token=abc'"));
        }

        @Test
        @DisplayName("Should NOT detect pipe inside double quotes")
        void testPipeInDoubleQuotes() {
            assertFalse(validator.containsMultipleCommands("echo \"a|b|c\""));
            assertFalse(validator.containsMultipleCommands("grep \"pattern|other\" file.txt"));
        }

        @Test
        @DisplayName("Should NOT detect pipe inside single quotes")
        void testPipeInSingleQuotes() {
            assertFalse(validator.containsMultipleCommands("echo 'a|b|c'"));
            assertFalse(validator.containsMultipleCommands("grep 'pattern|other' file.txt"));
        }

        @Test
        @DisplayName("Should NOT detect semicolon inside double quotes")
        void testSemicolonInDoubleQuotes() {
            assertFalse(validator.containsMultipleCommands("echo \"a;b;c\""));
            assertFalse(validator.containsMultipleCommands("awk \"BEGIN {print; exit}\""));
        }

        @Test
        @DisplayName("Should NOT detect semicolon inside single quotes")
        void testSemicolonInSingleQuotes() {
            assertFalse(validator.containsMultipleCommands("echo 'a;b;c'"));
            assertFalse(validator.containsMultipleCommands("grep 'test;more' file.txt"));
        }

        @Test
        @DisplayName("Should detect separators OUTSIDE quotes")
        void testSeparatorsOutsideQuotes() {
            assertTrue(validator.containsMultipleCommands("echo \"test\" & ls"));
            assertTrue(validator.containsMultipleCommands("ls | grep \"pattern\""));
            assertTrue(validator.containsMultipleCommands("echo 'a' ; echo 'b'"));
            assertTrue(validator.containsMultipleCommands("echo \"test\" && pwd"));
        }

        @Test
        @DisplayName("Should handle mixed quoted and unquoted content")
        void testMixedQuotedContent() {
            assertFalse(
                    validator.containsMultipleCommands("curl -H \"User-Agent: Bot&Crawler\" url"));
            assertFalse(validator.containsMultipleCommands("echo 'a|b' test"));

            assertTrue(
                    validator.containsMultipleCommands("curl \"http://example.com\" & echo done"));
            assertTrue(validator.containsMultipleCommands("echo 'test' | cat"));
        }

        @Test
        @DisplayName("Should handle escaped characters")
        void testEscapedCharacters() {
            assertFalse(validator.containsMultipleCommands("echo test\\&more"));
            assertFalse(validator.containsMultipleCommands("echo test\\|more"));
            assertFalse(validator.containsMultipleCommands("echo test\\;more"));
        }

        @Test
        @DisplayName("Should handle escaped quotes")
        void testEscapedQuotes() {
            assertFalse(validator.containsMultipleCommands("echo \"test\\\"quoted\" value"));
            assertFalse(validator.containsMultipleCommands("echo 'test\\'quoted' value"));
        }

        @Test
        @DisplayName("Should handle nested quotes")
        void testNestedQuotes() {
            assertFalse(validator.containsMultipleCommands("echo \"it's a test & more\""));
            assertFalse(validator.containsMultipleCommands("echo 'he said \"hello|world\"'"));
        }

        @Test
        @DisplayName("Should handle unclosed quotes gracefully")
        void testUnclosedQuotes() {
            assertFalse(validator.containsMultipleCommands("echo \"test & more"));
            assertFalse(validator.containsMultipleCommands("echo 'test | more"));
        }

        @Test
        @DisplayName("Should NOT detect separator-like characters in quoted strings without spaces")
        void testQuotedStringsWithSeparatorsNoSpaces() {
            assertFalse(validator.containsMultipleCommands("echo \"a&b\""));
            assertFalse(validator.containsMultipleCommands("echo \"x|y\""));
            assertFalse(validator.containsMultipleCommands("echo \"m;n\""));
        }

        @Test
        @DisplayName("Should detect real separators mixed with quoted content")
        void testRealSeparatorsMixedWithQuotes() {
            assertTrue(validator.containsMultipleCommands("echo \"test\"&ls"));
            assertTrue(validator.containsMultipleCommands("cat \"file\"|grep pattern"));
            assertTrue(validator.containsMultipleCommands("ls \"dir\";pwd"));
        }
    }

    @Nested
    @DisplayName("Whitelist Validation")
    class WhitelistValidationTests {

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
        @DisplayName("Should reject whitelisted command chained without spaces")
        void testRejectWhitelistedCommandWithoutSpaces() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("ls");
            whitelist.add("pwd");

            CommandValidator.ValidationResult result = validator.validate("ls&pwd", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("multiple command separators"));
        }
    }

    @Nested
    @DisplayName("Relative Path Security")
    class RelativePathSecurityTests {

        @Test
        @DisplayName("Should allow safe relative paths")
        void allowSafeRelativePaths() {
            // Unix-style paths
            assertTrue(validator.isPathWithinCurrentDirectory("./script.sh"));
            assertTrue(validator.isPathWithinCurrentDirectory("./subdir/script.sh"));
            assertTrue(validator.isPathWithinCurrentDirectory("./a/b/../c/script.sh"));

            // Windows-style paths (for cross-platform compatibility)
            assertTrue(validator.isPathWithinCurrentDirectory(".\\script.sh"));
            assertTrue(validator.isPathWithinCurrentDirectory(".\\subdir\\script.sh"));
        }

        @Test
        @DisplayName("Should reject escaping relative paths")
        void rejectEscapingPaths() {
            // Unix-style
            assertFalse(validator.isPathWithinCurrentDirectory("./../script.sh"));
            assertFalse(validator.isPathWithinCurrentDirectory("./../../script.sh"));
            assertFalse(validator.isPathWithinCurrentDirectory("./dir/../../script.sh"));
        }

        @Test
        @DisplayName("Should reject escaping path in validation")
        void rejectEscapingPathInValidation() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("activate check");
            CommandValidator.ValidationResult result =
                    validator.validate("./../script.sh", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("escapes"));
        }
    }

    @Nested
    @DisplayName("Security Tests")
    class SecurityTests {

        @Test
        @DisplayName("Should detect ampersand in unquoted URL as potential command separator")
        void testUnquotedUrlWithAmpersand() {
            assertTrue(validator.containsMultipleCommands("curl http://example.com?a=1&b=2"));
            assertTrue(
                    validator.containsMultipleCommands(
                            "wget https://api.example.com/data?id=123&token=abc"));
        }

        @Test
        @DisplayName("Should reject unquoted URL even if curl is whitelisted")
        void testRejectUnquotedUrlWithWhitelist() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("curl");

            CommandValidator.ValidationResult result =
                    validator.validate("curl http://example.com?a=1&b=2", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("multiple command separators"));
        }

        @Test
        @DisplayName("Should accept quoted URL with ampersand")
        void testQuotedUrlWithAmpersandIsAccepted() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("curl");

            CommandValidator.ValidationResult result =
                    validator.validate("curl \"http://example.com?a=1&b=2\"", whitelist);
            assertTrue(result.isAllowed());
            assertEquals("curl", result.getExecutable());
        }

        @Test
        @DisplayName("Should prevent command injection via unquoted URL")
        void testSecurityPreventCommandInjectionViaUrl() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("curl");

            CommandValidator.ValidationResult result =
                    validator.validate("curl http://safe.com&rm -rf /", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("multiple command separators"));
        }

        @Test
        @DisplayName("Should prevent command injection via no-space chaining")
        void testSecurityPreventNoSpaceChaining() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("echo");
            whitelist.add("ls");

            CommandValidator.ValidationResult result =
                    validator.validate("echo test&malicious_command", whitelist);
            assertFalse(result.isAllowed());
            assertTrue(result.getReason().contains("multiple command separators"));
        }

        @Test
        @DisplayName("Should enforce quoting for URLs with special characters")
        void testSecurityEnforceQuotingForUrls() {
            Set<String> whitelist = new HashSet<>();
            whitelist.add("wget");

            CommandValidator.ValidationResult result1 =
                    validator.validate("wget http://example.com?token=abc&user=123", whitelist);
            assertFalse(result1.isAllowed());

            CommandValidator.ValidationResult result2 =
                    validator.validate("wget \"http://example.com?token=abc&user=123\"", whitelist);
            assertTrue(result2.isAllowed());
        }
    }
}
