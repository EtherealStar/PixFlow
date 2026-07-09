# 重构 Redis 缓存序列化边界，移除 Redisson 业务 JSON 多态依赖

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本文档遵循仓库根目录 `PLANS.md`。后续执行者必须在推进、暂停、调整方向或完成任一里程碑时同步更新本文档，使一个没有上下文的新贡献者只读本文档也能继续完成工作。

## Purpose / Big Picture

当前 PixFlow 在刷新登录会话时偶发 Redisson / Jackson 反序列化错误，日志表现为 `InvalidTypeIdException: missing type id property '@class'`，调用链是 `AuthService.refresh -> RedisAuthSessionStore.find -> RedissonCacheStore.get`。用户能看到的影响是 refresh 缓存降级为 miss，轻则登录态刷新偶发失败或重新登录，重则其他未来业务缓存遇到同类脏数据时出现难以定位的 Redisson 解码异常。

本计划完成后，业务缓存值不再依赖 Redisson 的 `JsonJacksonCodec` 反序列化 Java 对象。Redis 原语仍由 Redisson 提供，业务 KV 值统一由 PixFlow 自己用 Jackson `ObjectMapper` 序列化为普通 JSON 字符串，再用 `StringCodec` 存入 Redis。这样 Redis 中的缓存协议稳定、可人工查看、可被 redis-cli 或外部工具写入同形 JSON，并且读取 `AuthSession.class` 时不再要求 Redis value 含有 Redisson 多态字段 `@class`。

可观察结果是：清理当前环境 Redis 中的旧业务缓存 key 后，`CacheStore.put(...)` 写入的 raw Redis value 是不含 `@class` 的普通 JSON 字符串；用无 `@class` 的普通 JSON 手工写入 `auth:refresh:{jti}` 后，`CacheStore.get(key, AuthSession.class)` 能读出 `AuthSession`；运行 `mvn -pl pixflow-infra-cache,pixflow-infra-auth -am test` 通过，并新增测试证明当前日志中的异常不会再发生。本计划是破坏式重构，不读取、不转换、不迁就旧 Redisson `JsonJacksonCodec` 写出的业务缓存值。

## Progress

- [x] (2026-07-07 14:20+08:00) 阅读 `PLANS.md`、当前 `docs/design-docs/exec-plans/` 活跃计划列表，以及 `docs/design-docs/infra/cache.md`、`docs/design-docs/infra/auth.md`。
- [x] (2026-07-07 14:25+08:00) 检查关键代码，确认 `RedissonCacheStore.get(...)` 在拿到调用方传入的 `Class<T>` 前先执行 `redissonClient.<Object>getBucket(key.value()).get()`，因此 Redisson 会按 `Object` 解码并要求 `@class`。
- [x] (2026-07-07 14:30+08:00) 确认 `AuthSession` 本身没有 `Object` 字段，`RedisAuthSessionStore.find(...)` 已经传入 `AuthSession.class`，问题属于通用缓存层序列化边界不清，而不是 auth DTO 设计错误。
- [x] (2026-07-07 14:40+08:00) 创建本文档，确定重构方向：业务 KV 值使用 PixFlow 自管 JSON 字符串，Redisson codec 只用于 Redis 原语和字符串传输。
- [x] (2026-07-07 14:55+08:00) 按用户要求修订本文档为破坏式重构计划：不兼容旧 `@class` 数据，不做迁移式安全，旧 Redis 业务缓存通过按前缀清理解决。
- [x] (2026-07-07 14:10+08:00) 实现 `JsonCacheSerializer`，统一负责 `ObjectMapper.writeValueAsString` 和按目标类型 `readValue`；该组件不识别、删除或兼容 `@class`。
- [x] (2026-07-07 14:10+08:00) 重写 `RedissonCacheStore`，所有业务 value 通过 `StringCodec.INSTANCE` 读写 JSON 字符串，不再使用 `getBucket<Object>()` 或 Redisson `JsonJacksonCodec` 解码业务对象。
- [x] (2026-07-07 14:10+08:00) 调整 `CacheAutoConfiguration`，全局 RedissonClient 不再注入 `JsonJacksonCodec(objectMapper)` 作为业务对象协议。
- [x] (2026-07-07 14:12+08:00) 修正 `RedisIntegrationTest#setUp()` 的 codec 缺陷，并补充普通 JSON、raw value 不含 `@class`、坏 JSON 降级三个回归测试。
- [x] (2026-07-07 14:16+08:00) 运行 infra-cache / infra-auth 相关 Maven 测试，`mvn -pl pixflow-infra-cache -am test`、`mvn -pl pixflow-infra-auth -am test` 与 `mvn -pl pixflow-infra-cache,pixflow-infra-auth -am test` 均通过。

