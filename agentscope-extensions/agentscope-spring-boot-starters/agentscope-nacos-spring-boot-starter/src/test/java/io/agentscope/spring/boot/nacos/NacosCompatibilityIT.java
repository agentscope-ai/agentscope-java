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

package io.agentscope.spring.boot.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ability.constant.AbilityStatus;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.converter.AgentConverters;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.alibaba.nacos.api.ai.model.agent.AgentDiscoveryFilter;
import com.alibaba.nacos.api.ai.model.agent.AgentEndpointRegistrationBatch;
import com.alibaba.nacos.api.ai.model.agent.AgentReference;
import com.alibaba.nacos.api.ai.model.agent.Endpoint;
import com.alibaba.nacos.api.ai.model.agent.EndpointSource;
import com.alibaba.nacos.api.ai.model.prompt.PromptVariable;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.utils.json.JsonUtils;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerFactory;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryTransportProperties;
import io.agentscope.core.nacos.prompt.NacosPromptListener;
import io.agentscope.core.nacos.skill.NacosSkillRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Opt-in tests against an authenticated, locally managed Nacos server.
 *
 * <p>Set {@code NACOS_IT_SERVER} (for example, {@code 127.0.0.1:8848}), {@code NACOS_IT_USERNAME},
 * and {@code NACOS_IT_PASSWORD} for a dedicated test environment. Run from the repository root:
 *
 * <pre>{@code
 * mvn -B -T1 \
 *   -pl agentscope-extensions/agentscope-spring-boot-starters/agentscope-nacos-spring-boot-starter \
 *   -am test -Dnacos.it=true -Dnacos.it.rad=true -Dtest=NacosCompatibilityIT
 * }</pre>
 *
 * <p>Omit {@code -Dnacos.it.rad=true} for older servers without the RAD publication workflow.
 * That option enables the native RAD resolver, Agent deletion/recreation and unsubmitted draft checks.
 * Set {@code -Dnacos.it.transport=http} to exercise the Agent HTTP transport (default: gRPC).
 * Agent discovery runs in automatic, A2A and RAD modes on supported servers: A2A retains
 * the cached Card while absent; RAD clears it. Both must receive the recreated Card without
 * a caller resubscription. Automatic selection and explicit A2A discovery also run on older servers.
 * The remaining checks cover authentication rejection, Prompt rendering/version/label/deletion,
 * Skill ZIP resources and published-version lookup, and Agent registration/version updates.
 * A minimal ReActAgent consumes the real Prompt and Skill fixtures using a scripted model;
 * no external model provider or unrelated example application is required.
 *
 * <p>Tests create randomly prefixed resources in the {@code public} namespace, publish only
 * those fixtures using administrative APIs, and delete them afterward. The account needs
 * permission to create, publish, and delete these resources. Keep credentials out of source files.
 */
