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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.web.binding.UserBinding;
import io.agentscope.dataagent.web.binding.UserBindingStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class UserBindingControllerTest {

    private final InMemoryStore store = new InMemoryStore();
    private final UserBindingController controller = new UserBindingController(store);

    @Test
    void supportsUserScopedCrudWithFrontendContract() {
        TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", "n/a");
        UserBinding request =
                new UserBinding(
                        " chatui ",
                        " My default session ",
                        " MAIN ",
                        "zh-CN",
                        List.of("sql-analysis", "chart-rendering", "sql-analysis"));

        UserBinding created = controller.add(request, alice).block();

        assertThat(created.channelId()).isEqualTo("chatui");
        assertThat(created.displayLabel()).isEqualTo("My default session");
        assertThat(created.enabledSkills()).containsExactly("sql-analysis", "chart-rendering");
        assertThat(controller.list(alice).block()).containsExactly(created);
        assertThat(controller.list(new TestingAuthenticationToken("bob", "n/a")).block()).isEmpty();

        UserBinding replacement =
                new UserBinding("slack", null, null, "en", List.of("sql-analysis"));
        assertThat(controller.update(0, replacement, alice).block()).isEqualTo(replacement);
        assertThat(controller.remove(0, alice).block().removed()).isTrue();
        assertThat(controller.list(alice).block()).isEmpty();
    }

    @Test
    void rejectsInvalidInputAndMissingIndexes() {
        TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", "n/a");

        StepVerifier.create(controller.add(new UserBinding(" ", null, null, null, null), alice))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) error).getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                        })
                .verify();

        StepVerifier.create(controller.remove(0, alice))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) error).getStatusCode())
                                    .isEqualTo(HttpStatus.NOT_FOUND);
                        })
                .verify();
    }

    private static final class InMemoryStore implements UserBindingStore {

        private final Map<String, List<UserBinding>> bindingsByUser = new LinkedHashMap<>();

        @Override
        public synchronized List<UserBinding> list(String userId) {
            return List.copyOf(bindingsByUser.getOrDefault(userId, List.of()));
        }

        @Override
        public synchronized UserBinding add(String userId, UserBinding binding) {
            bindingsByUser.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(binding);
            return binding;
        }

        @Override
        public synchronized Optional<UserBinding> update(
                String userId, int index, UserBinding binding) {
            List<UserBinding> bindings = bindingsByUser.get(userId);
            if (bindings == null || index < 0 || index >= bindings.size()) {
                return Optional.empty();
            }
            bindings.set(index, binding);
            return Optional.of(binding);
        }

        @Override
        public synchronized Optional<UserBinding> remove(String userId, int index) {
            List<UserBinding> bindings = bindingsByUser.get(userId);
            if (bindings == null || index < 0 || index >= bindings.size()) {
                return Optional.empty();
            }
            return Optional.of(bindings.remove(index));
        }
    }
}
