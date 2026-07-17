# 以精确像素准入和统一出站治理对齐 Image、Thirdparty 与 AI

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，并作为 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 的中等规模基础设施子计划执行。执行者只依赖当前工作树和本文，就应能完成最新架构提交中 Image、Thirdparty 与 AI 三个基础设施模块的重构性对齐。每个停止点都要更新本文；改变模块范围、接口、配置形状、阶段顺序、测试命令或交接条件时，必须同步更新四个 living sections 和文末 `Revision Notes`。

本仓库处于开发阶段，本计划采用一次性切换。旧配置、旧估算路径和旧构造方式在直接调用方迁移后立即删除，不保留别名、deprecated API、兼容重载、双绑定、双写或 fallback。

## Purpose / Big Picture

最新提交 `0faa1713204785303c49807430dc614d2ee5af6d` 只更新了架构与设计文档，没有在该提交中修改生产代码。按该提交相对父提交 `b620027` 的基础设施设计文档总变更量，Image 为 67 行、Thirdparty 为 98 行、AI 为 123 行，处于 Cache/MQ/Storage 之后且显著小于 Vector/Auth/Permission，因此组成中等变更批次。

完成本计划后，图片流水线会在任何 decode 之前按实际 resize 尺寸和 compose 画布精确计算 JVM 级加权像素预算，单边 resize、gap 和布局不会绕过内存准入；非模型第三方与模型调用会继续在每次真实 provider attempt 内执行并发和 Redis 令牌桶准入，同时产出低基数 quota 指标；AI 配置只接受目标设计中的 `pixflow.ai.quota.roles`，默认模型重试固定为三次，不再接受旧 `pixflow.ai.quotas`。

这些修改不新增页面。开发者可以通过定向测试、配置绑定失败测试、Micrometer 指标断言、负向源码搜索和模块依赖检查观察结果：预算不足时 decode 调用数仍为零；第一次 provider 失败、第二次成功时恰有两次许可与两次额度消费；本地桶拒绝不发 HTTP 且不污染 CircuitBreaker；缺少 quota role 或 admission adapter 时应用明确启动失败；旧配置关键词在生产配置与代码中无命中。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、当前活动执行计划、设计文档索引、总体设计、Context Map，以及 Image、Thirdparty、AI 的当前设计。
- [x] (2026-07-17) 对比 `0faa171` 与 `b620027`，按文档总变更量把 Image（67）、Thirdparty（98）、AI（123）划入中等批次；排除小型与大型基础设施模块。
- [x] (2026-07-17) 审计三个模块的生产源码、POM、自动配置、直接调用方、关键测试与 completed ExecPlan，确认 Pixel Budget、每-attempt token bucket 和 AI admission SPI 主体已经由历史计划实现。
- [x] (2026-07-17 13:42+08:00) 串行运行 `mvn -pl pixflow-infra-image,pixflow-infra-thirdparty,pixflow-infra-ai -am test`；Common 23、Cache 20、AI 26、Image 25、Thirdparty 13 项测试全部通过，零失败、零跳过。
- [x] (2026-07-17) 创建本中文 ExecPlan，固定无兼容层的一次性配置迁移、精确像素足迹算法、quota 可观测和验证顺序。
- [x] (2026-07-17) Milestone 0：冻结工作树所有权；用先红后绿测试固定 Image 单边 resize/compose gap、Thirdparty fail-closed 和 AI retry/quota 边界。
- [x] (2026-07-17) Milestone 1：Image 执行与准入共用 `ResizeGeometry`、`ComposeGeometry` 和 `PixelFootprintEstimator`；4×4 单边放大到 8×8 的加权足迹为 96，两个 4×4 输入横向 gap=2 的足迹为 122，低一单位均在 decode 前拒绝。
- [x] (2026-07-17) Milestone 2：Thirdparty 对每次 token-bucket attempt 只记录一次 `allowed`、`rejected` 或 `error`，并证明本地拒绝和 Redis 异常都不进入 HTTP 或 CircuitBreaker 失败统计。
- [x] (2026-07-17) Milestone 3：AI 只接受 `pixflow.ai.quota.roles`，默认 retry 改为 3，quota 指标与启动期 role/adapter fail-fast 已完成；未保留旧字段、构造重载或生产 fallback。
- [x] (2026-07-17 14:37+08:00) Milestone 4：三个模块及依赖的统一 test/verify、架构测试、App Redis adapter 测试和直接消费者测试通过；完整 reactor 被本计划范围外的 Context 与并行 Permission/Tools 工作树失败阻断，证据记录如下。

## Surprises & Discoveries

- Observation: 中等文档变更对应的主体机制已经在最新文档提交之前实现，不能再按“从零建设”重写。
  Evidence: `completed/execution-domain-refactor-plan.md` 已交付 `PixelBudget`、`ReopenableImageSource` 和 `RasterImage.retain/close`；`completed/outbound-provider-admission-resilience-plan.md` 已交付 `DistributedTokenBucket`、Thirdparty 每-attempt 准入、AI 的 `ModelQuotaLimiter` SPI 与 App Redis adapter。