## Surprises & Discoveries

- Observation: `AuthSession` 并没有 `Object`、`Map<String,Object>` 或多态字段，因此用户日志中的 `missing type id property '@class'` 不是由 auth session DTO 内部字段天然触发。
  Evidence: `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/AuthSession.java` 是一个只包含 `String`、`Long`、`Instant` 的 record。

- Observation: `RedisAuthSessionStore.find(...)` 已经把目标类型传给通用缓存层，但通用缓存层没有把该类型用于 Redis 解码。
  Evidence: `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/RedisAuthSessionStore.java` 调用 `cacheStore.get(namespace.key("auth", "refresh", refreshJwtId), AuthSession.class)`；但 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/RedissonCacheStore.java` 中先执行 `redissonClient.<Object>getBucket(key.value()).get()`。

- Observation: 当前生产配置把 Redisson 全局 codec 设置为 `new JsonJacksonCodec(objectMapper)`，这会让 Redisson 在业务 value 读取阶段承担 Jackson 多态反序列化职责。
  Evidence: `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/config/CacheAutoConfiguration.java` 中 `config.setCodec(new JsonJacksonCodec(objectMapper))`。

- Observation: 当前 `RedisIntegrationTest` 没有真实覆盖自定义 codec，因为它先创建 RedissonClient，后设置 codec。
  Evidence: `pixflow-infra-cache/src/test/java/com/pixflow/infra/cache/RedisIntegrationTest.java` 中 `redissonClient = Redisson.create(config)` 出现在 `config.setCodec(new JsonJacksonCodec(objectMapper))` 之前。

## Decision Log

- Decision: 业务 KV 缓存值统一改为 PixFlow 自管 JSON 字符串，而不是继续依赖 Redisson `JsonJacksonCodec` 或 `TypedJsonJacksonCodec`。
  Rationale: `TypedJsonJacksonCodec` 可以修补当前 `Object` 解码问题，但业务对象协议仍隐含在 Redisson codec 中。把业务 value 明确序列化为字符串后，Redis 中的协议是普通 JSON，读写一致性由 `CacheStore` 和 `ObjectMapper` 保证，不再受 Redisson default typing、历史 `@class` 字段或外部工具写入方式影响。
  Date/Author: 2026-07-07 / Codex

- Decision: 本重构不改变 `CacheStore` 第一阶段的公开接口签名，先保持 `<T> Optional<T> get(CacheKey key, Class<T> type)` 和 `<T> void put(CacheKey key, T value, Duration ttl)`。
  Rationale: 直接把 `CacheKey` 改成泛型 `CacheKey<T>` 会触及所有调用方，属于更大的 API 重构。当前风险点在实现层提前按 `Object` 反序列化，先用字符串 JSON 修掉根因，再在后续小步升级类型化 key，可以降低爆炸半径。
  Date/Author: 2026-07-07 / Codex

- Decision: 不兼容旧 Redisson `JsonJacksonCodec` 写出的业务缓存值，旧 Redis 业务缓存必须清理后由新代码重建。
  Rationale: 用户明确要求这是重构计划，不需要迁移式安全。保留 legacy 读取会把 `@class` 这套旧协议继续留在代码路径里，削弱“业务 KV 只认普通 JSON 字符串”的边界。缓存按设计可丢可重建，旧 auth refresh 会话、用户快照、失败计数等都应通过 TTL 或按前缀清理处理。
  Date/Author: 2026-07-07 / Codex

- Decision: Redis 旧业务缓存清理是本重构的必要执行步骤，不是兜底选项。
  Rationale: 本计划不做迁移兼容，因此新代码上线前后必须清理受旧 codec 影响的缓存 key。由于 `CacheStore` 按设计只存可丢弃缓存和会话状态，不存 MySQL 事实源，按命名空间删除比在代码中长期保留双协议更清晰。
  Date/Author: 2026-07-07 / Codex

## Outcomes & Retrospective

2026-07-07 / Codex: 完成本计划的代码和文档实施。修改文件包括 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/JsonCacheSerializer.java`、`RedissonCacheStore.java`、`CacheAutoConfiguration.java`、`pixflow-infra-cache/src/test/java/com/pixflow/infra/cache/RedisIntegrationTest.java`、`docs/design-docs/infra/cache.md` 和本文档。`RedissonCacheStore` 现在只用 `StringCodec.INSTANCE` 读写业务 JSON 字符串，`CacheAutoConfiguration` 不再设置全局 `JsonJacksonCodec`，主代码中不再保留 Redisson JSON codec 对业务 KV value 的依赖。新增 Redis 集成测试证明无 `@class` 的普通 JSON 可以读出、`CacheStore.put(...)` 写出的 raw value 不含 `@class`、坏 JSON会降级为 miss。验证命令 `mvn -pl pixflow-infra-cache -am test` 通过（infra-cache 12 个测试，其中 RedisIntegrationTest 8 个测试）；`mvn -pl pixflow-infra-auth -am test` 通过（infra-auth 11 个测试）；`mvn -pl pixflow-infra-cache,pixflow-infra-auth -am test` 通过。联合测试第一次在 Windows sandbox 内创建 Maven 进程时被 `CreateProcessAsUserW failed: 5` 拦截，随后按权限机制提升权限重跑通过。本地未执行真实环境旧 Redis key 清理；上线或联调前仍必须按环境前缀用 `SCAN` 预览并分批 `DEL` 清理旧业务缓存 key，不得使用 `FLUSHDB`。

