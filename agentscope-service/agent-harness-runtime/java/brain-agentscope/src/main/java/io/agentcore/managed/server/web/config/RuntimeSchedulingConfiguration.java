/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Control-plane synchronization cannot occupy the Turn Inbox's scheduling thread. */
@Configuration(proxyBeanMethods = false)
public class RuntimeSchedulingConfiguration {
    // Declaring only the policy scheduler would disable Boot's default scheduler and cause
    // unqualified @Scheduled tasks to use that single remaining scheduler.
    @Bean("taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:1}") int poolSize) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("dataplane-scheduling-");
        return scheduler;
    }

    @Bean("managedRuntimePolicyScheduler")
    @ConditionalOnMissingBean(name = "managedRuntimePolicyScheduler")
    public ThreadPoolTaskScheduler managedRuntimePolicyScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("managed-policy-refresh-");
        return scheduler;
    }
}
