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
package io.agentscope.extensions.redis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import redis.clients.jedis.UnifiedJedis;

/**
 * Unit tests for the Redis Cluster hash-tag key layout of {@link RedisStore}.
 *
 * <p>These tests verify (via reflection) that {@code itemKey} and {@code indexKey} collapse to the
 * same Redis Cluster slot, that an empty namespace does not degenerate into an ignored {@code {}}
 * tag, and that namespace segments containing {@code { }} are rejected.
 */
class RedisStoreKeyLayoutTest {

    private static final String PREFIX = "agentscope:store:";

    private RedisStore newStore() {
        return new RedisStore(Mockito.mock(UnifiedJedis.class), PREFIX);
    }

    private Object call(String name, Class<?>[] params, Object... args) throws Exception {
        try {
            Method m = RedisStore.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m.invoke(newStore(), args);
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
    void hashTagWrapsNamespace() throws Exception {
        String tag = (String) call("hashTag", new Class<?>[] {List.class}, List.of("ns1", "ns2"));
        assertTrue(tag.startsWith("{"), () -> "tag should start with '{': " + tag);
        assertTrue(tag.endsWith("}"), () -> "tag should end with '}': " + tag);
        assertTrue(
                tag.contains("ns1") && tag.contains("ns2"),
                () -> "tag should wrap the namespace: " + tag);
    }

    @Test
    void emptyNamespaceUsesPlaceholderTag() throws Exception {
        String tag = (String) call("hashTag", new Class<?>[] {List.class}, List.of());
        assertEquals(
                "{_root_}",
                tag,
                "an empty namespace must map to a non-empty placeholder tag so Redis honors it");
    }

    @Test
    void itemKeyAndIndexKeyShareSameHashTag() throws Exception {
        List<String> ns = List.of("a", "b");
        String itemKey =
                (String) call("itemKey", new Class<?>[] {List.class, String.class}, ns, "k1");
        String indexKey = (String) call("indexKey", new Class<?>[] {List.class}, ns);
        String itemTag = itemKey.substring(itemKey.indexOf('{'), itemKey.indexOf('}') + 1);
        String indexTag = indexKey.substring(indexKey.indexOf('{'), indexKey.indexOf('}') + 1);
        assertEquals(
                itemTag,
                indexTag,
                "itemKey and indexKey must share the same hash tag (same Cluster slot)");
    }

    @Test
    void emptyNamespaceKeysStillShareTag() throws Exception {
        String itemKey =
                (String) call("itemKey", new Class<?>[] {List.class, String.class}, List.of(), "k");
        String indexKey = (String) call("indexKey", new Class<?>[] {List.class}, List.of());
        assertTrue(
                itemKey.contains("{_root_}"), "empty-ns itemKey should use {_root_}: " + itemKey);
        assertTrue(
                indexKey.contains("{_root_}"),
                "empty-ns indexKey should use {_root_}: " + indexKey);
    }

    @Test
    void namespaceSegmentWithClosingBraceRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> call("hashTag", new Class<?>[] {List.class}, List.of("a}b")));
    }

    @Test
    void namespaceSegmentWithOpeningBraceRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> call("hashTag", new Class<?>[] {List.class}, List.of("a{b")));
    }

    @Test
    void literalRootPlaceholderNamespaceRejected() {
        // ["_root_"] joins to the same path as the empty namespace's placeholder tag and would
        // share the root namespace's index set (cross-namespace leak), so it must be rejected.
        assertThrows(
                IllegalArgumentException.class,
                () -> call("hashTag", new Class<?>[] {List.class}, List.of("_root_")));
    }

    @Test
    void rootPlaceholderInsideMultiSegmentNamespaceStillValid() throws Exception {
        // Only the exact joined path "_root_" is reserved; a segment named "_root_" inside a
        // larger namespace is fine.
        String tag = (String) call("hashTag", new Class<?>[] {List.class}, List.of("a", "_root_"));
        assertTrue(tag.contains("_root_"), "multi-segment namespace may use '_root_': " + tag);
    }

    @Test
    void nullNamespaceSegmentRejected() {
        // A null segment must fail fast instead of producing a malformed namespace path.
        assertThrows(
                IllegalArgumentException.class,
                () -> call("hashTag", new Class<?>[] {List.class}, Arrays.asList("a", null)));
    }

    @Test
    void namespaceSegmentWithSeparatorRejected() {
        // ["a\0b"] would join to the same path as ["a", "b"] — same hash tag, same index set:
        // a cross-namespace collision. (The relational/Mongo backends reject their separator
        // the same way.)
        assertThrows(
                IllegalArgumentException.class,
                () -> call("hashTag", new Class<?>[] {List.class}, List.of("a\u0000b")));
    }

    @Test
    void emptySegmentOnlyNamespaceRejected() {
        // [""] joins to an empty path, which hashTag maps to the root placeholder {_root_}.
        assertThrows(
                IllegalArgumentException.class,
                () -> call("hashTag", new Class<?>[] {List.class}, List.of("")));
    }
}
