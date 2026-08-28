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

import io.agentscope.dataagent.web.binding.UserBinding;
import io.agentscope.dataagent.web.binding.UserBindingStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import reactor.core.scheduler.Schedulers;

/** CRUD API for channel preferences scoped to the authenticated user. */
@RestController
@RequestMapping("/api/user/bindings")
public class UserBindingController {

    private static final int MAX_ENABLED_SKILLS = 100;

    private final UserBindingStore store;

    public UserBindingController(UserBindingStore store) {
        this.store = store;
    }

    @GetMapping
    public Mono<List<UserBinding>> list(Authentication authentication) {
        String userId = authentication.getName();
        return Mono.fromCallable(() -> store.list(userId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserBinding> add(@RequestBody UserBinding request, Authentication authentication) {
        String userId = authentication.getName();
        return Mono.fromCallable(() -> store.add(userId, normalize(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{index}")
    public Mono<UserBinding> update(
            @PathVariable int index,
            @RequestBody UserBinding request,
            Authentication authentication) {
        String userId = authentication.getName();
        return Mono.fromCallable(
                        () ->
                                store.update(userId, index, normalize(request))
                                        .orElseThrow(() -> bindingNotFound(index)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{index}")
    public Mono<RemoveResponse> remove(@PathVariable int index, Authentication authentication) {
        String userId = authentication.getName();
        return Mono.fromCallable(
                        () -> {
                            store.remove(userId, index).orElseThrow(() -> bindingNotFound(index));
                            return new RemoveResponse(true);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    static UserBinding normalize(UserBinding request) {
        if (request == null) {
            throw badRequest("Request body is required");
        }
        String channelId = required(request.channelId(), "channelId", 128);
        String displayLabel = optional(request.displayLabel(), "displayLabel", 255);
        String sessionScope = optional(request.sessionScope(), "sessionScope", 128);
        String language = optional(request.language(), "language", 35);
        if (language != null && !isLanguageTag(language)) {
            throw badRequest("language must be a valid BCP-47 language tag");
        }
        List<String> enabledSkills = normalizeSkills(request.enabledSkills());
        return new UserBinding(channelId, displayLabel, sessionScope, language, enabledSkills);
    }

    private static List<String> normalizeSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String skill : skills) {
            String value = optional(skill, "enabledSkills entry", 128);
            if (value != null) {
                normalized.add(value);
            }
        }
        if (normalized.size() > MAX_ENABLED_SKILLS) {
            throw badRequest(
                    "enabledSkills must contain at most " + MAX_ENABLED_SKILLS + " entries");
        }
        return normalized.isEmpty() ? null : new ArrayList<>(normalized);
    }

    private static boolean isLanguageTag(String language) {
        Locale locale = Locale.forLanguageTag(language);
        return !locale.getLanguage().isBlank() && !"und".equals(locale.toLanguageTag());
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = optional(value, field, maxLength);
        if (normalized == null) {
            throw badRequest(field + " must not be blank");
        }
        return normalized;
    }

    private static String optional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static ResponseStatusException bindingNotFound(int index) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "User binding index out of range: " + index);
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record RemoveResponse(boolean removed) {}
}
