# 实现 infra/cache Redis/Redisson 分布式缓存与协调

本 ExecPlan 必须按照仓库根目录的 `PLANS.md` 维护。它是活文档，后续任何实现者推进、修正或完成本计划时，都必须同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`，并在文末记录修改原因。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一层生产级 Redis/Redisson 基础设施。后续 `module/task` 可以用它防止同一任务被重复消费、记录任务进度、保存取消标志；`module/dag` 可以用它缓存支路中间产物的轻量引用；`infra/thirdparty` 可以用它限制第三方 API 的集群级并发；`permission` 可以通过 `contracts.ConfirmationTokenStore` 使用 Redis 保存并原子消费确认令牌。

从使用者角度看，这一步完成后，用户确认执行 DAG 时，后台 worker 可以用 Redisson 锁避免重复接管；前端能看到准确的 Redis 进度计数；用户取消任务时，worker 能在工作单元之间读到取消标志；确认令牌只能被消费一次，刷新或重复提交不会重复执行。这个模块本身不认识任务、支路、SKU 或图片业务，它只提供通用协调原语和带 TTL 的 KV 缓存。

本计划只描述设计与实施方案，不编写 Java 代码。后续实现者可以从本文件直接开始落地，无需知道前面对话内容。

## Progress

- [x] (2026-06-27 17:20+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/design.md`、`docs/design-docs/module/cache.md`、`docs/design-docs/module/common.md`、`docs/design-docs/module/contracts.md`。
- [x] (2026-06-27 17:20+08:00) 确认用户提供的 `.kiro/specs/pixflow/design.md` 在当前工作区不存在，当前完整重写阶段总设计实际位于 `docs/design-docs/design.md`。
- [x] (2026-06-27 17:20+08:00) 确认当前仓库已经完成 Maven 多模块改造，已有 `pixflow-common`、`pixflow-contracts`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app` 子模块。
- [x] (2026-06-27 17:20+08:00) 与用户确认 cache 的三项实现决策：`CacheStore.put/delete` 失败按设计吞掉并 warn；`AtomicCounter.incrementBy` 首次设置 TTL 后不在每次递增时刷新；`DistributedSemaphore` 为 `RPermitExpirableSemaphore` 补上配置化 leaseTime。
- [x] (2026-06-27 17:20+08:00) 新增本中文 ExecPlan，明确实现架构、机制、接口形状、验证方式和设计文档快速定位关键词。
- [x] (2026-06-27 17:32+08:00) 新增 `pixflow-infra-cache` Maven 子模块，并接入父 POM 与 `pixflow-app`。
- [x] (2026-06-27 17:32+08:00) 实现 key、KV、counter、lock、semaphore、confirmation token store、metrics、auto-configuration。
- [x] (2026-06-27 17:33+08:00) 补齐基础单元测试、common 异常归一化测试，并新增 Testcontainers Redis 集成测试；当前 Docker 环境不可用时集成测试按 `disabledWithoutDocker` 条件跳过。
- [x] (2026-06-27 17:34+08:00) 运行 `mvn -pl pixflow-infra-cache -am test` 与根目录 `mvn test`，两者均 `BUILD SUCCESS`；Redis 集成测试因本机 Docker 不可用被跳过，未作为真实 Redis 通过记录。

## Surprises & Discoveries

- Observation: 用户给出的 `.kiro/specs/pixflow/design.md` 当前不存在，不能作为本计划依据。
  Evidence: 执行 `Get-Content -Raw .kiro\specs\pixflow\design.md` 报错 `Cannot find path`；执行 `rg --files -g design.md` 返回 `docs\design-docs\design.md`。

- Observation: 当前执行计划目录中已完成的 storage、mq、permission、contracts 计划已被移动到 `docs/design-docs/exec-plans/completed/`，active 目录只保留未完成计划。
  Evidence: `Get-ChildItem docs\design-docs\exec-plans` 显示 `completed` 目录、`ai-module-implementation-plan.md`、`module-dependency-dag-plan.md`。

- Observation: cache 模块应新增为独立 Maven 子模块，而不是写入旧根目录 `src/main/java`。
  Evidence: 根 `pom.xml` 当前是 `packaging=pom`，已有模块包括 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app`。

- Observation: `contracts` 已经提供 `com.pixflow.contracts.confirmation.ConfirmationTokenStore`，cache 的 Redis 实现必须面向这个 SPI，不能依赖 permission。
  Evidence: `pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation/ConfirmationTokenStore.java` 定义 `save` 与原子 `consume` 契约。

- Observation: 当前执行环境没有可用 Docker daemon，Testcontainers Redis 集成测试无法启动真实 Redis。
  Evidence: `mvn test` 输出 `Could not find a valid Docker environment`，`RedisIntegrationTest` 显示 `Tests run: 4, Failures: 0, Errors: 0, Skipped: 4`。

- Observation: cache 模块源码没有出现被设计文档禁止的业务词。
  Evidence: 执行 `rg -n "task|branch|group|sku" pixflow-infra-cache\src` 返回空结果。

## Decision Log

- Decision: 新增 `pixflow-infra-cache` 子模块，依赖 `pixflow-common` 与 `pixflow-contracts`，不依赖 `permission`、`task`、`dag`、`storage`、`mq` 或任何业务模块。
  Rationale: `infra/cache` 位于 Wave 1 基础设施层，是被 task、dag、state、thirdparty 等消费的底座。让它依赖上层模块会破坏模块依赖 DAG，也会把业务词带入基础设施。
  Date/Author: 2026-06-27 / Codex

