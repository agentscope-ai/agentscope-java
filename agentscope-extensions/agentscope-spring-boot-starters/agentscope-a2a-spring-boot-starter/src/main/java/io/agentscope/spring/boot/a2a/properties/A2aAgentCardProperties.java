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

package io.agentscope.spring.boot.a2a.properties;

import io.a2a.spec.AgentInterface;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.SecurityScheme;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A2a server agent card properties.
 */
@ConfigurationProperties(Constants.A2A_SERVER_CARD_PREFIX)
public class A2aAgentCardProperties {

    private String name;

    private String description;

    private String url;

    private AgentProvider provider;

    private String version;

    private String documentationUrl;

    private List<String> defaultInputModes;

    private List<String> defaultOutputModes;

    private List<AgentSkill> skills;

    private Map<String, SecurityScheme> securitySchemes;

    private List<Map<String, List<String>>> security;

    private String iconUrl;

    private List<AgentInterface> additionalInterfaces;

    private String preferredTransport;
    ;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public AgentProvider getProvider() {
        return provider;
    }

    public void setProvider(AgentProvider provider) {
        this.provider = provider;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDocumentationUrl() {
        return documentationUrl;
    }

    public void setDocumentationUrl(String documentationUrl) {
        this.documentationUrl = documentationUrl;
    }

    public List<String> getDefaultInputModes() {
        return defaultInputModes;
    }

    public void setDefaultInputModes(List<String> defaultInputModes) {
        this.defaultInputModes = defaultInputModes;
    }

    public List<String> getDefaultOutputModes() {
        return defaultOutputModes;
    }

    public void setDefaultOutputModes(List<String> defaultOutputModes) {
        this.defaultOutputModes = defaultOutputModes;
    }

    public List<AgentSkill> getSkills() {
        return skills;
    }

    public void setSkills(List<AgentSkill> skills) {
        this.skills = skills;
    }

    public Map<String, SecurityScheme> getSecuritySchemes() {
        return securitySchemes;
    }

    public void setSecuritySchemes(Map<String, SecurityScheme> securitySchemes) {
        this.securitySchemes = securitySchemes;
    }

    public List<Map<String, List<String>>> getSecurity() {
        return security;
    }

    public void setSecurity(List<Map<String, List<String>>> security) {
        this.security = security;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public List<AgentInterface> getAdditionalInterfaces() {
        return additionalInterfaces;
    }

    public void setAdditionalInterfaces(List<AgentInterface> additionalInterfaces) {
        this.additionalInterfaces = additionalInterfaces;
    }

    public String getPreferredTransport() {
        return preferredTransport;
    }

    public void setPreferredTransport(String preferredTransport) {
        this.preferredTransport = preferredTransport;
    }
}
