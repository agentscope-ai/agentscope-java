/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical fingerprints and constant-time digest verification. */
public final class HitlTokenDigests {

    private HitlTokenDigests() {}

    public static String pendingFingerprint(List<ToolUseBlock> tools) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        tools.stream()
                .sorted(Comparator.comparing(ToolUseBlock::getId))
                .forEach(
                        tool -> {
                            Map<String, Object> value = new LinkedHashMap<>();
                            value.put("id", tool.getId());
                            value.put("name", tool.getName());
                            value.put("input", canonicalValue(tool.getInput()));
                            canonical.add(value);
                        });
        return sha256(JsonUtils.getJsonCodec().toJson(canonical));
    }

    public static String boundTokenDigest(
            String taskId,
            String contextId,
            String handoffId,
            HitlExecutionKey executionKey,
            String pendingFingerprint,
            String token) {
        String binding =
                safe(taskId)
                        + '\u001f'
                        + safe(contextId)
                        + '\u001f'
                        + safe(handoffId)
                        + '\u001f'
                        + executionKey.userId()
                        + '\u001f'
                        + executionKey.logicalAgentId()
                        + '\u001f'
                        + executionKey.contextId()
                        + '\u001f'
                        + safe(pendingFingerprint)
                        + '\u001f'
                        + safe(token);
        return sha256(binding);
    }

    public static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(HitlTokenDigests::canonicalValue).toList();
        }
        return value;
    }

    public static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
