# infra/cache —— Redis/Redisson 分布式缓存与协调（Wave 1 基础设施）

> 本文是 PixFlow 完整重写阶段 `infra/cache` 模块的设计文档，对应 `design.md` 第四章「技术栈选型」、第九章「DAG 确定性执行引擎」并发与断点部分、`13.3 Redis 键约定`，以及 `module-dependency-dag-plan.md` 的 **Wave 1 基础设施**。
> 范围：基于 Redis + Redisson 的分布式协调原语（锁 / 计数 / 信号量）与带 TTL 的 KV 缓存抽象。本文不涉及 MVP 既有实现，从新架构需求重新推导。
> 依赖契约沿用 `common` 模块（`docs/design-docs/module/common.md`）的错误模型与脱敏约定；确认令牌相关的 SPI / DTO 统一来自独立 `contracts` 模块。

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

`infra/cache` 处于依赖 DAG 的 **Wave 1**，依赖 `common + contracts`，被 `harness/state`、`module/dag`、`module/task`、`infra/thirdparty` 使用。它的职责是把 Redis/Redisson 的分布式能力收敛成**少量稳定、无业务知识的原语**，让上层只关心"用哪种协调能力"，不关心 Redisson API 细节、键拼装、续期与序列化。

模块专属设计原则：

1. **只提供通用原语，不承载业务知识**。模块内**不得出现** `task`、`branch`、`group`、`sku` 等业务词。`lock:task:{taskId}`、`branch:cache:*`、`group:cache:*` 这类键名与"成功即删 / 整组延迟删"这类生命周期，全部由 `module/task`、`module/dag` 自己拥有；`infra/cache` 只提供安全拼 key 的工具与执行机制。
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
| 业务语义 | `lock:task:{taskId}`、`progress:task:{taskId}`、`branch:cache:{taskId}:{imageId}:{branchId}`、`group:cache:...`、"整组聚合成功后才统一删" | `module/task`、`module/dag` |

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
├── counter/
│   ├── AtomicCounter.java          # incr/decr/get/reset + TTL
│   └── RedissonAtomicCounter.java
├── lock/
│   ├── LockTemplate.java           # runWithLock 模板（看门狗 + try-finally）
│   └── RedissonLockTemplate.java
├── semaphore/
│   ├── DistributedSemaphore.java   # acquire 返回 AutoCloseable Permit
│   └── RedissonDistributedSemaphore.java
├── confirmation/
│   └── RedisConfirmationTokenStore.java # contracts.ConfirmationTokenStore 的 Redis 实现
└── error/
    └── CacheException.java         # infra 内部具体异常（边界处归一化为 DEPENDENCY）
