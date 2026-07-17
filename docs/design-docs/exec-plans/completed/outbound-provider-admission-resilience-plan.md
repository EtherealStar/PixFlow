# 实现出站 Provider 每次尝试的并发、配额与韧性治理

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，都必须保持它自包含、可验证、可恢复，并同步维护进度、发现、决策和结果。本文面向第一次接触 PixFlow 的实现者：只依赖当前工作树和本文，就应能完成 Redis Lua 加权令牌桶、非模型第三方调用韧性管线、AI 调用配额 SPI、生产装配失败保护及验证闭环。

## Purpose / Big Picture

PixFlow 会调用模型供应商和抠图等非模型第三方服务。一次业务请求可能因为网络异常、供应商 429 或 5xx 被自动重试；如果只在整个业务请求开始时扣一次额度，后续真实 HTTP 重试会绕过供应商配额，如果在重试退避期间继续持有并发许可，又会让没有实际工作的请求占满系统。因此，本计划把治理单位统一为“provider attempt”：每一次真正可能发出 HTTP 的尝试，都重新获取并发许可、消费 Redis 中的请求权重，然后调用供应商；失败后先释放并发许可，再进行退避。

完成后，跨实例共享的出站额度由 Redis Lua 加权令牌桶原子维护，不再使用 `RRateLimiter.trySetRate(...)`。桶容量、补充速度和单次请求权重可以独立配置。非模型第三方调用使用 `Retry -> CircuitBreaker -> Bulkhead -> 每次 attempt 准入 -> HTTP`；AI 调用继续由 `ModelRetryRunner` 独占模型调用级 retry，并在每个 attempt 内通过 AI 自有 SPI 获取并发许可和消费额度。生产环境缺少 AI concurrency 或 quota adapter 时应用启动失败；测试只有显式注入 fake/noop 才能无限制运行。

可以通过新增的单元测试和 Redis Testcontainers 集成测试观察效果：模拟第一次 provider 调用失败、第二次成功时，令牌桶应消费两次，两个 attempt 应分别获取和释放许可，退避期间在途许可数应为零；本地桶拒绝、provider 429 和 Redis 故障应返回三个不同错误码。

## Progress

- [x] (2026-07-11) 阅读 `AGENTS.md`、`PLANS.md`、当前 `docs/design-docs/exec-plans/` 活动计划以及 `docs/design-docs/index.md`。
- [x] (2026-07-11) 阅读 `docs/design-docs/infra/cache.md`、`docs/design-docs/infra/thirdparty.md` 和 `docs/design-docs/infra/ai.md`，确认最终机制和模块边界。
- [x] (2026-07-11) 核对 cache、thirdparty、ai 和 app 当前实现，记录设计与代码的差距。
- [x] (2026-07-11) 创建本 ExecPlan，定义实施顺序、接口、错误契约和验证方式。
- [x] (2026-07-12) 实现 `infra/cache` Redis Lua 加权令牌桶，并删除旧 `DistributedRateLimiter` 双路径。
- [x] (2026-07-12) 重构 `infra/thirdparty` 调用模板，使每个 Retry attempt 重新经过 Bulkhead、分布式信号量和令牌桶。
- [x] (2026-07-12) 为 `infra/ai` 增加 quota SPI/guard，并在 `pixflow-app` 提供 Redis concurrency/quota adapters。
- [x] (2026-07-12) 补齐三类错误码、CircuitBreaker 失败过滤、生产 fail-fast、配置和低基数指标。
- [x] (2026-07-12) 完成 cache/thirdparty/ai/app 模块、Redis 集成和应用装配验证；全 reactor 测试被既有 context 断言失败阻断，已记录证据。

## Surprises & Discoveries

- Observation: 当前工作树中的 `infra/cache` 仍是 Redisson `RRateLimiter` 过渡实现，并在每次 acquire 时调用 `trySetRate`，不能独立表达突发容量与持续补充速度。
  Evidence: `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/rate/RedissonDistributedRateLimiter.java` 调用 `RRateLimiter.trySetRate(...)`；`docs/design-docs/infra/cache.md §4.6` 已明确实施时删除该双路径。

- Observation: 当前 thirdparty 的分布式 semaphore 包住整个 Resilience4j Retry，导致失败后的退避仍持有集群并发许可；本地 Resilience4j RateLimiter 也仍在装饰链中。
  Evidence: `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/resilience/ThirdPartyCallTemplate.java` 先 `distributedSemaphore.acquire(...)`，随后才构造 `Retry.decorateSupplier(...)`；`ThirdPartyResilienceRegistry.java` 仍创建 `RateLimiter`。

- Observation: `ThirdPartyResilienceRegistry` 创建了 `TimeLimiter`，但当前同步调用模板没有应用它。实现本计划时必须选择可实际执行 timeout 的方式，不能只保留未使用的 registry 对象并声称已有超时保护。
  Evidence: `ThirdPartyResilienceRegistry.ResilienceSet` 包含 `TimeLimiter`，而 `ThirdPartyCallTemplate.execute` 的装饰链未引用它。

