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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Redis control-plane settings for durable A2A HITL. */
@ConfigurationProperties("agentscope.a2a.server.hitl.redis")
public class RedisHitlProperties {

    private String namespace = "agentscope:a2a:hitl:";
    private Duration taskTtl = Duration.ofDays(30);
    private Duration claimRecoveryTimeout = Duration.ofMinutes(2);
    private Duration reconcilerInterval = Duration.ofSeconds(30);

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Duration getTaskTtl() {
        return taskTtl;
    }

    public void setTaskTtl(Duration taskTtl) {
        this.taskTtl = taskTtl;
    }

    public Duration getClaimRecoveryTimeout() {
        return claimRecoveryTimeout;
    }

    public void setClaimRecoveryTimeout(Duration claimRecoveryTimeout) {
        this.claimRecoveryTimeout = claimRecoveryTimeout;
    }

    public Duration getReconcilerInterval() {
        return reconcilerInterval;
    }

    public void setReconcilerInterval(Duration reconcilerInterval) {
        this.reconcilerInterval = reconcilerInterval;
    }
}
