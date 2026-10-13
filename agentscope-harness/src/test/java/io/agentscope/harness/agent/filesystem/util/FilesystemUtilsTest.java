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
package io.agentscope.harness.agent.filesystem.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FilesystemUtilsTest {

    @Test
    void performStringReplacement_emptyOldString_returnsErrorWithoutHanging() {
        Object[] result =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(1),
                        () ->
                                FilesystemUtils.performStringReplacement(
                                        "content", "", "replacement", false));

        assertEquals(1, result.length);
        assertEquals("Error: old_string must not be empty", result[0]);
    }

    @Test
    void performStringReplacement_nullOldString_returnsError() {
        Object[] result =
                FilesystemUtils.performStringReplacement("content", null, "replacement", false);

        assertEquals(1, result.length);
        assertEquals("Error: old_string must not be empty", result[0]);
    }
}