- Observation: AI 客户端的 retry 所有权和 attempt 边界已经基本正确。`DefaultChatModelClient.stream` 由 `ModelRetryRunner` 包裹 `streamOnce`，blocking clients 也由 `runBlocking` 包裹 `callOnce`；并发许可已在这些 once 方法内获取。因此额度 guard 应加在同一位置，不能再引入一层上层 retry。
  Evidence: `DefaultChatModelClient.java`、`DefaultEmbeddingClient.java`、`DefaultImageGenClient.java` 和 `DefaultRerankClient.java` 都把 once 方法作为 retry attempt supplier。

- Observation: AI 当前允许 concurrency adapter 缺失并静默返回 noop permit，与生产 fail-fast 要求相反；当前也不存在 `ModelQuotaLimiter`。
  Evidence: `AiAutoConfiguration.concurrencyGuard` 使用 `ObjectProvider<GlobalConcurrencyLimiter>.getIfAvailable()`；`ConcurrencyGuard.acquire` 在 limiter 为 null 时返回 `NoopPermit`；搜索生产代码没有 `ModelQuotaLimiter`。

- Observation: 当前工作树已经包含用户未提交的设计文档和 cache rate 目录改动。本计划实施时必须保留这些改动，只编辑任务范围内文件，不得用 reset/restore 覆盖用户工作。
  Evidence: 计划创建前 `git status --short` 显示 `docs/design-docs/infra/{cache,thirdparty,ai}.md` 已修改，`pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/rate/` 为未跟踪目录。

## Decision Log

- Decision: 使用 Redis Lua 实现通用 `DistributedTokenBucket`，实施时直接删除 `DistributedRateLimiter` 和 `RedissonDistributedRateLimiter`，不保留配置开关或双写迁移路径。
  Rationale: V1 需要 capacity、refill rate 和 cost 三个独立维度，旧 `RRateLimiter.trySetRate(...)` 接口无法忠实表达该语义；保留双接口会形成两个额度真相来源。
  Date/Author: 2026-07-11 / Codex

- Decision: retry 是 attempt 的外层控制器；并发许可与额度消费必须位于 attempt 内。固定准入顺序为本机 Bulkhead、集群 DistributedSemaphore、Redis DistributedTokenBucket、provider HTTP，额度消费发生在并发许可成功之后。
  Rationale: 每次真实 provider 尝试都应重新计入出站权重；先拿许可再扣权重可以避免因并发拒绝而白白消费额度；attempt 结束即释放许可，退避不占资源。
  Date/Author: 2026-07-11 / Codex

- Decision: thirdparty 的完整装饰关系为 `Retry(CircuitBreaker(Bulkhead(attempt)))`，其中 attempt 内执行 `DistributedSemaphore -> DistributedTokenBucket -> HTTP`。删除 Resilience4j 单 JVM RateLimiter。
  Rationale: Bulkhead 管单 JVM 隔离，semaphore 管集群在途数，token bucket 管跨实例出站速率/相对成本，CircuitBreaker 管真实供应商可用性，四者职责不同。
  Date/Author: 2026-07-11 / Codex

- Decision: 本地桶拒绝、provider 429 和 Redis/Lua 故障分别使用 `*_LOCAL_RATE_LIMITED`、`*_RATE_LIMITED` 和 `*_QUOTA_UNAVAILABLE`；本地准入拒绝和 provider 429 不计入 CircuitBreaker 失败率。
  Rationale: 三种失败的来源、运维处理和重试等待依据不同。将正常配额耗尽计为可用性故障会错误打开熔断器。
  Date/Author: 2026-07-11 / Codex

- Decision: AI 模块不直接依赖 cache，而定义 `GlobalConcurrencyLimiter` 与 `ModelQuotaLimiter` 两个 SPI；Redis adapters 放在同时依赖 ai 和 cache 的 `pixflow-app` 组合根。
  Rationale: 维持 `infra/ai` 对 `infra/cache` 零硬依赖，同时让 AI 和 thirdparty 共享同一个 Lua 令牌桶实现。
  Date/Author: 2026-07-11 / Codex

- Decision: AI 生产装配不允许 nullable/noop fallback。生产代码的 guard 构造参数必须非空，测试若需要无限制行为必须在测试配置中显式提供 fake/noop adapter。
  Rationale: 缺失保护时静默放行会把配置错误转化为无上限供应商调用和潜在费用事故。显式测试替身既保留测试便利，也不会污染生产默认值。
  Date/Author: 2026-07-11 / Codex

- Decision: 本计划不为 AI 新增 CircuitBreaker；AI V1 保留 `ModelRetryRunner + concurrency + quota + provider HTTP`，thirdparty 才实现当前设计中的 CircuitBreaker/Bulkhead 管线。
  Rationale: `docs/design-docs/infra/ai.md §9` 只规定未来若引入 CircuitBreaker 时不得把 rate-limit 计为可用性故障，尚未授权 AI V1 增加该组件。无关扩展违反开发阶段的范围约束。
  Date/Author: 2026-07-11 / Codex

