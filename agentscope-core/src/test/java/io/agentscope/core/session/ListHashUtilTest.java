/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for ListHashUtil utility class.
 */
class ListHashUtilTest {

    @Test
    void testComputeHashEmptyList() {
        String hash = ListHashUtil.computeHash(List.of());
        assertEquals("empty:0", hash);
    }

    @Test
    void testComputeHashNullList() {
        String hash = ListHashUtil.computeHash(null);
        assertEquals("empty:0", hash);
    }

    @Test
    void testComputeHashSameListSameHash() {
        List<Msg> list = createMsgList(5);

        // Same list instance should give same hash
        String hash1 = ListHashUtil.computeHash(list);
        String hash2 = ListHashUtil.computeHash(list);

        assertEquals(hash1, hash2);
    }

    @Test
    void testComputeHashListModifiedDifferentHash() {
        List<Msg> list = createMsgList(5);

        String hashBefore = ListHashUtil.computeHash(list);

        // Modify the list
        list.set(
                2,
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("modified content").build())
                        .build());

        String hashAfter = ListHashUtil.computeHash(list);

        assertNotEquals(hashBefore, hashAfter);
    }

    @Test
    void testComputeHashListGrowsDifferentHash() {
        List<Msg> list = createMsgList(5);

        String hashBefore = ListHashUtil.computeHash(list);

        // Add more items
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("new message").build())
                        .build());

        String hashAfter = ListHashUtil.computeHash(list);

        assertNotEquals(hashBefore, hashAfter);
    }

    @Test
    void testHasChangedNullStoredHash() {
        String currentHash = "abc123";
        assertFalse(ListHashUtil.hasChanged(currentHash, null));
    }

    @Test
    void testHasChangedSameHash() {
        String hash = "abc123";
        assertFalse(ListHashUtil.hasChanged(hash, hash));
    }

    @Test
    void testHasChangedDifferentHash() {
        assertTrue(ListHashUtil.hasChanged("abc123", "def456"));
    }

    @Test
    void testNeedsFullRewriteHashChanged() {
        List<Msg> list = createMsgList(5);
        String storedHash = ListHashUtil.computeHash(list);

        // Modify an element in the existing prefix (e.g., index 2)
        list.set(
                2,
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("modified content").build())
                        .build());

        assertTrue(ListHashUtil.needsFullRewrite(list, storedHash, 5));
    }

    @Test
    void testNeedsFullRewriteListShrunk() {
        List<Msg> list = createMsgList(3);
        // Current size (3) < existing count (5) -> must rewrite
        assertTrue(ListHashUtil.needsFullRewrite(list, "any_old_hash", 5));
    }

    @Test
    void testNeedsFullRewriteListGrew() {
        List<Msg> list = createMsgList(5);
        String storedHash = ListHashUtil.computeHash(list);

        // Append new items (list grows to 8)
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("msg 5").build())
                        .build());
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("msg 6").build())
                        .build());
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("msg 7").build())
                        .build());

        // The prefix (first 5) hasn't changed -> no full rewrite needed (incremental append)
        assertFalse(ListHashUtil.needsFullRewrite(list, storedHash, 5));
    }

    @Test
    void testNeedsFullRewriteNoChange() {
        List<Msg> list = createMsgList(5);
        String storedHash = ListHashUtil.computeHash(list);

        // Size and content are exactly the same -> no rewrite needed
        assertFalse(ListHashUtil.needsFullRewrite(list, storedHash, 5));
    }

    @Test
    void testNeedsFullRewriteFirstSave() {
        List<Msg> list = createMsgList(5);

        // No stored hash (first save), existing count is 0 -> no full rewrite needed
        assertFalse(ListHashUtil.needsFullRewrite(list, null, 0));
    }

    @Test
    void testNeedsFullRewriteNullList() {
        // If current list is null, but we had existing items -> must rewrite (essentially a delete
        // all)
        assertTrue(ListHashUtil.needsFullRewrite(null, "some_hash", 5));

        // If current list is null and existing count was 0 -> no rewrite needed
        assertFalse(ListHashUtil.needsFullRewrite(null, null, 0));
    }

    @Test
    void testNeedsFullRewriteMissingHashButHasData() {
        List<Msg> list = createMsgList(5);

        // Scenario: existingCount is 5, but storedHash is null
        // (e.g., system upgraded from an older version that didn't save hashes)
        assertTrue(ListHashUtil.needsFullRewrite(list, null, 5));
    }

    private List<Msg> createMsgList(int size) {
        List<Msg> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("message " + i).build())
                            .build());
        }
        return list;
    }
}
