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
package io.agentscope.a2a.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

final class RedisNamespaceAssertions {

    private RedisNamespaceAssertions() {}

    static void containsNone(RedissonClient client, String namespace, String... prohibitedValues) {
        List<String> stored = new ArrayList<>();
        for (String key : client.getKeys().getKeysByPattern(namespace + "*")) {
            stored.add(key);
            RType type = client.getKeys().getType(key);
            if (type == RType.MAP) {
                stored.add(client.getMap(key, StringCodec.INSTANCE).readAllMap().toString());
            } else if (type == RType.SET) {
                stored.add(client.getSet(key, StringCodec.INSTANCE).readAll().toString());
            } else if (type == RType.ZSET) {
                stored.add(
                        client.getScoredSortedSet(key, StringCodec.INSTANCE)
                                .entryRange(0, -1)
                                .toString());
            } else {
                stored.add(String.valueOf(client.getBucket(key, StringCodec.INSTANCE).get()));
            }
        }
        String all = String.join("\n", stored);
        for (String prohibited : prohibitedValues) {
            assertThat(all).doesNotContain(prohibited);
        }
    }

    static String hashTag(String key) {
        int start = key.indexOf('{');
        int end = key.indexOf('}', start + 1);
        assertThat(start).as("hash tag start in %s", key).isGreaterThanOrEqualTo(0);
        assertThat(end).as("hash tag end in %s", key).isGreaterThan(start);
        return key.substring(start, end + 1);
    }
}
