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
package io.agentscope.harness.agent.sandbox.impl.e2b;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Cross-language marker for E2B native snapshot references, aligned with openai-agents-python
 * {@code E2B_SANDBOX_SNAPSHOT_V1}.
 */
public final class E2bSnapshotRefs {

    public static final byte[] MAGIC_PREFIX =
            "E2B_SANDBOX_SNAPSHOT_V1\n".getBytes(StandardCharsets.UTF_8);

    private E2bSnapshotRefs() {}

    public static byte[] encodeSnapshotId(String snapshotId) throws Exception {
        ObjectMapper om = new ObjectMapper();
        byte[] body =
                om.writeValueAsBytes(Map.of("snapshot_id", snapshotId == null ? "" : snapshotId));
        byte[] out = new byte[MAGIC_PREFIX.length + body.length];
        System.arraycopy(MAGIC_PREFIX, 0, out, 0, MAGIC_PREFIX.length);
        System.arraycopy(body, 0, out, MAGIC_PREFIX.length, body.length);
        return out;
    }

    public static String decodeSnapshotIdIfPresent(byte[] raw) {
        if (raw == null || raw.length <= MAGIC_PREFIX.length) {
            return null;
        }
        for (int i = 0; i < MAGIC_PREFIX.length; i++) {
            if (raw[i] != MAGIC_PREFIX[i]) {
                return null;
            }
        }
        try {
            ObjectMapper om = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> m =
                    om.readValue(
                            new String(
                                    raw,
                                    MAGIC_PREFIX.length,
                                    raw.length - MAGIC_PREFIX.length,
                                    StandardCharsets.UTF_8),
                            Map.class);
            Object id = m.get("snapshot_id");
            return id != null ? id.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
