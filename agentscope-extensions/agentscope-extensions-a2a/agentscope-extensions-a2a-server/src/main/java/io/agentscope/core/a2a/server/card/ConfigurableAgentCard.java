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

package io.agentscope.core.a2a.server.card;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;

/**
 * Configurable attribute for export agent card of this agent.
 *
 * <p>
 * These attributes are used to generate agent card which export by well-known URL and register to A2A Registries.
 * </p>
 */
public class ConfigurableAgentCard {

    private final String name;

    private final String description;

    private final AgentProvider provider;

    private final String version;

    private final String documentationUrl;

    private final List<String> defaultInputModes;

    private final List<String> defaultOutputModes;

    private final List<AgentSkill> skills;

    private final Map<String, SecurityScheme> securitySchemes;

    private final List<SecurityRequirement> securityRequirements;

    private final String iconUrl;

    private final List<AgentInterface> supportedInterfaces;

    private ConfigurableAgentCard(
            String name,
            String description,
            AgentProvider provider,
            String version,
            String documentationUrl,
            List<String> defaultInputModes,
            List<String> defaultOutputModes,
            List<AgentSkill> skills,
            Map<String, SecurityScheme> securitySchemes,
            List<SecurityRequirement> securityRequirements,
            String iconUrl,
            List<AgentInterface> supportedInterfaces) {
        this.name = name;
        this.description = description;
        this.provider = provider;
        this.version = version;
        this.documentationUrl = documentationUrl;
        this.defaultInputModes = defaultInputModes;
        this.defaultOutputModes = defaultOutputModes;
        this.skills = skills;
        this.securitySchemes = securitySchemes;
        this.securityRequirements = securityRequirements;
        this.iconUrl = iconUrl;
        this.supportedInterfaces = supportedInterfaces;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public AgentProvider getProvider() {
        return provider;
    }

    public String getVersion() {
        return version;
    }

    public String getDocumentationUrl() {
        return documentationUrl;
    }

    public List<String> getDefaultInputModes() {
        return defaultInputModes;
    }

    public List<String> getDefaultOutputModes() {
        return defaultOutputModes;
    }

    public List<AgentSkill> getSkills() {
        return skills;
    }

    public Map<String, SecurityScheme> getSecuritySchemes() {
        return securitySchemes;
    }

    public List<SecurityRequirement> getSecurityRequirements() {
        return securityRequirements;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public List<AgentInterface> getSupportedInterfaces() {
        return supportedInterfaces;
    }

    public static class Builder {

        protected String name;

        protected String description;

        protected AgentProvider provider;

        protected String version;

        protected String documentationUrl;

        protected List<String> defaultInputModes;

        protected List<String> defaultOutputModes;

        protected List<AgentSkill> skills;

        protected Map<String, SecurityScheme> securitySchemes;

        protected List<SecurityRequirement> securityRequirements;

        protected String iconUrl;

        protected List<AgentInterface> supportedInterfaces;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder provider(AgentProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder documentationUrl(String documentationUrl) {
            this.documentationUrl = documentationUrl;
            return this;
        }

        public Builder defaultInputModes(List<String> defaultInputModes) {
            this.defaultInputModes = defaultInputModes;
            return this;
        }

        public Builder defaultOutputModes(List<String> defaultOutputModes) {
            this.defaultOutputModes = defaultOutputModes;
            return this;
        }

        public Builder skills(List<AgentSkill> skills) {
            this.skills = skills;
            return this;
        }

        public Builder securitySchemes(Map<String, SecurityScheme> securitySchemes) {
            this.securitySchemes = securitySchemes;
            return this;
        }

        public Builder securityRequirements(List<SecurityRequirement> securityRequirements) {
            this.securityRequirements = securityRequirements;
            return this;
        }

        public Builder iconUrl(String iconUrl) {
            this.iconUrl = iconUrl;
            return this;
        }

        public Builder supportedInterfaces(List<AgentInterface> supportedInterfaces) {
            this.supportedInterfaces = supportedInterfaces;
            return this;
        }

        public ConfigurableAgentCard build() {
            return new ConfigurableAgentCard(
                    name,
                    description,
                    provider,
                    version,
                    documentationUrl,
                    defaultInputModes,
                    defaultOutputModes,
                    skills,
                    securitySchemes,
                    securityRequirements,
                    iconUrl,
                    supportedInterfaces);
        }
    }
}
