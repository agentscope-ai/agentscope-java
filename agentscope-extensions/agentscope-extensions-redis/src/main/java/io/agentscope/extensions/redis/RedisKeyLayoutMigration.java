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
package io.agentscope.extensions.redis;

import io.agentscope.extensions.redis.state.RedisClientAdapter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import redis.clients.jedis.UnifiedJedis;

/**
 * Migration tool for the Redis Cluster hash-tag key-layout breaking change.
 *
 * <p>Since 2.0.4, {@code RedisAgentStateStore} and {@code RedisStore} wrap their slot id /
 * namespace in a Redis Cluster hash tag ({@code {...}}) so multi-key {@code EVAL} scripts stay
 * inside one slot. Data written with the previous, un-tagged layout is not readable by the new
 * code and must be migrated. Without migration the failure mode is silent data loss: reads on
 * pre-upgrade sessions return empty and the agent resumes with a blank transcript.
 *
 * <p>This tool copies the companion keys of every legacy session / namespace to the tagged
 * layout and (unless dry-run) deletes the legacy keys after a successful per-slot copy:
 *
 * <ul>
 *   <li>AgentStateStore: {@code <prefix><user>/<session>:<key>} (+ {@code :ver}),
 *       {@code ...:<key>:list} (+ {@code :list:_hash}) and the {@code :_keys} tracking set. Set
 *       member names are slot-relative and are re-added unchanged under the tagged marker.
 *   <li>RedisStore: {@code <prefix>item:<ns>\0<key>} item hashes and the {@code <prefix>idx:<ns>}
 *       sorted-set namespace index (an empty namespace maps to the new {@code {_root_}} tag).
 * </ul>
 *
 * <p>Sessions whose user/session id contains characters the new API rejects ({@code { }} in the
 * session id; {@code { } / * ? [ ] \} in the user id) cannot be addressed by the new layout and
 * are <em>skipped</em> — reported in {@link StateStoreMigrationReport#skippedSlots()} and left
 * untouched for manual handling. Legacy keys not reachable from any {@code _keys} marker
 * (orphans) are never touched; they are reported via
 * {@link StateStoreMigrationReport#orphanKeysSample()} and can be swept with
 * {@code RedisAgentStateStore.clearAllSessions(true)} after review.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * RedisClientAdapter adapter = ...; // same client config as the store
 * StateStoreMigrationReport report =
 *         RedisKeyLayoutMigration.migrateAgentStateStore(adapter, "agentscope:session:", true);
 * System.out.println(report); // dry-run first, then re-run with dryRun=false
 * }</pre>
 *
 * <p><b>Operational notes:</b> run while the system is quiescent — the copy is not atomic per
 * slot and concurrent writers are not blocked. All operations are single-key, so the tool is
 * safe in cluster mode (no cross-slot commands), and the key scans fan out to every master
 * (via the in-tree adapters for the state-store variant, via Jedis {@code scanIteration} for
 * the {@code RedisStore} variant).
 */
public final class RedisKeyLayoutMigration {

    /** Suffix of the session tracking set (shared with RedisAgentStateStore's layout). */
    private static final String KEYS_SUFFIX = ":_keys";

    private static final String LIST_SUFFIX = ":list";
    private static final String HASH_SUFFIX = ":_hash";
    private static final String VERSION_SUFFIX = ":ver";
    private static final String NS_SEPARATOR = "\0";
    private static final String ROOT_TAG = "{_root_}";

    /** The session-marker key name ({@code KEYS_SUFFIX} minus the leading separator). */
    private static final String MARKER_KEY_NAME = "_keys";

    /** Reserved path standing in for the empty namespace (shared with RedisStore's layout). */
    private static final String EMPTY_NAMESPACE_TAG = "_root_";

    /** Cap on reported orphan keys, so a huge keyspace does not blow up the report. */
    private static final int ORPHAN_SAMPLE_CAP = 1000;

    private RedisKeyLayoutMigration() {}

    // ---------------------------------------------------------------------
    // RedisAgentStateStore
    // ---------------------------------------------------------------------

