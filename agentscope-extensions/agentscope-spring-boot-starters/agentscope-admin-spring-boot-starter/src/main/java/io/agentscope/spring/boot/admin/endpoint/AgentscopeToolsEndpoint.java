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
package io.agentscope.spring.boot.admin.endpoint;

import io.agentscope.spring.boot.admin.service.AgentInventory;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/** {@code GET /actuator/agentscope-tools}: per-Toolkit-bean list of registered tools. */
@Endpoint(id = "agentscope-tools")
public class AgentscopeToolsEndpoint {

    private final AgentInventory inventory;

    public AgentscopeToolsEndpoint(AgentInventory inventory) {
        this.inventory = inventory;
    }

    @ReadOperation
    public Map<String, List<String>> tools() {
        return inventory.toolsByBean();
    }
}
