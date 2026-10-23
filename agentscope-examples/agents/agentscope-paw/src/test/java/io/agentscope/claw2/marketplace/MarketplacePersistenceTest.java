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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests for {@link MarketplacePersistence}: persisting marketplace mutations to
 * {@code agentscope.json} atomically and pushing changes through to {@link
 * ClawMarketplaceRegistry}. The registry is mocked so we can verify the reload/unregister
 * contract without standing up real marketplace implementations.
 */
class MarketplacePersistenceTest {

    @TempDir Path tempDir;

    private Path configPath;
    private ClawBootstrap bootstrap;
    private ClawMarketplaceRegistry registry;
    private MarketplacePersistence persistence;

    @BeforeEach
    void setUp() {
        configPath = tempDir.resolve("agentscope.json");
        bootstrap = mock(ClawBootstrap.class);
        when(bootstrap.configPath()).thenReturn(configPath);
        registry = mock(ClawMarketplaceRegistry.class);
        persistence = new MarketplacePersistence(bootstrap, registry);
    }

    @Test
    void mutate_addsEntryAndPersists() throws Exception {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType("git");
        entry.setProperty("remoteUrl", "https://example.com/x.git");

        String result =
                persistence.mutate(
                        map -> {
                            map.put("git-a", entry);
                            return "ok";
                        },
                        List.of("git-a"));

        assertEquals("ok", result);
        assertTrue(Files.isRegularFile(configPath));
        ObjectMapper mapper = new ObjectMapper();
        AgentscopeConfig persisted = mapper.readValue(configPath.toFile(), AgentscopeConfig.class);
        assertNotNull(persisted.getMarketplaces());
        assertTrue(persisted.getMarketplaces().containsKey("git-a"));
        assertEquals("git", persisted.getMarketplaces().get("git-a").getType());
        assertEquals(
                "https://example.com/x.git",
                persisted.getMarketplaces().get("git-a").getProperties().get("remoteUrl"));

        ArgumentCaptor<MarketplaceConfigEntry> reloaded =
                ArgumentCaptor.forClass(MarketplaceConfigEntry.class);
        verify(registry).reload(eq("git-a"), reloaded.capture());
        assertEquals("git", reloaded.getValue().getType());
    }

    @Test
    void mutate_removingEntry_callsUnregister() throws Exception {
        // First seed the config with an entry.
        writeConfigWithSingleEntry("doomed", "git", Map.of("remoteUrl", "https://x"));

        persistence.mutate(
                map -> {
                    map.remove("doomed");
                    return null;
                },
                List.of("doomed"));

        verify(registry).unregister("doomed");
        verify(registry, never()).reload(anyString(), any());

        ObjectMapper mapper = new ObjectMapper();
        AgentscopeConfig persisted = mapper.readValue(configPath.toFile(), AgentscopeConfig.class);
        assertFalse(persisted.getMarketplaces().containsKey("doomed"));
    }

    @Test
    void mutate_buildFailure_isReportedAsBadRequest() {
        when(registry.reload(anyString(), any()))
                .thenThrow(new IllegalArgumentException("bad remote"));

        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType("git");

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                persistence.mutate(
                                        map -> {
                                            map.put("broken", entry);
                                            return null;
                                        },
                                        List.of("broken")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void mutate_writesAtomically_noTempFileLeft() {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType("git");
        entry.setProperty("remoteUrl", "https://x");

        persistence.mutate(
                map -> {
                    map.put("clean", entry);
                    return null;
                },
                List.of("clean"));

        // The atomic-move pattern uses <name>.tmp staged next to the target. Any leftover would
        // suggest the move/rename failed silently.
        try (DirectoryStream<Path> ds =
                Files.newDirectoryStream(
                        tempDir, p -> p.getFileName().toString().endsWith(".tmp"))) {
            List<Path> tmp = new ArrayList<>();
            ds.forEach(tmp::add);
            assertTrue(tmp.isEmpty(), "no .tmp file should remain after successful write");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void mutate_concurrentWrites_serialiseWithoutLostUpdates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int idx = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    MarketplaceConfigEntry e = new MarketplaceConfigEntry();
                                    e.setType("git");
                                    e.setProperty("remoteUrl", "https://x/" + idx);
                                    persistence.mutate(
                                            map -> {
                                                map.put("repo-" + idx, e);
                                                return null;
                                            },
                                            List.of("repo-" + idx));
                                }));
            }
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        ObjectMapper mapper = new ObjectMapper();
        AgentscopeConfig persisted = mapper.readValue(configPath.toFile(), AgentscopeConfig.class);
        // All 20 entries land — proving the writes serialised correctly (no lost updates).
        assertEquals(20, persisted.getMarketplaces().size());
        // Registry saw 20 reload calls in some order.
        verify(registry, atLeastOnce()).reload(anyString(), any());
    }

    @Test
    void currentConfig_returnsLatestOnDisk() throws Exception {
        writeConfigWithSingleEntry("readme", "nacos", Map.of("serverAddr", "host:8848"));

        AgentscopeConfig cfg = persistence.currentConfig();
        assertNotNull(cfg);
        assertTrue(cfg.getMarketplaces().containsKey("readme"));
        assertEquals("nacos", cfg.getMarketplaces().get("readme").getType());
    }

    @Test
    void currentConfig_returnsEmpty_whenConfigMissing() {
        AgentscopeConfig cfg = persistence.currentConfig();
        assertNotNull(cfg);
        assertTrue(cfg.getMarketplaces() == null || cfg.getMarketplaces().isEmpty());
    }

    private void writeConfigWithSingleEntry(String id, String type, Map<String, Object> props)
            throws Exception {
        StringBuilder sb = new StringBuilder("{\n  \"marketplaces\": {\n");
        sb.append("    \"").append(id).append("\": {\n");
        sb.append("      \"type\": \"").append(type).append("\"");
        for (Map.Entry<String, Object> e : props.entrySet()) {
            sb.append(",\n      \"")
                    .append(e.getKey())
                    .append("\": \"")
                    .append(e.getValue())
                    .append("\"");
        }
        sb.append("\n    }\n  }\n}\n");
        Files.writeString(configPath, sb.toString(), StandardCharsets.UTF_8);
    }
}