- Observation: 当前测试全部通过，但 Image 的预算投影仍可能低估实际目标 raster。
  Evidence: `DefaultImagePipeline.targetPixels` 只有在 `ResizeSpec.width` 与 `height` 同时非空时才计算目标面积，而 `ResizeSpec` 明确允许只给一边；`runComposed` 使用 `成员数 × 最大成员面积` 估算 compose 画布，没有计入 `ComposeSpec.gap`，也没有复用 `ComposeGroupOp` 的布局尺寸公式。

- Observation: AI 配置与当前设计存在两个明确偏差。
  Evidence: `AiProperties` 的无配置默认是 `new Retry(10, ...)`，设计要求 `max-retries: 3`；Java 属性和 `application-dev.yml` 使用 `quotas`，设计文档使用 `quota.roles`。

- Observation: Thirdparty 和 AI 已执行 quota decision，但没有实现设计要求的 quota 指标。
  Evidence: `ThirdPartyMetrics` 不包含 `pixflow.thirdparty.quota`；`AiMetrics` 不包含 `pixflow.ai.quota`；两个 quota guard/template 都没有记录 allowed/rejected/error。

- Observation: 当前 admission 测试覆盖了“重试两次就消费两次”，但没有覆盖最重要的 fail-closed 与熔断统计边界。
  Evidence: `ThirdPartyCallTemplateTest` 只有成功/失败释放和 retryable 重试两项；AI 没有独立的 `ModelQuotaGuardTest` 或 `RedisModelQuotaLimiterTest`；`ModelRetryRunnerTest` 只有首次文本前透明重试与首次文本后 AttemptReset 两项。

- Observation: 当前工作树包含 Lint、小型基础设施、Common、File、Vector 和 Vision 等用户修改，且 `application-dev.yml` 正在被小型基础设施计划修改。
  Evidence: `git status --short` 显示这些文件均已修改或未跟踪。本计划只能在现状上做最小补丁，不能覆盖、恢复或顺手提交无关变化。

- Observation: 完整消费者 `-am` reactor 不能作为本计划的绿色验收信号，因为它先在未由本计划修改的 Context 测试失败。
  Evidence: 独立重跑仍失败于 `ContextBudgetServiceTest.externalizesLargeToolResultAndMicrocompactsOldResults` 和 `ContextProjectorTest.backsUpWindowStartToKeepToolPair`；直接 Loop 测试还受工作树中未完成的 Permission/Tools API 迁移影响。本计划未修改或回退这些并行工作。

- Observation: 最终消费者复验期间，并行 Vector/Memory 重构使 Memory 的已编译依赖失配。
  Evidence: Imagegen 与 DAG 通过后，`VectorRecallReadinessTest` 报 `NoSuchMethodError: VectorException.failureKind()`；单独运行 Vision 37 与 Rubrics 12 均通过。该异常来自本计划未修改的 Vector API/Memory 调用方，不应通过本计划恢复旧 Vector 方法。

- Observation: 精确 compose 预估要求在 decode 前知道操作的几何语义，因此未知 `MultiImageOp` 不能再走近似预算路径。
  Evidence: `PixelFootprintEstimator` 只接受具有共享精确几何实现的 `ComposeGroupOp`，其他 compose op 在读取 raster 前失败；架构测试禁止重新引入旧近似公式。

- Observation: 首轮双轨代码审查发现 `FILL` 的 cover 缩放中间 raster 未被初版 footprint 计入，且未知单图 `ImageOp` 被静默忽略。
  Evidence: 修复后 `ResizeGeometry.fillIntermediate` 同时供 `ResizeOp` 和估算器使用；100×1 输入填充到 10×10 时，中间 raster 为 1000×10、最终加权足迹为 12,725，预算 12,724 在 decode 前拒绝。未知 `ImageOp` 也在 decode 前明确拒绝。

## Decision Log

- Decision: 中等批次只包含 Image、Thirdparty、AI。
  Rationale: Cache、MQ、Storage 的 27/27/33 行文档变化已经由 `small-infrastructure-alignment-refactor-plan.md` 管理；Vector、Auth、Permission 的 305/441/519 行变化包含只读数据模型或安全模型重写，应独立规划。
  Date/Author: 2026-07-17 / Codex

- Decision: 以现有已实现机制为起点，先补能暴露真实偏差的测试，再重构共享计算与配置，不复制第二套实现。
  Rationale: Pixel Budget 和 provider admission 已通过现有测试。重复引入平行接口会增加漂移，且不能改善可观察行为。
  Date/Author: 2026-07-17 / Codex

- Decision: AI quota 配置一次性从 `pixflow.ai.quotas` 切换为 `pixflow.ai.quota.roles`，直接删除旧字段和旧 YAML，不保留别名或 fallback。
  Rationale: 用户明确要求重构性变更且不为兼容保留旧代码；仓库仍在开发阶段，双绑定只会产生两个配置真相源。
  Date/Author: 2026-07-17 / Codex

- Decision: Image 的实际执行与预算估算必须复用同一个 resize/compose 几何计算器，而不是分别维护近似公式。
  Rationale: 准入公式与操作公式分开演进已经造成单边 resize 和 gap 低估。共享纯函数可以用同一组参数测试同时证明“估算尺寸等于实际分配尺寸”。
  Date/Author: 2026-07-17 / Codex

- Decision: Pixel Budget 继续是 JVM 单例、进程内内存保护，不迁移到 Redis，也不与 task 线程池合并。
  Rationale: 线程池控制并行工作数量，Pixel Budget 控制活跃 raster 的内存足迹；跨实例 Redis 不知道每个 JVM 的堆容量。
  Date/Author: 2026-07-17 / Codex

