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
package io.agentscope.harness.claw.app.binding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.harness.claw.session.spi.KvStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Backend-agnostic store for per-user channel preferences.
 *
 * <p>Since DataAgent ships as a single product (one main agent), bindings are no longer used to
 * pick <i>which</i> agent answers — there is only ever one. They are now a free-form bag of
 * <i>preferences</i> the user keeps for a given channel: a friendly label, a preferred reply
 * language, an optional skill-allowlist, and the session scope hint that's already used by the
 * chat UI's session-key derivation.
 *
 * <p>State is owned by an injected {@link KvStore} keyed by {@code userId}:
 *
 * <ul>
 *   <li>{@code FileKvStore} (default) — JSON files under {@code .agentscope/users/{userId}/bindings.json}.
 *       Single-node only; the previous on-disk layout is preserved.
 *   <li>{@code RedisKvStore} (when {@code claw.session.redis.enabled=true}) — Redis strings under
 *       {@code claw:bindings:{userId}}. Distributed-safe; user updates on R1 are immediately
 *       visible on R2.
 * </ul>
 */
public class UserBindingStore {

    private final KvStore<List<UserBinding>> backend;

    public UserBindingStore(KvStore<List<UserBinding>> backend) {
        this.backend = backend;
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /** Returns all bindings declared by the given user, in declared order. */
    public List<UserBinding> list(String userId) {
        return new ArrayList<>(loadForUser(userId));
    }

    /**
     * Resolves the user's preference entry for the given channel, if any. Returns the first
     * binding whose {@code channelId} matches; {@link Optional#empty()} when none matches.
     */
    public Optional<UserBinding> resolveForChannel(String userId, String channelId) {
        if (channelId == null) return Optional.empty();
        for (UserBinding b : loadForUser(userId)) {
            if (channelId.equals(b.channelId())) return Optional.of(b);
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------
    //  Mutations
    // -----------------------------------------------------------------

    /** Replaces the user's entire binding list. */
    public List<UserBinding> replace(String userId, List<UserBinding> bindings) {
        List<UserBinding> next = bindings == null ? new ArrayList<>() : new ArrayList<>(bindings);
        backend.put(userId, next);
        return new ArrayList<>(next);
    }

    /** Appends a single binding (no dedupe — caller controls ordering). */
    public UserBinding add(String userId, UserBinding binding) {
        backend.mutate(
                userId,
                new ArrayList<>(),
                cur -> {
                    List<UserBinding> next = new ArrayList<>(cur);
                    next.add(binding);
                    return next;
                });
        return binding;
    }

    /** Removes the binding at {@code index}; returns {@code true} if removed. */
    public boolean removeAt(String userId, int index) {
        boolean[] removed = {false};
        backend.mutate(
                userId,
                new ArrayList<>(),
                cur -> {
                    if (index < 0 || index >= cur.size()) return cur;
                    List<UserBinding> next = new ArrayList<>(cur);
                    next.remove(index);
                    removed[0] = true;
                    return next;
                });
        return removed[0];
    }

    /** Updates the binding at {@code index}; returns {@code true} if updated. */
    public boolean updateAt(String userId, int index, UserBinding next) {
        boolean[] updated = {false};
        backend.mutate(
                userId,
                new ArrayList<>(),
                cur -> {
                    if (index < 0 || index >= cur.size()) return cur;
                    List<UserBinding> repl = new ArrayList<>(cur);
                    repl.set(index, next);
                    updated[0] = true;
                    return repl;
                });
        return updated[0];
    }

    // -----------------------------------------------------------------
    //  Stored data model
    // -----------------------------------------------------------------

    /**
     * One channel preference entry.
     *
     * @param channelId channel this preference applies to (e.g. {@code "chatui"})
     * @param displayLabel optional human label shown in the UI
     * @param sessionScope optional channel-DM session scope hint (e.g. {@code "MAIN"})
     * @param language optional BCP-47 reply-language preference (e.g. {@code "zh-CN"})
     * @param enabledSkills optional allowlist of skill names; {@code null} or empty means "use
     *     whatever skills are present in the user's workspace"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(
            value = {"agentId", "label"},
            ignoreUnknown = true)
    public record UserBinding(
            String channelId,
            String displayLabel,
            String sessionScope,
            String language,
            List<String> enabledSkills) {}

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private List<UserBinding> loadForUser(String userId) {
        return backend.get(userId).orElseGet(ArrayList::new);
    }

    /** Snapshot of all known user bindings — used by the admin overview API. */
    public Map<String, List<UserBinding>> snapshot() {
        Map<String, List<UserBinding>> out = new LinkedHashMap<>();
        for (String userId : backend.keys()) {
            List<UserBinding> list = loadForUser(userId);
            if (!list.isEmpty()) out.put(userId, list);
        }
        return out;
    }
}
