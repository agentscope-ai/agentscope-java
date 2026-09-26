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
package io.agentscope.dataagent.web.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.binding.UserBinding;
import io.agentscope.dataagent.web.binding.UserBindingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** JPA-backed {@link UserBindingStore} persisted on the owning user row. */
@Transactional
public class JpaUserBindingStore implements UserBindingStore {

    private static final TypeReference<List<UserBinding>> BINDING_LIST_TYPE =
            new TypeReference<>() {};

    private final UserEntityRepository repository;
    private final ObjectMapper objectMapper;

    public JpaUserBindingStore(UserEntityRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserBinding> list(String userId) {
        return repository
                .findById(userId)
                .map(UserEntity::getBindingsJson)
                .map(this::deserialize)
                .orElseGet(List::of);
    }

    @Override
    public UserBinding add(String userId, UserBinding binding) {
        UserEntity user = requireUserForUpdate(userId);
        List<UserBinding> bindings = mutableBindings(user);
        bindings.add(binding);
        persist(user, bindings);
        return binding;
    }

    @Override
    public Optional<UserBinding> update(String userId, int index, UserBinding binding) {
        UserEntity user = requireUserForUpdate(userId);
        List<UserBinding> bindings = mutableBindings(user);
        if (index < 0 || index >= bindings.size()) {
            return Optional.empty();
        }
        bindings.set(index, binding);
        persist(user, bindings);
        return Optional.of(binding);
    }

    @Override
    public Optional<UserBinding> remove(String userId, int index) {
        UserEntity user = requireUserForUpdate(userId);
        List<UserBinding> bindings = mutableBindings(user);
        if (index < 0 || index >= bindings.size()) {
            return Optional.empty();
        }
        UserBinding removed = bindings.remove(index);
        persist(user, bindings);
        return Optional.of(removed);
    }

    private UserEntity requireUserForUpdate(String userId) {
        return repository
                .findByIdForUpdate(userId)
                .orElseThrow(
                        () -> new IllegalStateException("Authenticated user not found: " + userId));
    }

    private List<UserBinding> mutableBindings(UserEntity user) {
        return new ArrayList<>(deserialize(user.getBindingsJson()));
    }

    private List<UserBinding> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<UserBinding> bindings = objectMapper.readValue(json, BINDING_LIST_TYPE);
            return bindings != null ? List.copyOf(bindings) : List.of();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize user channel preferences", e);
        }
    }

    private void persist(UserEntity user, List<UserBinding> bindings) {
        try {
            user.setBindingsJson(objectMapper.writeValueAsString(bindings));
            repository.save(user);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize user channel preferences", e);
        }
    }
}
