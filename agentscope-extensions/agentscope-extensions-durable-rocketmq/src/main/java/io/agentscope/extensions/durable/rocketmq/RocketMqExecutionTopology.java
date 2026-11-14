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
package io.agentscope.extensions.durable.rocketmq;

import io.agentscope.durable.DurableJson;
import java.util.regex.Pattern;

/** One naming convention shared by Brain and Worker; physical topics stay infrastructure details. */
final class RocketMqExecutionTopology {
    static final String DEFAULT_NAMESPACE = "durable";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

    record Route(
            String commands,
            String controls,
            String updates,
            String brainConsumerGroup,
            String workerConsumerGroup,
            String controlConsumerGroup) {}

    private RocketMqExecutionTopology() {}

    static Route route(String namespace, String executor) {
        namespace = component(namespace, "RocketMQ execution namespace");
        executor = component(executor, "executor route");
        String prefix = namespace + "-" + executor;
        return new Route(
                prefix + "-commands",
                prefix + "-controls",
                prefix + "-updates",
                prefix + "-brain",
                prefix + "-worker",
                prefix + "-worker-controls");
    }

    private static String component(String value, String description) {
        value = DurableJson.text(value, description);
        if (!NAME.matcher(value).matches())
            throw new IllegalArgumentException(description + " must use letters, digits, _ or -");
        return value;
    }
}
