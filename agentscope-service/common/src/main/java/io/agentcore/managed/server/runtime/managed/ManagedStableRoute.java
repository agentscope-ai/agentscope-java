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
package io.agentcore.managed.server.runtime.managed;

import java.nio.ByteBuffer;
import java.util.Base64;

/** Immutable logical route. Persist the selected value at Session creation, never recalculate it on receive. */
public record ManagedStableRoute(
        String scopeKey, String cell, int layoutVersion, int shardCount, int shard) {
    public ManagedStableRoute {
        if (scopeKey == null
                || !scopeKey.matches("[A-Za-z0-9_-]{43}")
                || cell == null
                || !cell.matches("[A-Za-z0-9_-]{1,24}")
                || layoutVersion < 1
                || shardCount < 1
                || shardCount > 65536
                || shard < 0
                || shard >= shardCount) throw new IllegalArgumentException("Invalid stable route");
    }

    public static ManagedStableRoute assign(
            ManagedScope scope, String cell, int version, int count) {
        if (count < 1 || count > 65536) throw new IllegalArgumentException("Invalid shard count");
        String key = scope.messageGroup();
        long hash = ByteBuffer.wrap(Base64.getUrlDecoder().decode(key)).getLong();
        return new ManagedStableRoute(
                key, cell, version, count, (int) Long.remainderUnsigned(hash, count));
    }

    public String liteTopic() {
        return cell + "_v" + layoutVersion + "_s" + shard;
    }

    public void validate(ManagedScope scope, String actualLiteTopic, int actualVersion) {
        if (!scope.messageGroup().equals(scopeKey)
                || actualVersion != layoutVersion
                || !liteTopic().equals(actualLiteTopic))
            throw new ManagedStateException(
                    "route_mismatch", "Message route differs from the persisted Session route");
    }
}
