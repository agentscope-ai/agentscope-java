/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.web.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ServiceSchemaMainTest {
    @Test
    void deploymentNamesResolveToTheExistingMigrationLedgers() {
        assertThat(ServiceSchemaMain.migrationComponent("agent-service")).isEqualTo("portal");
        assertThat(ServiceSchemaMain.migrationComponent("agent-harness-runtime"))
                .isEqualTo("dataplane");
        assertThat(ServiceSchemaMain.migrationComponent("runtime")).isEqualTo("managed");
    }

    @Test
    void invalidComponentsFailBeforeConnectingToTheDatabase() {
        for (String component : new String[] {null, "", "portal", "unknown"}) {
            assertThatThrownBy(() -> ServiceSchemaMain.migrationComponent(component))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
