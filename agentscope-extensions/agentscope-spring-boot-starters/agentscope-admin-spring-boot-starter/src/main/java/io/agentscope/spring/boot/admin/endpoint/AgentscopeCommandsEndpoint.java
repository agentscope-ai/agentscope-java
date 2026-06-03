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

import io.agentscope.spring.boot.admin.command.AdminCommand;
import io.agentscope.spring.boot.admin.command.AdminCommandRegistry;
import java.util.List;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code GET /actuator/agentscope-commands}: machine-readable catalog of every admin command
 * exposed by this process. Equivalent to opencode's CLI {@code /help} dialog, but in JSON for CLI
 * / Studio / Kubernetes operator clients to consume.
 */
@Endpoint(id = "agentscope-commands")
public class AgentscopeCommandsEndpoint {

    private final AdminCommandRegistry registry;

    public AgentscopeCommandsEndpoint(AdminCommandRegistry registry) {
        this.registry = registry;
    }

    @ReadOperation
    public List<AdminCommand> list() {
        return registry.list();
    }
}