- Decision: V1 额度只代表 provider attempt 的正整数请求权重，不代表用户级防滥用、真实 LLM token、TPM、账单费用或每日预算。
  Rationale: 当前模型/第三方请求没有稳定的 owner/tenant 额度上下文，真实 token 只能在响应后观测，不能事后回填为本次准入依据。
  Date/Author: 2026-07-11 / Codex

## Outcomes & Retrospective

实现已把出站治理单位统一为 provider attempt。cache 现在通过单段 Redis Lua 和整数定点余额提供加权令牌桶；thirdparty 的 semaphore/token bucket 已进入 Retry 的每次 attempt；AI 通过自有 concurrency/quota SPI 在组合根接入 Redis，并删除生产 noop fallback。provider 429、本地桶拒绝和额度依赖故障拥有独立错误码。

验证结果：`mvn -pl pixflow-infra-cache -am test`、`mvn -pl pixflow-infra-thirdparty -am test`、`mvn -pl pixflow-infra-ai -am test` 与隔离后的 `mvn -pl pixflow-app test` 均通过；Redis 7 Testcontainers 的 12 个测试实际执行并通过。`mvn -pl pixflow-app -am test` 在未进入本次变更模块前，被 `pixflow-context` 的 `ContextBudgetServiceTest` 与 `ContextProjectorTest` 两个既有断言失败阻断；跳过测试的 30 模块编译/安装通过。实现没有调用真实 provider API。

## Context and Orientation

`pixflow-infra-cache` 是 Redis 通用原语模块。它拥有 Redis 连接、key 命名空间、锁、信号量、计数器和缓存能力，但不理解 provider、模型角色或抠图 API。它应新增一个深模块接口 `DistributedTokenBucket`：调用方传入 key、通用桶策略和本次成本，cache 只负责在 Redis 中原子补充和消费。

“令牌桶”是一个随时间补充额度的状态。`capacity` 是最大余额，决定允许多大的瞬时突发；`refillTokens/refillPeriod` 是持续补充速度；`cost` 是一次 attempt 消耗的正整数权重。这三个值互不替代。例如 capacity=10、每分钟补充 2、图片生成 cost=5 表示最多可以瞬时放行两次满成本生图，之后每 2.5 分钟恢复一次 5 权重的尝试。

`pixflow-infra-thirdparty` 封装非模型第三方 HTTP 服务，目前主要是背景去除。它可以直接依赖 `pixflow-infra-cache`，因此 `ThirdPartyCallTemplate` 直接消费 `DistributedSemaphore` 与 `DistributedTokenBucket`。Resilience4j 的 `Retry`、`CircuitBreaker`、`Bulkhead` 和实际 timeout 在本模块内负责单实例韧性；不再保留 Resilience4j RateLimiter。

“Bulkhead”是本机并发隔离器，避免一个 provider 占满当前 JVM；`DistributedSemaphore` 是跨所有实例共享的在途上限；二者都在调用结束时释放。“CircuitBreaker”根据真实网络、超时和 5xx 失败率临时停止调用故障供应商；它不是额度工具。“Retry”在可恢复失败后等待再执行一次 attempt，等待阶段不能持有 Bulkhead 或 semaphore permit。

`pixflow-infra-ai` 封装 chat、vision、image generation、embedding 和 rerank。模型调用 retry 的唯一所有者是 `ModelRetryRunner`。AI 为保持模块依赖方向，只定义 `GlobalConcurrencyLimiter` 和新增的 `ModelQuotaLimiter` SPI，不直接 import cache 类型。`pixflow-app` 同时依赖 ai 和 cache，是把这两个 SPI 适配到 `DistributedSemaphore`/`DistributedTokenBucket` 的组合根。

“本地桶拒绝”在本文中指本应用策略使用的 Redis 分布式桶正常返回额度不足，并不是单 JVM 内存桶；名称中的 local 用来与 provider 自己返回的 429 区分。“Redis 故障”是 Lua、连接或 Redis 服务不可用，必须 fail-closed，即拒绝调用而不是退化为无限制放行。

主要生产文件如下：

