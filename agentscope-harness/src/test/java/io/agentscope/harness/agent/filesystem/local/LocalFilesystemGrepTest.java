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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

class LocalFilesystemGrepTest {

    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "plain needle",
                "needle = \"hello\";",
                "needle C:\\work\\file",
                "\tneedle\tvalue",
                "needle 中文 🚀",
                "needle literal \\n"
            })
    void grepPreservesMatchingLine(String text) throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), text + "\n");
        String output = event(file, text + "\n", 1);

        GrepResult result = grepWithOutput(output);

        assertTrue(result.isSuccess());
        assertEquals(List.of(new GrepMatch("/source.txt", 1, text)), result.matches());
    }

    @Test
    void grepAcceptsJsonWhitespaceAndDecodesEscapedPath() throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), "needle\n");
        String output =
                event(file, "needle\r\n", 3)
                        .replace("source.txt", "s\\u006furce.txt")
                        .replace("\":", "\": ");

        GrepResult result = grepWithOutput(output);

        assertEquals(List.of(new GrepMatch("/source.txt", 3, "needle")), result.matches());
    }

    @Test
    void grepSkipsNonMatchAndMalformedEvents() throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), "needle\n");
        String output =
                "{\"type\":\"begin\",\"data\":{}}\n"
                        + "not json\n"
                        + "{\"type\":\"match\",\"data\":{}}\n"
                        + event(file, "needle\n", 1)
                        + "\n{\"type\":\"summary\",\"data\":{}}\n";

        GrepResult result = grepWithOutput(output);

        assertEquals(List.of(new GrepMatch("/source.txt", 1, "needle")), result.matches());
    }

    @Test
    void grepDecodesBase64Lines() throws Exception {
        String text = "needle \"hello\";\tC:\\work";
        Path file = Files.writeString(root.resolve("source.txt"), text + "\n");
        String encoded =
                Base64.getEncoder().encodeToString((text + "\n").getBytes(StandardCharsets.UTF_8));

        GrepResult result = grepWithOutput(event(file, Map.of("bytes", encoded), 1));

        assertEquals(List.of(new GrepMatch("/source.txt", 1, text)), result.matches());
    }

    @Test
    void grepKeepsMatchesForNonUtf8Lines() throws Exception {
        byte[] content = {'n', 'e', 'e', 'd', 'l', 'e', ' ', (byte) 0xff, '\n'};
        Path file = Files.write(root.resolve("source.txt"), content);
        String encoded = Base64.getEncoder().encodeToString(content);

        GrepResult result = grepWithOutput(event(file, Map.of("bytes", encoded), 1));

        assertEquals(List.of(new GrepMatch("/source.txt", 1, "needle \ufffd")), result.matches());
    }

    @Test
    void grepSkipsMalformedBase64Lines() throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), "needle\n");
        String output =
                event(file, Map.of("bytes", "not-base64!"), 1) + "\n" + event(file, "needle\n", 2);

        GrepResult result = grepWithOutput(output);

        assertEquals(List.of(new GrepMatch("/source.txt", 2, "needle")), result.matches());
    }

    private GrepResult grepWithOutput(String output) throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream())
                .thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor()).thenReturn(0);
        try (MockedConstruction<ProcessBuilder> ignored =
                mockConstruction(
                        ProcessBuilder.class,
                        (builder, context) -> when(builder.start()).thenReturn(process))) {
            return new LocalFilesystem(root, true, 10)
                    .grep(RuntimeContext.empty(), "needle", ".", null);
        }
    }

    private String event(Path file, String text, int line) {
        return event(file, Map.of("text", text), line);
    }

    private String event(Path file, Map<String, String> lines, int line) {
        return JsonUtils.getJsonCodec()
                .toJson(
                        Map.of(
                                "type",
                                "match",
                                "data",
                                Map.of(
                                        "path", Map.of("text", file.toString()),
                                        "lines", lines,
                                        "line_number", line)));
    }
}
