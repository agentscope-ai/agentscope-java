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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Post-commit, bounded publication. Durable outbox identities survive lost wakeups and ACKs. */
public final class ManagedToolCommandPublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolCommandPublisher.class);
    private static final long CLAIM_MILLIS = 120_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ManagedMessageBus bus;
    private final ManagedBatchCodec codec;
    private final ObjectMapper mapper;
    private final Semaphore capacity;
    private final int concurrency;
    private final Duration repairInterval;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean queued = new AtomicBoolean();
    private ScheduledFuture<?> retryWakeup;
    private long retryAt = Long.MAX_VALUE;

    record Claim(String id, String token, String payload, long attempt, long createdAt) {}

    public ManagedToolCommandPublisher(
            DataSource source,
            ManagedMessageBus bus,
            ManagedBatchCodec codec,
            ObjectMapper mapper) {
        this(source, bus, codec, mapper, 32, Duration.ofSeconds(30));
    }

    public ManagedToolCommandPublisher(
            DataSource source,
            ManagedMessageBus bus,
            ManagedBatchCodec codec,
            ObjectMapper mapper,
            int concurrency,
            Duration repairInterval) {
        if (concurrency < 1 || repairInterval.isNegative() || repairInterval.isZero())
            throw new IllegalArgumentException(
                    "Positive publication capacity and repair interval required");
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(5);
        this.bus = bus;
        this.codec = codec;
        this.mapper = mapper;
        this.concurrency = concurrency;
        this.repairInterval = repairInterval;
        capacity = new Semaphore(concurrency);
        workers =
                new ThreadPoolExecutor(
                        concurrency,
                        concurrency,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(concurrency),
                        task -> {
                            var thread = new Thread(task, "tool-command-publication");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        scheduler =
                new ScheduledThreadPoolExecutor(
                        1,
                        task -> {
                            var thread = new Thread(task, "tool-command-wakeup");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler.scheduleWithFixedDelay(
                this::wakeup,
                repairInterval.toMillis(),
                repairInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        wakeup();
    }

    /** Coalesces hints without SQL, MQ IO or waiting on the committing thread. */
    public void wakeup() {
        if (!running.get() || !queued.compareAndSet(false, true)) return;
        try {
            scheduler.execute(
                    () -> {
                        queued.set(false);
                        dispatch();
                    });
        } catch (RejectedExecutionException stopped) {
            queued.set(false); // The outbox, not this process, owns pending work.
        }
    }

    private int reserve(int limit) {
        int count = 0;
        while (count < Math.min(limit, concurrency) && capacity.tryAcquire()) count++;
        return count;
    }

    private void dispatch() {
        int reserved = reserve(concurrency);
        if (reserved == 0) return; // Do not claim or update any row without real local capacity.
        List<Claim> claims;
        long started = System.nanoTime();
        try {
            claims = claim(reserved);
        } catch (RuntimeException unavailable) {
            capacity.release(reserved);
            log.warn("Tool publication scan deferred: {}", unavailable.getClass().getSimpleName());
            return;
        }
        if (!claims.isEmpty())
            ManagedRuntimeLog.info(
                    log,
                    "tool.commands.claimed",
                    null,
                    null,
                    "count",
                    claims.size(),
                    "claim_duration_ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        capacity.release(reserved - claims.size());
        for (var claim : claims) {
            try {
                workers.execute(
                        () -> {
                            try {
                                publish(claim);
                            } finally {
                                capacity.release();
                                wakeup(); // Drain backlog on completion, not on a periodic SQL
                                // tick.
                            }
                        });
            } catch (RejectedExecutionException stopped) {
                // No send started; release this claim if possible, otherwise its lease expires.
                retry(claim, 0);
                capacity.release();
            }
        }
    }

    /** Synchronous bounded maintenance pump, also used by deterministic integration fixtures. */
    public int publishCommands(int limit) {
        int reserved = reserve(limit);
        if (reserved == 0) return 0;
        try {
            int sent = 0;
            for (var claim : claim(reserved)) if (publish(claim)) sent++;
            return sent;
        } finally {
            capacity.release(reserved);
        }
    }

    private List<Claim> claim(int limit) {
        long now = System.currentTimeMillis();
        return tx.execute(
                ignored -> {
                    var claims =
                            jdbc.query(
                                    "SELECT command_id,payload,publish_attempt,created_at FROM"
                                            + " ma.tool_command_outbox WHERE authorized=TRUE AND"
                                            + " broker_id IS NULL AND next_attempt_at<=? AND"
                                            + " publisher_lease_until<=? ORDER BY"
                                            + " next_attempt_at,created_at,command_id LIMIT ? FOR"
                                            + " UPDATE SKIP LOCKED",
                                    (row, index) ->
                                            new Claim(
                                                    row.getString(1),
                                                    UUID.randomUUID().toString(),
                                                    row.getString(2),
                                                    row.getLong(3) + 1,
                                                    row.getLong(4)),
                                    now,
                                    now,
                                    limit);
                    if (!claims.isEmpty())
                        jdbc.batchUpdate(
                                "UPDATE ma.tool_command_outbox SET"
                                    + " publisher_token=?,publisher_lease_until=?,publish_attempt=publish_attempt+1"
                                    + " WHERE command_id=?",
                                claims,
                                claims.size(),
                                (statement, claim) -> {
                                    statement.setString(1, claim.token());
                                    statement.setLong(2, now + CLAIM_MILLIS);
                                    statement.setString(3, claim.id());
                                });
                    return claims;
                }); // Release database row locks before network IO.
    }

    private boolean publish(Claim claim) {
        long started = System.currentTimeMillis();
        try {
            var command = mapper.readValue(claim.payload(), ManagedToolCommand.class);
            var receipt =
                    bus.publish(
                            "execute".equals(command.kind())
                                    ? Channel.TOOL_EXECUTE
                                    : Channel.TOOL_CONTROL,
                            codec.encode(command));
            boolean owned =
                    jdbc.update(
                                    "UPDATE ma.tool_command_outbox SET"
                                        + " broker_id=?,publisher_token=NULL,publisher_lease_until=0"
                                        + " WHERE command_id=? AND publisher_token=?",
                                    receipt.brokerMessageId(),
                                    claim.id(),
                                    claim.token())
                            == 1;
            ManagedRuntimeLog.tool(
                    log,
                    "tool.command.published",
                    command.scope(),
                    command.task(),
                    "command_id",
                    claim.id(),
                    "broker_message_id",
                    receipt.brokerMessageId(),
                    "attempt",
                    claim.attempt(),
                    "outbox_wait_ms",
                    started - claim.createdAt(),
                    "publish_duration_ms",
                    System.currentTimeMillis() - started,
                    "claim_current",
                    owned);
            return owned;
        } catch (Exception unavailable) {
            long ceiling = Math.min(30_000, 1000L << (int) Math.min(5, claim.attempt() - 1));
            // Keep durable retry semantics, but do not synchronize all publishers at 1/2/4 seconds
            // after a shared broker quota window rejects a burst.
            long delay = ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1);
            retry(claim, delay);
            if (running.get()) {
                try {
                    scheduleRetry(delay);
                } catch (RejectedExecutionException stopped) {
                    /* Low-frequency repair takes over. */
                }
            }
            log.warn(
                    "Tool command publication remains pending: {}",
                    unavailable.getClass().getSimpleName());
            var failure = ManagedMqMetrics.failure(unavailable);
            ManagedRuntimeLog.info(
                    log,
                    "tool.command.publish_failed",
                    null,
                    null,
                    "command_id",
                    claim.id(),
                    "attempt",
                    claim.attempt(),
                    "retry_delay_ms",
                    delay,
                    "failure_category",
                    failure.category(),
                    "cause_types",
                    failure.causes(),
                    "response_code",
                    failure.responseCode(),
                    "request_id",
                    failure.requestId());
            return false;
        }
    }

    private synchronized void scheduleRetry(long delay) {
        long at = System.currentTimeMillis() + delay;
        if (retryWakeup != null && !retryWakeup.isDone() && retryAt <= at) return;
        if (retryWakeup != null) retryWakeup.cancel(false);
        retryAt = at;
        retryWakeup =
                scheduler.schedule(
                        () -> {
                            synchronized (this) {
                                retryAt = Long.MAX_VALUE;
                                retryWakeup = null;
                            }
                            wakeup();
                        },
                        delay,
                        TimeUnit.MILLISECONDS);
    }

    private void retry(Claim claim, long delay) {
        try {
            jdbc.update(
                    "UPDATE ma.tool_command_outbox SET"
                        + " publisher_token=NULL,publisher_lease_until=0,next_attempt_at=? WHERE"
                        + " command_id=? AND publisher_token=? AND broker_id IS NULL",
                    System.currentTimeMillis() + delay,
                    claim.id(),
                    claim.token());
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Tool publication lease retained for recovery: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
        // Abandoned claims remain recoverable after their lease, with the same command ID.
        workers.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
