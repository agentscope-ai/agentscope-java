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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link HarnessAgent#resolveDefaultWorkspace()} — the fallback used when
 * {@code workspace(...)} is not set explicitly on the builder.
 *
 * <p>The environment-variable branch ({@code AGENTSCOPE_WORKSPACE}) cannot be exercised here
 * because a JVM cannot mutate its own environment; it is documented and covered by the
 * system-property branch which shares the same resolution helper.
 */
class WorkspaceResolutionTest {

    @TempDir Path customWorkspace;

    private String previousWorkspaceProperty;

    @BeforeEach
    void saveProperty() {
        previousWorkspaceProperty = System.getProperty(HarnessAgent.WORKSPACE_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previousWorkspaceProperty != null) {
            System.setProperty(HarnessAgent.WORKSPACE_PROPERTY, previousWorkspaceProperty);
        } else {
            System.clearProperty(HarnessAgent.WORKSPACE_PROPERTY);
        }
    }

    @Test
    void systemProperty_overridesDefault() {
        System.setProperty(HarnessAgent.WORKSPACE_PROPERTY, customWorkspace.toString());
        assertEquals(customWorkspace, HarnessAgent.resolveDefaultWorkspace());
    }

    @Test
    void blankSystemProperty_fallsBackToDefault() {
        System.setProperty(HarnessAgent.WORKSPACE_PROPERTY, "   ");
        assertEquals(expectedDefault(), HarnessAgent.resolveDefaultWorkspace());
    }

    @Test
    void unsetSystemProperty_fallsBackToDefault() {
        System.clearProperty(HarnessAgent.WORKSPACE_PROPERTY);
        // Only meaningful when the env var is also unset in the test environment.
        if (System.getenv(HarnessAgent.WORKSPACE_ENV) == null) {
            assertEquals(expectedDefault(), HarnessAgent.resolveDefaultWorkspace());
        }
    }

    private static Path expectedDefault() {
        return Paths.get(System.getProperty("user.dir")).resolve(".agentscope/workspace");
    }
}