## Context and Orientation

PixFlow 的 Redis 能力集中在 Maven 模块 `pixflow-infra-cache`。这个模块对外暴露 `CacheStore`、`AtomicCounter`、`LockTemplate`、`DistributedSemaphore` 等原语。原语在这里指 Redis 或 Redisson 提供的基础能力，例如“存一个 key-value”、“加一把分布式锁”、“做一个原子计数”。业务模块不应直接操作 Redisson 细节，而应通过这些接口使用 Redis。

`CacheStore` 是泛型 KV 缓存接口，位于 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/CacheStore.java`。KV 的意思是 key-value，也就是通过一个字符串 key 存取一个小 JSON 值。当前实现类是 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/RedissonCacheStore.java`。

`Codec` 是 Redisson 对 Redis 字节内容做编码和解码的组件。`JsonJacksonCodec` 会让 Redisson 使用 Jackson 直接把 Redis value 反序列化成 Java 对象。为了在目标类型是 `Object` 时知道该实例化哪个类，它会依赖 JSON 中的 `@class` 多态字段。多态字段是指 JSON 里额外保存 Java 类名的字段，例如 `"@class":"com.pixflow.infra.auth.session.AuthSession"`。这类字段对 Java 内部对象恢复有用，但会让 Redis value 不再是稳定的普通 JSON 协议。

这次日志中的错误发生在 auth refresh 会话。`pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/RedisAuthSessionStore.java` 保存和读取 `AuthSession`，key 形如 `auth:refresh:{jti}`。`AuthSession` 是 refresh token 的服务端会话，保存用户 id、用户名、refresh token hash、创建时间和过期时间。refresh token 是浏览器 Cookie 中的长期随机字符串，服务端只保存 hash，用于轮换 access token。

当前关键问题是 `RedissonCacheStore.get(...)` 虽然接收了 `Class<T> type`，但实际先调用 `redissonClient.<Object>getBucket(key.value()).get()`。这会让 Redisson 先按 `Object` 解码 Redis value。如果 Redis value 是没有 `@class` 的普通 JSON，Redisson 会在返回给 `CacheStore` 前抛 `InvalidTypeIdException`。因此后续 `objectMapper.convertValue(value, type)` 没有机会执行。

设计文档 `docs/design-docs/infra/cache.md` 已经规定缓存值应是小而结构化的引用或元数据对象，Redis 不存大字节，并且缓存读失败可以降级为 miss。本文计划沿用这个设计，把“结构化对象如何变成 JSON”的职责从 Redisson codec 移到 PixFlow 自己的缓存序列化器中。

## Plan of Work