- Decision: `CacheStore.get` 读失败降级为 miss；`CacheStore.put` 和 `CacheStore.delete` 失败吞掉并记录 warn 与指标。
  Rationale: 纯缓存读写只是优化，不是正确性来源。Redis 写缓存失败会导致下次不命中并重算，删除失败还有 TTL 兜底，不应阻断主流程。用户已确认“照做”。
  Date/Author: 2026-06-27 / Codex

- Decision: `AtomicCounter.incrementBy` 首次写入时设置 TTL，后续递增不刷新 TTL。
  Rationale: 进度计数需要准确，Redis 故障必须上抛；TTL 作为任务生命周期兜底，不应因为频繁进度更新无限续命。任务终态由 `module/task` 主动 reset，自然过期只是回收兜底。用户已确认“可以”。
  Date/Author: 2026-06-27 / Codex

- Decision: `DistributedSemaphore` 使用 Redisson `RPermitExpirableSemaphore`，并为每个 semaphore 补上配置化 `leaseTime`。
  Rationale: 文档要求持有者进程崩溃后许可能自动回收。`RPermitExpirableSemaphore` 需要租约时间才能表达这个语义；只传 waitTime 无法完整实现“崩溃自动回收”。用户已确认“补上”。
  Date/Author: 2026-06-27 / Codex

- Decision: `LockTemplate` 使用 Redisson watchdog 自动续期，不暴露裸 `RLock`。
  Rationale: 不传 leaseTime 可触发 Redisson watchdog，长临界区不会在正常持有期间过期；模板方法强制 finally 释放，避免上层忘记 unlock。
  Date/Author: 2026-06-27 / Codex

- Decision: `RedisConfirmationTokenStore.consume` 必须使用 Lua 或等价原子机制实现 GET+DEL。
  Rationale: `contracts.ConfirmationTokenStore.consume` 的契约是原子读取并删除，并发下只有一次消费成功。普通 get 后 delete 有重放窗口，不满足确认令牌安全边界。
  Date/Author: 2026-06-27 / Codex

- Decision: Testcontainers Redis 集成测试使用 `@Testcontainers(disabledWithoutDocker = true)`。
  Rationale: 真实 Redis 语义仍应以容器测试覆盖；但本地或 CI 某些环境可能没有 Docker。条件跳过能区分环境缺失与代码失败，并在 Maven 输出中保留 skipped 证据。
  Date/Author: 2026-06-27 / Codex

- Decision: `CacheAutoConfiguration` 在没有外部 `ObjectMapper` 时提供一个注册 `JavaTimeModule` 且 `NON_NULL` 的默认 `ObjectMapper`。
  Rationale: `pixflow-app` 当前只通过依赖引入模块，若运行时没有 Boot Web/Jackson 自动创建的 mapper，cache 的 JSON codec 与确认令牌序列化仍应可装配；如果应用已有 mapper，则保留应用级配置。
  Date/Author: 2026-06-27 / Codex

- Decision: cache 模块内和测试中都不出现 `task`、`branch`、`group`、`sku` 等业务词作为 API 或固定 key 语义。
  Rationale: 业务键名和生命周期属于 `module/task`、`module/dag`。cache 只提供机制，不能把基础设施和业务生命周期绑死。
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

本计划已完成第一版代码落地：`pixflow-infra-cache` 子模块已经接入 reactor 和 `pixflow-app`，提供 key 工厂、KV、计数器、watchdog 锁模板、带 leaseTime 的 Redisson 信号量、Redis 确认令牌存储、Micrometer 指标与自动装配。`common` 已能把 `CacheException` 归一化为 `DEPENDENCY_UNAVAILABLE`。已运行 `mvn -pl pixflow-infra-cache -am test` 与根目录 `mvn test`，两者均 `BUILD SUCCESS`。当前缺口是本机 Docker 不可用，`RedisIntegrationTest` 里的 4 个真实 Redis 用例被条件跳过；这些用例覆盖 KV TTL、`putIfAbsent`、计数器首次 TTL 和确认令牌单次消费，待 Docker 可用时应重新执行并记录结果。

## Context and Orientation

本仓库是 PixFlow 电商运营 Agent。当前完整重写阶段的总设计位于 `docs/design-docs/design.md`，模块实施顺序位于 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`，cache 模块设计位于 `docs/design-docs/module/cache.md`，错误模型设计位于 `docs/design-docs/module/common.md`，确认令牌共享契约位于 `docs/design-docs/module/contracts.md`。

当前工程是 Maven reactor 多模块工程。根目录 `pom.xml` 是父 POM，已有 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app`。本计划应新增 `pixflow-infra-cache`，并在根 POM `<modules>` 中加入它。`pixflow-app` 是 Spring Boot 装配层，后续需要依赖 `pixflow-infra-cache`，让应用启动时能装配 Redis/Redisson 相关 Bean。

