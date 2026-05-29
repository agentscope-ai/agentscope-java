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
package io.agentscope.harness.coding.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates deterministic thread IDs from GitHub issue/PR/reviewer context.
 *
 * <p>SHA-256 hash of a canonical key string is converted to a
 * UUID for uniqueness and readability.
 *
 * <p>Slack and Linear thread ID generation are deferred to a later phase.
 */
public final class ThreadIdFactory {

    private ThreadIdFactory() {}

    /**
     * Thread ID for a GitHub issue conversation.
     *
     * @param owner repo owner
     * @param repo repo name
     * @param issueNumber issue number
     */
    public static String fromGitHubIssue(String owner, String repo, int issueNumber) {
        return toUUID("github:issue:" + owner + "/" + repo + "#" + issueNumber);
    }

    /**
     * Thread ID for a GitHub PR coding session (branch-stable).
     *
     * @param owner repo owner
     * @param repo repo name
     * @param prNumber PR number
     */
    public static String fromGitHubPr(String owner, String repo, int prNumber) {
        return toUUID("github:pr:" + owner + "/" + repo + "#" + prNumber);
    }

    /**
     * Thread ID for a reviewer session on a specific PR.
     *
     * @param owner repo owner
     * @param repo repo name
     * @param prNumber PR number
     */
    public static String fromGitHubReviewer(String owner, String repo, int prNumber) {
        return toUUID("github:reviewer:" + owner + "/" + repo + "#" + prNumber);
    }

    /**
     * Thread ID derived from a comment on a GitHub issue or PR.
     *
     * @param owner repo owner
     * @param repo repo name
     * @param issueOrPrNumber issue/PR number
     */
    public static String fromGitHubComment(String owner, String repo, int issueOrPrNumber) {
        return fromGitHubIssue(owner, repo, issueOrPrNumber);
    }

    /**
     * Thread ID for a DingTalk conversation (DM or group). The same conversation always maps to the
     * same thread, so consecutive messages in the same chat share session state.
     *
     * @param appKey enterprise internal app key (namespaces conversation ids across tenants)
     * @param conversationId DingTalk conversation id (DM senderStaffId or group openConversationId)
     */
    public static String fromDingtalkConversation(String appKey, String conversationId) {
        return toUUID("dingtalk:" + appKey + ":" + conversationId);
    }

    /**
     * Thread ID for a Feishu/Lark chat (DM, group, or thread).
     *
     * @param tenantKey Feishu tenant key (namespaces chat ids across tenants)
     * @param chatId Feishu chat id ({@code open_chat_id} for DMs/groups, {@code thread_id} for
     *     threaded conversations)
     */
    public static String fromFeishuChat(String tenantKey, String chatId) {
        return toUUID("feishu:" + tenantKey + ":" + chatId);
    }

    // -----------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------

    static String toUUID(String canonicalKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalKey.getBytes(StandardCharsets.UTF_8));
            // Use first 16 bytes as UUID bytes (variant/version bits set for UUID v4 style)
            byte[] uuidBytes = new byte[16];
            System.arraycopy(hash, 0, uuidBytes, 0, 16);
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0f) | 0x40); // version 4
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3f) | 0x80); // variant 1
            return uuidBytesToString(uuidBytes);
        } catch (NoSuchAlgorithmException e) {
            return UUID.nameUUIDFromBytes(canonicalKey.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private static String uuidBytesToString(byte[] b) {
        String hex = HexFormat.of().formatHex(b);
        return hex.substring(0, 8)
                + "-"
                + hex.substring(8, 12)
                + "-"
                + hex.substring(12, 16)
                + "-"
                + hex.substring(16, 20)
                + "-"
                + hex.substring(20, 32);
    }
}