    /**
     * Find keys under {@code keyPrefix} that use the pre-hash-tag legacy layout (i.e. do not
     * start with {@code '{}'} right after the prefix). Used both for migration planning and for
     * the store's build-time legacy-layout warning.
     *
     * @param client the Redis client adapter
     * @param keyPrefix the store's key prefix
     * @return legacy-layout keys (possibly empty)
     */
    public static Set<String> findLegacyStateKeys(RedisClientAdapter client, String keyPrefix) {
        Set<String> all = client.findKeysByPattern(keyPrefix + "*");
        Set<String> legacy = new LinkedHashSet<>();
        for (String key : all) {
            if (key.startsWith(keyPrefix)
                    && (key.length() <= keyPrefix.length()
                            || key.charAt(keyPrefix.length()) != '{')) {
                legacy.add(key);
            }
        }
        return legacy;
    }

    /**
     * Migrate all legacy-layout AgentStateStore sessions under {@code keyPrefix} to the tagged
     * layout. When {@code dryRun} is false each successfully copied slot's legacy keys are
     * deleted; when true nothing is written or deleted (reads only) and the report describes
     * what would happen.
     *
     * @param client the Redis client adapter (any of the in-tree Jedis / Lettuce / Redisson
     *     adapters; the scan fans out across masters in cluster mode)
     * @param keyPrefix the store's key prefix
     * @param dryRun true to only report, false to actually copy and delete
     * @return the migration report
     */
    public static StateStoreMigrationReport migrateAgentStateStore(
            RedisClientAdapter client, String keyPrefix, boolean dryRun) {
        StateStoreMigrationReport report = new StateStoreMigrationReport(dryRun);
        Set<String> legacy = findLegacyStateKeys(client, keyPrefix);
        Set<String> referenced = new LinkedHashSet<>();
        List<String> skippedSlotPrefixes = new ArrayList<>();

        for (String marker : legacy) {
            if (!marker.endsWith(KEYS_SUFFIX)) {
                continue;
            }
            report.slotsDiscovered++;
            String slot =
                    marker.substring(keyPrefix.length(), marker.length() - KEYS_SUFFIX.length());
            String rejectReason = slotRejectReason(slot);
            if (rejectReason != null) {
                report.skippedSlots.add(slot + " (" + rejectReason + ")");
                skippedSlotPrefixes.add(keyPrefix + slot + ":");
                continue;
            }
            String newSlot = "{" + slot + "}";
            Set<String> members = client.getSetMembers(marker);
            // A member the new validateStateKey would reject would migrate into a location the
            // new API can never address; skip the whole slot and leave every key untouched (the
            // prefix list keeps those keys out of the orphan report).
            String badStateKey = null;
            String badReason = null;
            for (String member : members) {
                String reason = stateKeyRejectReason(member);
                if (reason != null) {
                    badStateKey = member;
                    badReason = reason;
                    break;
                }
            }
            if (badStateKey != null) {
                report.skippedSlots.add(
                        slot + " (state key '" + badStateKey + "' " + badReason + ")");
                skippedSlotPrefixes.add(keyPrefix + slot + ":");
                continue;
            }
            Set<String> slotKeys = new LinkedHashSet<>();
            slotKeys.add(marker);
            long copied = 0;

            for (String member : members) {
                if (member.endsWith(LIST_SUFFIX)) {
                    String base = member.substring(0, member.length() - LIST_SUFFIX.length());
                    String oldList = keyPrefix + slot + ":" + base + LIST_SUFFIX;
                    String oldHash = oldList + HASH_SUFFIX;
                    slotKeys.add(oldList);
                    slotKeys.add(oldHash);
                    List<String> items = client.rangeList(oldList, 0, -1);
                    String hash = client.get(oldHash);
                    copied += items.size() + (hash != null ? 1 : 0);
                    if (!dryRun) {
                        String newList = keyPrefix + newSlot + ":" + base + LIST_SUFFIX;
                        for (String item : items) {
                            client.rightPushList(newList, item);
                        }
                        if (hash != null) {
                            client.set(newList + HASH_SUFFIX, hash);
                        }
                    }
                } else {
                    String oldPayload = keyPrefix + slot + ":" + member;
                    String oldVersion = oldPayload + VERSION_SUFFIX;
                    slotKeys.add(oldPayload);
                    slotKeys.add(oldVersion);
                    String payload = client.get(oldPayload);
                    String version = client.get(oldVersion);
                    copied += (payload != null ? 1 : 0) + (version != null ? 1 : 0);
                    if (!dryRun) {
                        String newPayload = keyPrefix + newSlot + ":" + member;
                        if (payload != null) {
                            client.set(newPayload, payload);
                        }
                        if (version != null) {
                            client.set(newPayload + VERSION_SUFFIX, version);
                        }
                    }
                }
            }

            if (!dryRun) {
                String newMarker = keyPrefix + newSlot + KEYS_SUFFIX;
                for (String member : members) {
                    client.addToSet(newMarker, member);
                }
                for (String oldKey : slotKeys) {
                    // Single-key deletes: safe in cluster mode (no cross-slot multi-key DEL).
                    client.deleteKeys(oldKey);
                }
                report.legacyKeysDeleted += slotKeys.size();
            }
            referenced.addAll(slotKeys);
            report.keysCopied += copied;
            report.slotsMigrated++;
        }

        // Anything legacy that no processed marker references and no skipped slot owns is an
        // orphan (partial writes, markers deleted out of band): never touched, reported for
        // manual review.
        for (String key : legacy) {
            if (referenced.contains(key)) {
                continue;
            }
            boolean inSkippedSlot = false;
            for (String slotPrefix : skippedSlotPrefixes) {
                if (key.startsWith(slotPrefix)) {
                    inSkippedSlot = true;
                    break;
                }
            }
            if (inSkippedSlot) {
                continue;
            }
            if (report.orphanKeysSample.size() < ORPHAN_SAMPLE_CAP) {
                report.orphanKeysSample.add(key);
            }
            report.orphanKeysTotal++;
        }
        return report;
    }

