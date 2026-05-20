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
package io.agentscope.harness.claw.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal configuration for the agentscope-claw-web admin console.
 *
 * <p>Unlike agentscope-claw, the admin console does not start a ClawBootstrap or any agent
 * runtime. It reads static workspace files (e.g. {@code .agentscope/agentscope.json}) and
 * delegates all live runtime queries to agentscope-claw's read-only REST API via
 * {@link io.agentscope.harness.claw.web.runtime.ClawRuntimeClient}.
 */
@Configuration
public class ClawConfig {

    private static final Logger log = LoggerFactory.getLogger(ClawConfig.class);

    @Value("${claw-web.claw-url:http://localhost:8080}")
    private String clawUrl;

    @Value("${claw-web.workspace:}")
    private String workspaceDir;

    public ClawConfig() {
        log.info("Admin console initialized — runtime data fetched from agentscope-claw");
    }
}
