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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.runtime.config.MarketplaceConfigEntry;
import io.agentscope.dataagent.runtime.marketplace.DataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.MarketSkillContent;
import io.agentscope.dataagent.runtime.marketplace.MarketSkillSummary;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplacePersistence;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Per-user marketplaces management. CRUD over rows in {@code dataagent_user_marketplace} (via
 * {@link UserMarketplacePersistence}) plus read-only browsing of the skills exposed by each
 * marketplace through {@link UserMarketplaceRegistry}.
 *
 * <p>Marketplaces are user-private — two users may reuse the same id without colliding and never
 * see each other's entries. The path is mounted under {@code /api/me/marketplaces} so the JWT
 * principal becomes the namespace key. Per-agent install lives on {@code AgentSkillsController}
 * which targets the agent's {@code workspace/skills/}.
 */
@RestController
@RequestMapping("/api/me/marketplaces")
public class MarketplacesController {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    /** Property keys never echoed back in API responses; passwords leak through history otherwise. */
    private static final Set<String> SECRET_KEYS = Set.of("password", "secretKey");

    private static final Set<String> SUPPORTED_TYPES = Set.of("git", "nacos");

    private final UserMarketplaceRegistry registry;
    private final UserMarketplacePersistence persistence;

    public MarketplacesController(
            UserMarketplaceRegistry registry, UserMarketplacePersistence persistence) {
        this.registry = registry;
        this.persistence = persistence;
    }

    // -----------------------------------------------------------------
    //  CRUD
    // -----------------------------------------------------------------

    @GetMapping("")
    public Mono<List<MarketplaceSummary>> listMarketplaces(Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromCallable(
                () -> {
                    Map<String, MarketplaceConfigEntry> declared =
                            persistence.loadAllForUser(userId);
                    if (declared == null || declared.isEmpty()) return List.of();
                    List<MarketplaceSummary> out = new ArrayList<>(declared.size());
                    for (Map.Entry<String, MarketplaceConfigEntry> e : declared.entrySet()) {
                        out.add(toSummary(e.getKey(), e.getValue()));
                    }
                    out.sort(Comparator.comparing(MarketplaceSummary::id));
                    return out;
                });
    }

    @PostMapping("")
    public Mono<MarketplaceSummary> createMarketplace(
            @RequestBody MarketplaceWriteRequest req, Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromCallable(
                () -> {
                    validateRequest(req, true);
                    String id = req.id().trim();
                    if (persistence.exists(userId, id)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Marketplace already exists: " + id);
                    }
                    MarketplaceConfigEntry entry = toConfigEntry(req);
                    persistence.insert(userId, id, entry);
                    return toSummary(id, entry);
                });
    }