- `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/rate/`：待删除的旧 rate limiter 过渡实现。
- `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/tokenbucket/`：待新增的通用接口、策略、决策和 Redis Lua adapter。
- `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/config/CacheAutoConfiguration.java`：替换自动装配 bean。
- `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/observability/`：将 rate limiter 指标收敛为 token bucket 指标。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/resilience/ThirdPartyCallTemplate.java`：重构每-attempt 准入顺序。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/resilience/ThirdPartyResilienceRegistry.java`：删除 RateLimiter，配置 Retry/CircuitBreaker/Bulkhead/timeout 的过滤语义。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/config/ThirdPartyProperties.java`：增加 provider/api 额度策略和单次成本。
- `pixflow-infra-thirdparty/src/main/java/com/pixflow/infra/thirdparty/error/ThirdPartyErrorCode.java`：增加本地拒绝与额度依赖故障错误码。
- `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/spi/`：新增 `ModelQuotaLimiter`，并扩展 concurrency SPI 的 provider 维度。
- `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/resilience/`：新增 `ModelQuotaGuard`，移除 `ConcurrencyGuard` 的隐式 noop。
- `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/config/AiProperties.java`：增加 role 级 quota group/policy/cost 配置。
- `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/config/AiAutoConfiguration.java`：把 concurrency/quota SPI 作为必需依赖装配 guards。
- `pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/{chat,embedding,imagegen,rerank}/Default*Client.java`：在每个 once attempt 内按固定顺序应用 guards。
- `pixflow-app/src/main/java/com/pixflow/app/`：新增 AI Redis adapter 配置类与实现。

## Plan of Work

### Milestone 1：用 Redis Lua 加权令牌桶替换旧 RateLimiter

在 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/tokenbucket/` 新建 `DistributedTokenBucket`、`TokenBucketPolicy`、`TokenBucketDecision` 和 `RedisLuaTokenBucket`。接口必须非阻塞地表达“立即允许或拒绝”，不得在 cache 内 sleep 或等待 retry。策略构造时校验 capacity、refillTokens、refillPeriod、idleTtl 为正；消费时校验 cost 为正且不大于 capacity。

`RedisLuaTokenBucket` 使用 Redisson `RScript` 执行单段 Lua。脚本只使用一个桶状态 key，在一次原子执行中调用 Redis `TIME`、读取余额和上次补充时间、按经过时间补充、容量封顶、判断成本、允许时扣减、写回并设置 TTL。实现应使用整数定点或显式保存补充余数，不能使用会长期累计误差的浮点余额。Java 侧还要为 Lua 整数范围设安全上限，避免 capacity、时间单位和缩放因子相乘超过 Redis Lua 精确整数范围。

同一 key 的状态中保存 policy fingerprint 或等价的 capacity/refill 参数。已存在桶收到不一致 policy 时抛 `CacheException` 并记录配置错误，不能清空或重建余额。拒绝时不扣减，按恢复到 cost 所需的最短时间向上取整得到保守 `retryAfter`。TTL 至少取 `idleTtl` 和两倍“空桶补满时间”的较大值，避免活跃额度状态过早丢失。

修改 `CacheAutoConfiguration` 装配新接口，修改 `CacheMetrics`、`MicrometerCacheMetrics` 和 `NoopCacheMetrics` 暴露低基数结果，例如 `allowed`、`rejected`、`redis_error`、`policy_conflict`，不得把完整 bucket key、用户 id 或 provider 请求参数放入标签。删除 `rate/DistributedRateLimiter.java` 与 `rate/RedissonDistributedRateLimiter.java`，并确保生产和测试代码都不再命中 `RRateLimiter`、`trySetRate` 或旧接口。

为快速算法测试增加 deterministic in-memory fake，只放在测试源码或明确的 test-support 位置。它要允许注入可控时钟，验证首次满桶、加权扣减、拒绝不扣减、分段补充、容量封顶、retryAfter 和策略冲突。扩展 `RedisIntegrationTest` 或新增 `RedisLuaTokenBucketIntegrationTest`，使用现有 Redis 7 Testcontainers 验证 Lua 原子性、Redis TIME、并发消费总数、TTL 和 Redis 故障 fail-closed。

本里程碑完成的可观察结果是：全仓搜索不再出现 `RRateLimiter` 和 `trySetRate`；给 capacity=5、cost=3 的新桶连续消费两次时第一次允许且 remaining=2，第二次拒绝且 remaining 仍为 2；并发 100 个 cost=1 请求面对 capacity=10 时恰好 10 个允许。

### Milestone 2：重构 non-model thirdparty 每-attempt 韧性管线

扩展 `ThirdPartyProperties`，在 `pixflow.thirdparty.outbound-quota` 下按 provider/api 保存 capacity、refillTokens、refillPeriod、idleTtl 和 costPerAttempt。保留 resilience 的 maxAttempts、baseDelay、maxDelay、bulkheadMaxConcurrent 和 timeout，删除 `rateLimitLimitForPeriod`。配置解析必须拒绝非正额度和成本；provider/api 找不到额度策略属于配置错误，不得放行。

新增 `OutboundQuotaPolicy` 或等价的 thirdparty 自有配置投影，由 `ThirdPartyCallContext` 明确携带 provider id、API id、semaphore wait time 和额度策略引用。Redis key 使用 cache namespace 构造 `bucket:thirdparty:{provider}:{api}`；semaphore key 使用 `sem:thirdparty:{provider}:{api}`，不能只使用 api 而让不同 provider 意外共享。

重构 `ThirdPartyCallTemplate.execute`。最外层 Retry 对同一个逻辑调用控制多次 attempt；每次 attempt 重新进入 CircuitBreaker 和 Bulkhead，再获取 DistributedSemaphore，随后调用 DistributedTokenBucket.tryConsume，只有允许时才执行 HTTP。try-with-resources 的作用域只能覆盖本次 attempt，确保 action 返回或抛错后立即释放 semaphore；Resilience4j Bulkhead decorator 也只包本次 attempt。令牌消费成功且 HTTP 已开始后，无论成功、超时、断连或 5xx 都不返还令牌。