- Decision: Thirdparty 保持直接依赖 Cache；AI 保持通过自有 SPI 在 App 组合根适配 Cache。二者不抽成共同的业务 admission 模块。
  Rationale: Thirdparty 的依赖图允许直连 Cache，而 AI 的反向约束要求只依赖 Common。共享的是 Redis 通用原语，不是 provider 业务接口。
  Date/Author: 2026-07-17 / Codex

- Decision: quota 指标只使用 role/provider 或 provider/api/result 低基数标签，不记录 taskId、imageId、conversationId、quotaGroup、remaining 或用户标识。
  Rationale: 指标要区分 allowed/rejected/error，但不能让动态业务标识造成时间序列爆炸；remaining 可按采样日志记录，不做 tag。
  Date/Author: 2026-07-17 / Codex

- Decision: Maven reactor 串行执行，配置迁移与所有直接调用方、测试必须原子提交。
  Rationale: 活动 Lint 计划已经记录并行 reactor 会争用 `target/spotbugsTemp.xml`；配置旧字段与新字段不能在任一提交中同时被生产代码接受。
  Date/Author: 2026-07-17 / Codex

## Outcomes & Retrospective

三个模块已经完成一次性重构。Image 把实际执行与准入统一到纯几何计算：4×4 输入按单边约束放大到 8×8 时，输入 16 加输出 64、乘 resize 权重后总足迹为 96；预算 95 在 decode 前拒绝。两个 4×4 输入按横向 gap=2 合成 10×4 画布时，总足迹为 122；预算 121 在任何 decode 前拒绝。旧 `targetPixels` 与 `composeUpperBound` 均已删除。

Thirdparty 产生 `pixflow.thirdparty.quota{provider,api,result}`，AI 产生 `pixflow.ai.quota{role,provider,result}`；`result` 仅为 `allowed`、`rejected`、`error`。Thirdparty 本地拒绝保留三秒 `retryAfter` 且 HTTP 调用数、CircuitBreaker failure count 均为零；Redis 异常映射为 `THIRDPARTY_QUOTA_UNAVAILABLE`。AI 启动时校验五个默认 role 及 Rubrics judge role 的 quota，缺少任一 quota、`GlobalConcurrencyLimiter` 或 `ModelQuotaLimiter` 都使上下文启动失败。App Redis adapter 测试证明同 provider/group 共用 key、不同 group 隔离。

最终验证结果：首轮实现的模块、App adapter 与直接消费者验证均通过；双轨审查后又补齐 FILL 临时 buffer、未知 op、vertical/grid/overflow、部分 role 绑定、provider 429/5xx 熔断与 quota 配置绑定合同。2026-07-17 14:55+08:00 最终运行 `mvn -pl pixflow-infra-image,pixflow-infra-thirdparty,pixflow-infra-ai -am test`，Common 23、Cache 20、AI 35、Image 32、Thirdparty 17，全部零失败、零跳过。`RedisModelQuotaLimiterTest` 在完整 App 依赖编译下通过。最终消费者复验中 Imagegen、DAG、Vision 37 与 Rubrics 12 通过；Memory 被并行 Vector 重构产生的 `VectorException.failureKind()` 二进制/API 失配阻断，不属于本计划。2026-07-17 14:53+08:00 最终统一运行 `mvn -pl pixflow-infra-image,pixflow-infra-thirdparty,pixflow-infra-ai -am -DskipTests verify`，六个 reactor 模块 Checkstyle 与 SpotBugs 均为零问题。

负向搜索没有发现生产代码中的 `targetPixels`、`composeUpperBound`、`new Retry(10` 或旧 `pixflow.ai.quotas`。旧配置词只保留在一个负向绑定测试中，用来证明它不能满足启动配置，而不是兼容入口。完整 reactor 的剩余失败来自上述 Context 与并行 Permission/Tools 改动，不属于本计划范围；因此没有为了获得全绿而改动无关模块。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。`pixflow-infra-image` 是无网络、无存储、无业务语义的本地像素计算模块；`pixflow-infra-thirdparty` 封装非模型外部 HTTP 服务，目前主要是背景去除；`pixflow-infra-ai` 封装 chat、vision、生图、embedding 和 rerank 的模型调用。`pixflow-app` 是组合根，负责把 AI 自有 admission SPI 接到 Cache 的 Redis 实现。

开始实施前必须重新阅读当时 `docs/design-docs/exec-plans/` 下全部活动计划、`docs/design-docs/infra/image.md`、`thirdparty.md`、`ai.md`、`docs/design-docs/design.md` 和 `CONTEXT-MAP.md`。Image 还要阅读 `pixflow-infra-image/CONTEXT.md` 与 `completed/execution-domain-refactor-plan.md` 的 Pixel Budget 段落；出站治理还要阅读 `completed/outbound-provider-admission-resilience-plan.md`。completed 计划只提供已实施事实和测试参考，当前设计文档仍是目标规范。

