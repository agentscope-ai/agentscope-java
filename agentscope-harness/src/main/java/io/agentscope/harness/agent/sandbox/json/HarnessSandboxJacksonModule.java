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
package io.agentscope.harness.agent.sandbox.json;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;

/**
 * Registers Jackson polymorphic subtypes for {@link io.agentscope.harness.agent.sandbox.SandboxState}.
 * Official backends add their {@link NamedType} entries here; callers may also use {@link
 * com.fasterxml.jackson.databind.ObjectMapper#registerSubtypes} for application-specific state
 * classes without editing {@link io.agentscope.harness.agent.sandbox.SandboxState}.
 */
public final class HarnessSandboxJacksonModule extends SimpleModule {

    public HarnessSandboxJacksonModule() {
        super("agentscope-harness-sandbox");
        registerSubtypes(new NamedType(DockerSandboxState.class, "docker"));
    }
}