删除 `ThirdPartyResilienceRegistry` 中的 RateLimiter 配置和 `ResilienceSet.rateLimiter`。CircuitBreaker 配置显式使用 record/ignore predicate：只把网络、实际调用超时、5xx/非法供应商响应计为失败；忽略本地 quota 拒绝、semaphore/Bulkhead 准入拒绝和 provider 429。Retry 只重试 recovery=RETRY 的规范化异常，并让异常携带的 `retryAfter` 优先于指数退避；如果 Resilience4j 当前版本的静态 waitDuration 不能读取每次异常的 retryAfter，改用 intervalBiFunction 或在受测的调用模板中实现等价动态等待，不能丢掉该契约。

处理现有未使用的 TimeLimiter。因为当前 provider API 是同步 `Supplier<T>`，应优先依赖 HTTP 客户端的 connect/read/request timeout 作为真实 attempt timeout，并删除误导性的未使用 TimeLimiter；只有在能安全取消实际 HTTP future、不会留下后台幽灵请求时，才使用 Resilience4j TimeLimiter。最终设计文档、properties 和实际调用代码必须只宣称真正生效的 timeout 机制。

扩展 `ThirdPartyErrorCode` 和 mapper：provider 429 为 `THIRDPARTY_RATE_LIMITED`，Redis 桶正常拒绝为 `THIRDPARTY_LOCAL_RATE_LIMITED`，Redis/Lua/cache 异常为 `THIRDPARTY_QUOTA_UNAVAILABLE`。前两者是 RATE_LIMIT/RETRY，但错误码不同；Redis 故障是 DEPENDENCY/RETRY 并 fail-closed。桶拒绝把 `TokenBucketDecision.retryAfter` 放入 `PixFlowException`，provider 429 保留 HTTP `Retry-After`。

重写 `ThirdPartyCallTemplateTest`，使用可记录调用顺序的 fake Bulkhead/sem/token bucket/action 或事件日志断言：两次 provider attempt 消费两次额度、获取释放两次 semaphore；第一次失败后的 retry delay 内 active permit 为 0；bucket 拒绝时 HTTP 调用次数为 0；并发拒绝不会扣 token；HTTP 已开始后失败不会退还 token。扩展 registry 和 error mapper 测试，证明 rate-limit 不打开 CircuitBreaker，而连续真实 5xx 能打开并返回 `THIRDPARTY_CIRCUIT_OPEN`。

本里程碑完成的可观察结果是：全模块不再 import `io.github.resilience4j.ratelimiter.*`；一次“500 后成功”的测试产生两次 token consumption 和两次 HTTP；一次本地桶拒绝产生零次 HTTP；退避期间 fake semaphore 的 active count 为零。

### Milestone 3：通过组合根为 AI 接入 concurrency 和 quota

在 `pixflow-infra-ai` 定义 provider-aware 的 `GlobalConcurrencyLimiter`。最终签名为 `Permit acquire(ModelRole role, String provider, Duration waitTime)`，使 app adapter 能构造稳定的 `sem:ai:{provider}:{role-or-group}` key。修改 `ConcurrencyGuard` 强制 `Objects.requireNonNull(limiter)`，删除 null 分支和 `NoopPermit`。所有 AI 单元测试若不关心并发，显式传入测试 fake，而不是依赖生产 fallback。

新增 AI 自有 `ModelQuotaLimiter` SPI 和不泄漏 cache 类型的 `QuotaDecision`。建议接口为：

    public interface ModelQuotaLimiter {
        QuotaDecision tryConsume(
                ModelRole role,
                String provider,
                String quotaGroup,
                long cost);
    }

    public record QuotaDecision(
            boolean allowed,
            long remaining,
            Duration retryAfter) {}

新增 `ModelQuotaGuard`，根据 `AiProperties` 中 role 级 quota 配置调用 SPI。它把 allowed=false 映射为带 retryAfter 的 `MODEL_LOCAL_RATE_LIMITED`；adapter 抛出的 Redis/cache 失败在 AI 边界映射为 `MODEL_QUOTA_UNAVAILABLE`，不得吞掉。配置包含 quotaGroup、capacity、refillTokens、refillPeriod、idleTtl、costPerAttempt；capacity/refill/cost 独立校验。共享 provider 配额的角色可以显式使用同一 quotaGroup，默认 quotaGroup 为 role 名。

在 `pixflow-app/src/main/java/com/pixflow/app/ai/` 新增 Redis adapters 和配置。concurrency adapter 使用 `DistributedSemaphore`，quota adapter 把 AI role policy投影为 cache 的 `TokenBucketPolicy` 并使用 key `bucket:ai:{provider}:{quotaGroup}`。adapter 负责翻译 cache 异常，AI SPI 不得 import `CacheException` 或其他 cache 类型。app 的 Maven 依赖已经同时包含 cache 和 ai，不新增反向模块依赖。