第一阶段先新增一个小而明确的 JSON 序列化边界。建议创建 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/JsonCacheSerializer.java`，构造函数接收 `ObjectMapper`。它提供两个方法：`String serialize(Object value)` 和 `<T> T deserialize(String json, Class<T> type)`。`serialize` 使用 `objectMapper.writeValueAsString(value)`。`deserialize` 直接使用 `objectMapper.readValue(json, type)`。不要把字符串先读成 `JsonNode` 再删除 `@class`，不要尝试识别旧 Redisson JSON 格式，也不要恢复任意多态对象。遇到带 `@class` 的旧数据时，将它视为旧协议污染；`CacheStore.get(...)` 捕获反序列化异常后降级为 miss，真正处理方式是清理旧 key。

同一阶段要定义异常处理策略。`JsonCacheSerializer` 内部可以抛 `IllegalArgumentException` 包装 Jackson 异常，由 `RedissonCacheStore.get(...)` 捕获并按现有设计降级为 miss。序列化失败发生在 `put(...)`，应记录 warn 并吞掉，因为缓存写失败不影响主事实源。日志中不得输出完整 refresh token、token hash、JWT 或用户隐私字段；只记录 namespace 和异常类型即可。

第二阶段重写 `RedissonCacheStore`。编辑 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/RedissonCacheStore.java`，删除业务 value 的 `redissonClient.<Object>getBucket(...)`、`RBucket<Object>` 和 `objectMapper.convertValue(...)` 路径。`get(...)` 应使用 `redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE).get()` 取回 JSON 字符串，再调用 `JsonCacheSerializer.deserialize(json, type)`。如果 key 不存在或 value 为空，按 miss 返回。`put(...)` 和 `putIfAbsent(...)` 应先把 value 序列化成 JSON 字符串，再使用 `StringCodec.INSTANCE` 写入 Redis。`exists(...)` 和 `delete(...)` 可以继续使用默认 bucket，也可以显式使用 `StringCodec.INSTANCE`，建议统一显式使用以减少 codec 隐含行为。