本计划使用以下术语。Raster 是解码后位于 JVM 堆或相关原生内存中的像素矩阵。Pixel Budget 是单 JVM 所有图片 worker 共享的加权许可池，许可在 probe 后、decode 前取得。Footprint 是某次流水线同时存活的源图、目标图和合成画布的保守像素上界。Provider Attempt 是一次真正准备发出外部 HTTP 请求的尝试；retry 产生的新尝试必须重新取得许可并消费令牌。Local Rate Limit 是 PixFlow 自己的 Redis 桶拒绝，不是供应商返回的 HTTP 429。Fail-closed 表示 Redis/Lua 或必要配置不可用时拒绝出站，而不是无限制放行。

依赖方向固定为：Image 只依赖 Common 和图像库；Thirdparty 依赖 Common、Cache、Resilience4j 与 HTTP client；AI 只依赖 Common 和模型/HTTP 库；App 同时依赖 AI 与 Cache 并提供 `GlobalConcurrencyLimiter`、`ModelQuotaLimiter`。DAG 与 Vision 可以消费 Image；DAG 消费 Thirdparty；Loop、Vision、Imagegen、Memory、Rubrics 消费 AI。三个 infra 模块不能反向依赖这些 harness/module/agent 消费方。

## Scope and Non-Goals

范围包括 `pixflow-infra-image` 的几何估算、流水线准入和测试；`pixflow-infra-thirdparty` 的 quota 指标、admission 错误边界和测试；`pixflow-infra-ai` 的 quota 配置模型、默认 retry、quota 指标、fail-fast 装配和测试；`pixflow-app` 中 AI Redis adapters、开发配置和必要的组合根测试；保持编译所需的直接消费者测试适配。

范围不包括 Cache token bucket 算法重写、Redis key 格式迁移、Task/MQ 重投、Storage publication、Vector 只读化、Memory 写路径删除、Auth/Permission、模型智能路由、真实 token/账单计费、用户级配额、图片算法功能扩展、外部供应商真实凭证测试或无关 lint 清理。Image 已对齐的 `RasterImage` 生命周期不重写；Thirdparty/AI 已对齐的 retry 所有权不搬层。

## Mechanism and Approach

Image 采用“先投影、后准入、再分配”。`ReopenableImageSource` 先打开 probe 流得到格式和原始宽高；纯函数根据 `ResizeSpec`、`ComposeSpec` 和操作顺序投影每一步尺寸，使用 `Math.multiplyExact`/`addExact` 计算峰值 footprint；超过可表示范围按 `SOURCE_TOO_LARGE` 拒绝。只有 `PixelBudget.acquire` 成功后才打开 decode 流。实际 `ResizeOp` 和 `ComposeGroupOp` 使用同一几何函数决定目标宽高，保证准入公式不会与真实 buffer 分配漂移。permit 覆盖 decode、全部操作、compose 和 encode，并由 try-with-resources 在成功、异常和中断时释放。

Thirdparty 保持 `Retry(CircuitBreaker(Bulkhead(attempt)))`。每个 attempt 内按 `DistributedSemaphore -> DistributedTokenBucket -> HTTP` 执行；semaphore 获取失败不会扣额度，桶拒绝释放许可且不发 HTTP，HTTP 一旦开始就不返还令牌。`THIRDPARTY_LOCAL_RATE_LIMITED`、`THIRDPARTY_QUOTA_UNAVAILABLE` 与 provider `THIRDPARTY_RATE_LIMITED` 保持不同错误码。本地桶拒绝、Redis 故障、semaphore/bulkhead 拒绝和 provider 429 不进入 CircuitBreaker 失败率，网络、超时和 5xx 才计入。

AI 保持 `ModelRetryRunner` 为唯一 retry owner。每个 client 把 `ConcurrencyGuard.acquire -> ModelQuotaGuard.tryConsume -> provider HTTP` 放在 attempt supplier 内，因此透明重试和 AttemptReset 后的新 attempt 都重新准入，退避期间不持有 permit。新 `AiProperties.QuotaSettings.roles` 是唯一 quota 配置真相源；生产装配在启动时验证所有已启用 ModelRole 都有 quota，缺失就失败。quota 正常拒绝携带 `retryAfter`，Redis adapter 异常转 `MODEL_QUOTA_UNAVAILABLE`，两者都由 runner 按 RETRY 控制，但指标 result 分别为 rejected/error。

## Plan of Work

### Milestone 0：冻结基线、文件所有权并先写失败合同

先运行 `git status --short`、最新提交 numstat、三个模块定向测试和负向搜索，保存本文 Artifacts 中的基线。确认 `application-dev.yml` 与 `config/checkstyle/suppressions.xml` 当前由哪个活动计划修改；如果小型基础设施或 Lint 工作尚未提交，实施者必须在现有内容上做最小补丁，不能整文件替换。

新增会在当前实现上失败的窄测试。Image 测试覆盖单边允许放大的 resize、带大 gap 的 horizontal/vertical/grid compose、算术溢出、预算不足时 decode=0。Thirdparty 测试覆盖本地桶拒绝、Cache 异常、provider 429 与 CircuitBreaker 计数。AI 测试覆盖默认重试数、旧/新配置形状、缺失 role quota、quota 三态指标和缺少 admission adapter 的启动失败。每类红测试单独运行并记录失败原因，随后才进入生产修改。

### Milestone 1：让 Image 准入与实际几何共用唯一算法