这里定义本计划会用到的术语。Redis 是内存数据服务，本计划用它保存短期缓存、计数、锁状态、信号量许可和确认令牌。Redisson 是 Java Redis 客户端，它在 Redis 之上提供分布式锁、原子对象、信号量等高级结构。TTL 是 time to live，意思是 key 的存活时间，到期后 Redis 自动删除该 key。watchdog 是 Redisson 的锁自动续期机制：持有锁的进程仍存活时自动延长锁过期时间，进程崩溃后续期停止，锁最终过期释放。semaphore 是信号量，用来限制同一时间最多有多少个调用在执行；本项目用它限制第三方 API 的集群级并发。leaseTime 是租约时间，表示许可持有多久后自动过期回收。

`infra/cache` 的职责是通用机制，不是业务缓存中心。它应该知道如何安全拼 key、如何执行 KV 读写、如何执行原子计数、如何包住 Redisson 锁、如何申请和释放信号量、如何实现确认令牌存储。它不应该知道什么是任务、支路、素材包、SKU、图片、组图或生图业务。

## 关联设计文档快速定位关键词

在 `docs/design-docs/module/cache.md` 中搜索 `只提供通用原语，不承载业务知识`，可以定位本模块最重要的边界。搜索 `机制与语义分离`，可以定位为什么 cache 只提供锁、计数、信号量和 KV，不拥有业务生命周期。搜索 `安全释放优先`，可以定位为什么锁和信号量要走模板方法或 `AutoCloseable`。搜索 `正确性靠事实源，锁只防重复`，可以定位为什么 MySQL `process_result` 才是持久断点。

在 `docs/design-docs/module/cache.md` 中搜索 `CacheKey`、`CacheNamespace`，可以定位 key 工厂设计。搜索 `CacheStore`，可以定位带 TTL KV 抽象。搜索 `AtomicCounter`，可以定位原子计数接口。搜索 `LockTemplate`，可以定位 Redisson watchdog 锁模板。搜索 `DistributedSemaphore`，可以定位第三方 API 全局并发控制。搜索 `RedisConfirmationTokenStore`，可以定位确认令牌 Redis 实现位置。

在 `docs/design-docs/module/cache.md` 中搜索 `降级分级策略`，可以定位 Redis 故障时不同能力的处理方式。搜索 `CacheStore.get`，可以定位读失败降级为 miss。搜索 `CacheStore.put`，可以定位写失败吞掉并 warn。搜索 `AtomicCounter` 和 `上抛`，可以定位进度计数不可降级。搜索 `锁 / 信号量获取失败必须上抛`，可以定位正确性相关能力的故障处理。

在 `docs/design-docs/module/cache.md` 中搜索 `中间产物边界` 或 `Redis 存引用，字节落 MinIO`，可以定位 Redis 与 MinIO 的边界：Redis 只能保存轻量引用和元数据，不能保存图片字节。搜索 `禁用全量扫描`，可以定位为什么本模块不提供 `KEYS` 或通配批量删除。搜索 `TTL 治理`，可以定位为什么所有缓存写入必须有 TTL。

在 `docs/design-docs/design.md` 中搜索 `Redis + Redisson`，可以定位技术选型。搜索 `9.3 并发保障`，可以定位分布式锁、prefetch 和第三方 API 信号量。搜索 `9.4 断点恢复与失败隔离`，可以定位 MySQL `process_result` 与 Redis 缓存的关系。搜索 `13.3 Redis` 或 `Redis（键约定）`，可以定位运行时 key 示例。搜索 `确认令牌`，可以定位 HITL 令牌与 Redis 运行时键的边界。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索 `Wave 1 基础设施`，可以定位 cache 的实施波次。搜索 `infra/cache`，可以定位它被哪些模块依赖。搜索 `contracts 前置`，可以定位为什么确认令牌契约要先独立成模块。搜索 `模块依赖 DAG`，可以查看 `contracts -> cache`、`common -> cache` 的依赖方向。

在 `docs/design-docs/module/common.md` 中搜索 `infra 异常收口策略`，可以定位 infra 内部异常跨边界后如何归一化。搜索 `ErrorNormalizer`，可以定位任意 Throwable 到 `PixFlowException` 的统一入口。搜索 `DEPENDENCY`，可以定位 Redis 不可用这类错误的分类和默认恢复建议。搜索 `Sanitizer`，可以定位 key、message、details 进入日志或响应前的脱敏与截断要求。

在 `docs/design-docs/module/contracts.md` 中搜索 `ConfirmationTokenStore`，可以定位确认令牌存储 SPI。搜索 `原子读取 + 删除`，可以定位 `consume` 的单次消费要求。搜索 `零依赖叶子`，可以定位为什么 contracts 不依赖 common 或 cache。搜索 `infra/cache`，可以定位 cache 实现 Redis 版本但不依赖 permission 的原因。

## Architecture and Mechanism

cache 的架构采用“五组原语 + 一个装配层”。

第一组是 key 工厂。`CacheKey` 是不可变值对象，保存最终 Redis key 和建议 TTL。`CacheNamespace` 是工厂，负责把环境前缀、命名空间和调用方传入的段拼成最终 key。所有 key 都必须通过这个工厂产生，最终格式是 `pixflow:{env}:{segment}:{segment}`。segment 不能是空字符串，不能包含冒号，不能包含路径分隔语义，也不能由调用方传入完整 Redis key 绕过前缀。

第二组是 `CacheStore`。它提供带 TTL 的泛型 KV 能力：get、put、putIfAbsent、exists、delete。它用于取消标志、轻量引用、短期元数据这类可丢失缓存。它的故障策略是固定契约：读失败返回 `Optional.empty()`，写和删失败吞掉并记录 warn 与指标，调用方无需在每个缓存点写 try-catch。它不提供无 TTL 写入，也不提供通配批量删除。