第三阶段调整 Redisson 全局配置。编辑 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/config/CacheAutoConfiguration.java`，移除 `config.setCodec(new JsonJacksonCodec(objectMapper))`。如果 Redisson 默认 codec 对锁、计数、信号量没有影响，可以不设置全局 codec；如果要明确表达 Redis 字符串协议，可以设置 `StringCodec.INSTANCE`。选择前要跑 infra-cache 测试，确认 `RedissonAtomicCounter` 的 Lua、`RedisConfirmationTokenStore` 的 `StringCodec`、锁和信号量都不受影响。`RedisConfirmationTokenStore` 当前已经自己用 `ObjectMapper` 写 JSON 字符串并配 `StringCodec.INSTANCE`，它是本次业务 KV 重构的参考实现。

第四阶段修正测试并补回归。编辑 `pixflow-infra-cache/src/test/java/com/pixflow/infra/cache/RedisIntegrationTest.java`，把 codec 设置放到 `Redisson.create(config)` 之前，或者在新设计中删除这个 codec 设置，保持测试与生产一致。新增测试 `cacheStoreReadsPlainJsonWithoutClassMetadata`：用 `redissonClient.getBucket(key.value(), StringCodec.INSTANCE).set(plainJson, ttl)` 写入一个没有 `@class` 的 `AuthSession` 形状 JSON，然后调用 `store.get(key, AuthSession.class)`，预期返回非空 `AuthSession`。新增测试 `cacheStoreWritesPlainJsonWithoutClassMetadata`：调用 `store.put(...)` 后用 `StringCodec` 直接读取 raw value，断言字符串不包含 `@class`。新增测试 `cacheStoreDegradesInvalidJsonToMiss`：写入坏 JSON，预期 `get(...)` 返回 empty 且测试不抛异常。不要新增 legacy `@class` 读取测试；如果出现这类测试，说明计划被重新引回迁移式兼容。

第五阶段补 auth 侧最小验证。可以在 `pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthServiceTest.java` 或新增 `RedisAuthSessionStoreTest` 中使用 fake `CacheStore` 保持 service 测试不依赖 Redis；真实 Redis 读写行为放在 infra-cache 集成测试即可。如果项目已有 auth Redis 集成测试，也可以补一条 `RedisAuthSessionStore` 端到端测试：`save(...)` 后用 `StringCodec` 读取 Redis raw value，断言 raw value 是普通 JSON 字符串且不包含 `@class`。

第六阶段同步设计文档。编辑 `docs/design-docs/infra/cache.md` 的“序列化与 codec”章节，把“Redisson 全局采用 JSON（Jackson）codec”改为“业务 KV 由 CacheStore 使用 ObjectMapper 序列化为普通 JSON 字符串，Redisson codec 只负责字符串传输和原语操作”。同步说明本次是缓存协议破坏式重构，旧 `@class` JSON 不兼容，必须通过 TTL 或按前缀清理；以后不得在业务缓存中存入依赖 Java 类名恢复的多态对象。如果实施者决定引入 `CacheKey<T>` 作为第二阶段类型化 key，需要在文档中另起小节说明独立重构计划；本计划第一轮不要求完成该 API 变更。

第七阶段执行 Redis 旧业务缓存清理。代码改完后，旧 `JsonJacksonCodec` 写出的业务缓存值不保证可读。本重构上线或本地联调前必须清理当前环境前缀下由 `CacheStore` 管理的业务 KV。生产环境不能用 `FLUSHDB`。建议先清理 auth refresh、auth blacklist、auth fail、auth user snapshot 等 auth 命名空间 key；如果确认当前环境没有其他重要 Redis 缓存，也可以清理项目环境前缀下全部可丢弃 cache key。执行前必须确认环境前缀，先用 `SCAN` 预览数量，再分批 `DEL`。这一步是重构验收的一部分，但不写进 Maven 测试。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。先确认工作树，避免覆盖其他任务已有改动：

    git status --short

当前工作树可能已经有大量未提交修改。实施本计划时只编辑本文列出的 infra-cache、infra-auth 测试和 cache 设计文档文件；不要顺手整理无关模块。

先搜索当前 Redisson 业务 value 读取路径：

    rg -n "getBucket\\(key\\.value\\(\\)\\)|JsonJacksonCodec|<Object>getBucket|convertValue\\(value, type\\)|StringCodec.INSTANCE" pixflow-infra-cache pixflow-infra-auth

预期会看到 `RedissonCacheStore` 的 `Object` bucket 读取、`CacheAutoConfiguration` 的 `JsonJacksonCodec`、以及 `RedisConfirmationTokenStore` 中已经使用的 `StringCodec` 字符串 JSON 路径。

创建 `JsonCacheSerializer`：

    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/JsonCacheSerializer.java

建议接口如下：

    final class JsonCacheSerializer {
        JsonCacheSerializer(ObjectMapper objectMapper) { ... }
        String serialize(Object value) { ... }
        <T> T deserialize(String json, Class<T> type) { ... }
    }

反序列化逻辑必须直接按目标类型读取，不得写 legacy 清理分支：

    return objectMapper.readValue(json, type);

编辑 `RedissonCacheStore`：

    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/RedissonCacheStore.java

构造器中保留 `ObjectMapper` 参数，但立即创建 `JsonCacheSerializer` 字段，或者把 serializer 作为独立 bean 注入。为了减少自动配置变更，第一轮可以在构造器内 `this.serializer = new JsonCacheSerializer(objectMapper)`。`get(...)`、`put(...)`、`putIfAbsent(...)` 都使用 `StringCodec.INSTANCE`。完成后再次搜索，`RedissonCacheStore` 中不应再出现 `<Object>getBucket` 和 `convertValue(value, type)`。

编辑 `CacheAutoConfiguration`：

    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/config/CacheAutoConfiguration.java

删除 `JsonJacksonCodec` import 和 `config.setCodec(new JsonJacksonCodec(objectMapper))`。如果设置全局 codec 为 `StringCodec.INSTANCE`，要导入 `org.redisson.client.codec.StringCodec` 并运行全部 infra-cache 测试确认锁、计数、信号量行为不变。若不设置全局 codec，则所有业务 value 调用点必须显式使用 `StringCodec.INSTANCE`。

编辑集成测试：

    pixflow-infra-cache/src/test/java/com/pixflow/infra/cache/RedisIntegrationTest.java

修正 `setUp()` 的 Redisson 配置顺序，并新增普通 JSON、raw value 不含 `@class`、坏 JSON 降级测试。测试中可以直接 import `com.pixflow.infra.auth.session.AuthSession`，如果这会让 `pixflow-infra-cache` 反向依赖 auth，则不要这么做；改为在 infra-cache 测试内定义一个 `private record SessionLike(...)`，字段保持与 AuthSession 同类即可。`pixflow-infra-cache` 主代码不能依赖 auth。不要新增任何带 `@class` 的读取兼容测试。

运行测试：

    mvn -pl pixflow-infra-cache -am test
    mvn -pl pixflow-infra-auth -am test
    mvn -pl pixflow-infra-cache,pixflow-infra-auth -am test

如果第一条失败在 Testcontainers 找不到 Docker，按当前仓库历史记录这可能是本地 Docker 环境问题。此时仍要运行不依赖 Docker 的单元测试，记录具体失败信息，并在本文 `Outcomes & Retrospective` 写明未完成真实 Redis 集成验证的原因。

最后同步文档：

    docs/design-docs/infra/cache.md

更新后运行一次搜索确认没有文档继续声称“Redisson 全局采用 JSON codec”：

    rg -n "JsonJacksonCodec|全局采用.*JSON|@class|StringCodec|业务 KV" docs/design-docs/infra/cache.md pixflow-infra-cache/src/main/java pixflow-infra-cache/src/test/java

## Validation and Acceptance

核心验收是证明当前日志里的错误路径不再存在。新增测试应能模拟 Redis 中已有一条没有 `@class` 的普通 JSON：

    {"refreshJwtId":"r1","userId":1,"username":"demo","tokenHash":"hash","createdAt":"2026-07-07T06:00:00Z","expiresAt":"2026-08-06T06:00:00Z"}

测试通过标准是 `CacheStore.get(key, SessionLike.class)` 返回对应 record，而不是抛 `InvalidTypeIdException` 或降级为空。这个测试在旧实现中会因为 `redissonClient.<Object>getBucket(...).get()` 触发 Redisson/Jackson 多态解码而失败；在新实现中应该通过。

破坏式重构验收是旧 `@class` 协议不再出现在测试期望中。测试套件不应断言旧 Redisson JSON 可以读出对象。若某条旧数据无法读取，`CacheStore.get(...)` 返回 `Optional.empty()` 并记录 warn 即可；真正处理方式是清理旧 Redis key 后由新代码重建缓存。

raw value 验收是 `CacheStore.put(key, new SessionLike(...), ttl)` 后，用 `redissonClient.getBucket(key.value(), StringCodec.INSTANCE).get()` 读取 Redis 原始值，看到的是普通 JSON 字符串，且不包含 `@class`。这证明业务缓存协议已经脱离 Redisson Java 类型信息。

自动化验收至少运行：

    mvn -pl pixflow-infra-cache -am test
    mvn -pl pixflow-infra-auth -am test

如果修改了全局 `RedissonClient` codec，还必须运行依赖 cache 原语的模块测试，至少包括：

    mvn -pl pixflow-state -am test
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-app -am test

验收通过后，启动应用并完成一次登录和 refresh 人工检查。浏览器登录后，等待或直接调用 `POST /api/auth/refresh`。预期后端不再出现 `InvalidTypeIdException: missing type id property '@class'`，也不再出现 `cache get degraded to miss, namespace=auth` 的同类栈。

## Idempotence and Recovery

代码修改是幂等的。`CacheStore.put(...)` 多次写同一 key 仍会覆盖为同样的普通 JSON 字符串；`putIfAbsent(...)` 仍保持 Redis 原子语义；`get(...)` 对不存在 key 返回 empty。

如果实施中发现全局 Redisson codec 改动影响锁、计数或信号量，先不要恢复业务 value 的 `JsonJacksonCodec`。应把全局 codec 留为 Redisson 默认，并在所有业务 value 读写点显式传 `StringCodec.INSTANCE`。锁和信号量不应依赖业务 JSON codec。

如果有人重新加入 legacy `@class` JSON 兼容逻辑，应视为偏离本计划。正确恢复方式是删除兼容分支、清理旧 Redis key、重新运行测试。不要修改全局 `ObjectMapper` 让所有业务反序列化都忽略未知字段，除非另起设计计划，因为这会改变 API 层严格性。

旧 Redis 数据必须按前缀清理。开发环境可以删除匹配当前环境前缀的 auth refresh key 后重新登录。生产环境清理必须按前缀 `SCAN` 后分批 `DEL`，不得使用 `FLUSHDB`，因为 Redis 还存上传会话、确认令牌、黑名单、失败计数等其他数据。

如果需要回滚代码，回滚后旧 Redisson JSON codec 可能再次要求 `@class`。由于本计划明确不做双协议迁移，回滚时应同时清理受影响缓存 key，让旧代码重新写入旧格式。缓存不是事实源，清理造成的影响是用户重新登录、缓存重建或短期降级，而不是丢失 MySQL 业务事实。

## Artifacts and Notes

当前错误日志的关键证据是：

    InvalidTypeIdException: Could not resolve subtype of [simple type, class java.lang.Object]: missing type id property '@class'
    at org.redisson.codec.JsonJacksonCodec$2.decode(JsonJacksonCodec.java:99)
    at com.pixflow.infra.cache.store.RedissonCacheStore.get(RedissonCacheStore.java:30)
    at com.pixflow.infra.auth.session.RedisAuthSessionStore.find(RedisAuthSessionStore.java:24)
    at com.pixflow.infra.auth.service.AuthService.refresh(AuthService.java:102)

当前源码证据是：

    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/RedissonCacheStore.java
    Object value = redissonClient.<Object>getBucket(key.value()).get();
    T converted = type.isInstance(value) ? type.cast(value) : objectMapper.convertValue(value, type);

目标行为示意是：

    String json = redissonClient.<String>getBucket(key.value(), StringCodec.INSTANCE).get();
    if (json == null) {
        return Optional.empty();
    }
    T value = serializer.deserialize(json, type);
    return Optional.of(value);

当前已有可参考实现是 `RedisConfirmationTokenStore`。它位于 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/confirmation/RedisConfirmationTokenStore.java`，已经用 `ObjectMapper` 把 `TokenClaims` 写成 JSON 字符串，并用 `StringCodec.INSTANCE` 从 Redis 原子消费。新的 `RedissonCacheStore` 应把同样的“自管 JSON 字符串”思想推广到通用业务 KV。