    /**
     * Why a state-key member of a legacy tracking set cannot be addressed by the new API, or null
     * when it migrates cleanly. For a list-form member the addressed state key is its base (the
     * member minus the list suffix); for a raw member it is the member itself. Must stay in sync
     * with {@code RedisAgentStateStore.validateStateKey} (blank / braces / reserved companion-key
     * names).
     */
    private static String stateKeyRejectReason(String member) {
        String stateKey =
                member.endsWith(LIST_SUFFIX)
                        ? member.substring(0, member.length() - LIST_SUFFIX.length())
                        : member;
        if (stateKey.isBlank()) {
            return "is blank";
        }
        if (stateKey.indexOf('{') >= 0 || stateKey.indexOf('}') >= 0) {
            return "contains '{' or '}', which the new API rejects";
        }
        // Hash companions always sit behind a list key ("<state-key>:list:_hash"), so only a
        // key ending with ':list:_hash' can shadow one.
        if (stateKey.endsWith(LIST_SUFFIX)
                || stateKey.endsWith(VERSION_SUFFIX)
                || stateKey.endsWith(LIST_SUFFIX + HASH_SUFFIX)
                || stateKey.equals(MARKER_KEY_NAME)) {
            return "uses a reserved companion-key name";
        }
        return null;
    }

