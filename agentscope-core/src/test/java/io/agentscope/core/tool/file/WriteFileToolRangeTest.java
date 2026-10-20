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
package io.agentscope.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.agentscope.core.message.ToolResultState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WriteFileToolRangeTest {

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"0,1", "1,0", "3,1", "-1,2", "0,0", "-2,-1"})
    void invalidRangesLeaveExistingFileUntouched(String ranges) throws Exception {
        Path file = directory.resolve("sample.txt");
        String original = "one\ntwo\nthree\n";
        Files.writeString(file, original);
        var result =
                new WriteFileTool(directory.toString())
                        .writeTextFile("sample.txt", "replacement", ranges)
                        .block();
        assertEquals(original, Files.readString(file));
        assertEquals(ToolResultState.ERROR, result.getState());
    }

    @Test
    void replacesAValidRange() throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "one\ntwo\nthree\n");
        var result =
                new WriteFileTool(directory.toString())
                        .writeTextFile("sample.txt", "replacement", "2,2")
                        .block();
        assertNotEquals(ToolResultState.ERROR, result.getState());
        assertEquals("one\nreplacement\nthree", Files.readString(file));
    }

    @Test
    void retainsExistingEndOfFileClamping() throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "one\ntwo\nthree\n");
        var result =
                new WriteFileTool(directory.toString())
                        .writeTextFile("sample.txt", "replacement", "2,99")
                        .block();
        assertNotEquals(ToolResultState.ERROR, result.getState());
        assertEquals("one\nreplacement", Files.readString(file));
    }
}