## Interfaces and Dependencies

本计划不新增外部依赖。继续使用 Redisson 3.30.0、Jackson `ObjectMapper`、Redisson `StringCodec`、JUnit 和 AssertJ。

`CacheStore` 第一阶段保持现有接口：

    public interface CacheStore {
        <T> Optional<T> get(CacheKey key, Class<T> type);
        <T> void put(CacheKey key, T value, Duration ttl);
        <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl);
        boolean exists(CacheKey key);
        void delete(CacheKey key);
    }

新增内部序列化器建议位于 `com.pixflow.infra.cache.store` 包：

    final class JsonCacheSerializer {
        JsonCacheSerializer(ObjectMapper objectMapper);
        String serialize(Object value);
        <T> T deserialize(String json, Class<T> type);
    }

如果后续要做第二阶段类型化 key，可以另起计划把 `CacheKey` 演进为：

    public final class CacheKey<T> {
        String value();
        String namespace();
        Duration suggestedTtl();
        Class<T> valueType();
    }

但这不是当前计划的验收条件。当前计划必须先解决 Redisson 按 `Object` 解码业务 JSON 导致 `@class` 缺失的问题。

## Revision Notes

2026-07-07 / Codex: 新建本文档。原因是用户询问是否存在“重构性的彻底解决方案”，并要求如果属于大修复就按 `PLANS.md` 撰写中文计划。本计划把修复范围定义为 infra/cache 的业务 KV 序列化边界重构：业务 value 由 `CacheStore` 自管 JSON 字符串，Redisson codec 不再承担 Java 对象多态反序列化；同时保留现有 `CacheStore` 接口，降低第一轮改动风险。

2026-07-07 / Codex: 修订为破坏式重构计划。原因是用户明确要求“不要兼容旧的，作为一个重构计划，不需要考虑迁移式安全”。本次修订删除 legacy `@class` 读取兼容、legacy 测试和“兼容窗口”表述，把旧 Redis 业务缓存清理改为必要执行步骤。

2026-07-07 / Codex: 实施计划并更新进度与结果。原因是业务 KV 已切换为 PixFlow 自管 JSON 字符串，测试已证明普通 JSON 读写和坏 JSON 降级行为；本文同步记录修改文件、验证命令和旧 Redis key 清理仍需在目标环境执行。