第三组是 `AtomicCounter`。它提供 increment、get、reset，用于任务进度这类必须准确的计数。它与 `CacheStore` 不同，Redis 故障必须抛 `CacheException`，因为错误计数会误导前端进度和终态判断。`incrementBy` 首次创建 key 时设置 TTL，后续递增不刷新 TTL。任务完成后由 `module/task` 主动 reset，自然过期只是兜底。

第四组是 `LockTemplate`。它提供 `runWithLock` 和 `tryRunWithLock`，内部使用 Redisson `RLock` 的 watchdog 模式。调用方只传 `CacheKey`、等待时间和回调，拿不到裸 `RLock`。实现必须在 finally 中检查当前线程仍持有锁再 unlock，避免误解别人锁。`runWithLock` 获取不到锁时抛 `CacheException`；`tryRunWithLock` 获取不到锁时返回 false，但 Redis 连接错误仍然抛异常。

第五组是 `DistributedSemaphore`。它使用 Redisson `RPermitExpirableSemaphore` 表达集群级全局并发上限。`acquire` 返回 `Permit extends AutoCloseable`，调用方用 try-with-resources 自动释放许可。每个 semaphore 的总许可数和 leaseTime 来自配置。leaseTime 到期后许可自动回收，解决进程崩溃导致许可永久泄漏的问题。

第六部分是确认令牌存储实现。`RedisConfirmationTokenStore` 实现 `com.pixflow.contracts.confirmation.ConfirmationTokenStore`。`save` 把 `TokenClaims` 以 TTL 写入 Redis。`consume` 使用 Lua 或等价原子机制执行“读取 claims 后删除 key”，并发下只有一个调用能得到 claims，其他调用得到 empty。这个实现属于 cache，因为真实存储是 Redis；但签发、校验、payloadHash 比对和权限判断仍属于 permission。

装配层负责读取 `pixflow.cache.*` 配置，创建 `RedissonClient`，选择 single/sentinel/cluster 模式，设置 JSON codec、watchdog timeout、连接池和健康检查，并注册上述原语 Bean。第一版只真实联调 single Redis，sentinel/cluster 仅预留配置结构。

错误机制是：cache 内部抛 `CacheException`，携带操作类型、key namespace、是否可重试、cause 和 details。跨出 infra 边界或进入 HTTP、Tool、MQ、Stream 出口时，由 common 的 `ErrorNormalizer` 翻译为 `PixFlowException(category=DEPENDENCY)`。纯缓存读写失败在 cache 实现内降级，不跨边界；锁、信号量、计数和令牌存储失败会抛出。

可观测机制是：每个原语记录 Micrometer 指标。缓存读写记录命中、miss、错误和降级；锁记录获取成功、超时、错误和等待耗时；信号量记录获取成功、超时、错误和在途许可数；确认令牌记录保存、消费成功、消费 miss、错误。指标 tag 只能包含低基数维度，例如 op、result、namespace、api，不允许包含 taskId、tokenId、imageId 这类高基数业务值。

## External API Shape

本节描述最终 Java API 形状。这里的 API 是模块之间的 Java 调用边界，不是 HTTP API。

`CacheKey` 和 `CacheNamespace` 是所有 Redis key 的入口。建议形态如下：

    public final class CacheKey {
        public String value();
        public Duration suggestedTtl();
        public String namespace();
    }

    public interface CacheNamespace {
        CacheKey key(String... segments);
        CacheNamespace withDefaultTtl(Duration ttl);
    }

`CacheNamespace.key(...)` 的 segments 由调用方表达业务含义。cache 模块不理解这些含义，只负责校验和拼接。实现应拒绝空 segment、空白 segment、包含冒号的 segment。默认 TTL 来自 namespace 或 `pixflow.cache.default-ttl`。

`CacheStore` 是可降级 KV：

    public interface CacheStore {
        <T> Optional<T> get(CacheKey key, Class<T> type);
        <T> void put(CacheKey key, T value, Duration ttl);
        <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl);
        boolean exists(CacheKey key);
        void delete(CacheKey key);
    }

`put` 和 `putIfAbsent` 必须有 TTL 参数。实现可以在 ttl 为 null 或非正数时回退到 `key.suggestedTtl()`，但不能产生无 TTL Redis key。`get` 反序列化失败时记录 warn 和指标，然后返回 empty。

`AtomicCounter` 是不可降级计数：

    public interface AtomicCounter {
        long incrementBy(CacheKey key, long delta, Duration ttl);
        long get(CacheKey key);
        void reset(CacheKey key);
    }

`incrementBy` 首次创建时设置 TTL；后续递增不刷新 TTL。`delta` 可以是正数或负数，但如果业务只允许正向递增，由调用方约束。Redis 连接错误、脚本错误、类型不匹配都应抛 `CacheException`。

`LockTemplate` 是锁模板：

    public interface LockTemplate {
        <T> T runWithLock(CacheKey key, Duration waitTime, Supplier<T> action);
        void runWithLock(CacheKey key, Duration waitTime, Runnable action);
        boolean tryRunWithLock(CacheKey key, Duration waitTime, Runnable action);
    }

