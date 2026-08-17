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
package io.agentscope.claw2.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.claw2.marketplace.ClawMarketplace;
import io.agentscope.claw2.marketplace.ClawMarketplaceRegistry;
import io.agentscope.claw2.marketplace.MarketSkillContent;
import io.agentscope.claw2.marketplace.MarketSkillSummary;
import io.agentscope.claw2.marketplace.MarketplacePersistence;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link MarketplacesController}. The controller is built directly with mocked
 * {@link ClawMarketplaceRegistry} and {@link MarketplacePersistence}: it has no other
 * collaborators, so we can exercise its routing, validation, and secret-stripping in isolation
 * without standing up a Spring context.
 */
class MarketplacesControllerTest {

    private ClawMarketplaceRegistry registry;
    private MarketplacePersistence persistence;
    private MarketplacesController controller;

    @BeforeEach
    void setUp() {
        registry = mock(ClawMarketplaceRegistry.class);
        persistence = mock(MarketplacePersistence.class);
        controller = new MarketplacesController(registry, persistence);
    }

    // -----------------------------------------------------------------
    //  list
    // -----------------------------------------------------------------

    @Test
    void list_returnsSortedSummariesWithSecretsStripped() {
        Map<String, MarketplaceConfigEntry> map = new LinkedHashMap<>();
        map.put("zeta", gitEntry("https://example.com/zeta.git"));
        MarketplaceConfigEntry nacos = nacosEntry("nacos:8848");
        nacos.setProperty("password", "leaked-secret"); // never echo
        nacos.setProperty("secretKey", "also-leaked");
        map.put("alpha", nacos);
        AgentscopeConfig cfg = new AgentscopeConfig();
        cfg.setMarketplaces(map);
        when(persistence.currentConfig()).thenReturn(cfg);

        List<MarketplacesController.MarketplaceSummary> out = controller.listMarketplaces().block();
        assertNotNull(out);
        assertEquals(2, out.size());
        assertEquals("alpha", out.get(0).id());
        assertEquals("zeta", out.get(1).id());
        assertFalse(out.get(0).properties().containsKey("password"));
        assertFalse(out.get(0).properties().containsKey("secretKey"));
        // Non-secret properties survive.
        assertEquals("nacos:8848", out.get(0).properties().get("serverAddr"));
    }

