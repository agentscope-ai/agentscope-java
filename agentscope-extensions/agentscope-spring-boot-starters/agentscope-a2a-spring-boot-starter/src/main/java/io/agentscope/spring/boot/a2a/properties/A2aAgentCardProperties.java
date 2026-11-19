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

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A2a server agent card properties.
 *
 * <p>Nested values use types of this starter rather than A2A SDK types, so the configuration keeps
 * binding when the SDK types change. Property names follow the fields of the A2A agent card.
 */
@ConfigurationProperties(Constants.A2A_SERVER_CARD_PREFIX)
public class A2aAgentCardProperties {

    private String name;

    private String description;

    private String url;

    private Provider provider;

    private String version;

    private String documentationUrl;

    private List<String> defaultInputModes;

    private List<String> defaultOutputModes;

    private List<Skill> skills;

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

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
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

    public List<Skill> getSkills() {
        return skills;
    }

    public void setSkills(List<Skill> skills) {
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

    /**
     * The organization providing the agent.
     *
     * @param organization organization name
     * @param url organization website
     */
    public record Provider(String organization, String url) {}

    /**
     * A skill the agent can perform.
     *
     * @param id unique skill identifier
     * @param name skill name
     * @param description skill description
     * @param tags keywords describing the skill
     * @param examples example prompts
     * @param inputModes supported input media types, overriding the card defaults
     * @param outputModes supported output media types, overriding the card defaults
     * @param security security requirements for this skill
     */
    public record Skill(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputModes,
            List<String> outputModes,
            List<Map<String, List<String>>> security) {}

    /**
     * A security scheme declared by the agent card. Which fields apply depends on {@code type}:
     *
     * <ul>
     *   <li>{@code apiKey}: {@code name} and {@code in} ({@code header}, {@code query} or {@code
     *       cookie})
     *   <li>{@code http}: {@code scheme} and optionally {@code bearerFormat}
     *   <li>{@code oauth2}: {@code flows} and optionally {@code oauth2MetadataUrl}
     *   <li>{@code openIdConnect}: {@code openIdConnectUrl}
     *   <li>{@code mutualTLS}: no other fields
     * </ul>
     *
     * @param type scheme type
     * @param description scheme description
     * @param name name of the header, query parameter or cookie holding the API key
     * @param in location of the API key
     * @param scheme HTTP authentication scheme, such as {@code bearer}
     * @param bearerFormat format of the bearer token, such as {@code JWT}
     * @param flows OAuth 2.0 flows
     * @param oauth2MetadataUrl OAuth 2.0 authorization server metadata URL
     * @param openIdConnectUrl OpenID Connect discovery URL
     */
    public record SecurityScheme(
            String type,
            String description,
            String name,
            String in,
            String scheme,
            String bearerFormat,
            OAuthFlows flows,
            String oauth2MetadataUrl,
            String openIdConnectUrl) {}

    /**
     * OAuth 2.0 flows of an {@code oauth2} security scheme.
     *
     * @param authorizationCode authorization code flow
     * @param clientCredentials client credentials flow
     * @param deviceCode device authorization flow
     * @param implicit not supported: A2A 1.0 has no implicit flow, setting it fails the startup
     * @param password not supported: A2A 1.0 has no password flow, setting it fails the startup
     */
    public record OAuthFlows(
            OAuthFlow authorizationCode,
            OAuthFlow clientCredentials,
            OAuthFlow deviceCode,
            OAuthFlow implicit,
            OAuthFlow password) {}

    /**
     * One OAuth 2.0 flow. Which fields apply depends on the flow.
     *
     * @param authorizationUrl authorization URL of the authorization code flow
     * @param tokenUrl token URL
     * @param refreshUrl refresh URL
     * @param deviceAuthorizationUrl device authorization URL of the device code flow
     * @param pkceRequired whether the authorization code flow requires PKCE
     * @param scopes available scopes and their descriptions
     */
    public record OAuthFlow(
            String authorizationUrl,
            String tokenUrl,
            String refreshUrl,
            String deviceAuthorizationUrl,
            Boolean pkceRequired,
            Map<String, String> scopes) {}

    /**
     * A transport interface the agent is reachable at.
     *
     * @param transport transport protocol, such as {@code JSONRPC}
     * @param url endpoint URL
     */
    public record AgentInterface(String transport, String url) {}
}