修改 `AiAutoConfiguration`，直接以必需参数接收 `GlobalConcurrencyLimiter` 和 `ModelQuotaLimiter` 并构造 guards，不再使用可空 ObjectProvider。生产 main 源码不声明 noop bean。`AiAutoConfigurationTest` 和各 client test 显式提供 fake adapters；如果测试 profile 需要通用 noop，只在 test source 创建并通过测试配置 import。增加 app context 测试：存在两个 adapter 时上下文启动成功；分别移除任一个时启动失败并明确指出缺失 bean。

修改 `DefaultChatModelClient.streamOnce`、`DefaultEmbeddingClient.callOnce`、`DefaultImageGenClient.callOnce` 和 `DefaultRerankClient.callOnce`。每个 once 方法在 Reactor 的 defer 边界内执行 `ConcurrencyGuard.acquire -> ModelQuotaGuard.tryConsume -> provider HTTP`。permit 必须覆盖 HTTP subscription/terminal signal 的真实生命周期并在 success、error、cancel 时释放；流式 chat 不能在返回 Flux 对象时提前 close，应使用 `Flux.using`/`usingWhen` 或等价的 `doFinally` 生命周期。quota 拒绝发生在 HTTP subscription 前并立即释放 permit。`DefaultVisionModelClient` 若委托 chat，则不得再包第二层 guards，避免一次 provider HTTP 重复扣费。

保持 `ModelRetryRunner` 为唯一模型调用 retry 所有者。它每次调用 once supplier 都会重新经过两个 guard，退避通过 `delaySubscription` 发生在下一 attempt 订阅前，所以不持有旧 permit。本计划不向 loop、agent 或 conversation 注入 retry/guard，也不在模型客户端外再包装 retry。

扩展 `AiErrorCode`：provider 429 继续使用 `MODEL_RATE_LIMITED`，本地桶拒绝新增 `MODEL_LOCAL_RATE_LIMITED`，Redis/Lua 故障新增 `MODEL_QUOTA_UNAVAILABLE`。注意当前 `ModelRetryRunner.isRetryable` 只按 RATE_LIMIT/NETWORK/PROVIDER 判断，新增的 DEPENDENCY/RETRY 如果仅用 category 会被错误地终止；应把 retry 判断收敛为 `recovery == RETRY` 并继续单独排除 CONTEXT_LIMIT，或显式纳入 `MODEL_QUOTA_UNAVAILABLE`，以公共错误恢复语义为首选。生产配置错误如缺失策略仍应 TERMINATE，而不是无限重试。

为 `ModelRetryRunnerTest` 和每类 client 测试增加 fake admission adapters。验证第一次 provider attempt 失败、第二次成功时 acquire/consume 均调用两次；retry delay 内 active permit 为零；quota 拒绝不调用 HTTP并保留 retryAfter；流式 cancel 释放 permit；Redis failure 产生 quota unavailable；provider 429 与本地拒绝使用不同 code。保持已有 AttemptReset、首次 delta 前透明 retry、首次 delta 后可见 reset 等测试不回归。

本里程碑完成的可观察结果是：生产代码没有 `new ConcurrencyGuard(null)`、`NoopPermit` 或可空 adapter；app 上下文缺少任一 adapter 明确启动失败；两次 AI provider attempt 消费两次请求权重；AI 模块的 pom 仍不依赖 infra-cache。

### Milestone 4：统一配置、可观测、文档与验收

在 `pixflow-app/src/main/resources/application.yml` 或既有环境配置位置加入明确示例。cache 只配置 Redis 连接和通用 semaphore 安全参数；thirdparty 配置 provider/api 的 capacity/refill/cost；ai 配置 role 的 quotaGroup/capacity/refill/cost。不要把 provider policy 放进 `pixflow.cache.*`，也不要把示例值描述为供应商正式额度。启动时校验所有启用的 provider/role 都有合法策略。

指标只使用低基数标签。cache 记录 token bucket allowed/rejected/error/policy-conflict；thirdparty 和 AI 记录 provider、能力或 role、attempt result、error source 和 retry count，但不记录完整 Redis key、请求内容、API key、owner id 或动态 URL。日志对异常消息继续使用 common Sanitizer。

更新 `docs/design-docs/infra/cache.md`、`thirdparty.md` 和 `ai.md`，只同步实际实现与计划偏差，不扩大范围。若最终删除未生效 TimeLimiter，修正文档的 timeout 表述；若接口命名或 key 结构因实现约束变化，在本计划 Decision Log 记录原因。更新本计划 Progress、Surprises、Outcomes 和末尾 Revision Notes。