    /**
     * Why a legacy slot cannot be addressed by the new API, or null if it migrates cleanly. The
     * first {@code '/'} is the user/session boundary by construction of the old slot id.
     */
    private static String slotRejectReason(String slot) {
        int slash = slot.indexOf('/');
        if (slash <= 0 || slash == slot.length() - 1) {
            return "malformed slot id, expected <user>/<session>";
        }
        String user = slot.substring(0, slash);
        String session = slot.substring(slash + 1);
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c == '{' || c == '}' || c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                return "userId contains a character the new API rejects: '" + c + "'";
            }
        }
        if (session.indexOf('{') >= 0 || session.indexOf('}') >= 0) {
            return "sessionId contains '{' or '}', which the new API rejects";
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // RedisStore
    // ---------------------------------------------------------------------

    /**
     * Migrate all legacy-layout RedisStore namespaces under {@code keyPrefix} to the tagged
     * layout. Same dry-run semantics as {@link #migrateAgentStateStore}: with {@code dryRun}
     * false each migrated namespace's legacy keys are deleted after the copy.
     *
     * @param jedis initialized Jedis client
     * @param keyPrefix the store's key prefix (e.g. {@code agentscope:store:})
     * @param dryRun true to only report, false to actually copy and delete
     * @return the migration report
     */
    public static StoreMigrationReport migrateRedisStore(
            UnifiedJedis jedis, String keyPrefix, boolean dryRun) {
        StoreMigrationReport report = new StoreMigrationReport(dryRun);
        Set<String> legacyIdx = findLegacyStoreIndexKeys(jedis, keyPrefix);

        for (String idxKey : legacyIdx) {
            report.namespacesDiscovered++;
            String ns = idxKey.substring((keyPrefix + "idx:").length());
            if (ns.indexOf('{') >= 0 || ns.indexOf('}') >= 0) {
                report.skippedNamespaces.add(
                        ns + " (contains '{' or '}', rejected by the new API)");
                continue;
            }
            // {_root_} is the root (empty) namespace's tag and the new API rejects this
            // namespace; migrating it would merge its data into the root namespace's index.
            if (EMPTY_NAMESPACE_TAG.equals(ns)) {
                report.skippedNamespaces.add(
                        ns
                                + " (is the reserved empty-namespace placeholder, rejected by the"
                                + " new API)");
                continue;
            }
            String newNs = ns.isEmpty() ? ROOT_TAG : "{" + ns + "}";
            List<String> members = jedis.zrange(idxKey, 0, -1);
            List<String> slotKeys = new ArrayList<>();
            slotKeys.add(idxKey);

            for (String member : members) {
                String oldItem = keyPrefix + "item:" + ns + NS_SEPARATOR + member;
                slotKeys.add(oldItem);
                Map<String, String> fields = jedis.hgetAll(oldItem);
                if (fields == null || fields.isEmpty()) {
                    // Index entry without an item hash (torn write window): skip the item, keep
                    // the index membership so the new search stays consistent with the old.
                    continue;
                }
                report.itemsCopied++;
                if (!dryRun) {
                    jedis.hset(keyPrefix + "item:" + newNs + NS_SEPARATOR + member, fields);
                }
            }
            if (!dryRun) {
                for (String member : members) {
                    jedis.zadd(keyPrefix + "idx:" + newNs, 0.0, member);
                }
                for (String oldKey : slotKeys) {
                    jedis.del(oldKey);
                }
                report.legacyKeysDeleted += slotKeys.size();
            }
            report.namespacesMigrated++;
        }
        return report;
    }

    /**
     * Find legacy (un-tagged) RedisStore index keys under {@code keyPrefix}. Used for migration
     * and for RedisStore's build-time legacy-layout warning.
     *
     * @param jedis initialized Jedis client
     * @param keyPrefix the store's key prefix
     * @return legacy index keys (possibly empty)
     */
    public static Set<String> findLegacyStoreIndexKeys(UnifiedJedis jedis, String keyPrefix) {
        String idxPrefix = keyPrefix + "idx:";
        Set<String> legacy = new LinkedHashSet<>();
        for (String key : scanAll(jedis, idxPrefix + "*")) {
            // The root namespace's legacy index key IS idxPrefix itself (length == idxPrefix
            // length) — it must be migrated too; only already-tagged keys ('{' right after the
            // prefix) are excluded.
            if (key.startsWith(idxPrefix)
                    && (key.length() <= idxPrefix.length()
                            || key.charAt(idxPrefix.length()) != '{')) {
                legacy.add(key);
            }
        }
        return legacy;
    }

    private static Set<String> scanAll(UnifiedJedis jedis, String pattern) {
        // scanIteration transparently walks every master node in cluster mode (and the single
        // node otherwise), so no per-master invocation is needed.
        Set<String> keys = new LinkedHashSet<>();
        jedis.scanIteration(200, pattern).collect(keys);
        return keys;
    }

    // ---------------------------------------------------------------------
    // Reports
    // ---------------------------------------------------------------------

    /** Report of {@link #migrateAgentStateStore}. */
    public static final class StateStoreMigrationReport {
        private final boolean dryRun;
        private int slotsDiscovered;
        private int slotsMigrated;
        private long keysCopied;
        private long legacyKeysDeleted;
        private final List<String> skippedSlots = new ArrayList<>();
        private final List<String> orphanKeysSample = new ArrayList<>();
        private long orphanKeysTotal;

        private StateStoreMigrationReport(boolean dryRun) {
            this.dryRun = dryRun;
        }

        /** True when the run only reported (no writes, no deletes). */
        public boolean dryRun() {
            return dryRun;
        }

        /** Legacy {@code _keys} markers found (= legacy sessions discovered). */
        public int slotsDiscovered() {
            return slotsDiscovered;
        }

        /** Sessions fully copied to the tagged layout. */
        public int slotsMigrated() {
            return slotsMigrated;
        }

        /** Companion keys (payloads, versions, list items, list hashes) copied. */
        public long keysCopied() {
            return keysCopied;
        }

        /** Legacy keys deleted after successful copies (always 0 for dry runs). */
        public long legacyKeysDeleted() {
            return legacyKeysDeleted;
        }

        /** Sessions skipped (with reasons) because the new API cannot address them. */
        public List<String> skippedSlots() {
            return skippedSlots;
        }

        /** Sample (capped) of legacy keys no marker tracks; review before sweeping. */
        public List<String> orphanKeysSample() {
            return orphanKeysSample;
        }

        /** Total number of untracked legacy keys (the sample is capped, this is not). */
        public long orphanKeysTotal() {
            return orphanKeysTotal;
        }

        @Override
        public String toString() {
            return "StateStoreMigrationReport{dryRun="
                    + dryRun
                    + ", slotsDiscovered="
                    + slotsDiscovered
                    + ", slotsMigrated="
                    + slotsMigrated
                    + ", keysCopied="
                    + keysCopied
                    + ", legacyKeysDeleted="
                    + legacyKeysDeleted
                    + ", skippedSlots="
                    + skippedSlots
                    + ", orphanKeysTotal="
                    + orphanKeysTotal
                    + '}';
        }
    }

    /** Report of {@link #migrateRedisStore}. */
    public static final class StoreMigrationReport {
        private final boolean dryRun;
        private int namespacesDiscovered;
        private int namespacesMigrated;
        private long itemsCopied;
        private long legacyKeysDeleted;
        private final List<String> skippedNamespaces = new ArrayList<>();

        private StoreMigrationReport(boolean dryRun) {
            this.dryRun = dryRun;
        }

        /** True when the run only reported (no writes, no deletes). */
        public boolean dryRun() {
            return dryRun;
        }

        /** Legacy index keys found (= legacy namespaces discovered). */
        public int namespacesDiscovered() {
            return namespacesDiscovered;
        }

        /** Namespaces fully copied to the tagged layout. */
        public int namespacesMigrated() {
            return namespacesMigrated;
        }

        /** Item hashes copied. */
        public long itemsCopied() {
            return itemsCopied;
        }

        /** Legacy keys deleted after successful copies (always 0 for dry runs). */
        public long legacyKeysDeleted() {
            return legacyKeysDeleted;
        }

        /** Namespaces skipped (with reasons) because the new API cannot address them. */
        public List<String> skippedNamespaces() {
            return skippedNamespaces;
        }

        @Override
        public String toString() {
            return "StoreMigrationReport{dryRun="
                    + dryRun
                    + ", namespacesDiscovered="
                    + namespacesDiscovered
                    + ", namespacesMigrated="
                    + namespacesMigrated
                    + ", itemsCopied="
                    + itemsCopied
                    + ", legacyKeysDeleted="
                    + legacyKeysDeleted
                    + ", skippedNamespaces="
                    + skippedNamespaces
                    + '}';
        }
    }
}
