/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.portal;

import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.runtime.managed.RocketMqManagedMessageBus;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Explicit dependency doubles for Portal tests that exercise HTTP/JPA rather than transport. */
abstract class PortalInfrastructureTestSupport {
    @MockitoBean(enforceOverride = true)
    RocketMqManagedMessageBus managedMessageBus;

    @MockitoBean(enforceOverride = true)
    RedisManagedInfrastructure managedRedis;

    @Autowired DataSource managedTestDatabase;

    @BeforeEach
    void initializeManagedFixture() {
        ManagedAdmissionStore.initialize(managedTestDatabase);
    }
}