最后按由小到大的顺序执行模块测试、组合根测试和编译。先运行无 Docker 的单元测试，再在 Docker 可用时运行 Redis Testcontainers；Docker 不可用导致的 skipped 测试不能作为 Redis Lua 验收通过，必须在有 Docker 的环境补跑并记录结果。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 运行。实施前先检查工作树并保存输出，识别用户已有改动：

    git status --short
    git diff -- docs/design-docs/infra/cache.md docs/design-docs/infra/thirdparty.md docs/design-docs/infra/ai.md

完成 Milestone 1 后运行：

    rg -n "RRateLimiter|trySetRate|DistributedRateLimiter|RedissonDistributedRateLimiter" pixflow-infra-cache pixflow-infra-thirdparty pixflow-app
    mvn -pl pixflow-infra-cache -am test

第一条搜索预期无输出。Redis Testcontainers 可运行时，测试日志应显示 token bucket 集成测试执行而不是 skipped。

完成 Milestone 2 后运行：

    rg -n "resilience4j\.ratelimiter|RateLimiterConfig|rateLimitLimitForPeriod" pixflow-infra-thirdparty
    mvn -pl pixflow-infra-thirdparty -am test

第一条搜索预期无输出。`ThirdPartyCallTemplateTest` 应证明每-attempt 准入和退避释放许可，CircuitBreaker 测试应证明 429/本地拒绝不进入失败率。

完成 Milestone 3 后运行：

    rg -n "NoopPermit|getIfAvailable\(\)|ModelQuotaLimiter|MODEL_LOCAL_RATE_LIMITED|MODEL_QUOTA_UNAVAILABLE" pixflow-infra-ai pixflow-app
    mvn -pl pixflow-infra-ai,pixflow-app -am test

搜索结果应只包含新增 quota 类型、错误码和显式测试替身，不应在生产 AI guard 中出现 noop/null fallback。app context tests 应包含缺失 concurrency adapter 和缺失 quota adapter 两个失败场景。

完成所有里程碑后运行：

    mvn -pl pixflow-infra-cache,pixflow-infra-thirdparty,pixflow-infra-ai,pixflow-app -am test
    mvn -DskipTests compile
    git diff --check
    git status --short

如果 Maven 因 Docker 不可用跳过 Redis 测试，记录这一事实并在 Docker 可用环境运行：

    docker info
    mvn -pl pixflow-infra-cache,pixflow-infra-thirdparty,pixflow-app -am test

不要运行真实 provider API 作为自动验收；所有 provider 行为由 fake HTTP server、MockWebServer、现有本地 HTTP 测试或可控 supplier 模拟。

## Validation and Acceptance

实现只有同时满足以下行为才算完成。

新 Redis 桶首次使用时拥有完整 capacity。capacity=10、refillTokens=2、refillPeriod=1s、cost=3 时，连续三次立即消费都允许并分别返回 7、4、1，第四次拒绝且余额仍为 1；经过足够 Redis 时间后重新允许。cost 大于 capacity 或非正策略在 HTTP 之前报配置错误。同一个 key 使用不同策略时 fail-closed，不重置余额。

Redis 中对同一 key 并发消费是原子的。capacity=N 且无补充时，无论从多少线程或两个 adapter 实例并发请求，允许的 cost 总和都不超过 N。Redis 断连或 Lua 执行失败时不得调用 provider，不得退化为本机内存桶或放行。

thirdparty 一次“第一次 5xx、第二次成功”的逻辑调用应产生两次 provider HTTP、两次 token consumption、两次 semaphore 获取释放。第一次释放与第二次获取之间的 retry delay 中，Bulkhead 和 distributed semaphore 的 active permits 都为零。provider 429 使用 `THIRDPARTY_RATE_LIMITED`；桶拒绝使用 `THIRDPARTY_LOCAL_RATE_LIMITED`；Redis 故障使用 `THIRDPARTY_QUOTA_UNAVAILABLE`。前两者不增加 CircuitBreaker 失败计数，真实网络/timeout/5xx 会增加。

AI 一次可重试失败后成功的模型调用应产生两次 quota consumption 和两次 concurrency permit 生命周期。chat 在流取消、错误和完成时都释放 permit。桶拒绝发生在首个 delta 和 HTTP 前；provider 429、本地拒绝和 Redis 故障分别使用 `MODEL_RATE_LIMITED`、`MODEL_LOCAL_RATE_LIMITED` 和 `MODEL_QUOTA_UNAVAILABLE`。已发射后的 retry 继续遵守现有 AttemptReset/SSE 合同，不新增 loop 外层 retry。

在正常生产应用上下文中，删除或屏蔽 `GlobalConcurrencyLimiter` bean 时启动失败；删除或屏蔽 `ModelQuotaLimiter` bean 时也启动失败。测试只有显式注册 fake/noop 才启动，不存在 classpath 自动提供的无限制生产 fallback。

配置和对外说明只能把 cost 称为“出站请求权重”或“provider attempt 权重”。代码、指标、配置注释和文档不得声称它提供用户级防滥用、真实 LLM token 计费、TPM 或账单预算。

## Idempotence and Recovery

