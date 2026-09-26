## Background

`RedisAgentStateStore` uses a Lua script to update the state value, version, and session index atomically. Redis Cluster requires every key passed to one Lua script to belong to the same hash slot.

The original key layout did not use a Redis hash tag:

- `agentscope:session:userId/sessionId:agent_state`
- `agentscope:session:userId/sessionId:agent_state:ver`
- `agentscope:session:userId/sessionId:_keys`

Redis Cluster hashes these complete keys independently, so they may be assigned to different slots and the save or CAS operation fails with `CROSSSLOT`.

Related issue: https://github.com/agentscope-ai/agentscope-java/issues/3085

## What Changed

- Added explicit V0 and V1 key layouts. V0 remains the default to preserve existing behavior; V1 must be selected for Redis Cluster.
- V1 wraps the session segment in a Redis hash tag:
  - V0: `agentscope:session:userId/sessionId:agent_state`
  - V1: `agentscope:session:{userId/sessionId}:agent_state`
- The state key, version key, and session index now share one Redis Cluster slot under V1.
- Applied the configured layout consistently to single state, list state, `exists`, `delete`, and `listSessionIds`.
- Added a `RedisDistributedStore.fromJedis(...)` overload that accepts the key layout version.
- Updated Jedis key scanning to iterate across all Redis Cluster masters.
- Changed session cleanup to delete keys one at a time, avoiding multi-key deletes across unknown slots.
- Added validation for V1 key components and escaped Redis glob characters used by session listing.
- Added focused unit tests and real three-master Redis Cluster integration tests for V1 save/CAS and cluster-wide session listing.
- Documented the V0/V1 key differences and migration process in the English and Chinese Redis distributed-storage guides.

V0 and V1 use different key namespaces and do not read each other's data. All application instances sharing a key prefix must use the same layout version and migrate existing V0 data before switching to V1.
