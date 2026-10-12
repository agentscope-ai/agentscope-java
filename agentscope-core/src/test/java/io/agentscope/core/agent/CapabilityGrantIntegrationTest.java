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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * End-to-end grant-layer tests: entry resolution, schema/skill filtering, gates.
 *
 * @author shanhongyu
 */
@Tag("integration")
@DisplayName("Capability grant layer integration")
class CapabilityGrantIntegrationTest {

    // ==================== helpers ====================

    /** Minimal non-system tool returning a fixed text. */
    private static AgentTool textTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "tool " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("ran:" + name));
            }
        };
    }

    /** Toolkit with two groups (g1, g2) + one ungrouped tool + the meta tool registered. */
    private Toolkit grantToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(textTool("t1"));
        toolkit.registerAgentTool(textTool("t2"));
        toolkit.registerAgentTool(textTool("t3"));
        toolkit.createToolGroup("g1", "group-one", true);
        toolkit.createToolGroup("g2", "group-two", false);
        toolkit.addToolToGroup("g1", "t1");
        toolkit.addToolToGroup("g2", "t2");
        toolkit.registerMetaTool();
        return toolkit;
    }

    /** A minimal per-call rc (user u1, session s1) without a surface. */
    private static RuntimeContext rc() {
        return RuntimeContext.builder().userId("u1").sessionId("s1").build();
    }

    /** The same rc as {@link #rc()} with the given surface seeded. */
    private static RuntimeContext rc(CapabilitySurface surface) {
        RuntimeContext ctx = rc();
        ctx.put(CapabilitySurface.class, surface);
        return ctx;
    }

    /** Builds a ToolCallParam with content JSON (schema validation requires the serialized form). */
    private static ToolCallParam param(
            String toolName, Map<String, Object> input, RuntimeContext rc) {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("id-" + toolName)
                        .name(toolName)
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        return ToolCallParam.builder()
                .toolUseBlock(toolUse)
                .input(input)
                .runtimeContext(rc)
                .build();
    }

    /** Executes via the public Toolkit entry (passes through ToolExecutor's physical gate). */
    private ToolResultBlock call(Toolkit toolkit, String toolName, RuntimeContext rc) {
        return toolkit.callTool(param(toolName, Map.of(), rc)).block();
    }

    /** Executes a tool's own callAsync directly (bypasses ToolExecutor's gates). */
    private ToolResultBlock callDirect(
            AgentTool tool, Map<String, Object> input, RuntimeContext rc) {
        return tool.callAsync(param(tool.getName(), input, rc)).block();
    }

    /** Extracts the first text block.
     *
     * @return the first text, or an empty string
     */
    private static String firstText(ToolResultBlock result) {
        List<ContentBlock> outputs = result.getOutput();
        if (outputs == null || outputs.isEmpty()) {
            return "";
        }
        return ((TextBlock) outputs.get(0)).getText();
    }

    /** Lists the schema names in order.
     *
     * @param schemas the schemas to read
     * @return the names in order
     */
    private static List<String> schemaNames(List<ToolSchema> schemas) {
        return schemas.stream().map(ToolSchema::getName).toList();
    }

    /** Middleware recording the CapabilitySurface visible on the call's rc. */
    private static final class RecordingMiddleware implements MiddlewareBase {
        /** Surface visible on the call's rc. */
        final AtomicReference<CapabilitySurface> seen = new AtomicReference<>();

        /** The rc instance seen by the middleware. */
        final AtomicReference<RuntimeContext> seenRc = new AtomicReference<>();

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            seen.set(ctx != null ? ctx.get(CapabilitySurface.class) : null);
            seenRc.set(ctx);
            return Mono.just(currentPrompt);
        }
    }

    /** Minimal in-memory skill repository stub. */
    private static final class StubRepo implements AgentSkillRepository {
        private final List<AgentSkill> skills;

        StubRepo(List<AgentSkill> skills) {
            this.skills = skills;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.of();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skills;
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return false;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return null;
        }

        @Override
        public String getSource() {
            return "stub";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    /** A skill named {@code name} with skillName {@code name_custom}. */
    private static AgentSkill skill(String name) {
        return new AgentSkill(name, "Skill " + name, "# " + name, null);
    }

    // ==================== entry resolution ====================

    @Test
    @DisplayName("no resolver, or a resolver returning null → ALLOW_ALL on the call-scoped rc")
    void entrySeedsAllowAllWithoutResolverOrNullReturn() {
        RecordingMiddleware recorder1 = new RecordingMiddleware();
        ReActAgent plain =
                ReActAgent.builder()
                        .name("a")
                        .model(new MockModel("done"))
                        .middleware(recorder1)
                        .build();
        plain.call(userMsg("hi"), rc()).block(Duration.ofSeconds(10));
        assertSame(CapabilitySurface.ALLOW_ALL, recorder1.seen.get());

        RecordingMiddleware recorder2 = new RecordingMiddleware();
        ReActAgent nullResolver =
                ReActAgent.builder()
                        .name("a")
                        .model(new MockModel("done"))
                        .capabilityResolver((ctx, a) -> null)
                        .middleware(recorder2)
                        .build();
        nullResolver.call(userMsg("hi"), rc()).block(Duration.ofSeconds(10));
        assertSame(CapabilitySurface.ALLOW_ALL, recorder2.seen.get());
    }

    @Test
    @DisplayName("resolver throwing → the exception aborts the call (no surface written)")
    void entryThrowingResolverAbortsTheCall() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(new MockModel("done"))
                        .capabilityResolver(
                                (ctx, a) -> {
                                    throw new IllegalStateException("authz backend down");
                                })
                        .build();
        RuntimeContext ctx = rc();
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(userMsg("hi"), ctx).block(Duration.ofSeconds(10)));
        assertEquals("authz backend down", error.getMessage());
        assertNull(
                ctx.get(CapabilitySurface.class),
                "a failed resolve must not leave a surface on the rc");
    }

    @Test
    @DisplayName("resolver result instance is carried as-is (same reference)")
    void entryCarriesResolvedInstance() {
        CapabilitySurface granted = new CapabilitySurface(null, Set.of("g1"), Set.of());
        RecordingMiddleware recorder = new RecordingMiddleware();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(new MockModel("done"))
                        .capabilityResolver((ctx, a) -> granted)
                        .middleware(recorder)
                        .build();
        agent.call(userMsg("hi"), rc()).block(Duration.ofSeconds(10));
        assertSame(granted, recorder.seen.get());
    }

    @Test
    @DisplayName("skillBox + capabilityResolver → resolver disabled, behavior equals unconfigured")
    void staticSkillBoxGuardDisablesResolver() {
        Toolkit toolkit = new Toolkit();
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.registerSkill(skill("boxed"));
        RecordingMiddleware recorder = new RecordingMiddleware();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(new MockModel("done"))
                        .toolkit(toolkit)
                        .skillBox(skillBox)
                        .capabilityResolver(
                                (ctx, a) -> new CapabilitySurface(null, Set.of("g1"), Set.of()))
                        .middleware(recorder)
                        .build();
        RuntimeContext ctx = rc();
        agent.call(userMsg("hi"), ctx).block(Duration.ofSeconds(10));
        // Guard fired: resolver disabled, ALLOW_ALL fallback (prompt catalog stays full).
        // Observed on the call-scoped rc — the caller's rc stays isolated.
        assertSame(CapabilitySurface.ALLOW_ALL, recorder.seen.get());
    }

    @Test
    @DisplayName("two concurrent calls with different users never cross surfaces")
    void concurrentCallsWithDistinctUsersDoNotCrossSurfaces() throws Exception {
        ConcurrentHashMap<String, CapabilitySurface> seenByUser = new ConcurrentHashMap<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(new MockModel("done"))
                        .capabilityResolver(
                                (ctx, a) ->
                                        "u1".equals(ctx.getUserId())
                                                ? new CapabilitySurface(
                                                        null, Set.of("g1"), Set.of())
                                                : new CapabilitySurface(
                                                        null, Set.of("g2"), Set.of()))
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent a, RuntimeContext ctx, String currentPrompt) {
                                        if (ctx != null && ctx.getUserId() != null) {
                                            seenByUser.put(
                                                    ctx.getUserId(),
                                                    ctx.get(CapabilitySurface.class));
                                        }
                                        return Mono.just(currentPrompt);
                                    }
                                })
                        .build();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (String user : List.of("u1", "u2")) {
                RuntimeContext ctx =
                        RuntimeContext.builder().userId(user).sessionId("s-" + user).build();
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    agent.call(userMsg("hi"), ctx).block(Duration.ofSeconds(20));
                                    return ctx;
                                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            // The middleware saw the matching surface mid-call (the caller rcs stay isolated).
            assertTrue(seenByUser.get("u1").allowsToolGroup("g1"));
            assertFalse(seenByUser.get("u1").allowsToolGroup("g2"));
            assertTrue(seenByUser.get("u2").allowsToolGroup("g2"));
            assertFalse(seenByUser.get("u2").allowsToolGroup("g1"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== sub-agent surface reuse ====================

    @Test
    @DisplayName("sub-agent without own resolver reuses the parent surface (reference equality)")
    void subAgentWithoutResolverReusesParentSurface() {
        CapabilitySurface parentSurface = new CapabilitySurface(null, Set.of("g1"), Set.of());
        RecordingMiddleware recorder = new RecordingMiddleware();
        SubAgentTool subAgentTool =
                new SubAgentTool(
                        (SubAgentProvider<ReActAgent>)
                                () ->
                                        ReActAgent.builder()
                                                .name("child")
                                                .model(new MockModel("child done"))
                                                .middleware(recorder)
                                                .build(),
                        SubAgentConfig.defaults());

        RuntimeContext parentRc = rc(parentSurface);
        callDirect(subAgentTool, Map.of("message", "do it"), parentRc);

        assertSame(
                parentSurface,
                recorder.seen.get(),
                "child without own resolver must see the parent surface instance");
        // The parent rc itself is untouched (no derived-copy needed on the keep path).
        assertSame(parentSurface, parentRc.get(CapabilitySurface.class));
    }

    @Test
    @DisplayName("sub-agent with own resolver derives a copy; parent rc surface stays intact")
    void subAgentWithOwnResolverDerivesCopyWithoutPollution() {
        CapabilitySurface parentSurface = new CapabilitySurface(null, Set.of("g1"), Set.of());
        CapabilitySurface childSurface = new CapabilitySurface(null, Set.of("g2"), Set.of());
        RecordingMiddleware recorder = new RecordingMiddleware();
        SubAgentTool subAgentTool =
                new SubAgentTool(
                        (SubAgentProvider<ReActAgent>)
                                () ->
                                        ReActAgent.builder()
                                                .name("child")
                                                .model(new MockModel("child done"))
                                                .middleware(recorder)
                                                .capabilityResolver((ctx, a) -> childSurface)
                                                .build(),
                        SubAgentConfig.defaults());

        RuntimeContext parentRc = rc(parentSurface);
        callDirect(subAgentTool, Map.of("message", "do it"), parentRc);

        assertSame(childSurface, recorder.seen.get(), "child resolves its own surface");
        assertNotSameInstance(recorder.seenRc.get(), parentRc, "child scope.rc is a derived copy");
        assertSame(
                parentSurface,
                parentRc.get(CapabilitySurface.class),
                "parent rc's surface instance must remain the parent's own (zero pollution)");
    }

    private static void assertNotSameInstance(Object a, Object b, String message) {
        assertTrue(a != b, message);
    }

    // ==================== schema filtering ====================

    @Test
    @DisplayName("DENY_ALL surface → only system tools remain in schemas")
    void denyAllLeavesOnlySystemTools() {
        Toolkit toolkit = grantToolkit();
        List<ToolSchema> schemas =
                toolkit.getToolSchemas(List.of("g1", "g2"), null, CapabilitySurface.DENY_ALL);
        assertEquals(List.of("reset_equipped_tools"), schemaNames(schemas));
    }

    @Test
    @DisplayName("granted group + per-name tool enter schemas; un-granted do not")
    void whitelistSchemaFiltering() {
        Toolkit toolkit = grantToolkit();
        CapabilitySurface surface = new CapabilitySurface(null, Set.of("g1"), Set.of("t3"));
        List<ToolSchema> schemas = toolkit.getToolSchemas(List.of("g1", "g2"), null, surface);
        List<String> names = schemaNames(schemas);
        assertTrue(names.contains("t1"), "granted group member visible");
        assertTrue(names.contains("t3"), "per-name granted ungrouped tool visible");
        assertFalse(names.contains("t2"), "un-granted group member cut");
        assertTrue(names.contains("reset_equipped_tools"), "system meta tool survives");
    }

    @Test
    @DisplayName("external tools from the request config are NOT trimmed")
    void externalToolsNotTrimmed() {
        Toolkit toolkit = grantToolkit();
        ToolRequestConfig config =
                new ToolRequestConfig(
                        Map.of("ext_tool", schemaOnly("ext_tool")),
                        ToolMergeMode.MERGE_EXTERNAL_PRIORITY);
        List<ToolSchema> schemas =
                toolkit.getToolSchemas(List.of("g1", "g2"), config, CapabilitySurface.DENY_ALL);
        assertTrue(schemaNames(schemas).contains("ext_tool"));
        // Backend trims to system tools only.
        assertEquals(
                List.of("ext_tool", "reset_equipped_tools"),
                schemaNames(schemas).stream().sorted().toList());
    }

    @Test
    @DisplayName(
            "an external override of reset_equipped_tools keeps the caller's schema (no rewrite)")
    void externalMetaToolOverrideNotRewritten() {
        Toolkit toolkit = grantToolkit();
        SchemaOnlyTool callerReset =
                new SchemaOnlyTool(
                        ToolSchema.builder()
                                .name("reset_equipped_tools")
                                .description("caller-hosted tool-group reset (preview)")
                                .parameters(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of("groups", Map.of("type", "array"))))
                                .build());
        ToolRequestConfig config =
                new ToolRequestConfig(
                        Map.of("reset_equipped_tools", callerReset), ToolMergeMode.EXTERNAL_ONLY);

        List<ToolSchema> schemas =
                toolkit.getToolSchemas(
                        List.of("g1", "g2"),
                        config,
                        new CapabilitySurface(null, Set.of("g1"), Set.of()));

        // The external override shadows the backend meta tool: exactly one row, the caller's.
        assertEquals(List.of("reset_equipped_tools"), schemaNames(schemas));
        ToolSchema reset = metaSchema(schemas);
        assertEquals(
                "caller-hosted tool-group reset (preview)",
                reset.getDescription(),
                "the caller-controlled schema must pass through untouched");
        assertTrue(
                reset.getParameters().toString().contains("groups"),
                "the caller's parameter shape is preserved");
        assertFalse(
                reset.getDescription().contains("Reset your equipped tools"),
                "backend base description must not replace the caller's");
        assertFalse(
                reset.getDescription().contains("group-one"),
                "backend group notes must not leak through the external tool");
    }

    private static SchemaOnlyTool schemaOnly(String name) {
        return new SchemaOnlyTool(
                ToolSchema.builder()
                        .name(name)
                        .description("external " + name)
                        .parameters(Map.of("type", "object"))
                        .build());
    }

    /** Finds the reset_equipped_tools schema.
     *
     * @param schemas the composed schemas
     * @return the meta tool's schema
     */
    private static ToolSchema metaSchema(List<ToolSchema> schemas) {
        return schemas.stream()
                .filter(s -> "reset_equipped_tools".equals(s.getName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName(
            "enum and description both keep only granted groups; name-reachable group kept in enum")
    void enumAndDescriptionFiltering() {
        Toolkit toolkit = grantToolkit();

        // Group-granted surface: g1 in both channels; g2 in neither.
        CapabilitySurface granted = new CapabilitySurface(null, Set.of("g1"), Set.of());
        ToolSchema meta = metaSchema(toolkit.getToolSchemas(List.of("g1", "g2"), null, granted));
        List<String> enumValues = toActivateEnum(meta);
        assertNotNull(enumValues, "enum must be present when activatable groups exist");
        assertTrue(enumValues.contains("g1"));
        assertFalse(enumValues.contains("g2"), "un-granted group must not be activatable");
        assertTrue(meta.getDescription().contains("group-one"), "granted group description kept");
        assertFalse(
                meta.getDescription().contains("group-two"),
                "un-granted group description must not leak via description");
        assertFalse(
                meta.getDescription().contains("g2:"),
                "un-granted group NAME must not leak via description");

        // Name-reachable surface: g2 enters the enum because t2 is per-name authorized.
        CapabilitySurface reachable = new CapabilitySurface(null, Set.of("g1"), Set.of("t2"));
        ToolSchema reachableMeta =
                metaSchema(toolkit.getToolSchemas(List.of("g1", "g2"), null, reachable));
        assertTrue(
                toActivateEnum(reachableMeta).contains("g2"),
                "name-reachable group is activatable");
    }

    @Test
    @DisplayName("DENY_ALL yields no enum key; a null surface = no trimming (direct trusted path)")
    void denyAllDropsEnumKeyNullSurfaceUntrimmed() {
        Toolkit toolkit = grantToolkit();
        assertNull(
                toActivateEnum(
                        metaSchema(
                                toolkit.getToolSchemas(
                                        List.of("g1", "g2"), null, CapabilitySurface.DENY_ALL))),
                "DENY_ALL → no activatable groups");
        assertNotNull(
                toActivateEnum(metaSchema(toolkit.getToolSchemas(List.of("g1", "g2"), null, null))),
                "null surface → un-trimmed compose, same as the 2-arg path");
    }

    /**
     * Reads the {@code to_activate} enum values.
     *
     * @param meta the reset_equipped_tools schema
     * @return the enum values, or {@code null} when the enum key is absent
     */
    @SuppressWarnings("unchecked")
    private static List<String> toActivateEnum(ToolSchema meta) {
        Map<String, Object> properties =
                (Map<String, Object>) meta.getParameters().get("properties");
        if (properties == null) {
            return null;
        }
        Map<String, Object> toActivate = (Map<String, Object>) properties.get("to_activate");
        if (toActivate == null) {
            return null;
        }
        Map<String, Object> items = (Map<String, Object>) toActivate.get("items");
        if (items == null) {
            return null;
        }
        Object enumObj = items.get("enum");
        return enumObj instanceof List<?> list ? (List<String>) list : null;
    }

    @Test
    @DisplayName("legacy overloads (0/1 args) remain surface-unaware")
    void legacyOverloadsUnaffected() {
        Toolkit toolkit = grantToolkit();
        // Shared flags: g1 active, g2 inactive → t1 + t3 (ungrouped) + meta; t2 hidden.
        assertEquals(3, toolkit.getToolSchemas().size(), "0-arg ignores surface and grant");
        assertEquals(
                3, toolkit.getToolSchemas(List.of("g1")).size(), "1-arg: g1 + ungrouped + meta");
    }

    // ==================== reset_equipped_tools gate ====================

    @Test
    @DisplayName("activating an un-granted group is rejected with the not-found wording")
    void resetGateRejectsUngrantedGroup() {
        Toolkit toolkit = grantToolkit();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .agentState(
                                AgentState.builder()
                                        .toolContext(ToolContextState.builder().build())
                                        .build())
                        .build();
        ctx.put(CapabilitySurface.class, new CapabilitySurface(null, Set.of("g1"), Set.of()));

        Map<String, Object> input = Map.of("to_activate", List.of("g2"));
        ToolResultBlock result =
                toolkit.callTool(param("reset_equipped_tools", input, ctx)).block();

        assertEquals(
                "Error: Tool group 'g2' does not exist",
                firstText(result),
                "deny wording must be byte-identical to the not-found wording");
    }

    @Test
    @DisplayName("surface = null (direct path) → unrestricted activation")
    void resetGateTreatsMissingSurfaceAsAllowAll() {
        Toolkit toolkit = grantToolkit();
        ToolContextState tcs = ToolContextState.builder().build();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .agentState(AgentState.builder().toolContext(tcs).build())
                        .build();
        // Direct callAsync so the executor's grant gate does not preempt the tool's own
        // gate; a missing surface means the direct path is unrestricted.
        AgentTool metaTool = toolkit.getTool("reset_equipped_tools");
        ToolResultBlock result = callDirect(metaTool, Map.of("to_activate", List.of("g1")), ctx);

        assertTrue(firstText(result).contains("g1"), "group activated (no surface = unrestricted)");
        assertTrue(tcs.getActivatedGroups().contains("g1"), "per-session activation written");
    }

    @Test
    @DisplayName("granted group activation keeps the replacement semantics")
    void resetGateAllowsGrantedGroup() {
        Toolkit toolkit = grantToolkit();
        ToolContextState tcs = ToolContextState.builder().build();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .agentState(AgentState.builder().toolContext(tcs).build())
                        .build();
        ctx.put(CapabilitySurface.class, new CapabilitySurface(null, Set.of("g1"), Set.of()));
        Map<String, Object> input = Map.of("to_activate", List.of("g1"));
        ToolResultBlock result =
                toolkit.callTool(param("reset_equipped_tools", input, ctx)).block();

        String text = firstText(result);
        assertTrue(text.contains("g1"), "granted group activated");
        assertTrue(text.contains("group-one"), "activated group notes surfaced");
        assertTrue(tcs.getActivatedGroups().contains("g1"), "per-session activation written");
    }

    @Test
    @DisplayName("null entry in to_activate → disguised not-found wording, never an NPE")
    void resetGateNullEntryIsDisguisedNotFound() {
        Toolkit toolkit = grantToolkit();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .agentState(
                                AgentState.builder()
                                        .toolContext(ToolContextState.builder().build())
                                        .build())
                        .build();
        ctx.put(CapabilitySurface.class, new CapabilitySurface(null, Set.of("g1"), Set.of()));
        AgentTool metaTool = toolkit.getTool("reset_equipped_tools");

        ToolResultBlock result =
                callDirect(metaTool, Map.of("to_activate", Arrays.asList("g1", null)), ctx);

        assertEquals(
                "Error: Tool group 'null' does not exist",
                firstText(result),
                "a null entry must hit the disguised not-found wording, not a CHM NPE"
                        + " (which the catch-all would turn into a null-message error block)");
    }

    // ==================== skill visibility ====================

    @Test
    @DisplayName("un-granted skills are cut from the prompt; granted and system kept")
    void skillVisibilityFiltering() {
        AgentSkill granted = skill("alpha");
        AgentSkill denied = skill("beta");
        AgentSkill systemSkill =
                AgentSkill.builder()
                        .name("gamma")
                        .description("system skill")
                        .skillContent("# gamma")
                        .system(true)
                        .build();
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(
                        List.of(new StubRepo(List.of(granted, denied, systemSkill))),
                        new Toolkit(),
                        null,
                        false,
                        null);

        String prompt =
                mw.onSystemPrompt(
                                null,
                                rc(new CapabilitySurface(Set.of("alpha"), null, null)),
                                "base")
                        .block();

        assertNotNull(prompt);
        assertTrue(prompt.contains("alpha"), "granted skill kept");
        assertTrue(prompt.contains("gamma"), "system skill kept");
        assertFalse(prompt.contains("beta"), "un-granted skill cut from the prompt");
    }

    @Test
    @DisplayName("missing surface → no skill prompt at all (fail-closed)")
    void skillFilterFailsClosedOnMissingSurface() {
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(
                        List.of(new StubRepo(List.of(skill("alpha")))),
                        new Toolkit(),
                        null,
                        false,
                        null);
        RuntimeContext ctx = rc();
        String prompt = mw.onSystemPrompt(null, ctx, "base").block();
        assertEquals("base", prompt, "no surface → no skill prompt");
        assertNull(ctx.get(SkillBox.class), "no box exposed");
    }

    @Test
    @DisplayName("different surfaces hit different box-cache entries (no cross-talk)")
    void boxCacheIsolationAcrossSurfaces() {
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(
                        List.of(new StubRepo(List.of(skill("alpha"), skill("beta")))),
                        new Toolkit(),
                        null,
                        false,
                        null);

        RuntimeContext rc1 = rc(new CapabilitySurface(Set.of("alpha"), null, null));
        mw.onSystemPrompt(null, rc1, "base").block();
        SkillBox box1 = rc1.get(SkillBox.class);

        RuntimeContext rc2 = rc(new CapabilitySurface(Set.of("beta"), null, null));
        mw.onSystemPrompt(null, rc2, "base").block();
        SkillBox box2 = rc2.get(SkillBox.class);

        assertNotNull(box1);
        assertNotNull(box2);
        assertTrue(box1 != box2, "different grants must materialize different boxes");
        assertTrue(box1.getAllSkillNames().contains("alpha"));
        assertFalse(box1.getAllSkillNames().contains("beta"));
        assertTrue(box2.getAllSkillNames().contains("beta"));
        assertFalse(box2.getAllSkillNames().contains("alpha"));
    }

    // ==================== tool execution gate ====================

    @Test
    @DisplayName("un-granted backend tool call → disguised not-found wording (no existence oracle)")
    void executorDeniesUngrantedTool() {
        Toolkit toolkit = grantToolkit();
        ToolResultBlock result =
                call(toolkit, "t2", rc(new CapabilitySurface(null, Set.of("g1"), Set.of())));
        assertEquals("Error: Tool not found: t2", firstText(result));
    }

    @Test
    @DisplayName("DENY_ALL surface still allows system tools")
    void executorAllowsSystemToolsUnderDenyAll() {
        Toolkit toolkit = grantToolkit();
        // reset_equipped_tools (system): must pass the grant gate (then fail later on missing
        // AgentState — distinct wording proves the grant gate was passed).
        ToolResultBlock result =
                call(toolkit, "reset_equipped_tools", rc(CapabilitySurface.DENY_ALL));
        assertNotUnauthorized(result);
    }

    @Test
    @DisplayName("missing surface (direct path) → treated as unrestricted")
    void executorTreatsMissingSurfaceAsAllowAll() {
        Toolkit toolkit = grantToolkit();
        ToolResultBlock result = call(toolkit, "t1", rc());
        assertNotUnauthorized(result);
    }

    @Test
    @DisplayName("external tools bypass the grant gate (suspended short-circuit)")
    void externalToolsBypassGrantGate() {
        Toolkit toolkit = new Toolkit();
        ToolRequestConfig config =
                new ToolRequestConfig(
                        Map.of("ext_tool", schemaOnly("ext_tool")),
                        ToolMergeMode.MERGE_EXTERNAL_PRIORITY);
        RuntimeContext ctx = rc(CapabilitySurface.DENY_ALL);
        ctx.setToolRequestConfig(config);
        ToolResultBlock result = toolkit.callTool(param("ext_tool", Map.of(), ctx)).block();

        assertTrue(result.isSuspended(), "external tool reaches the suspended short-circuit");
    }

    private static void assertNotUnauthorized(ToolResultBlock result) {
        String text = firstText(result);
        assertFalse(
                text.startsWith("Unauthorized tool call"),
                "system tool must pass the grant gate; got: " + text);
        assertFalse(
                text.startsWith("Error: Tool not found"),
                "the grant gate must not deny this call (disguised wording); got: " + text);
    }

    // ==================== skill load gate ====================

    @Test
    @DisplayName("Dynamic path: un-granted skill load → not-found wording; granted loads")
    void dynamicLoadPathGate() {
        Toolkit toolkit = new Toolkit();
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(
                        List.of(new StubRepo(List.of(skill("alpha"), skill("beta")))),
                        toolkit,
                        null,
                        false,
                        null);
        RuntimeContext ctx = rc(new CapabilitySurface(Set.of("alpha"), null, null));
        mw.onSystemPrompt(null, ctx, "base").block();
        assertNotNull(ctx.get(SkillBox.class));

        ToolResultBlock denied = loadViaToolkit(toolkit, "beta", ctx);
        assertEquals(
                "Error: Skill not found: 'beta'. Please check the skill name.",
                firstText(denied),
                "deny wording must be byte-identical to the not-found wording");

        ToolResultBlock allowed = loadViaToolkit(toolkit, "alpha", ctx);
        assertTrue(firstText(allowed).contains("Successfully loaded skill: alpha"));
    }

    @Test
    @DisplayName("Dynamic path: system skill loads even under a restricted surface")
    void dynamicLoadPathSystemSkill() {
        Toolkit toolkit = new Toolkit();
        AgentSkill systemSkill =
                AgentSkill.builder()
                        .name("gamma")
                        .description("system skill")
                        .skillContent("# gamma")
                        .system(true)
                        .build();
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(
                        List.of(new StubRepo(List.of(systemSkill))), toolkit, null, false, null);
        RuntimeContext ctx = rc(new CapabilitySurface(Set.of(), null, null));
        mw.onSystemPrompt(null, ctx, "base").block();

        ToolResultBlock result = loadViaToolkit(toolkit, "gamma", ctx);
        assertTrue(firstText(result).contains("Successfully loaded skill: gamma"));
    }

    @Test
    @DisplayName("Static path: direct load via the static skillBox tool is gated too")
    void staticLoadPathGate() {
        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill("alpha"));
        box.registerSkill(skill("secret"));
        box.registerSkillLoadTool();
        AgentTool loadTool = toolkit.getTool("load_skill_through_path");
        assertNotNull(loadTool);

        RuntimeContext ctx = rc(new CapabilitySurface(Set.of("alpha"), null, null));
        ToolResultBlock denied =
                callDirect(loadTool, Map.of("skillName", "secret", "path", "SKILL.md"), ctx);
        assertEquals(
                "Error: Skill not found: 'secret'. Please check the skill name.",
                firstText(denied));

        ToolResultBlock allowed =
                callDirect(loadTool, Map.of("skillName", "alpha", "path", "SKILL.md"), ctx);
        assertTrue(firstText(allowed).contains("Successfully loaded skill: alpha"));
    }

    @Test
    @DisplayName("missing surface (direct path) → unrestricted load")
    void loadGateTreatsMissingSurfaceAsAllowAll() {
        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        AgentSkill systemSkill =
                AgentSkill.builder()
                        .name("gamma")
                        .description("system skill")
                        .skillContent("# gamma")
                        .system(true)
                        .build();
        box.registerSkill(systemSkill);
        box.registerSkillLoadTool();
        AgentTool loadTool = toolkit.getTool("load_skill_through_path");

        // Direct callAsync so the executor gate does not preempt the load-gate check.
        ToolResultBlock result =
                callDirect(loadTool, Map.of("skillName", "gamma", "path", "SKILL.md"), rc());
        assertTrue(
                firstText(result).contains("Successfully loaded skill: gamma"),
                "no surface (direct path) = unrestricted: " + firstText(result));
    }

    /** Loads SKILL.md through the toolkit entry (executor + load gate). */
    private ToolResultBlock loadViaToolkit(Toolkit toolkit, String skillName, RuntimeContext ctx) {
        return toolkit.callTool(
                        param(
                                "load_skill_through_path",
                                Map.of("skillName", skillName, "path", "SKILL.md"),
                                ctx))
                .block();
    }

    // ==================== end to end ====================

    @Test
    @DisplayName("e2e: resolver grants 1 group; model sees only granted tools in the loop")
    void endToEndSchemaInViewOfModel() {
        Toolkit toolkit = grantToolkit();
        MockModel model = new MockModel("final");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(model)
                        .toolkit(toolkit)
                        .capabilityResolver(
                                (ctx, a) -> new CapabilitySurface(null, Set.of("g1"), Set.of()))
                        .build();
        agent.call(userMsg("hi"), rc()).block(Duration.ofSeconds(10));

        List<String> names = model.getLastTools().stream().map(ToolSchema::getName).toList();
        assertTrue(names.contains("t1"), "granted group member visible to the model");
        assertTrue(names.contains("reset_equipped_tools"), "system meta tool visible");
        assertFalse(names.contains("t2"), "un-granted group member invisible to the model");
    }

    @Test
    @DisplayName("e2e: model insists on a cut-off tool → ToolResult error feeds back to the model")
    void endToEndForcedCallIsDenied() {
        Toolkit toolkit = grantToolkit();
        AtomicInteger calls = new AtomicInteger();
        MockModel model =
                new MockModel(
                        msgs -> {
                            if (calls.getAndIncrement() == 0) {
                                Map<String, Object> args = new HashMap<>();
                                return List.of(
                                        ChatResponse.builder()
                                                .id("m1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call-1")
                                                                        .name("t2")
                                                                        .input(args)
                                                                        .build()))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .id("m2")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("final")
                                                                    .build()))
                                            .build());
                        });
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(model)
                        .toolkit(toolkit)
                        .capabilityResolver(
                                (ctx, a) -> new CapabilitySurface(null, Set.of("g1"), Set.of()))
                        .build();
        agent.call(userMsg("hi"), rc()).block(Duration.ofSeconds(15));

        String toolResultText =
                model.getLastMessages().stream()
                        .flatMap(m -> m.getContent().stream())
                        .filter(b -> b instanceof ToolResultBlock)
                        .map(b -> (ToolResultBlock) b)
                        .map(CapabilityGrantIntegrationTest::firstText)
                        .filter(t -> t.contains("Tool not found"))
                        .findFirst()
                        .orElse(null);
        assertEquals("Error: Tool not found: t2", toolResultText);
    }

    /** A single-user-message input list. */
    private static List<Msg> userMsg(String text) {
        return List.of(
                UserMessage.builder().content(TextBlock.builder().text(text).build()).build());
    }
}