所有 Java 与配置修改都应通过小范围 `apply_patch` 完成，并在每个里程碑先运行对应模块测试。Lua 脚本本身是幂等部署的：应用不需要初始化或迁移 Redis bucket；首次消费创建状态，重复部署继续读取相同策略状态。policy 变化不能通过运行时静默覆盖旧状态；需要变更部署策略时使用新的 quotaGroup/key 版本，等待旧 key 按 TTL 回收。

工作树在计划开始前已经有用户改动。不得执行 `git reset --hard`、`git checkout --` 或对任务范围文件做整体 restore。删除旧 rate 接口前先用 `rg` 找全调用点；如果用户未提交的文件与本计划重叠，以最小补丁在现有内容上演进。测试失败时只回退当前里程碑的局部修改，不动无关文档和模块。

如果 Lua 算术在 Redis 7 上暴露精度或溢出问题，不得降级回 RRateLimiter。先把发现、数值边界和失败样例记录在本计划，再调整固定点单位、安全上限或状态表示，并补回归测试。如果 Resilience4j API 无法同时满足动态 Retry-After 和装饰顺序，可在 thirdparty 内实现小型显式 attempt loop，但必须保留 CircuitBreaker/Bulkhead 的单-attempt语义并用测试证明，不得牺牲既定行为换取 API 形式一致。

## Artifacts and Notes

目标 thirdparty 运行时关系为：

    logical call
      Retry
        attempt 1
          CircuitBreaker permission
          Bulkhead permission
          DistributedSemaphore permit
          DistributedTokenBucket.tryConsume(cost)
          provider HTTP
          release semaphore and bulkhead
        backoff with no permit held
        attempt 2
          repeat all admission steps

目标 AI 运行时关系为：

    ModelRetryRunner
      attemptSupplier / streamOnce / callOnce
        GlobalConcurrencyLimiter.acquire(role, provider)
        ModelQuotaLimiter.tryConsume(role, provider, quotaGroup, cost)
        provider HTTP
        release permit on complete, error, or cancel
      backoff with no permit held
      invoke attemptSupplier again

三个必须可区分的失败来源为：

    Redis bucket denied normally -> LOCAL_RATE_LIMITED, retryAfter from bucket decision
    provider returned HTTP 429 -> RATE_LIMITED, retryAfter from HTTP header
    Redis/Lua failed -> QUOTA_UNAVAILABLE, fail-closed dependency error

## Interfaces and Dependencies

在 `pixflow-infra-cache` 最终提供：

    public interface DistributedTokenBucket {
        TokenBucketDecision tryConsume(
                CacheKey key,
                TokenBucketPolicy policy,
                long cost);
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

实现类为 `RedisLuaTokenBucket`，依赖现有 Redisson `RedissonClient`/`RScript` 与 `CacheMetrics`，不引入新的 rate limiter 库。cache 不接受 provider、role 或 api 类型。

在 `pixflow-infra-ai` 最终提供：

    public interface GlobalConcurrencyLimiter {
        Permit acquire(ModelRole role, String provider, Duration waitTime);

        interface Permit extends AutoCloseable {
            @Override
            void close();
        }
    }

    public interface ModelQuotaLimiter {
        QuotaDecision tryConsume(
                ModelRole role,
                String provider,
                String quotaGroup,
                long cost);
    }

    public record QuotaDecision(
            boolean allowed,
            long remaining,
            Duration retryAfter) {}

AI SPI 不引用 `CacheKey`、`TokenBucketPolicy`、`CacheException` 或 Redisson 类型。`pixflow-app` adapter 完成类型投影和异常翻译。`pixflow-infra-thirdparty` 可以直接引用 cache token bucket 类型，因为其 Maven 依赖已经包含 `pixflow-infra-cache`。

生产依赖关系最终保持：

    pixflow-infra-cache -> common + contracts + Redisson
    pixflow-infra-thirdparty -> common + infra-cache + Resilience4j + HTTP client
    pixflow-infra-ai -> common + Reactor + provider HTTP/Spring AI abstractions
    pixflow-app -> infra-cache + infra-ai + other application modules

不得新增 `pixflow-infra-ai -> pixflow-infra-cache`，不得把 retry 移回 harness/loop 或 agent，也不得让 cache 读取 `pixflow.ai.*` 或 `pixflow.thirdparty.*` 业务配置。

## Revision Notes

2026-07-11 / Codex: 创建计划。根据当前 `cache.md`、`thirdparty.md` 和 `ai.md`，把 Redis Lua 加权令牌桶、每-attempt 准入、thirdparty Resilience4j 管线、AI SPI 倒置、错误源区分、生产 fail-fast 和测试闭环整理为可独立执行的四个里程碑；明确 AI V1 不新增 CircuitBreaker，V1 额度只表示出站请求权重。

2026-07-12 / Codex: 完成四个实施里程碑并记录验证结果。实现采用“令牌 × refillPeriod 毫秒”的整数定点余额保存补充余数；应用配置中的额度值明确标为开发示例。全 reactor 测试的两个既有 context 失败作为外部阻断记录，任务范围模块均已独立通过。
