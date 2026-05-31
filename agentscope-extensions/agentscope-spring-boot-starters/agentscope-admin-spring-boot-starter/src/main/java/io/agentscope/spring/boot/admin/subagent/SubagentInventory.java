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
package io.agentscope.spring.boot.admin.subagent;

import java.util.List;

/**
 * SPI for enumerating the subagents available to this process.
 *
 * <p>Why an SPI: {@code SubagentsMiddleware} keeps its registration list private and there is no
 * cross-version accessor on {@code HarnessAgent}, so the admin starter cannot reach into the
 * built agent reliably. Applications opt in by exposing {@link io.agentscope.harness.agent.middleware.SubagentEntry}
 * or {@link io.agentscope.harness.agent.subagent.SubagentDeclaration} as Spring beans (picked up
 * by the default {@link SpringSubagentInventory}), or by providing their own
 * {@code SubagentInventory} implementation.
 */
public interface SubagentInventory {

    /** All subagent registrations known to the process. May be empty. */
    List<SubagentDescriptor> list();
}
