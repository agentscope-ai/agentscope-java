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
package io.agentcore.managed.server.agentservice.sessionservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMcpDiscovery;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Catalog persistence is available even when background consumption is disabled. */
@Configuration(proxyBeanMethods = false)
public class SessionCoordinationConfiguration {
    @Bean
    ManagedMcpCatalog managedMcpCatalog(
            DataSource source,
            ObjectMapper mapper,
            VaultCrypto crypto,
            ManagedJournalStore journal,
            @Value("${managed-agents.runtime.mcp.discovery-cache-ttl:60s}") Duration cacheTtl,
            @Value("${managed-agents.runtime.mcp.discovery-cache-entries:4096}") int cacheEntries) {
        var catalog =
                new ManagedMcpCatalog(
                        source,
                        mapper,
                        crypto,
                        journal.transport(),
                        new ManagedMcpDiscovery(cacheTtl, cacheEntries));
        return catalog;
    }
}
