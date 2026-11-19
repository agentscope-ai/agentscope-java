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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.binding.UserBinding;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class JpaUserBindingStoreTest {

    @Autowired private UserEntityRepository repository;

    private JpaUserBindingStore store;

    @BeforeEach
    void setUp() {
        repository.save(new UserEntity("alice", "alice", "hash", "user"));
        store = new JpaUserBindingStore(repository, new ObjectMapper());
    }

    @Test
    void persistsOrderedBindingsOnUserRow() {
        UserBinding first = new UserBinding("chatui", "Main", "MAIN", "zh-CN", null);
        UserBinding second = new UserBinding("slack", "Slack", null, "en", List.of("sql-analysis"));

        store.add("alice", first);
        store.add("alice", second);

        assertThat(store.list("alice")).containsExactly(first, second);
        assertThat(store.update("alice", 0, second)).contains(second);
        assertThat(store.remove("alice", 1)).contains(second);
        assertThat(store.list("alice")).containsExactly(second);
        assertThat(repository.findById("alice").orElseThrow().getBindingsJson()).isNotBlank();
    }
}
