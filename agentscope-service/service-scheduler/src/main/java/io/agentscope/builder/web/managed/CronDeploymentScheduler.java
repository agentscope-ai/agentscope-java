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

import io.agentscope.builder.web.coord.BuilderInstanceId;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.persistence.jpa.DeploymentEntity;
import io.agentscope.builder.web.persistence.jpa.DeploymentEntityRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scheduler-plane cron loop: every minute, finds due cron deployments, acquires a shared fire
 * lease, and asks the control plane to fire (create session + kick data-plane turn).
 */
@Component
public class CronDeploymentScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronDeploymentScheduler.class);
    private static final String TRIGGER_CRON = "cron";

    private final DeploymentEntityRepository repository;
    private final CoordinationStore coordinationStore;
    private final BuilderInstanceId instanceId;
    private final WebClient controlPlane;
    private final ScheduledExecutorService scheduler;

    public CronDeploymentScheduler(
            DeploymentEntityRepository repository,
            CoordinationStore coordinationStore,
            BuilderInstanceId instanceId,
            @Qualifier("controlPlaneWebClient") WebClient controlPlane) {
        this.repository = repository;
        this.coordinationStore = coordinationStore;
        this.instanceId = instanceId;
        this.controlPlane = controlPlane;
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
            int fired = 0;
            String fireWindow = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
            for (DeploymentEntity entity :
                    repository.findByTriggerTypeAndEnabledTrueAndArchivedAtIsNull(TRIGGER_CRON)) {
                if (!isCronDue(entity)) {
                    continue;
                }
                boolean won =
                        coordinationStore.tryAcquireFireLease(
                                entity.getDeploymentId(),
                                fireWindow,
                                instanceId.get(),
                                Duration.ofMinutes(2));
                if (!won) {
                    continue;
                }
                try {
                    controlPlane
                            .post()
                            .uri("/api/internal/deployments/{id}/fire", entity.getDeploymentId())
                            .retrieve()
                            .toBodilessEntity()
                            .block(Duration.ofSeconds(30));
                    fired++;
                } catch (Exception ex) {
                    log.warn(
                            "Failed to fire cron deployment {}: {}",
                            entity.getDeploymentId(),
                            ex.getMessage());
                }
            }
            if (fired > 0) {
                log.info("Fired {} due cron deployment(s)", fired);
            }
        } catch (Exception ex) {
            log.warn("Deployment cron cycle failed", ex);
        }
    }

    private static boolean isCronDue(DeploymentEntity entity) {
        if (entity.getCronExpression() == null || entity.getCronExpression().isBlank()) {
            return false;
        }
        try {
            CronExpression cron = CronExpression.parse(entity.getCronExpression());
            long anchorMs =
                    entity.getLastRunAt() != null ? entity.getLastRunAt() : entity.getCreatedAt();
            LocalDateTime anchor =
                    Instant.ofEpochMilli(anchorMs).atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime next = cron.next(anchor);
            return next != null && !next.isAfter(LocalDateTime.now());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
