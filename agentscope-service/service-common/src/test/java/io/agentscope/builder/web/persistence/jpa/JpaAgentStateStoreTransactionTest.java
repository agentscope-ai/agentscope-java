/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentStateStore;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards the reason every read path is declared on the class instead of inherited from {@link
 * AgentStateStore}. Spring resolves a transaction attribute from the method's declaring class, so an
 * inherited default method runs outside a transaction however the class is annotated — and a read
 * without one cannot open the PostgreSQL large object holding {@code state_data}, which silently
 * hands the agent an empty context.
 */
class JpaAgentStateStoreTransactionTest {

    @Test
    @DisplayName("every state read is declared on the store, inside a transaction")
    void readsAreDeclaredOnTheStoreAndTransactional() throws NoSuchMethodException {
        for (Method read :
                new Method[] {
                    JpaAgentStateStore.class.getDeclaredMethod(
                            "getVersioned", String.class, String.class, String.class, Class.class),
                    JpaAgentStateStore.class.getDeclaredMethod(
                            "get", String.class, String.class, String.class, Class.class),
                    JpaAgentStateStore.class.getDeclaredMethod(
                            "getList", String.class, String.class, String.class, Class.class)
                }) {
            Transactional tx = read.getAnnotation(Transactional.class);
            assertNotNull(tx, read.getName() + " must carry its own @Transactional");
            assertTrue(tx.readOnly(), read.getName() + " reads only");
        }
    }
}
