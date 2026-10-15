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
package io.agentscope.spring.boot.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.a2a.spec.APIKeySecurityScheme;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.HTTPAuthSecurityScheme;
import io.a2a.spec.OAuth2SecurityScheme;
import io.a2a.spec.OAuthFlows;
import io.a2a.spec.OpenIdConnectSecurityScheme;
import io.a2a.spec.SecurityScheme;
import io.a2a.util.Utils;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.spring.boot.a2a.controller.A2aJsonRpcController;
import io.agentscope.spring.boot.a2a.controller.AgentCardController;
import io.agentscope.spring.boot.a2a.listener.ServerReadyListener;
import io.agentscope.spring.boot.a2a.properties.A2aAgentCardProperties;
import io.agentscope.spring.boot.a2a.properties.A2aCommonProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.context.ConfigurableWebApplicationContext;

/**
 * Tests for {@link AgentscopeA2aAutoConfiguration}.
 *
 * <p>These tests verify that the auto-configuration creates the expected beans under different
 * property setups.
 */
class AgentscopeA2aAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                    .withBean(
                            ReActAgent.class, () -> ReActAgent.builder().name("mockAgent").build())
                    .withPropertyValues("agentscope.a2a.server.enabled=true", "server.port=8080");

    @Test
    void shouldCreateDefaultBeansWhenEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AgentRunner.class);
                    assertThat(context).hasSingleBean(AgentScopeA2aServer.class);
                    assertThat(context).hasSingleBean(AgentCardController.class);
                    assertThat(context).hasSingleBean(A2aJsonRpcController.class);
                });
    }

    @Test
    void shouldNotCreateA2aBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("agentscope.a2a.server.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(AgentScopeA2aServer.class);
                            assertThat(context).doesNotHaveBean(AgentCardController.class);
                            assertThat(context).doesNotHaveBean(A2aJsonRpcController.class);
                        });
    }

    @Test
    void shouldCreateAgentRunnerWithReActAgent() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(AgentRunner.class));
    }

    @Test
    void shouldCreateAgentRunnerWithReActAgentBuilder() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                .withBean(ReActAgent.Builder.class, () -> ReActAgent.builder().name("mockAgent"))
                .withPropertyValues("agentscope.a2a.server.enabled=true", "server.port=8080")
                .run(context -> assertThat(context).hasSingleBean(AgentRunner.class));
    }

    @Test
    void shouldCreateWithCustomAgentRunner() {
        AgentRunner mockRunner = mock(AgentRunner.class);
        when(mockRunner.getAgentName()).thenReturn("mock Agent");
        when(mockRunner.getAgentDescription()).thenReturn("mock Description");
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                .withBean(AgentRunner.class, () -> mockRunner)
                .withPropertyValues("agentscope.a2a.server.enabled=true", "server.port=8080")
                .run(context -> assertThat(context).hasSingleBean(AgentRunner.class));
    }

    @Test
    void shouldCreateControllersWhenA2aServerExists() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AgentCardController.class);
                    assertThat(context).hasSingleBean(A2aJsonRpcController.class);
                });
    }

    @Test
    void shouldBindA2aProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.enabled=true",
                        "agentscope.a2a.server.card.name=Test Agent",
                        "agentscope.a2a.server.card.description=Test Description")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(A2aCommonProperties.class);
                            assertThat(context).hasSingleBean(A2aAgentCardProperties.class);

                            A2aAgentCardProperties cardProps =
                                    context.getBean(A2aAgentCardProperties.class);
                            assertThat(cardProps.getName()).isEqualTo("Test Agent");
                            assertThat(cardProps.getDescription()).isEqualTo("Test Description");

                            assertThat(context).hasSingleBean(AgentCardController.class);
                            AgentCardController controller =
                                    context.getBean(AgentCardController.class);
                            AgentCard agentCard =
                                    Utils.OBJECT_MAPPER.readValue(
                                            controller.getAgentCard().getBody(), AgentCard.class);
                            assertThat(agentCard.name()).isEqualTo("Test Agent");
                            assertThat(agentCard.description()).isEqualTo("Test Description");
                        });
    }

    @Test
    void shouldStartSuccessWithoutServerProperties() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build());
        // In new version, the port has default value 8080, follow the spring boot web default
        // value.
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldStartSuccessWithAddressProperties() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build())
                        .withPropertyValues("server.address=localhost");
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    AgentScopeA2aServer server = context.getBean(AgentScopeA2aServer.class);
                    AgentCard agentCard = server.getAgentCard();
                    Assertions.assertEquals("http://localhost:8080", agentCard.url());
                });
    }

    @Test
    void shouldIncludeContextPathInAgentCardUrl() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build())
                        .withPropertyValues(
                                "server.address=192.168.1.100",
                                "server.port=8081",
                                "server.servlet.context-path=/api");
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    AgentScopeA2aServer server = context.getBean(AgentScopeA2aServer.class);
                    AgentCard agentCard = server.getAgentCard();
                    Assertions.assertEquals("http://192.168.1.100:8081/api", agentCard.url());
                    // Verify additional interfaces also contain the context-path
                    assertThat(agentCard.additionalInterfaces()).isNotEmpty();
                    assertThat(agentCard.additionalInterfaces().get(0).url())
                            .isEqualTo("http://192.168.1.100:8081/api");
                });
    }

    @Test
    void shouldGenerateCorrectUrlWithContextPath() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build())
                        .withPropertyValues("server.servlet.context-path=/myapp/v1");
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    AgentScopeA2aServer server = context.getBean(AgentScopeA2aServer.class);
                    AgentCard agentCard = server.getAgentCard();
                    // URL should contain the context-path
                    assertThat(agentCard.url()).contains("/myapp/v1");
                });
    }

    @Test
    void shouldCallbackServerReadyListener() {
        AgentScopeA2aServer mockServer = mock(AgentScopeA2aServer.class);
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(AgentScopeA2aServer.class, () -> mockServer);
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ServerReadyListener.class);
                    SpringApplication mockApplication = mock(SpringApplication.class);
                    ApplicationReadyEvent event =
                            new ApplicationReadyEvent(
                                    mockApplication,
                                    null,
                                    context.getSourceApplicationContext(
                                            ConfigurableWebApplicationContext.class),
                                    Duration.ofMillis(10));
                    context.publishEvent(event);
                    verify(mockServer).postEndpointReady();
                });
    }

    @Test
    void shouldStartWithA2aServerProperties() {
        WebApplicationContextRunner contextRunner =
                new WebApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(AgentscopeA2aAutoConfiguration.class))
                        .withBean(
                                ReActAgent.class,
                                () -> ReActAgent.builder().name("mockAgent").build())
                        .withPropertyValues(
                                "agentscope.a2a.server.complete-with-message=true",
                                "agentscope.a2a.server.require-inner-message=true");
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(A2aCommonProperties.class);
                    A2aCommonProperties a2aCommonProperties =
                            context.getBean(A2aCommonProperties.class);
                    Assertions.assertTrue(a2aCommonProperties.isCompleteWithMessage());
                    Assertions.assertTrue(a2aCommonProperties.isRequireInnerMessage());
                });
    }

    @Test
    void shouldServeConfiguredSecuritySchemes() {
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.card.security-schemes.apiKey.type=apiKey",
                        "agentscope.a2a.server.card.security-schemes.apiKey.name=X-API-Key",
                        "agentscope.a2a.server.card.security-schemes.apiKey.in=header",
                        "agentscope.a2a.server.card.security-schemes.bearer.type=http",
                        "agentscope.a2a.server.card.security-schemes.bearer.scheme=bearer",
                        "agentscope.a2a.server.card.security-schemes.bearer.bearer-format=JWT",
                        "agentscope.a2a.server.card.security-schemes.oauth.type=oauth2",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.authorization-code.authorization-url=https://auth/authorize",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.authorization-code.token-url=https://auth/token",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.authorization-code.scopes.read=Read",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.client-credentials.token-url=https://auth/token",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.client-credentials.scopes.read=Read",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.implicit.authorization-url=https://auth/authorize",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.implicit.scopes.read=Read",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.password.token-url=https://auth/token",
                        "agentscope.a2a.server.card.security-schemes.oauth.flows.password.scopes.read=Read",
                        "agentscope.a2a.server.card.security-schemes.machine.type=oauth2",
                        "agentscope.a2a.server.card.security-schemes.machine.flows.client-credentials.token-url=https://auth/token",
                        "agentscope.a2a.server.card.security-schemes.machine.flows.client-credentials.scopes.read=Read",
                        "agentscope.a2a.server.card.security-schemes.oidc.type=openIdConnect",
                        "agentscope.a2a.server.card.security-schemes.oidc.open-id-connect-url=https://auth/.well-known/openid-configuration",
                        "agentscope.a2a.server.card.security-schemes.mtls.type=mutualTLS",
                        "agentscope.a2a.server.card.security-schemes.mtls.description=Client"
                                + " certificate",
                        "agentscope.a2a.server.card.security[0].oauth[0]=read")
                .run(
                        context -> {
                            AgentCard card = servedAgentCard(context);
                            Map<String, SecurityScheme> schemes = card.securitySchemes();
                            assertThat(schemes)
                                    .containsOnlyKeys(
                                            "apiKey", "bearer", "oauth", "machine", "oidc", "mtls");

                            APIKeySecurityScheme apiKey =
                                    (APIKeySecurityScheme) schemes.get("apiKey");
                            assertThat(apiKey.getName()).isEqualTo("X-API-Key");
                            assertThat(apiKey.getIn()).isEqualTo("header");

                            HTTPAuthSecurityScheme bearer =
                                    (HTTPAuthSecurityScheme) schemes.get("bearer");
                            assertThat(bearer.getScheme()).isEqualTo("bearer");
                            assertThat(bearer.getBearerFormat()).isEqualTo("JWT");

                            OAuthFlows flows =
                                    ((OAuth2SecurityScheme) schemes.get("oauth")).getFlows();
                            assertThat(flows.authorizationCode().authorizationUrl())
                                    .isEqualTo("https://auth/authorize");
                            assertThat(flows.authorizationCode().tokenUrl())
                                    .isEqualTo("https://auth/token");
                            assertThat(flows.clientCredentials().tokenUrl())
                                    .isEqualTo("https://auth/token");
                            assertThat(flows.implicit().authorizationUrl())
                                    .isEqualTo("https://auth/authorize");
                            assertThat(flows.password().scopes()).containsEntry("read", "Read");

                            // A scheme may declare only some of the flows.
                            OAuthFlows machine =
                                    ((OAuth2SecurityScheme) schemes.get("machine")).getFlows();
                            assertThat(machine.clientCredentials().tokenUrl())
                                    .isEqualTo("https://auth/token");
                            assertThat(machine.authorizationCode()).isNull();
                            assertThat(machine.implicit()).isNull();
                            assertThat(machine.password()).isNull();

                            assertThat(
                                            ((OpenIdConnectSecurityScheme) schemes.get("oidc"))
                                                    .getOpenIdConnectUrl())
                                    .isEqualTo("https://auth/.well-known/openid-configuration");
                            assertThat(schemes.get("mtls").getDescription())
                                    .isEqualTo("Client certificate");
                            assertThat(card.security())
                                    .containsExactly(Map.of("oauth", List.of("read")));
                        });
    }

    @Test
    void shouldRejectUnknownSecuritySchemeType() {
        contextRunner
                .withPropertyValues("agentscope.a2a.server.card.security-schemes.basic.type=basic")
                .run(
                        context ->
                                assertThat(context)
                                        .getFailure()
                                        .rootCause()
                                        .hasMessageContaining(
                                                "Unsupported type 'basic' of security scheme"
                                                        + " 'basic'"));
    }

    @Test
    void shouldServeConfiguredProviderSkillsAndInterfaces() {
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.card.provider.organization=AgentScope",
                        "agentscope.a2a.server.card.provider.url=https://agentscope.io",
                        "agentscope.a2a.server.card.skills[0].id=translate",
                        "agentscope.a2a.server.card.skills[0].name=Translate",
                        "agentscope.a2a.server.card.skills[0].description=Translates text",
                        "agentscope.a2a.server.card.skills[0].tags[0]=language",
                        "agentscope.a2a.server.card.skills[0].examples[0]=Translate hello",
                        "agentscope.a2a.server.card.skills[0].input-modes[0]=text",
                        "agentscope.a2a.server.card.skills[0].output-modes[0]=text",
                        "agentscope.a2a.server.card.skills[0].security[0].oauth[0]=read",
                        "agentscope.a2a.server.card.additional-interfaces[0].transport=JSONRPC",
                        "agentscope.a2a.server.card.additional-interfaces[0].url=http://localhost:8080/a2a")
                .run(
                        context -> {
                            AgentCard card = servedAgentCard(context);
                            assertThat(card.provider())
                                    .isEqualTo(
                                            new AgentProvider(
                                                    "AgentScope", "https://agentscope.io"));
                            assertThat(card.skills())
                                    .containsExactly(
                                            new AgentSkill(
                                                    "translate",
                                                    "Translate",
                                                    "Translates text",
                                                    List.of("language"),
                                                    List.of("Translate hello"),
                                                    List.of("text"),
                                                    List.of("text"),
                                                    List.of(Map.of("oauth", List.of("read")))));
                            assertThat(card.additionalInterfaces())
                                    .containsExactly(
                                            new AgentInterface(
                                                    "JSONRPC", "http://localhost:8080/a2a"));
                        });
    }

    @Test
    void shouldServeEachApiKeyLocation() {
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.card.security-schemes.inHeader.type=apiKey",
                        "agentscope.a2a.server.card.security-schemes.inHeader.name=X-API-Key",
                        "agentscope.a2a.server.card.security-schemes.inHeader.in=header",
                        "agentscope.a2a.server.card.security-schemes.inQuery.type=apiKey",
                        "agentscope.a2a.server.card.security-schemes.inQuery.name=api_key",
                        "agentscope.a2a.server.card.security-schemes.inQuery.in=query",
                        "agentscope.a2a.server.card.security-schemes.inCookie.type=apiKey",
                        "agentscope.a2a.server.card.security-schemes.inCookie.name=session",
                        "agentscope.a2a.server.card.security-schemes.inCookie.in=cookie")
                .run(
                        context -> {
                            Map<String, SecurityScheme> schemes =
                                    servedAgentCard(context).securitySchemes();
                            assertThat(((APIKeySecurityScheme) schemes.get("inHeader")).getIn())
                                    .isEqualTo("header");
                            assertThat(((APIKeySecurityScheme) schemes.get("inQuery")).getIn())
                                    .isEqualTo("query");
                            assertThat(((APIKeySecurityScheme) schemes.get("inCookie")).getIn())
                                    .isEqualTo("cookie");
                        });
    }

    @Test
    void shouldRejectUnknownApiKeyLocation() {
        for (String location : new String[] {"HEADER", "body"}) {
            contextRunner
                    .withPropertyValues(
                            "agentscope.a2a.server.card.security-schemes.key.type=apiKey",
                            "agentscope.a2a.server.card.security-schemes.key.name=X-API-Key",
                            "agentscope.a2a.server.card.security-schemes.key.in=" + location)
                    .run(
                            context ->
                                    assertThat(context)
                                            .getFailure()
                                            .rootCause()
                                            .hasMessageContaining(
                                                    "Unsupported location '"
                                                            + location
                                                            + "' of API key security scheme"
                                                            + " 'key'"));
        }
    }

    @Test
    void shouldAcceptOAuthFlowsWithoutScopes() {
        withYaml(
                        """
                        agentscope.a2a.server.card.security-schemes:
                          oauth:
                            type: oauth2
                            flows:
                              client-credentials:
                                token-url: https://auth/token
                                scopes: {}
                        """)
                .run(
                        context -> {
                            OAuthFlows flows =
                                    ((OAuth2SecurityScheme)
                                                    servedAgentCard(context)
                                                            .securitySchemes()
                                                            .get("oauth"))
                                            .getFlows();
                            assertThat(flows.clientCredentials().scopes()).isEmpty();
                        });
    }

    @Test
    void shouldKeepBracketedScopeNames() {
        withYaml(
                        """
                        agentscope.a2a.server.card.security-schemes:
                          oauth:
                            type: oauth2
                            flows:
                              client-credentials:
                                token-url: https://auth/token
                                scopes:
                                  "[read:user]": Read the user
                                  "[https://example.com/scope]": Full access
                        """)
                .run(
                        context -> {
                            OAuthFlows flows =
                                    ((OAuth2SecurityScheme)
                                                    servedAgentCard(context)
                                                            .securitySchemes()
                                                            .get("oauth"))
                                            .getFlows();
                            assertThat(flows.clientCredentials().scopes())
                                    .containsOnlyKeys("read:user", "https://example.com/scope");
                        });
    }

    /** Adds {@code yaml} as configuration, parsed the way Spring Boot reads application.yml. */
    private WebApplicationContextRunner withYaml(String yaml) {
        return contextRunner.withInitializer(
                context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load(
                                        "test.yml",
                                        new ByteArrayResource(
                                                yaml.getBytes(StandardCharsets.UTF_8)))
                                .forEach(
                                        source ->
                                                context.getEnvironment()
                                                        .getPropertySources()
                                                        .addFirst(source));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /** Reads the agent card as an A2A client gets it from the card endpoint. */
    private static AgentCard servedAgentCard(AssertableWebApplicationContext context)
            throws Exception {
        assertThat(context).hasNotFailed();
        return Utils.OBJECT_MAPPER.readValue(
                context.getBean(AgentCardController.class).getAgentCard().getBody(),
                AgentCard.class);
    }
}