`runWithLock` 获取不到锁抛 `CacheException`，错误码或类型应能表达 `LOCK_ACQUIRE_TIMEOUT`。`tryRunWithLock` 获取不到锁返回 false，用于“已有实例在处理则跳过”的去重消费场景。action 抛出的业务异常应原样向外传播，不应被包装成 cache 异常。

`DistributedSemaphore` 是信号量：

    public interface DistributedSemaphore {
        Permit acquire(CacheKey key, int permits, Duration waitTime);

        interface Permit extends AutoCloseable {
            @Override
            void close();
        }
    }

`acquire` 的 permits 表示本次要拿几个许可，通常是 1。总许可数和 leaseTime 不在方法签名里传，来自 `pixflow.cache.semaphore.*` 配置。`Permit.close()` 必须幂等；重复 close 不应把许可释放多次。

`RedisConfirmationTokenStore` 实现 contracts：

    public final class RedisConfirmationTokenStore implements ConfirmationTokenStore {
        public void save(String tokenId, TokenClaims claims, Duration ttl);
        public Optional<TokenClaims> consume(String tokenId);
    }

key 建议通过专用 namespace 生成，语义是 `confirmation:token:{tokenId}`。tokenId 可以作为 key segment，但指标和日志中不能直接打完整 tokenId。`consume` 必须原子读取并删除，不能用普通 get 后 delete 两个 Redis 往返。

`CacheException` 和 `CacheErrorCode` 表达内部异常：

    public class CacheException extends RuntimeException {
        private final CacheErrorCode code;
        private final String operation;
        private final String keyNamespace;
        private final boolean retryable;
        private final Map<String, Object> details;
    }

    public enum CacheErrorCode implements ErrorCode {
        CACHE_REDIS_UNAVAILABLE,
        CACHE_SERIALIZATION_FAILED,
        CACHE_LOCK_ACQUIRE_TIMEOUT,
        CACHE_LOCK_RELEASE_FAILED,
        CACHE_COUNTER_FAILED,
        CACHE_SEMAPHORE_TIMEOUT,
        CACHE_SEMAPHORE_FAILED,
        CACHE_CONFIRMATION_TOKEN_FAILED
    }

如果实现 `CacheErrorCode implements ErrorCode`，它们的 category 应统一为 `ErrorCategory.DEPENDENCY`。若最终决定不让 cache 内部错误码实现 common 的 `ErrorCode`，也必须保证 `ErrorNormalizer` 能把 `CacheException` 归一化为 `DEPENDENCY_UNAVAILABLE` 或等价 dependency 错误。

## Configuration

`CacheProperties` 绑定 `pixflow.cache.*`。建议字段如下：

    pixflow.cache.mode=single
    pixflow.cache.address=redis://127.0.0.1:6379
    pixflow.cache.password=
    pixflow.cache.env-prefix=${spring.profiles.active:dev}
    pixflow.cache.default-ttl=1h
    pixflow.cache.lock.watchdog-timeout=30s
    pixflow.cache.connect-timeout=3s
    pixflow.cache.timeout=3s
    pixflow.cache.retry-attempts=3
    pixflow.cache.retry-interval=1500ms
    pixflow.cache.pool.size=16
    pixflow.cache.pool.min-idle=4
    pixflow.cache.semaphore.default-lease-time=5m
    pixflow.cache.semaphore.apis.removebg.permits=4
    pixflow.cache.semaphore.apis.removebg.lease-time=2m

`mode` 第一版支持 single，并为 sentinel、cluster 预留枚举和配置对象。不要在代码里把 Redis 单机地址散落到实现类。`password` 不得进入日志。`env-prefix` 参与所有 key 前缀，避免 dev/test/prod 共用 Redis 时串 key。`default-ttl` 是命名空间默认 TTL 兜底。`lock.watchdog-timeout` 写入 Redisson config 的 `lockWatchdogTimeout`。

信号量配置需要按 API 名称设置 permits 和 leaseTime。API 名称是 infra 层可接受的外部供应商能力标识，例如 `removebg`、`imagegen`、`vllm`。这些不是 task/dag 业务词。后续 `infra/thirdparty` 通过同一个 API 名称构造 semaphore namespace。

## Plan of Work

第一阶段新增 Maven 子模块。创建 `pixflow-infra-cache/pom.xml`，让它继承根 POM，依赖 `pixflow-common`、`pixflow-contracts`、Redisson、Jackson、Micrometer、Spring Boot autoconfigure、Spring context、Spring Boot test 和 Testcontainers。修改根 `pom.xml`，在 `<modules>` 中加入 `pixflow-infra-cache`，并在 `dependencyManagement` 中加入同版本依赖。修改 `pixflow-app/pom.xml`，加入对 `pixflow-infra-cache` 的依赖。

第二阶段实现 key 工厂。新增 `com.pixflow.infra.cache.key.CacheKey` 和 `CacheNamespace`，以及默认实现。它们必须做环境前缀、segment 校验、默认 TTL 和 namespace 记录。单元测试先覆盖 key 格式、空 segment 拒绝、冒号拒绝、默认 TTL 覆盖和不同 env-prefix 隔离。

第三阶段实现配置和 Redisson 装配。新增 `CacheProperties` 和 `CacheAutoConfiguration`。配置层创建 `RedissonClient`，使用 Jackson JSON codec，设置 watchdog timeout、连接超时和连接池。自动装配要注册 `CacheNamespace` 根工厂、`CacheStore`、`AtomicCounter`、`LockTemplate`、`DistributedSemaphore`、`ConfirmationTokenStore` 和 `CacheMetrics`。如果没有配置 Redis 地址，是否启用真实 Redisson Bean 应与团队启动策略一致；第一版可默认使用 `redis://127.0.0.1:6379`，测试中用 Testcontainers 动态覆盖。