在 `pixflow-infra-image/src/main/java/com/pixflow/infra/image/op/` 下提取一个包内纯几何组件，例如 `ResizeGeometry`，输入源宽高和 `ResizeSpec`，返回目标宽高。把 `ResizeOp.dimensions` 的现有公式迁入该组件，直接删除 `ResizeOp` 内的旧私有实现。单边尺寸按源宽高比推导，`upscale=false` 时和实际执行一样夹到源尺寸；`FIT`、`FILL`、`EXACT` 的画布尺寸必须与实际分配一致。

为 compose 提取等价的 `ComposeGeometry`，输入各成员投影后的尺寸和 `ComposeSpec`，按 horizontal、vertical、grid 精确计算画布并计入 gap。给 `ComposeGroupOp` 增加只服务模块内部投影的 `spec()`，或让它直接委托几何组件；不增加兼容接口。所有维度先用 long 和 checked arithmetic 计算，确认不超过 Java `BufferedImage` 可接受范围后才转 int。

在 `DefaultImagePipeline` 中删除当前 `targetPixels` 与 `members × maxMemberPixels` 近似分支，改为一个内部 `PixelFootprintEstimator`。单图按操作顺序投影尺寸；组链先投影每个成员的 per-member ops，再用 compose 几何得到画布，然后投影 post ops。预算至少覆盖同时存活的输入集合、当前操作目标和配置 headroom；估算宁可保守高估，不得低于实际分配。对于未知自定义 `ImageOp`/`MultiImageOp`，采用明确的保守 fallback 或拒绝不可估算操作，不能静默按源尺寸放行。

完成时现有 `ImagePipeline`、`PixelBudget`、`ReopenableImageSource` 公共签名保持不变，旧近似算法已删除。运行 Image 及 DAG/Vision 直接消费者测试，证明 probe-before-decode、单例预算、引用生命周期和现有像素结果没有回归。

### Milestone 2：补齐 Thirdparty quota 可观测与失败边界

在 `ThirdPartyMetrics` 增加 `recordQuota(provider, api, result)`，只接受 `allowed`、`rejected`、`error` 三种稳定结果并注册 `pixflow.thirdparty.quota` counter。将 `ThirdPartyMetrics` 作为 `ThirdPartyCallTemplate` 必需依赖，在 token bucket 允许、正常拒绝和抛出 Cache/Redis 异常三个出口各记录一次。直接修改唯一构造器并原子迁移自动配置与测试，不保留无 metrics 的旧构造器。

扩展 `ThirdPartyCallTemplateTest`：第一次 5xx、第二次成功时 HTTP/permit/token 各为 2；retry delay 期间 permit 为 0；本地桶拒绝不调用 action、释放 permit、保留 retryAfter 并记录 rejected；CacheException 不调用 action、fail-closed 为 `THIRDPARTY_QUOTA_UNAVAILABLE` 并记录 error。扩展 `ThirdPartyResilienceRegistry` 测试，证明本地拒绝、provider 429、quota unavailable 与 bulkhead/semaphore admission 不增加 CircuitBreaker failure count，而 5xx/timeout 增加。

为 `ThirdPartyProperties` 增加配置绑定测试，验证 `outbound-quota.{provider}.{api}` 的 capacity/refill/idleTTL/cost，并验证缺失 policy 在出站前终止。保留当前 `pixflow.thirdparty.*` 配置形状；本计划只删除任何残留 Resilience4j RateLimiter 旧代码或测试，不改变静态 provider 路由。

### Milestone 3：一次性收敛 AI 配置、默认重试、指标和装配

重构 `AiProperties`：用 `QuotaSettings quota` 替换 `Map<ModelRole, Quota> quotas`，`QuotaSettings` 只含不可变的 `roles` map，`quota(ModelRole)` 只从 `quota.roles` 读取。把 `application-dev.yml` 的 `pixflow.ai.quotas` 原子改为 `pixflow.ai.quota.roles`。删除旧 record component、旧 getter、旧 YAML 和所有旧测试输入，不使用 Spring alias 或自定义兼容 binder。负向搜索必须证明生产范围中不再出现旧配置形状。

把 `AiProperties` 的默认 `maxRetries` 从 10 改为 3，并新增 Java 默认值与 Spring binding 测试。配置校验在应用启动时枚举已配置的角色：基础五个角色和任何非空 Rubrics judge 角色都必须有 quota；缺少时以 `MODEL_CONFIGURATION_ERROR` 失败，不能推迟到首个线上调用，也不能注入无限制 noop。

在 `AiMetrics` 增加 `recordQuota(role, provider, result)` 和 `pixflow.ai.quota` counter，把 metrics 注入 `ModelQuotaGuard` 的唯一构造器。allowed、rejected、error 各记录一次；不把 quotaGroup、remaining 或业务 ID 放入 tag。修改所有 client/auto-configuration/test 构造点，不保留旧构造器。

新增 `ModelQuotaGuardTest`、`RedisModelQuotaLimiterTest` 和 App admission context test。证明允许时继续、拒绝时不发 provider HTTP 且携带 retryAfter、Redis 异常 fail-closed、同一 provider/quotaGroup 生成同一 key、不同 group 隔离；缺少 `GlobalConcurrencyLimiter` 或 `ModelQuotaLimiter` 任一个 Bean 时 AI 生产上下文启动失败。测试 profile 若要不限流，只能显式注册 test fake，生产 main 不新增 noop。

