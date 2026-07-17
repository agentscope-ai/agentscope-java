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

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.server.hitl.HitlCancelRequest;
import io.agentscope.core.a2a.server.hitl.HitlClaimRequest;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.a2a.server.hitl.HitlDurableStorageComponent;
import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlHandoffRecord;
import io.agentscope.core.a2a.server.hitl.HitlHandoffStatus;
import io.agentscope.core.a2a.server.hitl.HitlOpenRequest;
import io.agentscope.core.a2a.server.hitl.HitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.HitlResumeRejectedException;
import io.agentscope.core.a2a.server.hitl.HitlTokenDigests;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** Redis/Lua implementation of single-use durable HITL handoff admission. */
public final class RedisHitlResumeCoordinator
        implements HitlResumeCoordinator, HitlDurableStorageComponent {

    private static final String HASH_TAG = "{agentscope-a2a-hitl}:";
    private static final String OPEN_SCRIPT =
            "if redis.call('exists',KEYS[2]) == 1 then return 0 end; "
                    + "if ARGV[1] ~= '' then "
                    + " if redis.call('hget',KEYS[3],'status') ~= 'CLAIMED' then return -2 end; "
                    + " redis.call('hset',KEYS[3],'status',ARGV[16]); "
                    + " redis.call('zrem',KEYS[4],ARGV[1]); "
                    + "end; "
                    + "redis.call('hset',KEYS[1],"
                    + "'handoffId',ARGV[2],'taskId',ARGV[3],'contextId',ARGV[4],"
                    + "'userId',ARGV[5],'logicalAgentId',ARGV[6],"
                    + "'executionContextId',ARGV[7],'type',ARGV[8],"
                    + "'pendingJson',ARGV[9],'pendingFingerprint',ARGV[10],"
                    + "'tokenDigest',ARGV[11],'expiresAt',ARGV[12],"
                    + "'status',ARGV[13],'sessionKey',KEYS[2]); "
                    + "redis.call('pexpire',KEYS[1],ARGV[14]); "
                    + "redis.call('set',KEYS[2],ARGV[2],'PX',ARGV[15]); return 1";
    private static final String ADMIT_SCRIPT =
            "if redis.call('exists',KEYS[1]) == 0 then return -4 end; if"
                + " redis.call('hget',KEYS[1],'status') ~= ARGV[1] then return -1 end; if"
                + " tonumber(redis.call('hget',KEYS[1],'expiresAt')) <= tonumber(ARGV[2]) then "
                + " redis.call('hset',KEYS[1],'status',ARGV[3]); if redis.call('get',KEYS[2]) =="
                + " ARGV[12] then redis.call('del',KEYS[2]); end;"
                + " redis.call('zrem',KEYS[3],ARGV[12]); return -3 end; if"
                + " redis.call('hget',KEYS[1],'taskId') ~= ARGV[4] or"
                + " redis.call('hget',KEYS[1],'contextId') ~= ARGV[5] or"
                + " redis.call('hget',KEYS[1],'userId') ~= ARGV[6] or"
                + " redis.call('hget',KEYS[1],'logicalAgentId') ~= ARGV[7] or"
                + " redis.call('hget',KEYS[1],'executionContextId') ~= ARGV[8] or"
                + " redis.call('hget',KEYS[1],'pendingFingerprint') ~= ARGV[9] or"
                + " redis.call('hget',KEYS[1],'tokenDigest') ~= ARGV[10] then return -2 end;"
                + " redis.call('hset',KEYS[1],'status',ARGV[11]); if redis.call('get',KEYS[2]) =="
                + " ARGV[12] then redis.call('del',KEYS[2]); end; if ARGV[11] == 'CLAIMED' then "
                + " redis.call('hset',KEYS[1],'claimedAt',ARGV[2]); "
                + " redis.call('zadd',KEYS[3],ARGV[2],ARGV[12]); else"
                + " redis.call('zrem',KEYS[3],ARGV[12]); end; return 1";
    private static final String TRANSITION_SCRIPT =
            "if redis.call('exists',KEYS[1]) == 0 then return -2 end; if"
                    + " redis.call('hget',KEYS[1],'status') ~= ARGV[1] then return -1 end;"
                    + " redis.call('hset',KEYS[1],'status',ARGV[2]); if ARGV[1] == 'OPEN' then"
                    + " if redis.call('get',KEYS[2]) == ARGV[3] then"
                    + " redis.call('del',KEYS[2]); end; end; if ARGV[1] == 'CLAIMED' then"
                    + " redis.call('zrem',KEYS[3],ARGV[3]); end; return 1";
    private static final String RECONCILE_SCRIPT =
            "if redis.call('exists',KEYS[1]) == 0 then "
                    + " redis.call('zrem',KEYS[3],ARGV[1]); return 0 end; "
                    + "if redis.call('hget',KEYS[1],'status') ~= 'CLAIMED' then "
                    + " redis.call('zrem',KEYS[3],ARGV[1]); return 0 end; "
                    + "local claimed=tonumber(redis.call('hget',KEYS[1],'claimedAt') or '0'); "
                    + "if claimed == 0 or claimed > tonumber(ARGV[2]) then return -1 end; "
                    + "if redis.call('exists',KEYS[2]) == 1 then return -1 end; "
                    + "redis.call('hset',KEYS[1],'status','RECOVERY_REQUIRED'); "
                    + "redis.call('zrem',KEYS[3],ARGV[1]); return 1";
    private static final String COMPARE_DELETE_SCRIPT =
            "if redis.call('get',KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del',KEYS[1]); end; return 0";

    private final RedissonClient redissonClient;
    private final String namespace;
    private final Duration recordTtl;
    private final Clock clock;

    public RedisHitlResumeCoordinator(
            RedissonClient redissonClient, String namespace, Duration recordTtl) {
        this(redissonClient, namespace, recordTtl, Clock.systemUTC());
    }

    RedisHitlResumeCoordinator(
            RedissonClient redissonClient, String namespace, Duration recordTtl, Clock clock) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.namespace = RedisTaskStore.normalizeNamespace(namespace);
        this.recordTtl = RedisTaskStore.requirePositiveMillis(recordTtl, "recordTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public HitlHandoffRecord open(HitlOpenRequest request) {
        validateOpen(request);
        Duration handoffTtl = request.ttl() == null ? Duration.ofDays(7) : request.ttl();
        RedisTaskStore.requirePositiveMillis(handoffTtl, "handoff ttl");
        if (handoffTtl.compareTo(recordTtl) > 0) {
            throw new IllegalArgumentException("handoff ttl must not exceed task record ttl");
        }
        String handoffId = UUID.randomUUID().toString();
        String fingerprint = HitlTokenDigests.pendingFingerprint(request.pendingTools());
        String digest =
                HitlTokenDigests.boundTokenDigest(
                        request.taskId(),
                        request.contextId(),
                        handoffId,
                        request.executionKey(),
                        fingerprint,
                        request.nextResumeToken());
        Instant expiresAt = Instant.now(clock).plus(handoffTtl);
        String previous = nullToEmpty(request.claimedHandoffId());
        String previousKey = previous.isEmpty() ? recordKey(handoffId) : recordKey(previous);
        Long code =
                script().eval(
                                RScript.Mode.READ_WRITE,
                                OPEN_SCRIPT,
                                RScript.ReturnType.LONG,
                                List.of(
                                        recordKey(handoffId),
                                        sessionKey(request.executionKey()),
                                        previousKey,
                                        claimedIndexKey()),
                                previous,
                                handoffId,
                                request.taskId(),
                                request.contextId(),
                                request.executionKey().userId(),
                                request.executionKey().logicalAgentId(),
                                request.executionKey().contextId(),
                                request.type().name(),
                                encodePendingTools(request.pendingTools()),
                                fingerprint,
                                digest,
                                String.valueOf(expiresAt.toEpochMilli()),
                                HitlHandoffStatus.OPEN.name(),
                                String.valueOf(recordTtl.toMillis()),
                                String.valueOf(handoffTtl.toMillis()),
                                HitlHandoffStatus.SUPERSEDED.name());
        if (code != null && code == 0L) {
            throw new HitlResumeRejectedException("Session already has an open HITL handoff");
        }
        if (code == null || code != 1L) {
            throw new HitlResumeRejectedException("Previous HITL handoff cannot be superseded");
        }
        return requireRecord(handoffId);
    }

    @Override
    public HitlHandoffRecord validateClaim(HitlClaimRequest request) {
        Objects.requireNonNull(request, "request");
        HitlHandoffRecord record = requireRecord(request.handoffId());
        if (record.status() != HitlHandoffStatus.OPEN) {
            throw new HitlResumeRejectedException("HITL handoff is not open");
        }
        verifyBinding(record, request.taskId(), request.contextId(), request.resumeToken());
        return record;
    }

    @Override
    public HitlHandoffRecord claim(HitlClaimRequest request) {
        HitlHandoffRecord record = validateClaim(request);
        return admit(record, request.resumeToken(), HitlHandoffStatus.CLAIMED);
    }

    @Override
    public HitlHandoffRecord cancel(HitlCancelRequest request) {
        Objects.requireNonNull(request, "request");
        HitlHandoffRecord record = requireRecord(request.handoffId());
        if (record.status() != HitlHandoffStatus.OPEN) {
            throw new HitlResumeRejectedException("HITL handoff is not open");
        }
        verifyBinding(record, request.taskId(), request.contextId(), request.resumeToken());
        return admit(record, request.resumeToken(), HitlHandoffStatus.CANCELED);
    }

    @Override
    public HitlHandoffRecord transition(
            String handoffId, HitlHandoffStatus expected, HitlHandoffStatus target) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        HitlHandoffRecord record = readRecord(handoffId);
        if (record == null) {
            throw new HitlResumeRejectedException("Unknown HITL handoff");
        }
        Long code =
                script().eval(
                                RScript.Mode.READ_WRITE,
                                TRANSITION_SCRIPT,
                                RScript.ReturnType.LONG,
                                List.of(
                                        recordKey(handoffId),
                                        sessionKey(record.executionKey()),
                                        claimedIndexKey()),
                                expected.name(),
                                target.name(),
                                handoffId);
        if (code == null || code != 1L) {
            throw new HitlResumeRejectedException("HITL handoff state conflict");
        }
        return requireRecord(handoffId);
    }

    @Override
    public Optional<HitlHandoffRecord> get(String handoffId) {
        HitlHandoffRecord record = readRecord(handoffId);
        if (record == null) {
            return Optional.empty();
        }
        if (record.status() == HitlHandoffStatus.OPEN
                && !record.expiresAt().isAfter(Instant.now(clock))) {
            try {
                return Optional.of(
                        transition(handoffId, HitlHandoffStatus.OPEN, HitlHandoffStatus.EXPIRED));
            } catch (HitlResumeRejectedException raced) {
                return Optional.ofNullable(readRecord(handoffId));
            }
        }
        return Optional.of(record);
    }

    @Override
    public boolean hasOpenHandoff(HitlExecutionKey executionKey) {
        if (executionKey == null) {
            return false;
        }
        String key = sessionKey(executionKey);
        var session = redissonClient.<String>getBucket(key, StringCodec.INSTANCE);
        while (true) {
            String handoffId = session.get();
            if (handoffId == null) {
                return false;
            }
            Optional<HitlHandoffRecord> record = get(handoffId);
            if (record.isPresent() && record.get().status() == HitlHandoffStatus.OPEN) {
                if (handoffId.equals(session.get())) {
                    return true;
                }
                continue;
            }
            Long deleted =
                    script().eval(
                                    RScript.Mode.READ_WRITE,
                                    COMPARE_DELETE_SCRIPT,
                                    RScript.ReturnType.LONG,
                                    List.of(key),
                                    handoffId);
            if (deleted != null && deleted == 1L) {
                return false;
            }
        }
    }

    @Override
    public HitlDurabilityCapability durabilityCapability() {
        return HitlDurabilityCapability.DURABLE;
    }

    public RedissonClient redissonClient() {
        return redissonClient;
    }

    public String namespace() {
        return namespace;
    }

    @Override
    public Object storageClientIdentity() {
        return redissonClient;
    }

    @Override
    public String logicalStoreId() {
        return namespace;
    }

    int reconcileClaimed(Duration claimTimeout) {
        Duration timeout = RedisTaskStore.requirePositiveMillis(claimTimeout, "claimTimeout");
        long deadline = Instant.now(clock).minus(timeout).toEpochMilli();
        var claimedIndex =
                redissonClient.<String>getScoredSortedSet(claimedIndexKey(), StringCodec.INSTANCE);
        int recovered = 0;
        int offset = 0;
        while (true) {
            var candidates =
                    claimedIndex.valueRange(
                            Double.NEGATIVE_INFINITY, true, deadline, true, offset, 256);
            if (candidates.isEmpty()) {
                break;
            }
            int retained = 0;
            for (String handoffId : candidates) {
                HitlHandoffRecord record;
                try {
                    record = readRecord(handoffId);
                } catch (CorruptHitlRecordException corrupt) {
                    claimedIndex.remove(handoffId);
                    continue;
                }
                if (record == null) {
                    continue;
                }
                Long changed =
                        script().eval(
                                        RScript.Mode.READ_WRITE,
                                        RECONCILE_SCRIPT,
                                        RScript.ReturnType.LONG,
                                        List.of(
                                                recordKey(handoffId),
                                                leaseKey(record.executionKey()),
                                                claimedIndexKey()),
                                        handoffId,
                                        String.valueOf(deadline));
                if (changed == null) {
                    throw new IllegalStateException("Redis HITL reconciliation returned no result");
                }
                if (changed == 1L) {
                    recovered++;
                } else if (changed == -1L) {
                    retained++;
                }
            }
            offset += retained;
            if (candidates.size() < 256) {
                break;
            }
        }
        return recovered;
    }

    void deleteVerificationRecord(HitlHandoffRecord record) {
        if (record == null) {
            return;
        }
        script().eval(
                        RScript.Mode.READ_WRITE,
                        "redis.call('del',KEYS[1]); if redis.call('get',KEYS[2]) == ARGV[1] then"
                                + " redis.call('del',KEYS[2]); end;"
                                + " redis.call('zrem',KEYS[3],ARGV[1]); return 1",
                        RScript.ReturnType.LONG,
                        List.of(
                                recordKey(record.handoffId()),
                                sessionKey(record.executionKey()),
                                claimedIndexKey()),
                        record.handoffId());
    }

    String recordKey(String handoffId) {
        return namespace + HASH_TAG + "handoff:" + handoffId;
    }

    String sessionKey(HitlExecutionKey executionKey) {
        return namespace
                + HASH_TAG
                + "session:"
                + HitlTokenDigests.sha256(executionKey.sessionKey());
    }

    String leaseKey(HitlExecutionKey executionKey) {
        return namespace + HASH_TAG + "lease:" + HitlTokenDigests.sha256(executionKey.sessionKey());
    }

    String claimedIndexKey() {
        return namespace + HASH_TAG + "claimed";
    }

    private HitlHandoffRecord admit(
            HitlHandoffRecord record, String token, HitlHandoffStatus target) {
        String digest =
                HitlTokenDigests.boundTokenDigest(
                        record.taskId(),
                        record.contextId(),
                        record.handoffId(),
                        record.executionKey(),
                        record.pendingFingerprint(),
                        token);
        Long code =
                script().eval(
                                RScript.Mode.READ_WRITE,
                                ADMIT_SCRIPT,
                                RScript.ReturnType.LONG,
                                List.of(
                                        recordKey(record.handoffId()),
                                        sessionKey(record.executionKey()),
                                        claimedIndexKey()),
                                HitlHandoffStatus.OPEN.name(),
                                String.valueOf(Instant.now(clock).toEpochMilli()),
                                HitlHandoffStatus.EXPIRED.name(),
                                record.taskId(),
                                record.contextId(),
                                record.executionKey().userId(),
                                record.executionKey().logicalAgentId(),
                                record.executionKey().contextId(),
                                record.pendingFingerprint(),
                                digest,
                                target.name(),
                                record.handoffId());
        if (code != null && code == -3L) {
            throw new HitlResumeRejectedException("HITL handoff has expired");
        }
        if (code == null || code != 1L) {
            throw new HitlResumeRejectedException("HITL resume credential or state was rejected");
        }
        return requireRecord(record.handoffId());
    }

    private void verifyBinding(
            HitlHandoffRecord record, String taskId, String contextId, String token) {
        if (!record.taskId().equals(taskId) || !record.contextId().equals(contextId)) {
            throw new HitlResumeRejectedException("HITL handoff coordinates do not match");
        }
        String digest =
                HitlTokenDigests.boundTokenDigest(
                        taskId,
                        contextId,
                        record.handoffId(),
                        record.executionKey(),
                        record.pendingFingerprint(),
                        token);
        if (!HitlTokenDigests.constantTimeEquals(record.tokenDigest(), digest)) {
            throw new HitlResumeRejectedException("HITL resume credential was rejected");
        }
    }

    private HitlHandoffRecord requireRecord(String handoffId) {
        return get(handoffId)
                .orElseThrow(() -> new HitlResumeRejectedException("Unknown HITL handoff"));
    }

    private HitlHandoffRecord readRecord(String handoffId) {
        if (handoffId == null || handoffId.isBlank()) {
            return null;
        }
        RMap<String, String> map =
                redissonClient.getMap(recordKey(handoffId), StringCodec.INSTANCE);
        Map<String, String> values = map.readAllMap();
        if (values.isEmpty()) {
            redissonClient
                    .getScoredSortedSet(claimedIndexKey(), StringCodec.INSTANCE)
                    .remove(handoffId);
            return null;
        }
        try {
            List<ToolUseBlock> tools = decodePendingTools(values.get("pendingJson"));
            HitlExecutionKey executionKey =
                    new HitlExecutionKey(
                            values.get("userId"),
                            values.get("logicalAgentId"),
                            values.get("executionContextId"));
            return new HitlHandoffRecord(
                    values.get("handoffId"),
                    values.get("taskId"),
                    values.get("contextId"),
                    executionKey,
                    A2aHandoffType.valueOf(values.get("type")),
                    tools,
                    values.get("pendingFingerprint"),
                    values.get("tokenDigest"),
                    Instant.ofEpochMilli(Long.parseLong(values.get("expiresAt"))),
                    HitlHandoffStatus.valueOf(values.get("status")));
        } catch (RuntimeException corrupt) {
            throw new CorruptHitlRecordException(handoffId, corrupt);
        }
    }

    private RScript script() {
        return redissonClient.getScript(StringCodec.INSTANCE);
    }

    private static String encodePendingTools(List<ToolUseBlock> tools) {
        List<Map<String, Object>> values =
                tools.stream()
                        .map(
                                tool -> {
                                    Map<String, Object> value = new LinkedHashMap<>();
                                    value.put("id", tool.getId());
                                    value.put("name", tool.getName());
                                    value.put("input", tool.getInput());
                                    value.put("content", tool.getContent());
                                    value.put("metadata", tool.getMetadata());
                                    value.put("state", tool.getState().name());
                                    return value;
                                })
                        .toList();
        return JsonUtils.getJsonCodec().toJson(values);
    }

    private static List<ToolUseBlock> decodePendingTools(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Redis HITL record has no pending tools");
        }
        List<Map<String, Object>> values =
                JsonUtils.getJsonCodec()
                        .fromJson(json, new TypeReference<List<Map<String, Object>>>() {});
        return values.stream()
                .map(
                        value ->
                                new ToolUseBlock(
                                        requiredString(value, "id"),
                                        requiredString(value, "name"),
                                        mapValue(value.get("input")),
                                        value.get("content") == null
                                                ? null
                                                : String.valueOf(value.get("content")),
                                        mapValue(value.get("metadata")),
                                        ToolCallState.valueOf(requiredString(value, "state"))))
                .toList();
    }

    private static String requiredString(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (item == null || String.valueOf(item).isBlank()) {
            throw new IllegalStateException("Redis HITL record has invalid " + key);
        }
        return String.valueOf(item);
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static void validateOpen(HitlOpenRequest request) {
        if (request == null
                || request.taskId() == null
                || request.taskId().isBlank()
                || request.contextId() == null
                || request.contextId().isBlank()
                || request.executionKey() == null
                || request.type() == null
                || request.pendingTools().isEmpty()
                || request.nextResumeToken() == null
                || request.nextResumeToken().isBlank()) {
            throw new IllegalArgumentException("Incomplete HITL open request");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class CorruptHitlRecordException extends IllegalStateException {

        private CorruptHitlRecordException(String handoffId, Throwable cause) {
            super("Redis HITL record is corrupt: " + handoffId, cause);
        }
    }
}