第四阶段实现 `CacheStore`。使用 Redisson bucket 或 map cache 存储泛型 JSON 值。读失败、反序列化失败和 Redis 不可用都应返回 empty，并记录 warn 与指标。写、putIfAbsent、delete 失败应吞掉并记录 warn 与指标。`putIfAbsent` 在 Redis 正常时必须保持原子语义。

第五阶段实现 `AtomicCounter`。推荐使用 Redisson atomic long 加 Lua 或 Redis 命令组合保证“首次递增设置 TTL，后续不刷新 TTL”。如果 Redisson 当前版本提供可靠的 `expireIfNotSet` 或等价 API，可以使用该 API；否则用 Lua 脚本。集成测试必须证明 TTL 不会因第二次 increment 刷新。

第六阶段实现 `LockTemplate`。使用 `RLock.tryLock(waitTime, TimeUnit)` 或等价不带 leaseTime 的调用触发 watchdog。获取成功后执行 action，finally 中 `isHeldByCurrentThread()` 再 unlock。测试要证明互斥、超时、action 抛异常后锁释放，以及长于 watchdog 初始超时的临界区不会被提前释放。

第七阶段实现 `DistributedSemaphore`。使用 `RPermitExpirableSemaphore`。首次使用某个 key 时，根据配置设置总许可数；配置变更时应谨慎处理，第一版可以在初始化或 acquire 前尝试 `trySetPermits`，并在文档中说明运行时减少许可数不保证立刻收缩已持有许可。`acquire` 用 waitTime 等待许可，拿到后保存 permit id，`close` 时按 permit id 释放。leaseTime 从配置读取，缺省为 `default-lease-time`。

第八阶段实现 `RedisConfirmationTokenStore`。`save` 使用 TTL 写入 claims；`consume` 使用 Lua 脚本原子 GET+DEL。claims 序列化使用同一个 ObjectMapper 或 Redisson codec，必须支持 Java Time。并发测试应启动多个线程同时 consume 同一个 token，断言只有一个成功。

第九阶段实现错误、指标和归一化。新增 `CacheException`、`CacheErrorCode`、`CacheMetrics`、`MicrometerCacheMetrics`。补充 `pixflow-common` 的 `ErrorNormalizer.normalizeInfra`，识别 `CacheException` 并归一化为 dependency 类错误。指标 tag 不允许包含完整 key 或 tokenId。

第十阶段补测试。单元测试覆盖 key 工厂、降级分支、Permit 幂等 close、异常属性和配置绑定。Redis 集成测试使用 Testcontainers 启动真实 Redis，覆盖 TTL、putIfAbsent、counter 并发、锁互斥和 watchdog、信号量许可上限和租约回收、确认令牌原子消费。若 Docker 不可用，集成测试应可按环境条件跳过，但最终说明必须写清楚哪些测试未执行。

## Concrete Steps

从仓库根目录开始：

    cd D:\study\PixFlow
    git status --short

当前工作区已有多模块迁移产生的删除和新增文件，以及多个已完成计划移动到 `completed` 目录。实施者不能回滚这些改动。只在本计划相关文件和后续 `pixflow-infra-cache` 模块范围内工作。

实现前重新阅读关键文档：

    Get-Content AGENTS.md
    Get-Content PLANS.md
    Get-Content docs\design-docs\exec-plans\module-dependency-dag-plan.md
    Get-Content docs\design-docs\design.md
    Get-Content docs\design-docs\module\cache.md
    Get-Content docs\design-docs\module\common.md
    Get-Content docs\design-docs\module\contracts.md

确认当前 Maven 模块：

    Get-Content pom.xml
    Get-ChildItem