@EnabledIfSystemProperty(named = "nacos.it", matches = "true")
@Timeout(90)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NacosCompatibilityIT {
    private static final String NAMESPACE = "public";
    private final String name = "agentscope-it-" + UUID.randomUUID().toString().replace("-", "");
    private AiService client;
    private AiMaintainerService admin;
    private final List<Cleanup> cleanup = new ArrayList<>();

    @BeforeEach
    void connect() throws Exception {
        Properties properties = properties();
        admin = AiMaintainerFactory.createAiMaintainerService(properties);
        client = AiFactory.createAiService(properties);
    }

    @AfterEach
    void release() throws Exception {
        try {
            if (client != null) client.shutdown();
        } finally {
            Exception failure = null;
            for (Cleanup action : cleanup) {
                try {
                    action.run();
                } catch (Exception e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }

    @Test
    void promptRenderingBaseline() throws Exception {
        cleanup.add(() -> admin.prompt().deletePrompt(NAMESPACE, name));
        publishPromptFixture("1.0.0", "Hello {{name}}");
        try (NacosPromptListener listener = new NacosPromptListener(client)) {
            assertEquals(
                    "Hello test",
                    listener.getPrompt(name, "1.0.0", null, Map.of("name", "test"), null));
        }
        assertNotNull(client.getPromptByVersion(name, "1.0.0"));
    }

    @Test
    void promptVariableDefaultsAndOverrides() throws Exception {
        cleanup.add(() -> admin.prompt().deletePrompt(NAMESPACE, name));
        PromptVariable variable = new PromptVariable();
        variable.setName("name");
        variable.setDefaultValue("default");
        admin.prompt()
                .createDraft(
                        NAMESPACE,
                        name,
                        null,
                        "1.0.0",
                        "Hello {{name}}",
                        JsonUtils.toJson(List.of(variable)),
                        "IT",
                        "IT",
                        null);
        admin.prompt().forcePublish(NAMESPACE, name, "1.0.0", true);
        try (NacosPromptListener listener = new NacosPromptListener(client)) {
            assertEquals("Hello default", listener.getPrompt(name));
            assertEquals("Hello override", listener.getPrompt(name, Map.of("name", "override")));
        }
    }

    @Test
    void promptVersionLabelAndDeletion() throws Exception {
        cleanup.add(() -> admin.prompt().deletePrompt(NAMESPACE, name));
        publishPromptFixture("1.0.0", "first");
        publishPromptFixture("2.0.0", "second");
        admin.prompt().bindLabel(NAMESPACE, name, "prod", "1.0.0");
        try (NacosPromptListener listener = new NacosPromptListener(client)) {
            assertEquals("first", listener.getPrompt(name, "1.0.0", null, null, null));
            assertEquals("second", listener.getPrompt(name, "2.0.0", null, null, null));
            assertEquals("first", listener.getPrompt(name, null, "prod", null, null));
            admin.prompt().bindLabel(NAMESPACE, name, "prod", "2.0.0");
            await(() -> assertEquals("second", listener.getPrompt(name, null, "prod", null, null)));
            admin.prompt().deletePrompt(NAMESPACE, name);
            await(
                    () ->
                            assertEquals(
                                    "fallback",
                                    listener.getPrompt(name, null, "prod", null, "fallback")));
            publishPromptFixture("3.0.0", "recreated");
            admin.prompt().bindLabel(NAMESPACE, name, "prod", "3.0.0");
            await(
                    () ->
                            assertEquals(
                                    "recreated",
                                    listener.getPrompt(name, null, "prod", null, null)));
            assertEquals("fallback", listener.getPrompt(name, "1.0.0", null, null, "fallback"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "a2a", "rad"})
    @Timeout(120)
    void registrationDiscoveryAndVersionPublication(String mode) throws Exception {
        Assumptions.assumeTrue(
                !"rad".equals(mode) || Boolean.getBoolean("nacos.it.rad"),
                "Native RAD discovery requires a RAD server");
        cleanup.add(() -> admin.a2a().deleteAgent(name, NAMESPACE, null));
        NacosA2aRegistry registry = new NacosA2aRegistry(client);
        NacosA2aRegistryProperties config =
                NacosA2aRegistryProperties.builder()
                        .setAsLatest(true)
                        .enabledRegisterEndpoint(true)
                        .build();
        config.addTransport(
                NacosA2aRegistryTransportProperties.builder()
                        .transport("JSONRPC")
                        .host("127.0.0.1")
                        .port(18080)
                        .path("/agent")
                        .build());
        registry.registerAgent(card("1.0.0"), config);
        String property = "agentscope.nacos.a2a.discovery.mode";
        String previous = System.getProperty(property);
        System.setProperty(property, mode);
        try (NacosAgentCardResolver resolver = new NacosAgentCardResolver(client)) {
            await(
                    () -> {
                        AgentCard result = resolver.getAgentCard(name);
                        assertNotNull(result);
                        assertEquals("1.0.0", result.version());
                        assertEquals(
                                "http://127.0.0.1:18080/agent",
                                result.supportedInterfaces().get(0).url());
                    });
            A2aAgent remote = A2aAgent.builder().name(name).agentCardResolver(resolver).build();
            assertEquals("AgentScope compatibility test", remote.getDescription());
            registry.registerAgent(card("1.0.0"), config);
            registry.registerAgent(card("2.0.0"), config);
            await(
                    () -> {
                        AgentCard discovered = resolver.getAgentCard(name);
                        assertNotNull(discovered);
                        assertEquals("2.0.0", discovered.version());
                    });
            AgentEndpoint endpoint = new AgentEndpoint();
            endpoint.setTransport("JSONRPC");
            endpoint.setAddress("127.0.0.1");
            endpoint.setPort(18080);
            endpoint.setPath("/agent");
            // Retire the old version's reference, which can retain a compatible endpoint range.
            // On a RAD server, replace version 2's complete intent directly; deregistering its
            // old address afterward would also remove the newly registered version-2 address.
            endpoint.setVersion("1.0.0");
            client.deregisterAgentEndpoint(name, endpoint);
            endpoint.setVersion("2.0.0");
            if (!Boolean.getBoolean("nacos.it.rad")) {
                // The legacy server still supports per-address deregistration.
                client.deregisterAgentEndpoint(name, endpoint);
            }
            endpoint.setPort(18081);
            client.registerAgentEndpoint(name, endpoint);
            await(
                    () -> {
                        AgentCard discovered = resolver.getAgentCard(name);
                        assertNotNull(discovered);
                        assertEquals(
                                "http://127.0.0.1:18081/agent",
                                discovered.supportedInterfaces().get(0).url());
                    });
            if (Boolean.getBoolean("nacos.it.rad")) {
                admin.a2a().deleteAgent(name, NAMESPACE, null);
                await(() -> assertThrows(NacosException.class, () -> client.getAgentCard(name)));
                if (!"a2a".equals(mode)) {
                    await(() -> assertNull(resolver.getAgentCard(name)));
                } else {
                    assertEquals("2.0.0", resolver.getAgentCard(name).version());
                }
                registry.registerAgent(card("3.0.0"), config);
                // The SDK keeps the missing reference pending with retry delays up to 60s.
                // Allow one retry interval plus local request time; do not resubscribe here.
                await(
                        Duration.ofSeconds(75),
                        () -> {
                            AgentCard discovered = resolver.getAgentCard(name);
                            assertNotNull(discovered);
                            assertEquals("3.0.0", discovered.version());
                        });
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
        assertNotNull(client.getAgentCard(name));
    }

    @Test
    @EnabledIfSystemProperty(named = "nacos.it.rad", matches = "true")
    void initiallyMissingAgentIsDiscoveredByTheAcceptedSubscription() throws Exception {
        cleanup.add(() -> admin.a2a().deleteAgent(name, NAMESPACE, null));
        String property = "agentscope.nacos.a2a.discovery.mode";
        String previous = System.getProperty(property);
        System.setProperty(property, "rad");
        try (NacosAgentCardResolver resolver = new NacosAgentCardResolver(client)) {
            assertNull(resolver.getAgentCard(name));
            new NacosA2aRegistry(client)
                    .registerAgent(
                            card("1.0.0"),
                            NacosA2aRegistryProperties.builder()
                                    .setAsLatest(true)
                                    .enabledRegisterEndpoint(false)
                                    .build());
            await(
                    Duration.ofSeconds(75),
                    () -> {
                        AgentCard discovered = resolver.getAgentCard(name);
                        assertNotNull(discovered);
                        assertEquals("1.0.0", discovered.version());
                    });
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reactAgentUsesNacosPromptAndLoadsNacosSkill(@TempDir Path workDir) throws Exception {
        cleanup.add(() -> admin.prompt().deletePrompt(NAMESPACE, name));
        cleanup.add(() -> admin.skill().deleteSkill(NAMESPACE, name));
        publishPromptFixture("1.0.0", "You are {{role}}.");
        admin.skill().uploadSkillFromZip(NAMESPACE, skillZip("1.0.0"), false);
        admin.skill().forcePublish(NAMESPACE, name, "1.0.0", true);

        try (NacosPromptListener prompts = new NacosPromptListener(client);
                NacosSkillRepository skills =
                        new NacosSkillRepository(client, NAMESPACE, null, List.of(name))) {
            String skillId = skills.getSkill(name).getSkillId();
            Map<String, Object> toolInput = Map.of("skillId", skillId, "path", "SKILL.md");
            Model model = mock(Model.class);
            when(model.getModelName()).thenReturn("nacos-compatibility-fixture");
            when(model.stream(anyList(), any(), any()))
                    .thenReturn(
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("load-nacos-skill")
                                                                    .name("load_skill_through_path")
                                                                    .input(toolInput)
                                                                    .content(
                                                                            JsonUtils.toJson(
                                                                                    toolInput))
                                                                    .build()))
                                            .build()),
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build()));
            try (ReActAgent agent =
                    ReActAgent.builder()
                            .name("nacos-consumer")
                            .model(model)
                            .sysPrompt(
                                    prompts.getPrompt(name, Map.of("role", "a Nacos test agent")))
                            .skillRepository(skills)
                            .skillWorkDir(workDir)
                            .build()) {
                Msg reply =
                        agent.call(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .content(
                                                        TextBlock.builder()
                                                                .text("Load the skill")
                                                                .build())
                                                .build())
                                .block(Duration.ofSeconds(20));
                assertNotNull(reply);
                assertEquals("done", reply.getTextContent());
                ArgumentCaptor<List<Msg>> requests = ArgumentCaptor.forClass(List.class);
                verify(model, times(2)).stream(requests.capture(), any(), any());
                assertTrue(
                        requests.getAllValues().get(0).stream()
                                .filter(message -> message.getRole() == MsgRole.SYSTEM)
                                .anyMatch(
                                        message ->
                                                message.getTextContent()
                                                                .contains(
                                                                        "You are a Nacos test"
                                                                                + " agent.")
                                                        && message.getTextContent()
                                                                .contains(name)));
                List<String> toolOutput =
                        requests.getAllValues().get(1).stream()
                                .flatMap(message -> message.getContent().stream())
                                .filter(ToolResultBlock.class::isInstance)
                                .map(ToolResultBlock.class::cast)
                                .flatMap(result -> result.getOutput().stream())
                                .filter(TextBlock.class::isInstance)
                                .map(TextBlock.class::cast)
                                .map(TextBlock::getText)
                                .toList();
                assertTrue(
                        toolOutput.stream().anyMatch(text -> text.contains("Use this test skill")),
                        "Skill loader output: " + toolOutput);
            }
        }
        assertNotNull(client.getPrompt(name));
    }

    @Test
    void skillDownloadAndMarketplaceListingBaseline() throws Exception {
        cleanup.add(() -> admin.skill().deleteSkill(NAMESPACE, name));
        admin.skill().uploadSkillFromZip(NAMESPACE, skillZip("1.0.0"), false);
        admin.skill().forcePublish(NAMESPACE, name, "1.0.0", true);
        Properties selection = new Properties();
        selection.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "1.0.0");
        NacosSkillRepository repository = new NacosSkillRepository(client, NAMESPACE, selection);
        await(() -> assertEquals(name, repository.getSkill(name).getName()));
        assertEquals(name, new NacosSkillRepository(client, NAMESPACE).getSkill(name).getName());
        Properties labelSelection = new Properties();
        labelSelection.setProperty(NacosSkillRepository.SKILL_LABEL_PATH, "latest");
        assertEquals(
                name,
                new NacosSkillRepository(client, NAMESPACE, labelSelection)
                        .getSkill(name)
                        .getName());
        assertTrue(
                admin.skill().listSkills(NAMESPACE, name, "accurate", 1, 10).getPageItems().stream()
                        .anyMatch(skill -> name.equals(skill.getName())));
        String publishedVersion =
                admin.skill().getSkillMeta(NAMESPACE, name).getLabels().get("latest");
        assertEquals("1.0.0", publishedVersion);
        var latest = admin.skill().getSkillVersionDetail(NAMESPACE, name, publishedVersion);
        assertTrue(latest.getSkillMd().contains("Use this test skill"));
        assertTrue(
                latest.getResource().values().stream()
                        .anyMatch(resource -> "fixture".equals(resource.getContent())),
                "Resource paths: " + latest.getResource().keySet());
        admin.skill().uploadSkillFromZip(NAMESPACE, skillZip("2.0.0"), false);
        assertEquals(
                "1.0.0", admin.skill().getSkillMeta(NAMESPACE, name).getLabels().get("latest"));
        admin.skill().forcePublish(NAMESPACE, name, "2.0.0", true);
        assertEquals(
                "2.0.0", admin.skill().getSkillMeta(NAMESPACE, name).getLabels().get("latest"));
        repository.close();
        assertEquals(name, new NacosSkillRepository(client, NAMESPACE).getSkill(name).getName());
    }

    @Test
    void invalidCredentialsAreRejected() throws Exception {
        cleanup.add(() -> admin.prompt().deletePrompt(NAMESPACE, name));
        publishPromptFixture("1.0.0", "private fixture");
        Properties invalid = properties();
        invalid.setProperty(PropertyKeyConst.USERNAME, "missing-" + UUID.randomUUID());
        invalid.setProperty(PropertyKeyConst.PASSWORD, "invalid-" + UUID.randomUUID());
        AiService unauthenticated = AiFactory.createAiService(invalid);
        try {
            NacosException failure =
                    assertThrows(NacosException.class, () -> unauthenticated.getPrompt(name));
            assertTrue(
                    failure.getErrCode() == 401 || failure.getErrCode() == 403,
                    "Authentication failure code: " + failure.getErrCode());
        } finally {
            unauthenticated.shutdown();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "nacos.it.rad", matches = "true")
    void unsubmittedSubsequentVersionRemainsDraft() throws Exception {
        cleanup.add(() -> admin.a2a().deleteAgent(name, NAMESPACE, null));
        NacosA2aRegistry registry = new NacosA2aRegistry(client);
        NacosA2aRegistryProperties config =
                NacosA2aRegistryProperties.builder()
                        .setAsLatest(false)
                        .enabledRegisterEndpoint(false)
                        .build();
        registry.registerAgent(card("1.0.0"), config);
        await(() -> assertEquals("1.0.0", client.getAgentCard(name).getVersion()));
        registry.registerAgent(card("2.0.0"), config);
        assertEquals("draft", admin.agent().getAgentVersion(NAMESPACE, name, "2.0.0").getStatus());
        assertEquals("1.0.0", client.getAgentCard(name).getVersion());
    }

    @Test
    void publicCapabilityMatchesServerWithoutABusinessProbe() throws Exception {
        AbilityStatus expected =
                Boolean.getBoolean("nacos.it.rad")
                        ? AbilityStatus.SUPPORTED
                        : AbilityStatus.NOT_SUPPORTED;
        await(() -> assertEquals(expected, client.getAgentDiscoveryCapability()));
    }

    @Test
    @EnabledIfSystemProperty(named = "nacos.it.rad", matches = "true")
    void legacyEndpointReplacementAndDeregistrationUseWholeVersionIntent() throws Exception {
        cleanup.add(() -> admin.a2a().deleteAgent(name, NAMESPACE, null));
        new NacosA2aRegistry(client)
                .registerAgent(
                        card("1.0.0"),
                        NacosA2aRegistryProperties.builder()
                                .setAsLatest(true)
                                .enabledRegisterEndpoint(false)
                                .build());
        AgentEndpoint old = legacyEndpoint(18082);
        client.registerAgentEndpoint(name, old);
        await(() -> assertEquals(List.of("http://127.0.0.1:18082/agent"), runtimeUrls()));
        client.registerAgentEndpoint(name, legacyEndpoint(18083));
        await(() -> assertEquals(List.of("http://127.0.0.1:18083/agent"), runtimeUrls()));
        // The argument identifies the version intent, even when it contains the old address.
        client.deregisterAgentEndpoint(name, old);
        await(() -> assertTrue(runtimeUrls().isEmpty()));
    }

    @Test
    @EnabledIfSystemProperty(named = "nacos.it.rad", matches = "true")
    void nativeEndpointBatchReplacesAddressesAndDeregistersOnlySelectedAddress() throws Exception {
        cleanup.add(() -> admin.a2a().deleteAgent(name, NAMESPACE, null));
        new NacosA2aRegistry(client)
                .registerAgent(
                        card("1.0.0"),
                        NacosA2aRegistryProperties.builder()
                                .setAsLatest(true)
                                .enabledRegisterEndpoint(false)
                                .build());
        AgentEndpointRegistrationBatch batch = new AgentEndpointRegistrationBatch();
        batch.setAgentName(name);
        batch.setProtocol("a2a");
        batch.setRuntimeVersion("1.0.0");
        Endpoint first = nativeEndpoint(18082);
        Endpoint second = nativeEndpoint(18083);
        batch.setEndpoints(List.of(first, second));
        client.agent().registerAgentEndpoints(batch);
        await(
                () ->
                        assertEquals(
                                List.of(
                                        "http://127.0.0.1:18082/agent",
                                        "http://127.0.0.1:18083/agent"),
                                runtimeUrls()));
        Endpoint replacement = nativeEndpoint(18084);
        batch.setEndpoints(List.of(second, replacement));
        client.agent().registerAgentEndpoints(batch);
        await(
                () ->
                        assertEquals(
                                List.of(
                                        "http://127.0.0.1:18083/agent",
                                        "http://127.0.0.1:18084/agent"),
                                runtimeUrls()));
        client.agent().deregisterAgentEndpoints(name, "a2a", List.of(second));
        await(() -> assertEquals(List.of("http://127.0.0.1:18084/agent"), runtimeUrls()));
        AgentDiscoveryFilter filter = new AgentDiscoveryFilter();
        filter.setProtocols(List.of("other"));
        var snapshot = client.agent().discoverAgent(reference(), filter);
        assertTrue(snapshot.getCallInterfaces().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> AgentConverters.a2a().convert(snapshot));
    }

    private AgentReference reference() {
        AgentReference reference = new AgentReference();
        reference.setAgentName(name);
        reference.setLabel("latest");
        return reference;
    }

    private List<String> runtimeUrls() throws NacosException {
        return client.agent().discoverAgent(reference()).getCallInterfaces().stream()
                .flatMap(call -> call.getEndpointSets().stream())
                .filter(set -> set.getSource() == EndpointSource.RUNTIME)
                .flatMap(set -> set.getEndpoints().stream())
                .map(Endpoint::getUri)
                .sorted()
                .toList();
    }

    private AgentEndpoint legacyEndpoint(int port) {
        AgentEndpoint endpoint = new AgentEndpoint();
        endpoint.setVersion("1.0.0");
        endpoint.setTransport("JSONRPC");
        endpoint.setAddress("127.0.0.1");
        endpoint.setPort(port);
        endpoint.setPath("/agent");
        return endpoint;
    }

    private static Endpoint nativeEndpoint(int port) {
        Endpoint endpoint = new Endpoint();
        endpoint.setUri("http://127.0.0.1:" + port + "/agent");
        endpoint.setTransport("JSONRPC");
        return endpoint;
    }

    private void publishPromptFixture(String version, String template) throws NacosException {
        // Avoid racing legacy publish's asynchronous review with administrative fixture
        // publication.
        admin.prompt()
                .createDraft(NAMESPACE, name, null, version, template, null, "IT", "IT", null);
        admin.prompt().forcePublish(NAMESPACE, name, version, true);
    }

    private AgentCard card(String version) {
        return AgentCard.builder()
                .name(name)
                .description("AgentScope compatibility test")
                .version(version)
                .supportedInterfaces(
                        List.of(
                                new org.a2aproject.sdk.spec.AgentInterface(
                                        "JSONRPC", "http://127.0.0.1:18080/agent", null, "1.0")))
                .capabilities(AgentCapabilities.builder().build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private byte[] skillZip(String version) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            zip.write(
                    ("---\nname: "
                                    + name
                                    + "\n"
                                    + "description: Compatibility test\n"
                                    + "version: "
                                    + version
                                    + "\n"
                                    + "---\n"
                                    + "Use this test skill\n")
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name + "/notes.txt"));
            zip.write("fixture".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Properties properties() {
        Properties result = new Properties();
        result.setProperty(PropertyKeyConst.SERVER_ADDR, requiredEnv("NACOS_IT_SERVER"));
        result.setProperty(PropertyKeyConst.USERNAME, requiredEnv("NACOS_IT_USERNAME"));
        result.setProperty(PropertyKeyConst.PASSWORD, requiredEnv("NACOS_IT_PASSWORD"));
        result.setProperty(PropertyKeyConst.NAMESPACE, NAMESPACE);
        result.setProperty(
                AiConstants.AI_AGENT_TRANSPORT_MODE,
                System.getProperty("nacos.it.transport", "grpc"));
        return result;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }

    private static void await(Cleanup assertion) throws Exception {
        await(Duration.ofSeconds(20), assertion);
    }

    private static void await(Duration timeout, Cleanup assertion) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError | NacosException e) {
                if (System.nanoTime() >= deadline) throw e;
                Thread.sleep(200);
            }
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws Exception;
    }
}
