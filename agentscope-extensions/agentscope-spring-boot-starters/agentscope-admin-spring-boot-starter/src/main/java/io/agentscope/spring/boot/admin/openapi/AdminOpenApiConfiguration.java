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
package io.agentscope.spring.boot.admin.openapi;

import io.agentscope.spring.boot.admin.command.AdminCommand;
import io.agentscope.spring.boot.admin.command.AdminCommandRegistry;
import io.agentscope.spring.boot.admin.command.CommandPlane;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the admin starter into springdoc-openapi <em>only when springdoc is on the classpath</em>.
 *
 * <p>Consumers opt in by adding {@code springdoc-openapi-starter-webmvc-ui} to their app pom (it
 * is declared as {@code optional} here). When present:
 *
 * <ul>
 *   <li>A {@link GroupedOpenApi} named {@code agentscope-admin} surfaces only the admin REST
 *       routes, so the admin surface shows up as a separate tab in Swagger UI.
 *   <li>An {@link OpenAPI} bean (when none is present) sets sensible title / description /
 *       version metadata and registers one OpenAPI {@link Tag} per {@code AdminCommand}
 *       {@code category} — so Swagger UI groups routes by Session / Agent / System.
 * </ul>
 *
 * <p>Loaded lazily by {@link io.agentscope.spring.boot.admin.AgentscopeAdminAutoConfiguration};
 * marked {@link ConditionalOnClass} on the springdoc tag so the class never fails to load when
 * springdoc is absent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({GroupedOpenApi.class, OpenAPI.class})
public class AdminOpenApiConfiguration {

    /** Group all admin routes under their own Swagger UI definition. */
    @Bean
    public GroupedOpenApi agentscopeAdminOpenApi(AdminProperties properties) {
        String base = properties.getBasePath();
        return GroupedOpenApi.builder()
                .group("agentscope-admin")
                .displayName("AgentScope Admin")
                .pathsToMatch(base + "/**")
                .pathsToExclude(base + "/internal/**")
                .build();
    }

    /**
     * Provide a default top-level {@link OpenAPI} bean if the consumer has not declared one.
     * Tags are seeded from the {@link AdminCommandRegistry} so each category Session/Agent/System
     * gets its own labelled group in Swagger UI.
     */
    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI agentscopeAdminOpenApiDefinition(
            AdminProperties properties, AdminCommandRegistry registry) {
        Set<String> categories = new LinkedHashSet<>();
        for (AdminCommand cmd : registry.list()) {
            if (cmd.plane() == CommandPlane.DATA) {
                categories.add(cmd.category());
            }
        }
        List<Tag> tags = new ArrayList<>();
        for (String c : categories) {
            tags.add(new Tag().name(c).description("AgentScope admin — " + c));
        }
        return new OpenAPI()
                .info(
                        new Info()
                                .title("AgentScope Admin API")
                                .version("v1")
                                .description(
                                        "Per-session admin / ops actions over agentscope-java agents. "
                                                + "Discover the full command catalog at "
                                                + "GET /actuator/agentscope-commands."))
                .externalDocs(
                        new ExternalDocumentation()
                                .description("Plan & gap analysis")
                                .url(
                                        "https://github.com/agentscope-ai/agentscope-java/blob/main/ADMIN_OPS_API_PLAN.md"))
                .addServersItem(new Server().url(properties.getBasePath()).description("Admin base path"))
                .tags(tags);
    }
}
