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
package io.agentscope.builder.web.managed;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every minute, checks enabled cron-triggered {@link DeploymentEntity deployments} and fires the
 * ones whose next scheduled run has come due, delegating the actual firing (and lastRunAt /
 * lastSessionId / lastStatus bookkeeping) to {@link DeploymentService#fireDueCronDeployments()}.
 */
@Component
public class DeploymentScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeploymentScheduler.class);

    private final DeploymentService deploymentService;
    private final ScheduledExecutorService scheduler;

    public DeploymentScheduler(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "deployment-cron-scheduler");
                            t.setDaemon(true);
                            return t;
                        });
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::runCycle, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private void runCycle() {
        try {
            List<DeploymentDto> fired = deploymentService.fireDueCronDeployments();
            if (!fired.isEmpty()) {
                log.info("Fired {} due cron deployment(s)", fired.size());
            }
        } catch (Exception ex) {
            log.warn("Deployment cron cycle failed", ex);
        }
    }
}
