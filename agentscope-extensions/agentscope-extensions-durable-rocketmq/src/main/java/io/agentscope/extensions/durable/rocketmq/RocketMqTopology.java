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
import java.util.List;
import java.util.regex.Pattern;

/** LiteTopic parents, bound groups and subscription modes used by the RocketMQ extension. */
public final class RocketMqTopology {
    public static final String DEFAULT_NAMESPACE = "durable";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

    public enum SubscriptionMode {
        WILDCARD,
        EXPLICIT
    }

    /** A LITE parent and its bound consumer group. */
    public record Subscription(String topic, String consumerGroup, SubscriptionMode mode) {}

    public record Turn(
            String dispatch,
            String signal,
            String ownerSignal,
            String runnerGroup,
            String signalGroup,
            String ownerGroup) {
        public List<Subscription> subscriptions() {
            return List.of(
                    new Subscription(dispatch, runnerGroup, SubscriptionMode.WILDCARD),
                    new Subscription(signal, signalGroup, SubscriptionMode.WILDCARD),
                    new Subscription(ownerSignal, ownerGroup, SubscriptionMode.EXPLICIT));
        }
    }

    public record Route(
            String commands,
            String controls,
            String updates,
            String brainConsumerGroup,
            String workerConsumerGroup,
            String controlConsumerGroup) {
        public List<Subscription> subscriptions() {
            return List.of(
                    new Subscription(commands, workerConsumerGroup, SubscriptionMode.WILDCARD),
                    new Subscription(controls, controlConsumerGroup, SubscriptionMode.WILDCARD),
                    new Subscription(updates, brainConsumerGroup, SubscriptionMode.WILDCARD));
        }
    }

    private RocketMqTopology() {}

    public static Turn turns(String namespace) {
        String prefix = component(namespace, "RocketMQ Turn namespace");
        return new Turn(
                prefix + "-turn-dispatch",
                prefix + "-turn-signal",
                prefix + "-turn-owner",
                prefix + "-turn-runner",
                prefix + "-turn-signal",
                prefix + "-turn-owner");
    }

    public static Route route(String namespace, String executor) {
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
