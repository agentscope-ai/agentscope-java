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
package io.agentscope.claw2.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ClawMarketplaceRegistry}. Bypasses the real Git/Nacos stores by
 * subclassing {@link ClawMarketplaceRegistry} to return in-memory fakes from {@code build(...)} —
 * the registry contract (reload-replaces-and-closes, unregister-closes, type-driven dispatch)
 * is type-agnostic, so the fakes are a faithful stand-in.
 */
class ClawMarketplaceRegistryTest {

    @TempDir Path tempDir;

    @Test
    void reload_replacesAndClosesPrevious() {
        TestRegistry registry = new TestRegistry(tempDir);

        ClawMarketplace first = registry.reload("repo", entryOfType("fake"));
        assertTrue(first instanceof FakeMarketplace);
        assertEquals(0, ((FakeMarketplace) first).closeCount.get());
        assertSame(first, registry.find("repo").orElseThrow());

        ClawMarketplace second = registry.reload("repo", entryOfType("fake"));
        assertSame(second, registry.find("repo").orElseThrow());
        // Replaced instance is closed exactly once; new one is still open.
        assertEquals(
                1,
                ((FakeMarketplace) first).closeCount.get(),
                "previous instance must be closed when reload swaps it out");
        assertEquals(0, ((FakeMarketplace) second).closeCount.get());
    }

    @Test
    void unregister_closesInstance() {
        TestRegistry registry = new TestRegistry(tempDir);
        ClawMarketplace mp = registry.reload("repo", entryOfType("fake"));

        assertTrue(registry.unregister("repo"));
        assertFalse(registry.contains("repo"));
        assertEquals(1, ((FakeMarketplace) mp).closeCount.get());
    }

    @Test
    void unregister_unknownId_returnsFalse() {
        TestRegistry registry = new TestRegistry(tempDir);
        assertFalse(registry.unregister("nope"));
    }

    @Test
    void closeAll_closesEverything() {
        TestRegistry registry = new TestRegistry(tempDir);
        ClawMarketplace a = registry.reload("a", entryOfType("fake"));
        ClawMarketplace b = registry.reload("b", entryOfType("fake"));

        registry.closeAll();
        assertTrue(registry.list().isEmpty());
        assertEquals(1, ((FakeMarketplace) a).closeCount.get());
        assertEquals(1, ((FakeMarketplace) b).closeCount.get());
    }

    @Test
    void list_returnsInstancesSortedById() {
        TestRegistry registry = new TestRegistry(tempDir);
        registry.reload("zeta", entryOfType("fake"));
        registry.reload("alpha", entryOfType("fake"));
        registry.reload("mike", entryOfType("fake"));

        List<ClawMarketplace> list = registry.list();
        assertEquals(
                List.of("alpha", "mike", "zeta"), list.stream().map(ClawMarketplace::id).toList());
    }

    @Test
    void build_unknownType_throws() {
        ClawMarketplaceRegistry registry =
                new ClawMarketplaceRegistry(stubBootstrap(tempDir, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.build("x", entryOfType("totally-unsupported")));
    }

    @Test
    void build_missingType_throws() {
        ClawMarketplaceRegistry registry =
                new ClawMarketplaceRegistry(stubBootstrap(tempDir, null));
        MarketplaceConfigEntry blank = new MarketplaceConfigEntry();
        assertThrows(IllegalArgumentException.class, () -> registry.build("x", blank));
    }

    @Test
    void build_gitWithoutRemoteUrl_throws() {
        ClawMarketplaceRegistry registry =
                new ClawMarketplaceRegistry(stubBootstrap(tempDir, null));
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType("git");
        assertThrows(IllegalArgumentException.class, () -> registry.build("x", entry));
    }

    @Test
    void build_nacosWithoutServerAddr_throws() {
        ClawMarketplaceRegistry registry =
                new ClawMarketplaceRegistry(stubBootstrap(tempDir, null));
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType("nacos");
        assertThrows(IllegalArgumentException.class, () -> registry.build("x", entry));
    }

    @Test
    void initFromBootstrapConfig_loadsEveryDeclaredEntry() {
        Map<String, MarketplaceConfigEntry> declared = new LinkedHashMap<>();
        declared.put("a", entryOfType("fake"));
        declared.put("b", entryOfType("fake"));
        AgentscopeConfig cfg = new AgentscopeConfig();
        cfg.setMarketplaces(declared);

        TestRegistry registry = new TestRegistry(stubBootstrap(tempDir, cfg));
        registry.initFromBootstrapConfig();

        assertTrue(registry.contains("a"));
        assertTrue(registry.contains("b"));
    }

    @Test
    void initFromBootstrapConfig_tolerates_individualFailures() {
        Map<String, MarketplaceConfigEntry> declared = new LinkedHashMap<>();
        declared.put("good", entryOfType("fake"));
        declared.put("broken", entryOfType("explode")); // FakeBuilder throws on this type
        AgentscopeConfig cfg = new AgentscopeConfig();
        cfg.setMarketplaces(declared);

        TestRegistry registry = new TestRegistry(stubBootstrap(tempDir, cfg));
        // Must not throw — failures are logged, not propagated, so claw still boots.
        registry.initFromBootstrapConfig();

        assertTrue(registry.contains("good"));
        assertFalse(registry.contains("broken"));
    }

    // ---------- helpers ----------

    private static MarketplaceConfigEntry entryOfType(String type) {
        MarketplaceConfigEntry e = new MarketplaceConfigEntry();
        e.setType(type);
        return e;
    }

    private static ClawBootstrap stubBootstrap(Path tempDir, AgentscopeConfig cfg) {
        ClawBootstrap mock = org.mockito.Mockito.mock(ClawBootstrap.class);
        org.mockito.Mockito.when(mock.clawHome()).thenReturn(tempDir);
        org.mockito.Mockito.when(mock.loadedConfig()).thenReturn(cfg);
        return mock;
    }

    /**
     * Registry subclass that returns an in-memory {@link FakeMarketplace} so tests never touch
     * the network. Recognises the {@code "fake"} type plus an {@code "explode"} type that throws
     * to exercise the failure-recovery path.
     */
    private static final class TestRegistry extends ClawMarketplaceRegistry {

        TestRegistry(Path tempDir) {
            super(stubBootstrap(tempDir, null));
        }

        TestRegistry(ClawBootstrap bootstrap) {
            super(bootstrap);
        }

        @Override
        public ClawMarketplace build(String id, MarketplaceConfigEntry entry) {
            String type = entry.getType();
            if ("explode".equals(type)) {
                throw new IllegalArgumentException("synthetic failure for " + id);
            }
            if ("fake".equals(type)) {
                return new FakeMarketplace(id);
            }
            return super.build(id, entry);
        }
    }

    /** Minimal {@link ClawMarketplace} that records close calls for assertions. */
    private static final class FakeMarketplace implements ClawMarketplace {

        final AtomicInteger closeCount = new AtomicInteger();
        private final String id;

        FakeMarketplace(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String displayLocation() {
            return "in-memory://" + id;
        }

        @Override
        public List<MarketSkillSummary> list() {
            return List.of();
        }

        @Override
        public MarketSkillContent fetch(String name) {
            return null;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