补全 `ModelRetryRunnerTest`，覆盖纯工具轮透明重试、不可重试错误、CONTEXT_LIMIT 不在 runner 重试、耗尽后上抛最后一个归一化错误、`Retry-After` 优先、blocking client retry。用 client 级计数桩证明每个真实 attempt 都重新取/释 permit 和消费 quota，退避期间没有许可。保持 `AttemptReset` SSE 事件形状不变，不把 retry 上移到 Loop 或 Agent。

### Milestone 4：依赖守护、组合验证与总计划交接

为 Image 与 AI 增加可维护的依赖守护：Image 禁止 storage/cache/thirdparty/ai/harness/module/agent；AI 禁止 cache/storage/vector/harness/module/agent；Thirdparty 只允许 Common、Cache 与外部库，不依赖 DAG。可以使用 Maven Enforcer 或已有 ArchUnit 风格，但不要为了守护引入一个新的全仓测试框架。

先分别运行三个模块测试和严格 verify，再运行 DAG/Vision、App、Loop/Imagegen/Memory/Rubrics 等直接消费者，最后串行跑全仓 reactor。搜索旧 AI config、近似 footprint 方法、Resilience4j RateLimiter 和生产 noop；结果必须无命中或只命中 completed 历史文档。更新后端总计划中对应基础设施前置条件，记录本计划已经完成且没有触碰 Vector/Auth/Permission。

## Tiny Commit Sequence

建议提交顺序为 M0 红测试与基线说明，I1 提取 resize/compose 几何并原子迁移操作，I2 替换 footprint estimator 并补准入测试，T1 增加 Thirdparty quota metrics 并迁移唯一构造器，T2 补 admission/circuit/config 合同，A1 一次性切换 `quota.roles` 与默认 retry，A2 增加 AI quota metrics 并迁移唯一构造器，A3 补 fail-fast/App/retry 合同，G1 增加依赖守护与组合验证。I1、T1、A1、A2 每个提交都必须编译；A1 不得拆成“先同时支持新旧配置、以后删除旧配置”。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。开始和每个停止点运行：

    git status --short
    git diff --check

复现模块分档：

    git show -s --format=fuller 0faa171
    git diff 0faa171^ 0faa171 --numstat -- docs/design-docs/infra

Image 搜索与验证：

    rg -n "targetPixels|composeUpperBound|ResizeGeometry|ComposeGeometry|PixelFootprintEstimator|ReopenableImageSource|PixelBudget" pixflow-infra-image pixflow-module-dag pixflow-module-vision
    mvn -pl pixflow-infra-image -am test
    mvn -pl pixflow-module-dag,pixflow-module-vision -am test
    mvn -pl pixflow-infra-image -am -DskipTests verify

完成后 `targetPixels` 和旧 `composeUpperBound` 近似实现应无命中；几何与 footprint 关键词应只命中唯一实现及其测试。

Thirdparty 搜索与验证：

    rg -n "ThirdPartyCallTemplate|DistributedSemaphore|DistributedTokenBucket|THIRDPARTY_(LOCAL_RATE_LIMITED|QUOTA_UNAVAILABLE|RATE_LIMITED)|pixflow\.thirdparty\.quota|RateLimiter" pixflow-infra-thirdparty pixflow-app/src/main/resources
    mvn -pl pixflow-infra-thirdparty -am test
    mvn -pl pixflow-infra-thirdparty -am -DskipTests verify

生产源码中 `RateLimiter` 预期无输出；历史 completed 文档中的命中不算生产残留。

AI 搜索与验证：

    rg -n "pixflow\.ai\.quotas|\bquotas\b|quota\.roles|ModelQuotaLimiter|ModelQuotaGuard|pixflow\.ai\.quota|maxRetries|new Retry\(10" pixflow-infra-ai pixflow-app
    mvn -pl pixflow-infra-ai -am test
    mvn -pl pixflow-app -am test
    mvn -pl pixflow-infra-ai,pixflow-app -am -DskipTests verify

完成后旧 `pixflow.ai.quotas`、旧 record component 和 `new Retry(10` 预期无输出；`quota.roles`、两个 admission SPI 和 quota metric 必须有生产与测试命中。

直接消费者与全仓验证串行运行：

    mvn -pl pixflow-module-dag,pixflow-module-vision,pixflow-module-imagegen,pixflow-module-memory,pixflow-module-rubrics,pixflow-loop,pixflow-app -am test
    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

若 Docker 不可用，只允许记录明确跳过的 Redis Testcontainers 测试；纯单测、配置绑定和 `-DskipTests verify` 仍必须运行。不得把真实 Redis 合同替换成 mock 后声称跨实例 quota 已验证。

## Validation and Acceptance

Image 验收要求单边 resize 和带 gap compose 的投影尺寸与实际 `BufferedImage` 尺寸一致；预算小于 footprint 时所有 source 的 decode 调用数为零；预算许可在成功、操作异常、encode 异常和线程中断后都可再次完整取得；组链必须在任何成员 decode 前完成全部 probe 与一次性准入。现有 alpha、EXIF、WebP、操作结果和 decode-once/encode-once 测试继续通过。

Thirdparty 验收要求一次 5xx 后成功的逻辑调用恰有两次 HTTP、两次 token consumption 和两次 permit；retry delay 期间 permit 为零。本地桶拒绝、provider 429、Redis 故障使用三个不同错误码；前两者保留各自 retryAfter；本地拒绝和 Redis 故障不发 HTTP。`pixflow.thirdparty.quota{provider,api,result}` 对每个 attempt 只增加一次且无高基数 tag。

