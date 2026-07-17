# infra/cache —— Redis/Redisson 分布式缓存与协调（Wave 1 基础设施）

> 本文是 PixFlow 完整重写阶段 `infra/cache` 模块的设计文档，对应 `design.md` 第四章「技术栈选型」、第九章「DAG 确定性执行引擎」并发与断点部分、`13.3 Redis 键约定`，以及 `module-dependency-dag-plan.md` 的 **Wave 1 基础设施**。
> 范围：基于 Redis + Redisson 的分布式协调原语（锁 / 计数 / 信号量 / 加权令牌桶）与带 TTL 的 KV 缓存抽象。本文不涉及 MVP 既有实现，从新架构需求重新推导。
> 依赖契约沿用 `common` 模块的错误模型与脱敏约定。确认令牌机制已删除，infra/cache 不提供对应业务存储适配器。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、为什么只做「通用原语」而非「业务缓存」](#二为什么只做通用原语而非业务缓存)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、核心抽象](#四核心抽象)
- [五、分布式锁语义（看门狗）](#五分布式锁语义看门狗)
- [六、序列化与 codec](#六序列化与-codec)
- [七、键命名空间与 TTL 治理](#七键命名空间与-ttl-治理)
- [八、降级分级策略](#八降级分级策略)
- [九、中间产物边界：Redis 存引用，字节落 MinIO](#九中间产物边界redis-存引用字节落-minio)
- [十、与 Resilience4j 的分工](#十与-resilience4j-的分工)
- [十一、异常归一化与可观测](#十一异常归一化与可观测)
- [十二、部署形态与配置](#十二部署形态与配置)
- [十三、对其他模块的契约](#十三对其他模块的契约)
- [十四、测试策略](#十四测试策略)
- [十五、暂不考虑](#十五暂不考虑)

---

## 一、文档定位与设计原则

`infra/cache` 处于依赖 DAG 的 **Wave 1**，依赖 `common`，被 `harness/state`、`module/dag`、`module/task`、`module/file`、`infra/thirdparty` 使用。它的职责是把 Redis/Redisson 的分布式能力收敛成**少量稳定、无业务知识的原语**，让上层只关心“用哪种协调能力”，不关心 Redisson API 细节、键拼装、续期与序列化。

模块专属设计原则：

1. **只提供通用原语，不承载业务知识**。模块实现内**不得出现** `task`、`branch`、`group`、`sku`、具体 provider 等业务词。`lock:task:{taskId}:execution`、`runref:group:*`、`bucket:thirdparty:*` 这类键名、令牌成本与生命周期，全部由调用模块拥有；`infra/cache` 只提供安全拼 key 的工具与执行机制。
2. **机制与语义分离**。锁、计数、信号量、KV 是机制；什么时候加锁、缓存什么、何时失效是语义。语义留给调用方。
3. **安全释放优先**。锁与信号量一律走"在临界区内执行回调"的模板方法，强制 try-finally 释放，禁止把裸 `RLock` 暴露给上层手动 lock/unlock。
4. **正确性靠事实源，锁只防重复**。分布式锁用于防止同一工作单元被并发/重复消费，**不作为业务正确性的唯一保证**；真正的幂等与断点以 MySQL `process_result` 为事实源（见 `design.md` 9.4）。
5. **分级降级**。锁 / 信号量获取失败必须上抛（影响正确性与并发安全）；纯缓存读失败可静默降级为 miss（退化为重算，不影响正确性）。
6. **Redis 不存大字节**。缓存值只放轻量引用 / 元数据，原始图片等大字节一律落 MinIO（见 [§九](#九中间产物边界redis-存引用字节落-minio)）。
7. **异常跨边界才归一化**。模块内部用自有 `CacheException`；跨出 infra 边界时由 `common` 的 `ErrorNormalizer` 翻译为 `DEPENDENCY`（见 `common.md` §10）。

---

## 二、为什么只做「通用原语」而非「业务缓存」

`design.md 13.3` 的 Redis 键约定里混着两层东西：

| 层次 | 例子 | 归属 |
|---|---|---|
| 纯机制 | 分布式锁、原子计数、信号量、带 TTL 的 KV | **本模块** |
| 业务语义 | `lock:task:{taskId}:execution`、`progress:task:{taskId}`、`runref:group:{taskId}:{runEpoch}:...`、"当前 epoch compose 后删除" | `module/task`、`module/dag` |

如果把业务键名和生命周期塞进 `infra/cache`，那么每次 task/dag 的业务调整都会反向震动地基模块，违背 Wave 1 的稳定性目标，也违背 `common.md` 已确立的"infra 不承载业务知识"原则。

因此本模块对外只暴露：**怎么安全地加锁 / 计数 / 限并发 / 存取带 TTL 的值**，以及**怎么按命名空间安全地拼一个带环境前缀、带 TTL 兜底的 key**。`task` 模块自己定义 `taskProgressKey(taskId)` 返回一个 `CacheKey`，再交给本模块的 `AtomicCounter` 执行。

---

## 三、模块结构与依赖位置

源码包：`com.pixflow.infra.cache`

```
infra/cache/
├── config/
│   ├── RedissonProperties.java     # 绑定 pixflow.cache.* 配置
│   └── RedissonConfig.java         # 装配 RedissonClient + codec + 健康检查
├── key/
│   ├── CacheKey.java               # 不可变 key 载体（含完整字符串 + 命名空间元信息）
│   └── CacheNamespace.java         # 按环境前缀 + 段拼 key 的工厂
├── store/
│   ├── CacheStore.java             # 泛型 KV（get/put/putIfAbsent/delete/exists）
│   └── RedissonCacheStore.java
├── state/
│   ├── ExpiringStateStore.java      # fail-closed 的权威临时 KV 状态
│   ├── ExpiringHashStore.java       # fail-closed 的 field-level Hash 状态
│   ├── RedissonExpiringStateStore.java
│   └── RedissonExpiringHashStore.java
├── counter/
│   ├── AtomicCounter.java          # incr/decr/get/reset + TTL
│   └── RedissonAtomicCounter.java
├── lock/
│   ├── LockTemplate.java           # runWithLock 模板（看门狗 + try-finally）
│   └── RedissonLockTemplate.java
├── semaphore/
│   ├── DistributedSemaphore.java   # acquire 返回 AutoCloseable Permit
│   └── RedissonDistributedSemaphore.java
├── tokenbucket/
│   ├── DistributedTokenBucket.java # 原子尝试消费，返回 remaining/retryAfter
│   ├── TokenBucketPolicy.java       # capacity/refillTokens/refillPeriod
│   ├── TokenBucketDecision.java     # allowed/remaining/retryAfter
│   └── RedisLuaTokenBucket.java     # Redisson RScript + Redis TIME
└── error/
    └── CacheException.java         # infra 内部具体异常（边界处归一化为 DEPENDENCY）
```

依赖方向：`infra/cache → common`。本模块不依赖 permission / harness / module / agent，也不依赖已删除的 confirmation contracts。

---

## 四、核心抽象

五组原语 + 一个 key 工厂，全部接口化，Redis 实现可替换（便于测试以 fake/in-memory adapter 注入）。

### 4.1 `CacheKey` / `CacheNamespace` —— 键工厂

key 不是裸字符串，而是经命名空间工厂产出的不可变对象，保证全局加环境前缀、段间分隔统一、并携带"建议 TTL"以支持兜底治理。

```java
public final class CacheKey {
    String value();        // 最终 Redis key，如 "pixflow:prod:task:42:progress"
    Duration suggestedTtl();// 命名空间给定的默认 TTL（可被调用覆盖）
}

public interface CacheNamespace {
    // segments 由调用方语义化拼接，如 key("task", taskId, "progress")
    CacheKey key(String... segments);
    CacheNamespace withDefaultTtl(Duration ttl);
}
```

- 环境前缀（`pixflow:{env}:`）由配置注入，**避免多环境/多实例共用一个 Redis 时键冲突**。
- 业务模块持有自己的 `CacheNamespace` 实例，在模块内集中定义 key 构造方法，本模块不知道段的业务含义。

### 4.2 `CacheStore` —— 带 TTL 的泛型 KV

```java
public interface CacheStore {
    <T> Optional<T> get(CacheKey key, Class<T> type);
    <T> void put(CacheKey key, T value, Duration ttl);
    <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl); // 取消标志位等"占位"语义
    boolean exists(CacheKey key);
    void delete(CacheKey key);
}
```

- 取消标志位（`cancel:task:{taskId}`）就是 `CacheStore` 的一个布尔特例，不单独造类型。
- **强约束**：`put` 必须带 TTL（无 TTL 重载不提供），防止孤儿 key 永驻导致内存泄漏。需要长期存在的状态属于 MySQL，不属于 cache。
- "删除某支路全部缓存引用"这类批量需求，**由上层把同一支路的多个引用聚到一个 Hash 值**（一次 `delete` 即清空），而非在本模块用 `KEYS`/通配扫描（生产环境禁用 `KEYS`）。本模块只提供单 key 删除，批量聚合策略写在 `dag`/`task` 模块文档。

### 4.2.1 `ExpiringStateStore` / `ExpiringHashStore` —— 权威临时状态

上传会话等状态带 TTL，但在生命周期内是正确性事实，不能套用普通 `CacheStore` 的“读失败视为 miss、写失败吞掉”降级语义。infra/cache 为此提供 fail-closed 的权威临时状态 interface；业务模块不直接依赖 Redisson：

```java
public interface ExpiringStateStore {
    <T> Optional<T> get(CacheKey key, Class<T> type);
    <T> void put(CacheKey key, T value, Duration ttl);
    <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl);
    void expire(CacheKey key, Duration ttl);
    void delete(CacheKey key);
}

public interface ExpiringHashStore {
    <T> Optional<T> get(CacheKey key, String field, Class<T> type);
    <T> void put(CacheKey key, String field, T value, Duration ttl);
    <T> Map<String, T> entries(CacheKey key, Class<T> type);
    Set<String> fields(CacheKey key);
    void deleteField(CacheKey key, String field);
    void expire(CacheKey key, Duration ttl);
    void delete(CacheKey key);
}
```

Interface 语义：

- value 仍使用项目 `ObjectMapper` 序列化为普通 JSON，不暴露 Redisson codec。
- 所有 Redis 读写失败都上抛，调用方 fail-closed；不得把连接失败伪装成 miss，也不得吞掉状态写入失败。
- `put` 成功后必须保证整个 Hash key 至少拥有调用方给出的 TTL；`expire` 用于多个相关 key 的统一续期。
- `fields/entries` 使用单次 Redis Hash 操作，不允许退化为按预期 field 范围循环 `EXISTS`。
- 本 interface 不理解业务冲突。`chunkHash` 是否一致、重复写应返回什么状态，属于 module/file 的 `UploadSessionStore` implementation。
- 生产 adapter 为 `RedissonExpiringStateStore` / `RedissonExpiringHashStore`；契约测试使用 in-memory adapter，并以 Testcontainers Redis 验证 KV、field、JSON、TTL、并发和故障上抛行为。

### 4.3 `AtomicCounter` —— 原子计数

```java
public interface AtomicCounter {
    long incrementBy(CacheKey key, long delta, Duration ttl); // 首次写入设置 TTL
    long get(CacheKey key);
    void reset(CacheKey key);
}
```

支撑 `progress:task:{taskId}` 进度计数。TTL 随任务生命周期略放宽，任务终态后由 task 模块主动 reset 或自然过期。

### 4.4 `LockTemplate` —— 分布式锁模板

```java
public interface LockTemplate {
    // 看门狗自动续期；waitTime 为获取超时，获取不到抛 CacheException(LOCK_ACQUIRE_TIMEOUT)
    <T> T runWithLock(CacheKey key, Duration waitTime, Supplier<T> action);
    void runWithLock(CacheKey key, Duration waitTime, Runnable action);
    // 获取不到不抛错，返回 false（用于"已有人在处理则跳过"的去重消费场景）
    boolean tryRunWithLock(CacheKey key, Duration waitTime, Runnable action);
}
```

详见 [§五](#五分布式锁语义看门狗)。

### 4.5 `DistributedSemaphore` —— 分布式信号量

集群级全局并发上限，给第三方 API（抠图 / 生图 / VLLM）限并发用；业务键由调用方构造，例如 `sem:thirdparty:{provider}:{api}`。

```java
public interface DistributedSemaphore {
    // 返回 AutoCloseable，try-with-resources 自动 release
    Permit acquire(CacheKey key, int permits, Duration waitTime);

    interface Permit extends AutoCloseable {
        @Override void close(); // 释放许可，幂等
    }
}
```

- 实现优先选 Redisson `RPermitExpirableSemaphore`：**许可带租约，持有者进程崩溃后许可自动过期回收**，避免永久泄漏导致并发额度被耗尽。
- 总许可数与第三方 API 的全局并发上限对齐，由配置注入。

### 4.6 `DistributedTokenBucket` —— Redis 加权令牌桶

令牌桶用于跨实例控制**出站供应商调用速率与相对成本**。它与信号量不同：信号量限制当前在途数并在完成后释放；令牌桶消费后不返还，只按时间补充。

对外接口保持单方法，调用方只需给出业务 key、通用策略和本次成本；Redis key 语义、provider 选择和成本权重仍归调用方：

```java
public interface DistributedTokenBucket {
    TokenBucketDecision tryConsume(CacheKey key, TokenBucketPolicy policy, long cost);
}

public record TokenBucketPolicy(
        long capacity,
        long refillTokens,
        Duration refillPeriod,
        Duration idleTtl) {}

public record TokenBucketDecision(
        boolean allowed,
        long remaining,
        Duration retryAfter) {}
```

接口语义：

- `tryConsume` **不等待、不睡眠**。允许时立即扣减并返回剩余额度；拒绝时不扣减，返回下一次足够消费 `cost` 的保守 `retryAfter`。等待与 retry 归调用模块，cache 不持有业务线程。
- `cost` 是正整数权重，可表达文本调用、视觉分析、生图等不同相对成本；它不是供应商实际 token 账单。`cost > capacity` 属配置错误，直接拒绝。
- `TokenBucketPolicy` 是调用方配置投影，不由 cache 按 provider 查配置。相同 key 在一个部署周期内必须使用相同 policy；发现不一致时记录配置错误并 fail-closed，不能静默重置现有额度。
- Redis adapter 使用**单段 Lua** 原子执行读取、按时间补充、判断、扣减、写回和 TTL 设置。时间取 Redis `TIME`，不使用应用节点时钟，避免多节点时钟漂移。
- 内部以定点整数保存余额和补充余数，避免浮点累计误差。TTL 至少覆盖两次“从空桶补满”的时间，并受 `idleTtl` 下限约束；长期无调用的桶可自动回收。
- V1 不使用 `RRateLimiter.trySetRate(...)` 作为最终实现。它无法独立表达 `capacity` 与 refill 速率，且把策略初始化暴露到每次 acquire；现有 `DistributedRateLimiter`/`RedissonDistributedRateLimiter` 属过渡实现，实施本设计时直接替换，不保留双接口。

---

## 五、分布式锁语义（看门狗）

按已确认决策采用 **Redisson 看门狗（watchdog）自动续期**：

- 调用 Redisson `lock()` 时**不传 leaseTime**（leaseTime = -1），触发看门狗：默认锁超时 30s（`lockWatchdogTimeout` 可配），持有期间每 1/3 超时（约 10s）自动续期一次，直到显式 `unlock`。
- **强制模板内 try-finally 释放**：`LockTemplate.runWithLock` 在 finally 中 `unlock`（并校验 `isHeldByCurrentThread`，避免误解他人锁）。上层拿不到裸 `RLock`，杜绝忘记解锁。
- **进程假死兜底**：看门狗会随进程一起停摆，超过 `lockWatchdogTimeout` 未续期后锁自动释放，其他实例可接管。配合恢复机制（`@Scheduled` 重扫 `status=执行中` 任务）重新入队。
- **正确性不押在锁上**：锁仅防"同一任务/工作单元被并发或重复消费"。task 在取得锁后由 MySQL `run_epoch` 产生 ownership generation，并以条件更新 fence 失去锁的旧 worker；该 epoch 不是 Redis lock token，cache 只提供 watchdog lock 与只读 guard 机制。

锁键示例（由 `task` 模块构造，本模块只执行）：`lock:task:{taskId}:execution`。Work Unit 不另建锁，统一由 task owner 的锁与 MySQL epoch 管理提交。

---

## 六、序列化与 codec

- 业务 KV 缓存值由 `CacheStore` 使用 PixFlow 自己的 `ObjectMapper` 序列化为**普通 JSON 字符串**，再通过 Redisson `StringCodec` 写入 Redis。Redisson 全局 codec 不承载业务对象协议，不再依赖 `JsonJacksonCodec` 或 Java 多态字段恢复业务对象。
- `CacheStore.get(key, type)` 先以 `StringCodec` 取回 JSON 字符串，再直接按调用方传入的 `Class<T>` 执行 `objectMapper.readValue(json, type)`。Redis value 不需要、也不应该包含 Redisson 多态字段 `@class`。
- 缓存值约定为**小而结构化的引用/元数据对象**（见 [§九](#九中间产物边界redis-存引用字节落-minio)），JSON 体积可控；不缓存大字节，故不需要二进制 codec 的极致紧凑。
- 序列化失败（反序列化时类型不匹配、坏 JSON、旧协议污染等）按"缓存读降级"处理：只记录命名空间和异常类型，视为 miss，不输出完整 value，不影响主流程（见 [§八](#八降级分级策略)）。
- 这是一次缓存协议破坏式重构。旧 Redisson `JsonJacksonCodec` 写出的业务缓存值不做代码兼容，必须通过 TTL 自然过期或按当前环境前缀清理。生产环境清理必须先用 `SCAN` 预览并分批 `DEL`，不得使用 `FLUSHDB`。

---

## 七、键命名空间与 TTL 治理

生产环境下"无 TTL 的孤儿 key"是最常见的 Redis 内存泄漏来源，本模块从机制上规避：

1. **统一环境前缀**：所有 key 经 `CacheNamespace` 强制加 `pixflow:{env}:` 前缀，多环境共享实例不串。
2. **TTL 强制**：`CacheStore.put` / `AtomicCounter.incrementBy` 必签 TTL；`CacheNamespace` 可给命名空间设默认 TTL 作兜底。需长期存在的数据不进 cache，进 MySQL。
3. **生命周期归上层**："成功即删支路缓存""整组聚合后统一删""任务终态 reset 进度"等显式删除由 `task`/`dag` 模块负责调用 `delete`/`reset`；本模块只保证操作原子与正确。
4. **禁用全量扫描**：模块不提供基于 `KEYS`/通配的批量删除；批量清理靠上层把相关引用聚到单个 Hash 后整体删除。

---

## 八、降级分级策略

按 Redis 抖动 / 不可用时的影响面分级处理（已确认采用分级而非一刀切）：

| 能力 | Redis 故障时行为 | 理由 |
|---|---|---|
| `LockTemplate` 获取锁 | **上抛** `CacheException`（边界归一化为 `DEPENDENCY`/RETRY） | 拿不到锁就放行会破坏并发安全与去重 |
| `DistributedSemaphore` 获取许可 | **上抛** | 失去全局并发上限会冲垮第三方 API |
| `DistributedTokenBucket.tryConsume` Redis 失败 | **上抛**（fail-closed） | 成本敏感调用失去全局额度后放行会放大费用；不得自动退化为本机桶 |
| `DistributedTokenBucket` 正常额度不足 | **返回拒绝决策** | 不是 Redis 故障；调用方用 `retryAfter` 决定等待、重试或终止 |
| `AtomicCounter` 进度自增 | **上抛**（计数错误会误导前端进度与终态判定） | 进度需准确 |
| `CacheStore.get`（纯缓存读） | **静默降级为 miss**（warn + 指标），调用方退化为重算 | 不影响正确性，仅损失一次优化 |
| `CacheStore.put`（写缓存引用） | **吞掉 + warn**，不阻断主流程 | 写失败只是下次不命中，可重算 |
| `CacheStore.delete`（清理） | **吞掉 + warn**，依赖 TTL 兜底回收 | 删除失败有 TTL 托底 |

降级行为在实现层封装，调用方无需在每个点写 try-catch；"读/写/删可降级，锁/信号量/计数上抛"是固定契约。

---

## 九、中间产物边界：Redis 存引用，字节落 MinIO

> 本节解决并已同步修订 `design.md`（9.4 / 9.5 / 13.3 / 5.4）中"中间产物存 Redis 还是 MinIO"的歧义。

- **原始字节（图片等）一律落 MinIO**，绝不进 Redis；Redis 缓存值只放**轻量引用**：节点完成标记 + 对应 MinIO key + 必要元数据（如尺寸、格式），JSON 小对象。
- **持久断点是 MySQL `process_result`**；Redis 引用缓存只是"同一次运行内避免重算"的优化，可丢失、可重建——故它适用 [§八](#八降级分级策略) 的读写降级。
- `CacheStore` 因此天然只面对小结构化对象，配合 JSON codec 体积可控；任何"想往 cache 塞大字节"的需求都应改为"落 MinIO + cache 存 key"。

这条边界是本模块对 `dag`/`task` 的硬约束：**缓存引用，不缓存内容**。

---

## 十、与 Resilience4j 的分工

Redis 信号量、Redis 令牌桶与 Resilience4j 各管一个维度：

| 关注点 | 归属 | 能力 |
|---|---|---|
| **集群级全局并发上限** | `infra/cache`（本模块） | `DistributedSemaphore`，跨实例统计在途请求数，封顶第三方 API 总并发 |
| **集群级出站速率/相对成本** | `infra/cache`（本模块） | `DistributedTokenBucket`，跨实例原子补充和加权消费 |
| **单实例故障治理** | `infra/thirdparty` / `infra/ai` | Resilience4j 或自有 runner 的重试 / 熔断 / 舱壁 / 超时；不再拥有第二套 RateLimiter |

调用顺序由消费模块拥有，但必须满足一个不变量：**每次真实 provider attempt 都重新经过信号量和令牌桶，重试退避期间不占信号量，也不会绕过额度**。本模块不依赖、不感知 Resilience4j 或 `ModelRetryRunner`。

---

## 十一、异常归一化与可观测

### 11.1 异常

- 模块内部抛自有 `CacheException`（携带操作类型：LOCK_ACQUIRE_TIMEOUT / SEMAPHORE_TIMEOUT / TOKEN_BUCKET_FAILED / REDIS_UNAVAILABLE 等语义）。正常桶额度不足不抛 cache 异常，只返回拒绝 decision。
- 按 `common.md §10`，infra 异常**跨出 infra 边界**时由 `ErrorNormalizer` 翻译为 `ErrorCategory.DEPENDENCY`（默认 `RETRY` / HTTP 503）；锁/信号量超时这类不可立即恢复的，可在归一化映射中视情况标 `recovery`。
- 落盘/对外文案经 `common` 的 `Sanitizer` 处理（key 中可能含 id，非敏感；但统一走脱敏管线保持一致）。

### 11.2 可观测（Micrometer）

最小指标集：

- `pixflow.cache.op{op=get|put|delete, result=hit|miss|error}`：缓存命中率与错误率。
- `pixflow.cache.lock{key_ns, result=acquired|timeout|error}` + 获取耗时计时器：锁竞争可见性。
- `pixflow.cache.semaphore{api, result=acquired|timeout}` + 在途许可数 gauge：第三方并发水位。
- `pixflow.cache.token_bucket{namespace, result=allowed|rejected|error}`：令牌桶决策计数；`namespace` 只允许低基数命名空间，不记录完整 key、userId 或 provider 凭证。
- `pixflow.cache.token_bucket.remaining{namespace}`：可选抽样 gauge；不得为每个用户 key 建 Micrometer tag。
- Redisson 连接健康并入 Spring Boot Actuator `health`（Redis 不可用时 `DOWN`）。

---

## 十二、部署形态与配置

本期按**单实例 Redis** 设计与联调（Docker Compose 拉起），但配置抽象预留 Sentinel / Cluster 切换位，不在代码里写死单机假设。

配置前缀 `pixflow.cache.*`（绑定到 `RedissonProperties`）：

| 配置项 | 含义 | 默认 |
|---|---|---|
| `pixflow.cache.mode` | single / sentinel / cluster | single |
| `pixflow.cache.address` | Redis 地址 | redis://127.0.0.1:6379 |
| `pixflow.cache.password` | 密码（脱敏，不入日志） | - |
| `pixflow.cache.env-prefix` | key 环境前缀段 | 由 `spring.profiles.active` 推导 |
| `pixflow.cache.pool.*` | 连接池 / 最小空闲 / 超时 | Redisson 合理默认 |
| `pixflow.cache.lock.watchdog-timeout` | 看门狗锁超时 | 30s |
| `pixflow.cache.default-ttl` | 命名空间默认 TTL 兜底 | 1h |
| `pixflow.cache.semaphore.{api}.permits` | 各第三方 API 全局并发许可 | 按 API 配置 |

`RedissonConfig` 依据 `mode` 装配单机/哨兵/集群的 `RedissonClient`，统一配置连接超时、重试与锁看门狗。业务 KV value 的 JSON 协议由 `CacheStore` 显式使用 `StringCodec` 和 `ObjectMapper` 管理；锁、计数、信号量等 Redisson 原语按各调用点显式选择安全 codec 或 Redisson 默认 codec。

令牌桶的 `capacity/refill/cost` **不放在 `pixflow.cache.*`**。这些是 provider/capability 策略，分别由 `pixflow.thirdparty.*` 与 `pixflow.ai.*` 配置并在调用时投影为 `TokenBucketPolicy`。cache 只持有 Redis 连接、Lua 实现和通用安全上限。

---

## 十三、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `harness/state` | 用 `CacheStore` 读写任务运行态引用、`AtomicCounter` 暴露进度；故障读降级、计数上抛 |
| `module/task` | 持有 task 命名空间，自定义 `lock:task` / `progress:task` / `cancel:task` 的 key 构造；用 `LockTemplate` 防重复消费、`AtomicCounter` 计进度、`CacheStore` 置取消标志 |
| `module/file` | `UploadSessionStore` implementation 使用 `ExpiringStateStore` 保存 fileHash 索引/session，使用 `ExpiringHashStore` 保存 `index -> ChunkMetadata`；module/file 的调用方只消费上传快照与幂等结果，不理解 Redis |
| `module/dag` | 持有 group runtime-reference 命名空间，自定义 `runref:group:*` key 与当前 epoch 生命周期；缓存值只存引用 |
| `infra/thirdparty` | 直接消费 `DistributedSemaphore` + `DistributedTokenBucket`；provider/api key 与 cost policy 归 thirdparty，重试/熔断/舱壁归 Resilience4j |
| `infra/ai` | 通过 ai 自有 quota SPI 间接消费令牌桶；组合根提供 cache adapter，避免 ai 反向依赖 cache |
| `common` | 本模块异常跨边界由 `ErrorNormalizer` 归一化为 `DEPENDENCY`；文案经 `Sanitizer` |

**反向约束**：本模块对以上任何模块零依赖、零业务词。

---

## 十四、测试策略

- **原语契约测试**：以 Testcontainers 起真实 Redis，验证 `CacheStore` 的可降级缓存语义；验证 `ExpiringStateStore/ExpiringHashStore` 的 KV/field CRUD、一次性 fields/entries、JSON、统一续期和 Redis 故障 fail-closed；再验证 `AtomicCounter`、`LockTemplate`、`DistributedSemaphore`。
- **令牌桶契约测试**：覆盖首次满桶、加权消费、拒绝不扣减、Redis 时间补充、容量封顶、`retryAfter`、并发原子性、TTL 回收、相同 key 策略冲突和 Redis 故障 fail-closed。单元测试使用 deterministic in-memory adapter，集成测试用 Testcontainers Redis 验证 Lua 语义。
- **看门狗续期**：长于初始锁超时的临界区，验证锁未被提前释放（间接观测：另一线程在持有期内拿不到锁）。
- **降级分级**：用故障注入（关停容器/断连）验证"读降级为 miss、锁/计数上抛"两类行为。
- **归一化**：断言 cache 异常跨边界后变成 `DEPENDENCY` 的 `PixFlowException`。
- **键命名空间**：断言环境前缀、段拼接、默认 TTL 兜底。
- 单元层用 in-memory 替身（无需 Redis）跑模板逻辑与降级分支，集成层用 Testcontainers 跑真实语义。

---

## 十五、暂不考虑

- 多级缓存（本地 Caffeine + Redis 两级）：本期只做分布式单级，后续如需读热点再叠本地层。
- Redis fencing token：不引入；执行 fencing 由 task 拥有的 MySQL `run_epoch` 条件更新承担。
- Redis 多级降级到本地内存继续服务：本期 Redis 不可用按 `DEPENDENCY` 上抛 + 任务恢复机制兜底，不做无 Redis 降级运行。
- 用户级 HTTP 入站防滥用、每日预算、真实 LLM TPM/账单核算：不属于本通用原语；本期令牌桶只为出站 provider attempt 提供请求权重额度。
- 缓存预热、批量管道(pipeline)优化：待出现明确性能瓶颈再做。
- Sentinel / Cluster 的真实联调（仅预留配置位，本期单实例验证）。

## 修订记录

2026-07-11 / Codex: 新增 fail-closed 的 `ExpiringStateStore/ExpiringHashStore` interface 与 Redis/in-memory adapter 契约，为 module/file 的 `UploadSessionStore` 提供权威临时 KV/Hash 状态；业务模块不再以 N 个 KV key 扫描分片进度，也不直接依赖 Redisson。

2026-07-11 / Codex: 将集群级出站限速从 resilience4j 单 JVM RateLimiter 收敛为 Redis Lua 加权令牌桶。新增 `DistributedTokenBucket` 深模块接口、原子补充/消费/`retryAfter` 语义、fail-closed 策略、低基数指标和 Testcontainers 契约；明确 provider policy 归调用模块、每个 retry attempt 必须重新过桶，并决定实施时删除过渡的 `DistributedRateLimiter`/`RedissonDistributedRateLimiter` 双路径。

2026-07-12 / Codex: 更新运行态引用边界：task 互斥使用调用方命名的 Redisson `RLock`，group 中间引用使用带 `runEpoch` 的 `runref:group:*`，只服务当前 fan-in，不作为 MySQL Work Unit Checkpoint；fencing 与 heartbeat 事实由 task/MySQL 持有。
