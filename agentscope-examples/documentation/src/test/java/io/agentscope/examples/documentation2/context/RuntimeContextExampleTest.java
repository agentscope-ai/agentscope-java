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
package io.agentscope.examples.documentation2.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RuntimeContextExampleTest {

    @Test
    void directDashScopeBuilderUsesApiModelName() throws IOException {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/io/agentscope/examples/documentation2/context/"
                                        + "RuntimeContextExample.java"));

        assertTrue(source.contains(".modelName(\"qwen-plus\")"));
        assertFalse(source.contains(".modelName(\"dashscope:qwen-plus\")"));
    }
}