AI 验收要求唯一配置路径是 `pixflow.ai.quota.roles`，旧路径无法绑定成有效生产配置；默认 maxRetries=3；每个已启用角色缺少 quota 时启动失败。删除任一 admission adapter 时生产上下文失败；显式 test fake 时可启动。模型每个真实 attempt 都重新取得 permit、消费 quota，退避不持许可；`MODEL_LOCAL_RATE_LIMITED` 与 `MODEL_QUOTA_UNAVAILABLE` 分开；`pixflow.ai.quota{role,provider,result}` 三态可查询且不含高基数 tag。流式首次发射前透明重试、首次发射后 AttemptReset、CONTEXT_LIMIT 外抛语义保持不变。

组合验收要求 Image、AI 仍只依赖 Common，Thirdparty 只额外依赖 Cache；DAG/Vision/Loop/Imagegen/Memory/Rubrics/App 的直接消费者通过；全仓严格 lint 和测试通过，或把与本计划无关的既有失败以测试名、堆栈首个业务帧和复现命令记录在 `Surprises & Discoveries`，不得误报成功。

## Idempotence and Recovery

搜索、测试、verify 与配置绑定检查可重复运行。Pixel Budget 测试只使用内存桩，不留文件；Redis quota 测试使用唯一命名空间或测试结束清理 key。指标测试使用新的 `SimpleMeterRegistry`，不得依赖前一测试的 counter。

配置迁移的恢复单位是 `AiProperties + application-dev.yml + 所有配置测试/调用点`。若 A1 中途停止，应继续完成同一原子迁移，不通过恢复 `quotas` alias 让编译暂时变绿。构造器迁移的恢复单位是目标类、自动配置和全部测试调用点；不增加临时重载。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。当前工作树中的 Common、Cache、MQ、Storage、File、Vector、Vision、Lint 和 completed-plan 移动都不属于本计划。若触达 `application-dev.yml`，只修改 AI quota 节点并保留 MQ/Storage 等现有未提交内容。

## Quick Reference Search Keywords

判断为什么选择这三个模块，搜索 `git diff 0faa171^ 0faa171 --numstat -- docs/design-docs/infra`、`Image 67`、`Thirdparty 98`、`AI 123`。定位已实施历史，搜索 `completed/execution-domain-refactor-plan.md` 中的 `PixelBudget`、`ReopenableImageSource`、`retain/close`，以及 `completed/outbound-provider-admission-resilience-plan.md` 中的 `DistributedTokenBucket`、`每 attempt`、`ModelQuotaLimiter`。

Image 机制与缺口：搜索 `DefaultImagePipeline.targetPixels`、`composeUpperBound`、`ResizeSpec`、`ResizeOp.dimensions`、`ComposeSpec.gap`、`ComposeGroupOp`、`PixelBudget.acquire`、`PIXEL_BUDGET_EXCEEDED`、`PIXEL_BUDGET_TIMEOUT`、`probe-before-decode`、`RasterImage.retain`、`borrowBuffer`。实施后快速定位关键词是 `ResizeGeometry`、`ComposeGeometry`、`PixelFootprintEstimator`。

Thirdparty 机制与缺口：搜索 `ThirdPartyCallTemplate.executeAttempt`、`Retry.decorateSupplier`、`CircuitBreaker.decorateSupplier`、`DistributedSemaphore`、`DistributedTokenBucket`、`THIRDPARTY_LOCAL_RATE_LIMITED`、`THIRDPARTY_QUOTA_UNAVAILABLE`、`isAdmissionRejection`、`retryAfter`、`ThirdPartyMetrics`、`pixflow.thirdparty.quota`。

AI 机制与缺口：搜索 `AiProperties.Quota`、`quotas`、`quota.roles`、`new Retry(10`、`ModelRetryRunner`、`ModelQuotaGuard`、`GlobalConcurrencyLimiter`、`ModelQuotaLimiter`、`RedisModelQuotaLimiter`、`MODEL_LOCAL_RATE_LIMITED`、`MODEL_QUOTA_UNAVAILABLE`、`AttemptReset`、`RUBRICS_JUDGE_TEXT`、`RUBRICS_JUDGE_VISION`、`pixflow.ai.quota`。

建议直接使用：

    rg -n "targetPixels|composeUpperBound|ResizeGeometry|ComposeGeometry|PixelFootprintEstimator"
    rg -n "ThirdPartyCallTemplate|THIRDPARTY_(LOCAL_RATE_LIMITED|QUOTA_UNAVAILABLE)|pixflow\.thirdparty\.quota"
    rg -n "pixflow\.ai\.quotas|quota\.roles|new Retry\(10|ModelQuotaGuard|pixflow\.ai\.quota"
    rg -n "AttemptReset|CONTEXT_LIMIT|RetryAfter|retryAfter"

## Artifacts and Notes

对比基线：

    HEAD  0faa171 docs: align architecture, ADRs, and execution plans
    BASE  b620027 feat(app): expose task assets and progress events

    image.md       50 insertions, 17 deletions   = 67 total
    thirdparty.md  64 insertions, 34 deletions   = 98 total
    ai.md          90 insertions, 33 deletions   = 123 total