预期能看到 `pixflow-common`、`pixflow-contracts`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app`。如果未来执行时 `pixflow-infra-cache` 已存在，先读取现有文件并更新本计划，说明是增量改造还是补完测试。

创建模块后，建议先运行最小编译：

    mvn -pl pixflow-infra-cache -am test

如果 Maven 因依赖下载失败、DNS 失败或仓库不可访问失败，这通常是网络或沙箱问题，应按当前执行环境权限要求重新请求网络执行，不要把它记录为代码失败。

实现 key 工厂后，运行 cache 模块单元测试。预期测试应覆盖这些行为：

    env=dev, segments=("alpha","42") -> pixflow:dev:alpha:42
    withDefaultTtl(30m) -> key.suggestedTtl() is 30m
    segment="" -> rejected
    segment="a:b" -> rejected
    env=prod and env=dev produce different key prefixes

实现 `CacheStore` 后，用单元测试或 fake Redisson 故障注入验证降级：

    get on Redis failure returns Optional.empty
    get on deserialization mismatch returns Optional.empty
    put on Redis failure does not throw
    delete on Redis failure does not throw

实现 Redis 集成后，用真实 Redis 验证：

    put then get returns the value before TTL expires
    value is absent after TTL expires
    putIfAbsent succeeds once and fails the second time
    incrementBy sets TTL on first write
    incrementBy does not refresh TTL on second write
    concurrent incrementBy from many threads returns final count exactly
    runWithLock serializes two competing threads
    runWithLock releases lock when action throws
    tryRunWithLock returns false when lock is held by another thread
    semaphore allows no more than configured permits
    semaphore permit is released by close
    semaphore permit is automatically reclaimed after leaseTime
    confirmation token consume succeeds once under concurrency

最终运行：

    mvn -pl pixflow-infra-cache -am test
    mvn test

成功时应看到 Maven `BUILD SUCCESS`。如果 Redis Testcontainers 因 Docker 环境缺失被条件跳过，最终说明必须明确写“Redis 集成测试未执行”，不能写成真实 Redis 集成测试通过。

## Validation and Acceptance

第一层验收是模块依赖方向正确。`pixflow-infra-cache/pom.xml` 必须依赖 `pixflow-common` 和 `pixflow-contracts`，不能依赖 `pixflow-permission`、`pixflow-infra-mq`、`pixflow-infra-storage`、`module/*` 或 `agent/*`。根 `pom.xml` 能识别 `pixflow-infra-cache`，`pixflow-app` 能装配它。

第二层验收是 key 和 TTL 治理。所有 Redis key 都带 `pixflow:{env}:` 前缀，非法 segment 被拒绝，所有 KV 写入和 counter 写入都有 TTL。扫描代码时不应出现业务模块绕过 `CacheNamespace` 直接拼完整 Redis key 的情况。

第三层验收是降级分级。测试应证明 `CacheStore.get` 故障返回 miss，`CacheStore.put/delete` 故障不抛出；同时证明 `AtomicCounter`、`LockTemplate`、`DistributedSemaphore`、`RedisConfirmationTokenStore` 遇到 Redis 不可用或语义失败时抛 `CacheException`。这说明“可丢失缓存”和“正确性相关协调原语”没有混在一起。

第四层验收是锁语义。两个线程竞争同一个 `CacheKey` 时，不会同时进入临界区；获取超时时能得到明确异常或 false；action 抛异常后锁仍释放；长临界区在 watchdog 续期下不会被其他线程提前拿到锁。

第五层验收是信号量语义。配置 permits 为 2 时，三个并发调用最多只有两个同时持有许可。持有者 close 后第三个调用能拿到许可。持有者不 close 时，超过 leaseTime 后许可能自动回收。

第六层验收是确认令牌单次消费。保存一个 token 后，第一次 consume 返回 claims，第二次返回 empty。多个线程同时 consume 同一个 token 时，只有一个线程成功。这个测试直接证明用户刷新或重复提交不会重复执行受控动作。

第七层验收是异常归一化和指标。`CacheException` 跨出 infra 边界后能被 `ErrorNormalizer` 归一化为 `DEPENDENCY` 类错误。Micrometer 指标能记录 cache hit/miss/error、lock acquired/timeout/error、semaphore acquired/timeout/error、confirmation token consumed/miss/error。指标 tag 不包含完整 Redis key、tokenId、taskId 或 imageId。

## Idempotence and Recovery

本计划的实现步骤应当是可重复执行的。新增 Maven 模块和 POM 依赖时，重复执行应只看到相同条目，不应产生重复 module。Redisson Bean 装配应允许测试通过 Spring 条件或测试配置替换成测试 Redis。

key 生成是确定性的。相同 env-prefix、namespace 和 segments 必须得到相同 Redis key。不同 env-prefix 必须得到不同 key。业务模块后续可以依赖这个确定性做重试和断点恢复。

锁释放和信号量释放必须幂等。`LockTemplate` 在 finally 中释放锁，如果 action 抛异常也不能泄漏锁。`Permit.close()` 可以被重复调用，但只释放一次许可。如果 release 失败，应记录 warn 并抛或吞掉取决于 close 语义；建议 close 不抛业务不可处理异常，但 metrics 必须记录 release error。

确认令牌消费必须是不可逆的。Lua GET+DEL 成功返回 claims 后，如果调用方后续业务校验失败，这个 token 也不应恢复。原因是 token 是一次性安全凭证，失败后应让用户重新确认，而不是复用旧 token。

如果 Testcontainers Redis 无法启动，实施者应在 `Progress` 和 `Outcomes & Retrospective` 记录“纯单元测试已通过，Redis 集成测试因环境缺失未执行”。不要把未执行的集成测试说成通过。

## Artifacts and Notes

本计划最重要的架构边界是下面几句话，后续实现和 code review 都应围绕它们判断：

    cache 只提供通用原语，不承载业务知识。
    Redis 存轻量引用和协调状态，不存图片字节。
    MySQL process_result 是持久断点事实源，Redis 缓存可丢失可重建。
    纯缓存读写失败可降级，锁、信号量、计数和确认令牌失败必须上抛。
    所有写入 Redis 的 KV 和 counter 都必须有 TTL。
    确认令牌 consume 必须原子读删，并发下只有一次成功。

建议实现完成后保留这些测试作为证据：

    CacheNamespaceTest
    RedissonCacheStoreIntegrationTest
    RedissonAtomicCounterIntegrationTest
    RedissonLockTemplateIntegrationTest
    RedissonDistributedSemaphoreIntegrationTest
    RedisConfirmationTokenStoreIntegrationTest
    CacheExceptionNormalizationTest
    MicrometerCacheMetricsTest

一个理想的测试输出应能说明下面这些事实：

    CacheNamespaceTest > prefixes keys with environment PASSED
    RedissonCacheStoreIntegrationTest > degrades get failures to miss PASSED
    RedissonAtomicCounterIntegrationTest > does not refresh ttl on later increments PASSED
    RedissonLockTemplateIntegrationTest > keeps lock during watchdog protected action PASSED
    RedissonDistributedSemaphoreIntegrationTest > recovers permit after lease expiry PASSED
    RedisConfirmationTokenStoreIntegrationTest > consumes token only once under concurrency PASSED

## Interfaces and Dependencies

最终模块路径应是 `pixflow-infra-cache`。主源码根包是 `com.pixflow.infra.cache`。测试路径是 `pixflow-infra-cache/src/test/java/com/pixflow/infra/cache`。

`pixflow-infra-cache/pom.xml` 应依赖：

    com.pixflow:pixflow-common
    com.pixflow:pixflow-contracts
    org.redisson:redisson
    com.fasterxml.jackson.core:jackson-databind
    com.fasterxml.jackson.datatype:jackson-datatype-jsr310
    io.micrometer:micrometer-core
    org.springframework.boot:spring-boot-autoconfigure
    org.springframework:spring-context
    org.springframework.boot:spring-boot-starter-test (test)
    org.testcontainers:testcontainers (test)
    org.testcontainers:junit-jupiter (test)

如果 Redisson starter 与手写 RedissonClient 装配冲突，优先手写 `RedissonClient` Bean。原因是本模块需要明确控制 mode、codec、watchdog timeout、连接池和健康检查。

应新增这些主要文件：

    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/config/CacheProperties.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/config/CacheAutoConfiguration.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/key/CacheKey.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/key/CacheNamespace.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/CacheStore.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/RedissonCacheStore.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/counter/AtomicCounter.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/counter/RedissonAtomicCounter.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/lock/LockTemplate.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/lock/RedissonLockTemplate.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/semaphore/DistributedSemaphore.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/semaphore/RedissonDistributedSemaphore.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/confirmation/RedisConfirmationTokenStore.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/error/CacheException.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/error/CacheErrorCode.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/observability/CacheMetrics.java
    pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/observability/MicrometerCacheMetrics.java

`CacheProperties` 应绑定 `pixflow.cache.*`，包括 mode、address、password、envPrefix、defaultTtl、lock.watchdogTimeout、连接池、超时、retry、semaphore default leaseTime 和各 API permits/leaseTime。

`CacheAutoConfiguration` 应注册 RedissonClient、CacheNamespace 根工厂、CacheStore、AtomicCounter、LockTemplate、DistributedSemaphore、ConfirmationTokenStore、CacheMetrics。它不能注册任何 task/dag 业务 key 工厂。

`CacheNamespace` 应是业务模块生成 key 的唯一入口。业务模块后续可以创建自己的 namespace 并在模块内集中定义 key 构造方法，但 those methods 不属于 cache 模块。

`RedissonCacheStore` 应封装 JSON 序列化和降级策略。实现时要注意 Redisson codec 已经做序列化时，不要重复把对象转成字符串导致类型恢复困难。若选择 RBucket<Object> 加 ObjectMapper，则 get 时按 `Class<T>` convertValue 或 readValue；若选择 Redisson JSON codec，则测试必须证明 Java Time record 可以正确往返。

`RedissonAtomicCounter` 应保证 TTL 首次设置语义。若用 Lua，脚本逻辑应类似“INCRBY 后读取 TTL，若 TTL 不存在则 EXPIRE”。这里写的是语义说明，不要求脚本文本逐字照抄。

`RedissonLockTemplate` 应保证 action 异常原样传播。只有锁获取、释放和 Redis 操作错误才包装为 `CacheException`。

`RedissonDistributedSemaphore` 应维护 API permits 配置。若 key 没有显式 API 配置，使用默认 permits 或抛配置错误需要在实现中固定。建议没有配置时抛 `CacheException`，避免第三方 API 在无上限情况下运行。

`RedisConfirmationTokenStore` 应实现 `ConfirmationTokenStore`。它可以使用专用 `CacheNamespace`，但不应通过 `CacheStore.get` + `delete` 实现 consume，因为 `CacheStore` 的 get 降级语义和非原子两步都不适合安全令牌。

`pixflow-common/src/main/java/com/pixflow/common/error/ErrorNormalizer.java` 应补充 `CacheException` 识别。当前已有基于 simpleName 的 infra 异常映射风格，可以按现有风格新增 `CacheException`，或改为更强类型依赖；若改强类型，需要确认 common 是否允许依赖 infra-cache。根据现有依赖方向，common 不能依赖 infra-cache，因此应保持 simpleName 或通用 marker 方式。

## Change Notes

2026-06-27 / Codex: 创建本计划。原因是用户要求按照 `PLANS.md` 的格式撰写中文计划文档，并明确 cache 模块的实现架构、机制、验证方式，以及在参考设计文档中可快速定位对应设计文本的搜索关键词。本次只新增计划文档，不实现代码。

2026-06-27 / Codex: 更新本计划以记录第一版实现结果。原因是本次已经新增 `pixflow-infra-cache` 子模块、核心 Redis/Redisson 原语、确认令牌 Redis 实现、基础测试和条件 Redis 集成测试，并完成 Maven 验证；同时需要明确记录 Redis 集成测试因 Docker 不可用被跳过。