```

依赖方向：`infra/cache → common + contracts`。其中 `common` 提供错误模型与脱敏，`contracts` 只提供确认令牌 SPI / DTO；本模块不依赖 permission / harness / module / agent。

---

## 四、核心抽象

四组原语 + 一个 key 工厂，全部接口化，Redisson 实现可替换（便于测试以 fake/in-memory 替身注入）。

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

集群级全局并发上限，给第三方 API（抠图 / 生图 / VLLM）限并发用（`sem:thirdparty:{api}`）。

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

---

## 五、分布式锁语义（看门狗）

按已确认决策采用 **Redisson 看门狗（watchdog）自动续期**：

- 调用 Redisson `lock()` 时**不传 leaseTime**（leaseTime = -1），触发看门狗：默认锁超时 30s（`lockWatchdogTimeout` 可配），持有期间每 1/3 超时（约 10s）自动续期一次，直到显式 `unlock`。
- **强制模板内 try-finally 释放**：`LockTemplate.runWithLock` 在 finally 中 `unlock`（并校验 `isHeldByCurrentThread`，避免误解他人锁）。上层拿不到裸 `RLock`，杜绝忘记解锁。
- **进程假死兜底**：看门狗会随进程一起停摆，超过 `lockWatchdogTimeout` 未续期后锁自动释放，其他实例可接管。配合恢复机制（`@Scheduled` 重扫 `status=执行中` 任务）重新入队。
- **正确性不押在锁上**：锁仅防"同一任务/工作单元被并发或重复消费"。即使锁异常释放导致重复执行，因工作单元以 MySQL `process_result` 为幂等事实源（已成功的单元被跳过），不会产生重复副作用。本期**不引入 fencing token**（单体 + DB 事实源已足够，避免过度设计）。

锁键示例（由 `task` 模块构造，本模块只执行）：`lock:task:{taskId}`、`lock:unit:{taskId}:{imageId}:{branchId}`。

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

`design.md` 写的"Redisson 信号量 + Resilience4j 限流"是两层互补能力，避免重复造：

| 关注点 | 归属 | 能力 |
|---|---|---|
| **集群级全局并发上限** | `infra/cache`（本模块） | `DistributedSemaphore`，跨实例统计在途请求数，封顶第三方 API 总并发 |
| **单实例治理** | `infra/thirdparty` | Resilience4j 的重试 / 熔断 / 舱壁(bulkhead) / 限速(rate limiter)，作用于本进程内的调用 |

调用顺序上，`infra/thirdparty` 在发起第三方调用时：先经 `DistributedSemaphore.acquire` 拿全局许可（try-with-resources 自动释放），再套 Resilience4j 装饰器执行实际 HTTP 调用。本模块不依赖、不感知 Resilience4j。

---

## 十一、异常归一化与可观测

### 11.1 异常

- 模块内部抛自有 `CacheException`（携带操作类型：LOCK_ACQUIRE_TIMEOUT / SEMAPHORE_TIMEOUT / REDIS_UNAVAILABLE 等语义）。
- 按 `common.md §10`，infra 异常**跨出 infra 边界**时由 `ErrorNormalizer` 翻译为 `ErrorCategory.DEPENDENCY`（默认 `RETRY` / HTTP 503）；锁/信号量超时这类不可立即恢复的，可在归一化映射中视情况标 `recovery`。
- 落盘/对外文案经 `common` 的 `Sanitizer` 处理（key 中可能含 id，非敏感；但统一走脱敏管线保持一致）。

### 11.2 可观测（Micrometer）

最小指标集：

- `pixflow.cache.op{op=get|put|delete, result=hit|miss|error}`：缓存命中率与错误率。
- `pixflow.cache.lock{key_ns, result=acquired|timeout|error}` + 获取耗时计时器：锁竞争可见性。
- `pixflow.cache.semaphore{api, result=acquired|timeout}` + 在途许可数 gauge：第三方并发水位。
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

---

## 十三、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `contracts` | 只提供确认令牌相关的纯类型与 SPI，不承载存储实现 |
| `permission` | 通过 `contracts.ConfirmationTokenStore` 注入 Redis 实现；permission 不直接依赖本模块 |
| `harness/state` | 用 `CacheStore` 读写任务运行态引用、`AtomicCounter` 暴露进度；故障读降级、计数上抛 |
| `module/task` | 持有 task 命名空间，自定义 `lock:task` / `progress:task` / `cancel:task` 的 key 构造；用 `LockTemplate` 防重复消费、`AtomicCounter` 计进度、`CacheStore` 置取消标志 |
| `module/dag` | 持有 branch/group 命名空间，自定义 `branch:cache` / `group:cache` 的 key 与"成功即删 / 整组延迟删"生命周期；缓存值只存引用 |
| `infra/thirdparty` | 用 `DistributedSemaphore` 封第三方全局并发，再叠 Resilience4j |
| `common` | 本模块异常跨边界由 `ErrorNormalizer` 归一化为 `DEPENDENCY`；文案经 `Sanitizer` |

**反向约束**：本模块对以上任何模块零依赖、零业务词。

---

## 十四、测试策略

- **原语契约测试**：以 Testcontainers 起真实 Redis，验证 `CacheStore`（TTL 生效、putIfAbsent 语义、缺失 TTL 编译期不可达）、`AtomicCounter`（并发自增正确）、`LockTemplate`（互斥、超时抛错、finally 释放）、`DistributedSemaphore`（许可上限、持有者崩溃后许可可回收）。
- **看门狗续期**：长于初始锁超时的临界区，验证锁未被提前释放（间接观测：另一线程在持有期内拿不到锁）。
- **降级分级**：用故障注入（关停容器/断连）验证"读降级为 miss、锁/计数上抛"两类行为。
- **归一化**：断言 cache 异常跨边界后变成 `DEPENDENCY` 的 `PixFlowException`。
- **键命名空间**：断言环境前缀、段拼接、默认 TTL 兜底。
- **确认令牌存储**：验证 `RedisConfirmationTokenStore` 的 TTL 生效、原子 consume、并发下只有一次消费成功。
- 单元层用 in-memory 替身（无需 Redis）跑模板逻辑与降级分支，集成层用 Testcontainers 跑真实语义。

---

## 十五、暂不考虑

- 多级缓存（本地 Caffeine + Redis 两级）：本期只做分布式单级，后续如需读热点再叠本地层。
- fencing token 强一致锁：本期以 MySQL 事实源保证幂等，不引入。
- Redis 多级降级到本地内存继续服务：本期 Redis 不可用按 `DEPENDENCY` 上抛 + 任务恢复机制兜底，不做无 Redis 降级运行。
- 缓存预热、批量管道(pipeline)优化：待出现明确性能瓶颈再做。
- Sentinel / Cluster 的真实联调（仅预留配置位，本期单实例验证）。
