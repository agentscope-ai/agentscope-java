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
package io.agentscope.extensions.redis.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Redis Cluster hash-tag layout of {@link RedisAgentStateStore}'s slot id.
 *
 * <p>Verifies that {@code slotId} wraps {@code (userId, sessionId)} in a {@code {...}} hash tag,
 * falls back to the anonymous user for blank ids, and rejects {@code { }} characters that would
 * truncate the tag early.
 */
class RedisAgentStateStoreKeyLayoutTest {

    private static Object callStatic(String name, Class<?>[] params, Object... args)
            throws Exception {
        try {
            Method m = RedisAgentStateStore.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (InvocationTargetException ite) {
            Throwable c = ite.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(c);
        }
    }

    @Test
    void slotIdWrappedInHashTag() throws Exception {
        String slot =
                (String)
                        callStatic(
                                "slotId",
                                new Class<?>[] {String.class, String.class},
                                "user1",
                                "sess1");
        assertEquals("{user1/sess1}", slot);
    }

    @Test
    void slotIdUsesAnonUserForBlankUserId() throws Exception {
        String slot =
                (String)
                        callStatic(
                                "slotId",
                                new Class<?>[] {String.class, String.class},
                                null,
                                "sess1");
        assertEquals("{__anon__/sess1}", slot);
    }

    @Test
    void slotIdRejectsClosingBraceInSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        callStatic(
                                "slotId",
                                new Class<?>[] {String.class, String.class},
                                "user1",
                                "ses}s1"));
    }

    @Test
    void slotIdRejectsClosingBraceInUserId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        callStatic(
                                "slotId",
                                new Class<?>[] {String.class, String.class},
                                "us}er1",
                                "sess1"));
    }

    @Test
    void slotIdRejectsOpeningBraceInSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        callStatic(
                                "slotId",
                                new Class<?>[] {String.class, String.class},
                                "user1",
                                "se{ss1"));
    }

    @Test
    void slotIdRejectsOpeningBraceInUserId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        callStatic(
                                "slotId",
                                new Class<?>[] {String.class, String.class},
                                "us{er1",
                                "sess1"));
    }

    @Test
    void slotIdRejectsBlankSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        callStatic(
                                "slotId",
                                new Class<?>[] {String.class, String.class},
                                "user1",
                                "  "));
    }
}
