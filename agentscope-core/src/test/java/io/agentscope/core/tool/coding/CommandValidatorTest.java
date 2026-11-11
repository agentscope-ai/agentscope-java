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

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandValidatorTest {

    private final CommandValidator unix = new UnixCommandValidator();
    private final CommandValidator windows = new WindowsCommandValidator();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "echo $(printf marker)",
                "echo `printf marker`",
                "echo \"$(printf marker)\"",
                "echo \"`printf marker`\"",
                "echo $(sh -c 'printf marker|cat')",
                "echo ${IFS}marker",
                "echo \"$SHELL\"",
                "echo $((1 + 1))",
                "echo marker > output.txt",
                "echo marker >> output.txt",
                "echo marker 2> output.txt",
                "echo < input.txt",
                "echo <(printf marker)",
                "echo >(cat)",
                "echo (printf marker)",
                "echo marker)",
                "echo {marker,other}",
                "echo marker}",
                "echo marker; printf other",
                "echo marker | cat",
                "echo marker && printf other",
                "echo marker\nprintf other",
                "echo '\\'$(printf marker)",
                "echo '\\'; printf marker",
                "echo \"unterminated",
                "echo 'unterminated",
                "echo marker\\"
            })
    void unixRequiresApprovalForShellSyntax(String command) {
        assertFalse(unix.validate(command, Set.of("echo")).isAllowed(), command);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "echo hello",
                "echo 'hello world'",
                "echo \"hello world\"",
                "echo '$(printf marker) `printf marker` $SHELL > < ( ) { } & | ;'",
                "echo \"literal > < ( ) { } & | ;\"",
                "echo \\$SHELL",
                "echo \"\\$SHELL\"",
                "echo \\`marker\\`",
                "echo escaped\\;separator",
                "echo '\\' literal",
                "'echo'",
                "\"echo\"",
                "'echo' hello",
                "\"echo\" hello",
                "'echo'\thello",
                "\"echo\"\thello"
            })
    void unixAllowsLiteralArguments(String command) {
        assertTrue(unix.validate(command, Set.of("echo")).isAllowed(), command);
        assertFalse(unix.containsMultipleCommands(command), command);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "echo %PATH%",
                "echo \"%PATH%\"",
                "echo ^%PATH^%",
                "echo !PATH!",
                "echo \"!PATH!\"",
                "echo ^!PATH^!",
                "echo 50% done",
                "echo \"50% done\"",
                "echo done!",
                "echo \"done!\"",
                "echo marker > output.txt",
                "echo marker >> output.txt",
                "echo < input.txt",
                "echo (marker)",
                "echo marker)",
                "echo marker & echo other",
                "echo marker | more",
                "echo marker\necho other",
                "echo marker\recho other",
                "echo \"^\" & echo other",
                "echo 'marker & echo other'",
                "echo \"unterminated",
                "echo marker^"
            })
    void windowsRequiresApprovalForShellSyntax(String command) {
        assertFalse(windows.validate(command, Set.of("echo")).isAllowed(), command);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "echo hello",
                "ECHO hello",
                "echo \"hello world\"",
                "echo \"literal > < ( ) & |\"",
                "echo ^& ^| ^> ^< ^( ^)",
                "echo hello;world",
                "echo \"^\" literal",
                "\"echo\"",
                "\"echo\"\thello"
            })
    void windowsAllowsLiteralArguments(String command) {
        assertTrue(windows.validate(command, Set.of("echo")).isAllowed(), command);
        assertFalse(windows.containsMultipleCommands(command), command);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void separatorDetectionHandlesMissingCommands(String command) {
        for (CommandValidator validator : List.of(unix, windows)) {
            assertFalse(validator.containsMultipleCommands(command));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"echo marker & echo other", "echo marker | more", "echo marker\necho other"})
    void separatorDetectionFindsUnquotedSeparators(String command) {
        for (CommandValidator validator : List.of(unix, windows)) {
            assertTrue(validator.containsMultipleCommands(command), command);
        }
    }

    @Test
    void unixSeparatorDetectionHandlesSemicolonsAndSingleQuotedBackslashes() {
        assertTrue(unix.containsMultipleCommands("echo marker; printf other"));
        assertTrue(unix.containsMultipleCommands("echo '\\'; printf other"));
    }

    @Test
    void separatorDetectionDoesNotReplaceApprovalValidation() {
        // The public separator check retains its narrower contract; validation also checks
        // expansions, redirection, grouping, and incomplete quoting or escaping.
        for (String command :
                List.of(
                        "echo $SHELL",
                        "echo `printf marker`",
                        "echo $(printf marker)",
                        "echo marker > output.txt",
                        "echo 'unterminated",
                        "echo marker\\")) {
            assertFalse(unix.containsMultipleCommands(command), command);
            assertFalse(unix.validate(command, Set.of("echo")).isAllowed(), command);
        }
        for (String command :
                List.of(
                        "echo %PATH%",
                        "echo !PATH!",
                        "echo (marker)",
                        "echo marker > output.txt",
                        "echo \"unterminated",
                        "echo marker^")) {
            assertFalse(windows.containsMultipleCommands(command), command);
            assertFalse(windows.validate(command, Set.of("echo")).isAllowed(), command);
        }
    }

    @Test
    void missingWhitelistRequiresApproval() {
        for (CommandValidator validator : List.of(unix, windows)) {
            assertFalse(validator.validate("echo hello", null).isAllowed());
            assertFalse(validator.validate("echo hello", Set.of()).isAllowed());
        }
    }

    @Test
    void nonWhitelistedCommandsStillRequireApproval() {
        for (CommandValidator validator : List.of(unix, windows)) {
            assertFalse(validator.validate("unlisted hello", Set.of("echo")).isAllowed());
            assertFalse(validator.validate("./unlisted hello", Set.of("echo")).isAllowed());
            assertFalse(validator.validate("\"echo\"unlisted hello", Set.of("echo")).isAllowed());
        }
        assertFalse(windows.validate(".\\unlisted hello", Set.of("echo")).isAllowed());
        assertFalse(unix.validate("'echo'unlisted hello", Set.of("echo")).isAllowed());
    }

    @Test
    void whitelistedRelativePathsRemainSubjectToValidation() {
        for (CommandValidator validator : List.of(unix, windows)) {
            assertTrue(validator.validate("./script hello", Set.of("./script")).isAllowed());
            assertFalse(validator.validate("./../script", Set.of("./../script")).isAllowed());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"echo\" \"literal & echo marker\"",
                "\"echo\" \"literal > marker.txt\"",
                "\"echo\" \"literal | more\"",
                "\"echo\" \"literal ^& echo marker\""
            })
    void windowsRequiresApprovalWhenOuterQuoteStrippingExposesOperators(String command) {
        assertFalse(windows.validate(command, Set.of("echo")).isAllowed(), command);
    }

    @Test
    void windowsRequiresApprovalForParenthesesInQuotedExecutablePaths() {
        String executable = "C:\\Program Files (x86)\\tool";

        assertFalse(
                windows.validate("\"" + executable + ".exe\" arg", Set.of(executable)).isAllowed());
    }
}
