# Redis Session Extension

Redis-based session implementation for AgentScope supporting multiple Redis clients.

## Overview

This extension provides a Redis-based session storage implementation for AgentScope, supporting multiple Redis client libraries and deployment modes.

## Supported Redis Clients

- **Jedis** - Standalone, Cluster, Sentinel
- **Lettuce** - Standalone, Cluster, Sentinel
- **Redisson** - Standalone, Cluster, Sentinel, Master/Slave

## Maven Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-session-redis</artifactId>
    <version>1.0.8-SNAPSHOT</version>
</dependency>
```

## Usage Examples

### Jedis

#### Standalone

```java
import redis.clients.jedis.RedisClient;
import io.agentscope.core.session.redis.RedisSession;

// Create Jedis RedisClient
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

// Build RedisSession
Session session = RedisSession.builder()
    .jedisClient(redisClient)
    .build();
```

#### Cluster

```java
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.HostAndPort;
import io.agentscope.core.session.redis.RedisSession;

// Create Jedis RedisClusterClient
Set<HostAndPort> nodes = new HashSet<>();
nodes.add(new HostAndPort("localhost", 7000));
nodes.add(new HostAndPort("localhost", 7001));
nodes.add(new HostAndPort("localhost", 7002));
RedisClusterClient clusterClient = RedisClusterClient.create(nodes);

// Build RedisSession
Session session = RedisSession.builder()
    .jedisClient(clusterClient)
    .build();
```

#### Sentinel

```java
import redis.clients.jedis.RedisSentinelClient;
import io.agentscope.core.session.redis.RedisSession;

// Create Jedis RedisSentinelClient
Set<String> sentinelNodes = new HashSet<>();
sentinelNodes.add("localhost:26379");
sentinelNodes.add("localhost:26380");
RedisSentinelClient sentinelClient = RedisSentinelClient.create("mymaster", sentinelNodes);

// Build RedisSession
Session session = RedisSession.builder()
    .jedisClient(sentinelClient)
    .build();
```

### Lettuce

#### Standalone

```java
import io.lettuce.core.RedisClient;
import io.agentscope.core.session.redis.RedisSession;

// Create Lettuce RedisClient
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

// Build RedisSession
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

#### Cluster

```java
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.agentscope.core.session.redis.RedisSession;

// Create Lettuce RedisClient for cluster
RedisURI clusterUri = RedisURI.create("redis://localhost:7000");
RedisClient redisClient = RedisClient.create(clusterUri);

// Build RedisSession
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

#### Sentinel

```java
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.agentscope.core.session.redis.RedisSession;

// Create Lettuce RedisClient for sentinel
RedisURI sentinelUri = RedisURI.builder()
    .withSentinelMasterId("mymaster")
    .withSentinel("localhost", 26379)
    .withSentinel("localhost", 26380)
    .build();
RedisClient redisClient = RedisClient.create(sentinelUri);

// Build RedisSession
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

### Redisson

```java
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import io.agentscope.core.session.redis.RedisSession;

// Create RedissonClient
Config config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");
// cluster: config.useClusterServers().addNodeAddress("redis://localhost:7000");
// sentinel: config.useSentinelServers().setMasterName("mymaster").addSentinelAddress("redis://localhost:26379");

RedissonClient redissonClient = Redisson.create(config);

// Build RedisSession
Session session = RedisSession.builder()
    .redissonClient(redissonClient)
    .build();
```

### Custom Key Prefix

```java
import redis.clients.jedis.RedisClient;
import io.agentscope.core.session.redis.RedisSession;

// Create Redis client
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

// Build RedisSession with custom key prefix
Session session = RedisSession.builder()
    .jedisClient(redisClient)
    .keyPrefix("myapp:session:")
    .build();
```

## Key Structure

The session state is stored in Redis with following key structure:

- Single state: `{prefix}{sessionId}:{stateKey}` - Redis String containing JSON
- List state: `{prefix}{sessionId}:{stateKey}:list` - Redis List containing JSON items
- List hash: `{prefix}{sessionId}:{stateKey}:list:_hash` - Hash for change detection
- Session marker: `{prefix}{sessionId}:_keys` - Redis Set tracking all state keys

## Core Functionality

### Save Single

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
TestState state = new TestState("value", 42);

session.save(sessionKey, "testModule", state);
```

### Get Single

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
Optional<TestState> state = session.get(sessionKey, "testModule", TestState.class);
```

### Save List

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
List<TestState> states = List.of(
    new TestState("value1", 1),
    new TestState("value2", 2)
);

session.save(sessionKey, "testList", states);
```

### Get List

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
List<TestState> states = session.getList(sessionKey, "testList", TestState.class);
```

### Check Session Existence

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
boolean exists = session.exists(sessionKey);
```

### Delete Session

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
session.delete(sessionKey);
```

### List All Sessions

```java
Set<SessionKey> sessionKeys = session.listSessionKeys();
```

### Clear All Sessions

```java
Mono<Long> deletedCount = session.clearAllSessions();
long count = deletedCount.block();
```