    @Test
    void list_emptyConfig_returnsEmptyList() {
        when(persistence.currentConfig()).thenReturn(new AgentscopeConfig());
        List<MarketplacesController.MarketplaceSummary> out = controller.listMarketplaces().block();
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    // -----------------------------------------------------------------
    //  create
    // -----------------------------------------------------------------

    @Test
    void create_callsPersistenceAndReturnsSummary() {
        primeMutateToInvoke();

        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest(
                        "team-git", "git", Map.of("remoteUrl", "https://example/g.git"));
        MarketplacesController.MarketplaceSummary out = controller.createMarketplace(req).block();

        assertNotNull(out);
        assertEquals("team-git", out.id());
        assertEquals("git", out.type());
        assertEquals("https://example/g.git", out.properties().get("remoteUrl"));
        verify(persistence).mutate(any(), eq(List.of("team-git")));
    }

    @Test
    void create_rejectsBlankId() {
        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest("", "git", Map.of());
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createMarketplace(req).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_rejectsInvalidIdCharacters() {
        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest("has space", "git", Map.of());
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createMarketplace(req).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_rejectsUnsupportedType() {
        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest("x", "ftp", Map.of());
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createMarketplace(req).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_gitRequiresRemoteUrl() {
        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest("g", "git", Map.of());
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createMarketplace(req).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_nacosRequiresServerAddr() {
        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest("n", "nacos", Map.of());
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createMarketplace(req).block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void create_conflict_whenIdAlreadyExists() {
        // The persistence mutate runs the supplied function inline so the conflict check trips.
        Map<String, MarketplaceConfigEntry> live = new LinkedHashMap<>();
        live.put("dup", gitEntry("https://x"));
        when(persistence.mutate(any(), any()))
                .thenAnswer(
                        inv -> {
                            MarketplacePersistence.MutationFn<?> fn = inv.getArgument(0);
                            return fn.apply(live);
                        });

        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest(
                        "dup", "git", Map.of("remoteUrl", "https://y"));
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.createMarketplace(req).block());
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // -----------------------------------------------------------------
    //  update / delete
    // -----------------------------------------------------------------

    @Test
    void update_pathIdWinsOverBodyId() {
        Map<String, MarketplaceConfigEntry> live = new LinkedHashMap<>();
        live.put("real-id", gitEntry("https://example/a.git"));
        when(persistence.mutate(any(), any()))
                .thenAnswer(
                        inv -> {
                            MarketplacePersistence.MutationFn<?> fn = inv.getArgument(0);
                            return fn.apply(live);
                        });

        // Body id "imposter" is ignored; mutation is applied to path id "real-id".
        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest(
                        "imposter", "git", Map.of("remoteUrl", "https://example/b.git"));
        MarketplacesController.MarketplaceSummary out =
                controller.updateMarketplace("real-id", req).block();
        assertNotNull(out);
        assertEquals("real-id", out.id());
        assertEquals("https://example/b.git", out.properties().get("remoteUrl"));
        assertFalse(live.containsKey("imposter"));
    }

    @Test
    void update_404_whenIdMissing() {
        Map<String, MarketplaceConfigEntry> live = new LinkedHashMap<>();
        when(persistence.mutate(any(), any()))
                .thenAnswer(
                        inv -> {
                            MarketplacePersistence.MutationFn<?> fn = inv.getArgument(0);
                            return fn.apply(live);
                        });

        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest(
                        "ghost", "git", Map.of("remoteUrl", "https://x"));
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.updateMarketplace("ghost", req).block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void delete_404_whenIdMissing() {
        Map<String, MarketplaceConfigEntry> live = new LinkedHashMap<>();
        when(persistence.mutate(any(), any()))
                .thenAnswer(
                        inv -> {
                            MarketplacePersistence.MutationFn<?> fn = inv.getArgument(0);
                            return fn.apply(live);
                        });

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.deleteMarketplace("ghost").block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void delete_removesEntry() {
        Map<String, MarketplaceConfigEntry> live = new LinkedHashMap<>();
        live.put("doomed", gitEntry("https://x"));
        when(persistence.mutate(any(), any()))
                .thenAnswer(
                        inv -> {
                            MarketplacePersistence.MutationFn<?> fn = inv.getArgument(0);
                            return fn.apply(live);
                        });

        controller.deleteMarketplace("doomed").block();
        assertFalse(live.containsKey("doomed"));
    }

    // -----------------------------------------------------------------
    //  test connection
    // -----------------------------------------------------------------

    @Test
    void testTransient_buildsProbeListsAndClosesEvenOnSuccess() {
        FakeMarketplace probe = new FakeMarketplace("probe", List.of("a", "b"));
        when(registry.build(eq("probe"), any())).thenReturn(probe);

        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest(
                        "probe", "git", Map.of("remoteUrl", "https://x"));
        MarketplacesController.TestConnectionResult res = controller.testTransient(req).block();

        assertNotNull(res);
        assertTrue(res.ok(), "expected ok=true on successful probe");
        assertEquals(2, res.skillCount());
        assertEquals(1, probe.closeCount.get(), "probe instance must be closed in finally");
    }

    @Test
    void testTransient_returnsErrorWhenListThrows() {
        FakeMarketplace probe =
                new FakeMarketplace("probe", List.of()) {
                    @Override
                    public List<MarketSkillSummary> list() {
                        throw new RuntimeException("network down");
                    }
                };
        when(registry.build(eq("probe"), any())).thenReturn(probe);

        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest(
                        "probe", "git", Map.of("remoteUrl", "https://x"));
        MarketplacesController.TestConnectionResult res = controller.testTransient(req).block();

        assertNotNull(res);
        assertFalse(res.ok());
        assertNotNull(res.message());
        assertTrue(res.message().contains("network down"));
        // Even on list failure, close must still run.
        assertEquals(1, probe.closeCount.get());
    }

    @Test
    void testTransient_returnsErrorWhenBuildThrows() {
        when(registry.build(eq("bad"), any()))
                .thenThrow(new IllegalArgumentException("missing remoteUrl"));

        MarketplacesController.MarketplaceWriteRequest req =
                new MarketplacesController.MarketplaceWriteRequest(
                        "bad", "git", Map.of("remoteUrl", "https://x"));
        MarketplacesController.TestConnectionResult res = controller.testTransient(req).block();
        assertNotNull(res);
        assertFalse(res.ok());
        assertTrue(res.message().contains("missing remoteUrl"));
        assertNull(res.skillCount());
    }

    @Test
    void testExisting_404_whenIdMissingInConfig() {
        when(persistence.currentConfig()).thenReturn(new AgentscopeConfig());
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.testExisting("nope").block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void testExisting_loadsEntryFromConfigAndProbes() {
        AgentscopeConfig cfg = new AgentscopeConfig();
        Map<String, MarketplaceConfigEntry> map = new LinkedHashMap<>();
        map.put("known", gitEntry("https://x"));
        cfg.setMarketplaces(map);
        when(persistence.currentConfig()).thenReturn(cfg);

        FakeMarketplace probe = new FakeMarketplace("known", List.of("only"));
        when(registry.build(eq("known"), any())).thenReturn(probe);

        MarketplacesController.TestConnectionResult res = controller.testExisting("known").block();
        assertNotNull(res);
        assertTrue(res.ok());
        assertEquals(1, res.skillCount());
        assertEquals(1, probe.closeCount.get());
    }

    // -----------------------------------------------------------------
    //  list / fetch skills
    // -----------------------------------------------------------------

    @Test
    void listSkills_returnsSortedBriefsFromRegistered() {
        FakeMarketplace mp = new FakeMarketplace("git-a", List.of("zeta", "alpha", "mike"));
        when(registry.find("git-a")).thenReturn(Optional.of(mp));

        List<MarketplacesController.MarketSkillBrief> out = controller.listSkills("git-a").block();
        assertNotNull(out);
        assertEquals(List.of("alpha", "mike", "zeta"), out.stream().map(b -> b.name()).toList());
    }

    @Test
    void listSkills_404_whenIdNotRegistered() {
        when(registry.find("ghost")).thenReturn(Optional.empty());
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.listSkills("ghost").block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void listSkills_502_whenUpstreamThrows() {
        FakeMarketplace mp =
                new FakeMarketplace("git-a", List.of()) {
                    @Override
                    public List<MarketSkillSummary> list() {
                        throw new RuntimeException("upstream-down");
                    }
                };
        when(registry.find("git-a")).thenReturn(Optional.of(mp));

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.listSkills("git-a").block());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    @Test
    void getSkill_returnsContent() {
        FakeMarketplace mp = new FakeMarketplace("git-a", List.of("only"));
        when(registry.find("git-a")).thenReturn(Optional.of(mp));

        MarketplacesController.MarketSkillDetail out = controller.getSkill("git-a", "only").block();
        assertNotNull(out);
        assertEquals("only", out.name());
        assertTrue(out.markdown().contains("body"));
        assertEquals("res-content", out.resources().get("res.txt"));
    }

    @Test
    void getSkill_404_whenSkillMissing() {
        FakeMarketplace mp = new FakeMarketplace("git-a", List.of());
        when(registry.find("git-a")).thenReturn(Optional.of(mp));
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.getSkill("git-a", "nope").block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void getSkill_400_whenNameBlank() {
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.getSkill("git-a", "  ").block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void getSkill_502_whenUpstreamThrows() {
        FakeMarketplace mp =
                new FakeMarketplace("git-a", List.of("name")) {
                    @Override
                    public MarketSkillContent fetch(String name) {
                        throw new RuntimeException("network broken");
                    }
                };
        when(registry.find("git-a")).thenReturn(Optional.of(mp));
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.getSkill("git-a", "name").block());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    // -----------------------------------------------------------------
    //  helpers
    // -----------------------------------------------------------------

    private static MarketplaceConfigEntry gitEntry(String remoteUrl) {
        MarketplaceConfigEntry e = new MarketplaceConfigEntry();
        e.setType("git");
        e.setProperty("remoteUrl", remoteUrl);
        return e;
    }

    private static MarketplaceConfigEntry nacosEntry(String serverAddr) {
        MarketplaceConfigEntry e = new MarketplaceConfigEntry();
        e.setType("nacos");
        e.setProperty("serverAddr", serverAddr);
        return e;
    }

    /** Make {@code persistence.mutate(fn, ids)} pass an empty map to the fn (no-op storage). */
    private void primeMutateToInvoke() {
        Map<String, MarketplaceConfigEntry> live = new LinkedHashMap<>();
        when(persistence.mutate(any(), any()))
                .thenAnswer(
                        inv -> {
                            MarketplacePersistence.MutationFn<?> fn = inv.getArgument(0);
                            return fn.apply(live);
                        });
    }

    /** In-memory marketplace for assertions on dispatch + close lifecycle. */
    private static class FakeMarketplace implements ClawMarketplace {

        private final String id;
        private final List<String> skillNames;
        final AtomicInteger closeCount = new AtomicInteger();

        FakeMarketplace(String id, List<String> skillNames) {
            this.id = id;
            this.skillNames = skillNames;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String type() {
            return "git";
        }

        @Override
        public String displayLocation() {
            return "fake://" + id;
        }

        @Override
        public List<MarketSkillSummary> list() {
            return skillNames.stream()
                    .map(n -> new MarketSkillSummary(n, "desc-" + n, null))
                    .toList();
        }

        @Override
        public MarketSkillContent fetch(String name) {
            if (!skillNames.contains(name)) return null;
            return new MarketSkillContent(
                    name, "desc-" + name, "body of " + name, Map.of("res.txt", "res-content"));
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