    @PutMapping("/{id}")
    public Mono<MarketplaceSummary> updateMarketplace(
            @PathVariable String id,
            @RequestBody MarketplaceWriteRequest req,
            Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromCallable(
                () -> {
                    validateId(id);
                    if (req == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "request body is required");
                    }
                    if (!persistence.exists(userId, id)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Marketplace not found: " + id);
                    }
                    // Body's id is ignored on PUT; the path id wins so a typo can't move the entry.
                    MarketplaceWriteRequest normalized =
                            new MarketplaceWriteRequest(id, req.type(), req.properties());
                    validateRequest(normalized, false);
                    MarketplaceConfigEntry entry = toConfigEntry(normalized);
                    persistence.update(userId, id, entry);
                    return toSummary(id, entry);
                });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteMarketplace(@PathVariable String id, Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromRunnable(
                () -> {
                    validateId(id);
                    if (!persistence.delete(userId, id)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Marketplace not found: " + id);
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Test connection
    // -----------------------------------------------------------------

    @PostMapping("/test")
    public Mono<TestConnectionResult> testTransient(
            @RequestBody MarketplaceWriteRequest req, Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromCallable(
                () -> {
                    validateRequest(req, true);
                    MarketplaceConfigEntry entry = toConfigEntry(req);
                    return probe(userId, req.id().trim(), entry);
                });
    }

    @PostMapping("/{id}/test")
    public Mono<TestConnectionResult> testExisting(@PathVariable String id, Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromCallable(
                () -> {
                    validateId(id);
                    MarketplaceConfigEntry entry =
                            persistence
                                    .load(userId, id)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Marketplace not found: " + id));
                    return probe(userId, id, entry);
                });
    }

    // -----------------------------------------------------------------
    //  Skill listing / fetching
    // -----------------------------------------------------------------

    @GetMapping("/{id}/skills")
    public Mono<List<MarketSkillBrief>> listSkills(@PathVariable String id, Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromCallable(
                () -> {
                    DataAgentMarketplace mp = requireRegistered(userId, id);
                    List<MarketSkillSummary> raw;
                    try {
                        raw = mp.list();
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Marketplace listing failed: " + e.getMessage(),
                                e);
                    }
                    List<MarketSkillBrief> out = new ArrayList<>(raw.size());
                    for (MarketSkillSummary s : raw) {
                        out.add(new MarketSkillBrief(s.name(), s.description(), s.version()));
                    }
                    out.sort(Comparator.comparing(MarketSkillBrief::name));
                    return out;
                });
    }

    @GetMapping("/{id}/skills/{name}")
    public Mono<MarketSkillDetail> getSkill(
            @PathVariable String id, @PathVariable String name, Authentication auth) {
        String userId = principalUser(auth);
        return Mono.fromCallable(
                () -> {
                    if (name == null || name.isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "skill name is required");
                    }
                    DataAgentMarketplace mp = requireRegistered(userId, id);
                    MarketSkillContent content;
                    try {
                        content = mp.fetch(name);
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Marketplace fetch failed: " + e.getMessage(),
                                e);
                    }
                    if (content == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Skill '" + name + "' not found in marketplace '" + id + "'");
                    }
                    Map<String, String> resources =
                            content.resources() != null
                                    ? new LinkedHashMap<>(content.resources())
                                    : Map.of();
                    return new MarketSkillDetail(
                            content.name(), content.description(), content.markdown(), resources);
                });
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static String principalUser(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return (String) auth.getPrincipal();
    }

    private DataAgentMarketplace requireRegistered(String userId, String id) {
        validateId(id);
        return registry.find(userId, id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Marketplace not registered: " + id));
    }

    private TestConnectionResult probe(String userId, String id, MarketplaceConfigEntry entry) {
        DataAgentMarketplace mp;
        try {
            mp = registry.build(userId, id, entry);
        } catch (IllegalArgumentException e) {
            return new TestConnectionResult(false, e.getMessage(), null);
        } catch (RuntimeException e) {
            return new TestConnectionResult(
                    false, "Failed to build marketplace: " + e.getMessage(), null);
        }
        try {
            // For probes we list — for git this forces a clone, which is the point: proves
            // credentials and the skills/ layout in one call.
            List<MarketSkillSummary> sample = mp.list();
            int total = sample != null ? sample.size() : 0;
            return new TestConnectionResult(true, "Connected", total);
        } catch (RuntimeException e) {
            return new TestConnectionResult(false, e.getMessage(), null);
        } finally {
            try {
                mp.close();
            } catch (RuntimeException ignored) {
                // probe is throwaway — failure to release transient resources is non-fatal
            }
        }
    }

    private static void validateRequest(MarketplaceWriteRequest req, boolean requireId) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (requireId) {
            validateId(req.id());
        }
        if (req.type() == null || req.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        String type = req.type().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unsupported marketplace type '"
                            + req.type()
                            + "', expected one of "
                            + SUPPORTED_TYPES);
        }
        Map<String, Object> props = req.properties();
        if ("git".equals(type)) {
            requireStringProp(props, "remoteUrl");
        } else if ("nacos".equals(type)) {
            requireStringProp(props, "serverAddr");
        }
    }

    private static void requireStringProp(Map<String, Object> props, String key) {
        if (props == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + key + "' is required");
        }
        Object v = props.get(key);
        if (v == null || v.toString().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + key + "' is required");
        }
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "invalid marketplace id: '" + id + "' (allowed: letters, digits, ._-)");
        }
    }

    private static MarketplaceConfigEntry toConfigEntry(MarketplaceWriteRequest req) {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType(req.type().toLowerCase(Locale.ROOT));
        if (req.properties() != null) {
            for (Map.Entry<String, Object> e : req.properties().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                entry.setProperty(e.getKey(), e.getValue());
            }
        }
        return entry;
    }

    private static MarketplaceSummary toSummary(String id, MarketplaceConfigEntry entry) {
        // Strip secrets from the surfaced property bag so they never round-trip through the UI.
        Map<String, Object> safeProps = new LinkedHashMap<>();
        if (entry.getProperties() != null) {
            for (Map.Entry<String, Object> e : entry.getProperties().entrySet()) {
                if (SECRET_KEYS.contains(e.getKey())) continue;
                safeProps.put(e.getKey(), e.getValue());
            }
        }
        return new MarketplaceSummary(id, entry.getType(), safeProps);
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceWriteRequest(String id, String type, Map<String, Object> properties) {}

    /** Response payload — never carries credentials. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceSummary(String id, String type, Map<String, Object> properties) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestConnectionResult(boolean ok, String message, Integer skillCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillBrief(String name, String description, String version) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillDetail(
            String name, String description, String markdown, Map<String, String> resources) {}
}
