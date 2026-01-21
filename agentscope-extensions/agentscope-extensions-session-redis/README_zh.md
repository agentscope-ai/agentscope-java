# Redis Session 扩展

基于Redis的AgentScope会话实现，支持多种Redis客户端。

## 概述

此扩展为AgentScope提供了基于Redis的会话存储实现，支持多种Redis客户端库和部署模式。

## 支持的Redis客户端

- **Jedis** - 单机、集群、哨兵
- **Lettuce** - 单机、集群、哨兵
- **Redisson** - 单机、集群、哨兵、主从

## Maven依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-session-redis</artifactId>
    <version>1.0.8-SNAPSHOT</version>
</dependency>
```

## 使用示例

### Jedis

#### 单机模式

```java
import redis.clients.jedis.RedisClient;
import io.agentscope.core.session.redis.RedisSession;

// 创建Jedis RedisClient
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

// 构建RedisSession
Session session = RedisSession.builder()
    .jedisClient(redisClient)
    .build();
```

#### 集群模式

```java
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.HostAndPort;
import io.agentscope.core.session.redis.RedisSession;

// 创建Jedis RedisClusterClient
Set<HostAndPort> nodes = new HashSet<>();
nodes.add(new HostAndPort("localhost", 7000));
nodes.add(new HostAndPort("localhost", 7001));
nodes.add(new HostAndPort("localhost", 7002));
RedisClusterClient clusterClient = RedisClusterClient.create(nodes);

// 构建RedisSession
Session session = RedisSession.builder()
    .jedisClient(clusterClient)
    .build();
```

#### 哨兵模式

```java
import redis.clients.jedis.RedisSentinelClient;
import io.agentscope.core.session.redis.RedisSession;

// 创建Jedis RedisSentinelClient
Set<String> sentinelNodes = new HashSet<>();
sentinelNodes.add("localhost:26379");
sentinelNodes.add("localhost:26380");
RedisSentinelClient sentinelClient = RedisSentinelClient.create("mymaster", sentinelNodes);

// 构建RedisSession
Session session = RedisSession.builder()
    .jedisClient(sentinelClient)
    .build();
```

### Lettuce

#### 单机模式

```java
import io.lettuce.core.RedisClient;
import io.agentscope.core.session.redis.RedisSession;

// 创建Lettuce RedisClient
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

// 构建RedisSession
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

#### 集群模式

```java
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.agentscope.core.session.redis.RedisSession;

// 创建Lettuce RedisClient用于集群
RedisURI clusterUri = RedisURI.create("redis://localhost:7000");
RedisClient redisClient = RedisClient.create(clusterUri);

// 构建RedisSession
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

#### 哨兵模式

```java
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.agentscope.core.session.redis.RedisSession;

// 创建Lettuce RedisClient用于哨兵
RedisURI sentinelUri = RedisURI.builder()
    .withSentinelMasterId("mymaster")
    .withSentinel("localhost", 26379)
    .withSentinel("localhost", 26380)
    .build();
RedisClient redisClient = RedisClient.create(sentinelUri);

// 构建RedisSession
Session session = RedisSession.builder()
    .lettuceClient(redisClient)
    .build();
```

### Redisson

```java
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import io.agentscope.core.session.redis.RedisSession;

// 创建RedissonClient
Config config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");
// 集群模式: config.useClusterServers().addNodeAddress("redis://localhost:7000");
// 哨兵模式: config.useSentinelServers().setMasterName("mymaster").addSentinelAddress("redis://localhost:26379");

RedissonClient redissonClient = Redisson.create(config);

// 构建RedisSession
Session session = RedisSession.builder()
    .redissonClient(redissonClient)
    .build();
```

### 自定义前缀

```java
import redis.clients.jedis.RedisClient;
import io.agentscope.core.session.redis.RedisSession;

// 创建Redis客户端
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

// 构建带有自定义键前缀的RedisSession
Session session = RedisSession.builder()
    .jedisClient(redisClient)
    .keyPrefix("myapp:session:")
    .build();
```

## key结构

会话状态在Redis中以下列键结构存储：

- 单个状态: `{prefix}{sessionId}:{stateKey}` - 包含 JSON 的 Redis String
- 列表状态: `{prefix}{sessionId}:{stateKey}:list` - 包含 JSON 项的 Redis List
- 列表哈希: `{prefix}{sessionId}:{stateKey}:list:_hash` - 用于变更检测的哈希
- 会话标记: `{prefix}{sessionId}:_keys` - 跟踪所有状态键的 Redis Set

## 核心功能

### 保存单个

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
TestState state = new TestState("value", 42);

session.save(sessionKey, "testModule", state);
```

### 获取单个

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
Optional<TestState> state = session.get(sessionKey, "testModule", TestState.class);
```

### 保存列表

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
List<TestState> states = List.of(
    new TestState("value1", 1),
    new TestState("value2", 2)
);

session.save(sessionKey, "testList", states);
```

### 获取列表

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
List<TestState> states = session.getList(sessionKey, "testList", TestState.class);
```

### 检查session是否存在

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
boolean exists = session.exists(sessionKey);
```

### 删除session

```java
SessionKey sessionKey = SimpleSessionKey.of("session1");
session.delete(sessionKey);
```

### 列出所有session

```java
Set<SessionKey> sessionKeys = session.listSessionKeys();
```

### 清空所有session

```java
Mono<Integer> deletedCount = session.clearAllSessions();
long count = deletedCount.block();
```