2026-07-17 测试基线：

    mvn -pl pixflow-infra-image,pixflow-infra-thirdparty,pixflow-infra-ai -am test
    Common      23 passed
    Cache       20 passed
    AI          26 passed
    Image       25 passed
    Thirdparty  13 passed
    BUILD SUCCESS, 0 failures, 0 errors, 0 skipped

当前关键差距：

    Image       one-sided resize is not projected; compose gap is not in admission estimate
    Thirdparty  quota decision exists; quota metric and fail-closed/circuit tests are incomplete
    AI          default maxRetries=10; config uses quotas; quota metric/fail-fast tests are incomplete

## Interfaces and Dependencies

Image 的公共接口保持：

    public interface PixelBudget {
        Permit acquire(long weightedPixels, Duration timeout);
        interface Permit extends AutoCloseable {
            long weightedPixels();
            void close();
        }
    }

    public interface ReopenableImageSource {
        InputStream openStream();
    }

    public interface ImagePipeline {
        byte[] run(ReopenableImageSource source, List<ImageOp> ops, EncodeSpec encode);
        byte[] runComposed(List<ReopenableImageSource> members,
                           List<ImageOp> perMemberOps,
                           MultiImageOp compose,
                           List<ImageOp> postOps,
                           EncodeSpec encode);
    }

模块内部必须有唯一、语义等价的纯几何/足迹接口；名称可按仓库风格调整，但不得保留旧近似实现：

    record RasterDimensions(long width, long height) {
        long pixels();
    }

    final class ResizeGeometry {
        static RasterDimensions resolve(RasterDimensions source, ResizeSpec spec);
    }

    final class ComposeGeometry {
        static RasterDimensions resolve(List<RasterDimensions> members, ComposeSpec spec);
    }

    final class PixelFootprintEstimator {
        long estimateSingle(ImageProbe source, List<ImageOp> ops);
        long estimateComposed(List<ImageProbe> members,
                              List<ImageOp> perMemberOps,
                              MultiImageOp compose,
                              List<ImageOp> postOps);
    }

Thirdparty 不新增业务 SPI。`ThirdPartyCallTemplate` 的唯一构造器增加 `ThirdPartyMetrics` 必需参数，`ThirdPartyMetrics` 增加：

    void recordQuota(String provider, String api, QuotaResult result);

其中 `QuotaResult` 是固定 `ALLOWED/REJECTED/ERROR` 枚举或等价受控类型，不能接受任意字符串。Thirdparty 继续直接依赖 Cache 的 `DistributedSemaphore` 与 `DistributedTokenBucket`。

AI 的 quota 配置最终只有：

    public record AiProperties(
            String defaultProvider,
            DashScope dashscope,
            Map<String, ProviderConfig> providers,
            Roles roles,
            Retry retry,
            Duration timeout,
            Concurrency concurrency,
            QuotaSettings quota) {
        public Quota quota(ModelRole role);
    }

    public record QuotaSettings(Map<ModelRole, Quota> roles) {}

YAML 唯一形状是：

    pixflow:
      ai:
        retry:
          max-retries: 3
        quota:
          roles:
            primary-chat: { quota-group: chat, capacity: 60, refill-tokens: 60, refill-period: 1m, idle-ttl: 10m, cost-per-attempt: 1 }

AI 保留两个自有 SPI，不 import Cache 类型：

    public interface GlobalConcurrencyLimiter {
        Permit acquire(ModelRole role, String provider, Duration waitTime);
    }

    public interface ModelQuotaLimiter {
        QuotaDecision tryConsume(ModelRole role, String provider, String quotaGroup, long cost);
    }

`ModelQuotaGuard` 的唯一构造器最终接收 `ModelQuotaLimiter`、`AiProperties`、`AiMetrics`。`AiMetrics` 增加受控三态的 `recordQuota(ModelRole role, String provider, QuotaResult result)`。App 的 `RedisModelQuotaLimiter` 仍是唯一生产 adapter；AI POM 不得新增 Cache 依赖。

## Revision Notes

2026-07-17 / Codex: 创建本计划。依据提交 `0faa171` 的 infra 文档差异，把 Image、Thirdparty、AI 划为中等变更批次。源码与 completed ExecPlan 审计证明三项主体机制已经存在，因此计划不从零重写，而是修复 Image footprint 低估、一次性迁移 AI quota 配置与三次 retry 默认值、补齐 Thirdparty/AI quota 可观测和失败边界。根据用户明确要求，所有迁移均删除旧代码与旧配置，不保留兼容层。

2026-07-17 / Codex: 完成 Milestone 0–4。补记精确 Image 足迹 96/122、两套 quota 三态指标、AI 启动 fail-fast、配置负向搜索、模块/消费者测试与最终统一 lint 结果；记录完整 reactor 被范围外 Context 和并行 Permission/Tools 改动阻断，未扩大本计划范围修复。

2026-07-17 / Codex: 按 `$implement` 要求运行 Standards/Spec 双轨审查并修复全部发现。Image footprint 新增 FILL 中间 raster 与未知单图 op 的 fail-closed；AI 五个基础角色 quota 改为无条件启动校验；补齐 compose 布局/溢出、Thirdparty provider/CB/config 合同。审查修复后重新运行完整模块 reactor 与三个模块统一严格 lint，均通过。
