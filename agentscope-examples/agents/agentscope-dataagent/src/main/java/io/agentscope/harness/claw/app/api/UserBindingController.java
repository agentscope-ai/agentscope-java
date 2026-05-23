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
package io.agentscope.harness.claw.app.api;

import io.agentscope.harness.claw.app.binding.UserBindingStore;
import io.agentscope.harness.claw.app.binding.UserBindingStore.UserBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST endpoints for per-user channel preferences.
 *
 * <p>DataAgent ships as a single product — there's no "which agent does this channel route to"
 * decision to make. These endpoints now manage <i>preferences</i> the user keeps for a given
 * channel: a friendly label, a session-scope hint, a reply-language preference, and an optional
 * skill allowlist.
 *
 * <ul>
 *   <li>{@code GET /api/user/bindings} — list the caller's channel preferences
 *   <li>{@code POST /api/user/bindings} — add a preference entry
 *   <li>{@code PUT /api/user/bindings/{index}} — update one
 *   <li>{@code DELETE /api/user/bindings/{index}} — remove one
 * </ul>
 *
 * <p>The route prefix ({@code /api/user/bindings}) is kept stable for client compatibility even
 * though the underlying model is no longer an "agent binding".
 */
@RestController
public class UserBindingController {

    private final UserBindingStore store;

    public UserBindingController(UserBindingStore store) {
        this.store = store;
    }

    @GetMapping("/api/user/bindings")
    public Mono<List<UserBinding>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> store.list(userId));
    }

    @PostMapping("/api/user/bindings")
    public Mono<UserBinding> add(@RequestBody BindingRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    validate(req);
                    return store.add(userId, toBinding(req));
                });
    }

    @PutMapping("/api/user/bindings/{index}")
    public Mono<UserBinding> update(
            @PathVariable int index, @RequestBody BindingRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    validate(req);
                    UserBinding b = toBinding(req);
                    if (!store.updateAt(userId, index, b)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Binding index out of range: " + index);
                    }
                    return b;
                });
    }

    @DeleteMapping("/api/user/bindings/{index}")
    public Mono<Map<String, Object>> remove(@PathVariable int index, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (!store.removeAt(userId, index)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Binding index out of range: " + index);
                    }
                    return Map.of("removed", true);
                });
    }

    private void validate(BindingRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
        }
        if (req.channelId() == null || req.channelId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelId is required");
        }
    }

    private static UserBinding toBinding(BindingRequest req) {
        List<String> skills =
                req.enabledSkills() == null ? null : new ArrayList<>(req.enabledSkills());
        return new UserBinding(
                req.channelId().trim(),
                blankToNull(req.displayLabel()),
                blankToNull(req.sessionScope()),
                blankToNull(req.language()),
                skills);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Request body for create/update.
     *
     * @param channelId required — channel this preference applies to
     * @param displayLabel optional UI label
     * @param sessionScope optional channel-DM session scope hint (e.g. {@code "MAIN"})
     * @param language optional BCP-47 reply-language preference (e.g. {@code "zh-CN"})
     * @param enabledSkills optional allowlist of skill names ({@code null}/empty = use all
     *     workspace skills)
     */
    public record BindingRequest(
            String channelId,
            String displayLabel,
            String sessionScope,
            String language,
            List<String> enabledSkills) {}
}
