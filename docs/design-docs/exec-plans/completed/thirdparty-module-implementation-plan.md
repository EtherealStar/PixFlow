# 实现生产级 infra/thirdparty 非模型第三方集成模块

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document must be maintained in accordance with `PLANS.md`.

## Purpose / Big Picture

完成这个模块后，`module/dag` 可以把源图片字节交给统一的 `BackgroundRemovalClient`，不需要知道底层是 remove.bg 还是其他云厂商，也不需要关心请求投影、鉴权、签名、重试、熔断、限流或全局并发封顶。对用户来说，结果是：抠图能力变成一个稳定、可替换、可观测的基础设施服务，而不是一个绑定单一供应商的孤立客户端。

这个计划不是 MVP，也不是“先写一个 remove.bg client”。目标是直接交付完整的生产级 `infra/thirdparty`：供应商无关的能力接口、通用 HTTP 调用内核、provider adapter 路由、鉴权与签名扩展点、Resilience4j 韧性治理、Redis/Redisson 分布式信号量、源头错误归一化、脱敏、指标、配置和测试。

## Progress

- [x] (2026-06-27 21:00+08:00) 阅读 `PLANS.md`，确认 ExecPlan 必须自包含、面向新人、带有 Progress / Surprises & Discoveries / Decision Log / Outcomes & Retrospective。
- [x] (2026-06-27 21:10+08:00) 阅读 `docs/design-docs/design.md`、`docs/design-docs/infra/thirdparty.md`、`docs/design-docs/base/common.md`、`docs/design-docs/infra/cache.md`、`docs/design-docs/infra/image.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`，确认 thirdparty 的正确边界与依赖关系。
- [x] (2026-06-27 21:20+08:00) 识别并纠正了旧草稿里的错误说法：核心不应写成供应商专属 client，`infra/ai` 和 `infra/image` 都不应依赖 `infra/thirdparty`，直接消费者是 `module/dag`。
- [x] (2026-06-27 22:05+08:00) 创建 `pixflow-infra-thirdparty` Maven module，并把它接入根 `pom.xml` 的模块列表与依赖管理。
- [x] (2026-06-27 22:10+08:00) 定义供应商无关的背景去除能力契约、请求、响应、options 和 usage 类型。
- [x] (2026-06-27 22:18+08:00) 定义通用 HTTP 内核、鉴权扩展点、签名扩展点、响应提取器和请求工厂，并补入默认鉴权策略。
- [x] (2026-06-27 22:22+08:00) 实现 provider adapter、路由客户端、Resilience4j 调用模板、分布式信号量接入、错误映射和指标。
- [x] (2026-06-27 22:23+08:00) 补齐配置、自动装配、i18n 文案和测试；`mvn -pl pixflow-infra-thirdparty -am test` 通过，thirdparty 模块 10 个测试全部通过。

## Surprises & Discoveries

- Observation: 当前仓库里关于 thirdparty 的说法并不完全一致，`infra/thirdparty.md` 以 `module/dag` 为直接消费者，而旧的依赖 DAG 计划里还残留了 `ai --> thirdparty` 和 `thirdparty --> tools` 之类的旧边。
  Evidence: `docs/design-docs/infra/thirdparty.md` 的“对其他模块的契约”与“与依赖 DAG 计划的差异说明”段落；`docs/design-docs/exec-plans/module-dependency-dag-plan.md` 的 Wave 1 / Wave 3 描述。

- Observation: `infra/cache.md` 把 `DistributedSemaphore` 定义成通用原语，thirdparty 只需要知道 `sem:thirdparty:{api}` 这样的键语义，不需要自造自己的并发控制实现。
  Evidence: `docs/design-docs/infra/cache.md` 的 `DistributedSemaphore` 和 “与 Resilience4j 的分工” 段落。

- Observation: `infra/image.md` 明确把 `remove_bg` 归给 `infra/thirdparty`，并把 `set_background`、`resize`、`compress`、`watermark`、`convert_format`、`compose_group` 留给 `infra/image`。
  Evidence: `docs/design-docs/infra/image.md` 的“关键边界：像素工具白名单横跨三个 infra 模块”段落。

- Observation: 只运行 `mvn -pl pixflow-infra-thirdparty test` 会因为本地 Maven 仓库里没有 `pixflow-common` 与 `pixflow-infra-cache` 的 SNAPSHOT artifact 而失败；按计划使用 `-am` 后 Maven 会先构建依赖模块。
  Evidence: `mvn -pl pixflow-infra-thirdparty test` 报 `pixflow-common` / `pixflow-infra-cache` missing；`mvn -pl pixflow-infra-thirdparty -am test` 成功。

- Observation: 初始实现里 Resilience4j `RetryConfig` 忽略了所有 `PixFlowException`，会导致 429、5xx、超时等已经归一化为 `RecoveryHint.RETRY` 的错误不再重试。
  Evidence: `ThirdPartyResilienceRegistry` 的旧配置包含 `ignoreExceptions(PixFlowException.class, IllegalArgumentException.class)`；新增 `ThirdPartyCallTemplateTest.retriesNormalizedRetryablePixFlowException` 固化了正确行为。

- Observation: 异步轮询 provider 的 endpoint 与 path 直接字符串相加时会产生 `//submit` 这种双斜杠路径。
  Evidence: 新增 `BackgroundRemovalProviderHttpTest.asyncProviderEncapsulatesSubmitAndPollingBehindSynchronousRemove` 首次运行失败，实际 path 为 `//submit`；修复后该测试通过。

## Decision Log

- Decision: 以 `docs/design-docs/infra/thirdparty.md` 作为 thirdparty 的主设计依据，计划文档中的术语全部围绕“能力接口 + provider adapter + 通用 HTTP 内核”展开，不再使用“核心是某厂商专用 client”这种说法。
  Rationale: 这样才能把供应商差异限制在 adapter 内部，保证后续增加新厂商时只新增实现，不改调用方与能力契约。
  Date/Author: 2026-06-27 / Codex

- Decision: `BackgroundRemovalClient.remove(request)` 采用同步语义，对上层始终返回 `BackgroundRemovalResult`；如果某个 provider 内部是“提交任务 + 轮询”，轮询封装在 adapter 内部。
  Rationale: `module/dag` 的工作单元天然是同步执行路径，同步接口最容易和失败隔离、进度统计和本地图片流水线缝合。
  Date/Author: 2026-06-27 / Codex

- Decision: 全局并发封顶由 `infra/cache` 的 `DistributedSemaphore` 负责，并且要包住整个含重试的调用，而不是每次 retry attempt 单独 acquire/release。
  Rationale: 这与 `infra/cache.md` 的原语边界和 `infra/thirdparty.md` 的调用管线一致，代价是重试退避期间会占用许可，但实现更稳定，也更符合“控制第三方总体压力”的目标。
  Date/Author: 2026-06-27 / Codex

- Decision: thirdparty 的配置采用 map 结构，以 provider id 为键，不写死 `aliyun`、`removebg` 这类固定字段到配置类属性名里。
  Rationale: 这样才能表达“当前默认 provider 是谁”与“有哪些 provider 被注册”这两层概念，便于切换和并存。
  Date/Author: 2026-06-27 / Codex

- Decision: 如果依赖 DAG 计划里还有旧边，后续要同步修正文档，但实现计划本身以主设计文档的正确说法为准。
  Rationale: 计划文档不能继续放大旧说法，否则会把实现者带回错误的依赖方向。
  Date/Author: 2026-06-27 / Codex

- Decision: 默认鉴权实现集中在 `DefaultThirdPartyAuthStrategy`，provider 只负责把内部请求投影成 HTTP 请求，然后调用鉴权策略补 header。
  Rationale: 这样避免 remove.bg、通用 HTTP、异步轮询各自手写凭证逻辑，也能保证提交任务和轮询任务两个阶段使用同一鉴权方式。
  Date/Author: 2026-06-27 / Codex

- Decision: Resilience4j 重试判定改为 `PixFlowException.recovery() == RETRY`，普通非归一化运行时异常仍按可重试处理，`IllegalArgumentException` 不重试。
  Rationale: `infra/thirdparty` 在源头构造归一化错误，重试层必须消费这个控制流提示，而不是按异常类型排除所有归一化错误。
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

2026-06-27：已完成 `pixflow-infra-thirdparty` 生产级基础实现。模块形成了供应商无关的 `BackgroundRemovalClient` 能力契约、provider adapter、通用 HTTP 请求/响应内核、默认鉴权策略、Resilience4j 调用模板、Redis/Redisson 分布式信号量接入、错误映射、Micrometer 指标与 Spring Boot 自动装配。remove.bg multipart、通用 JSON/base64、异步“提交任务 + 轮询”三种路径均有 MockWebServer 测试覆盖；路由缺省、错误映射、许可释放、可重试归一化错误重试和空 provider 启动也有测试覆盖。验证命令 `mvn -pl pixflow-infra-thirdparty -am test` 通过；依赖模块中的 Redis Testcontainers 集成测试因本机 Docker 不可用跳过 4 个，这是 cache 模块既有跳过行为，不影响 thirdparty 单元测试结果。

## Context and Orientation

`infra/thirdparty` 是 `infra` 层的基础设施模块，不是业务模块。它的职责只是一件事：把非模型第三方 HTTP 服务封装成稳定的内部能力接口，并通过 provider adapter 支持多个供应商。当前唯一落地能力是背景去除，也就是 `remove_bg`。`module/dag` 是直接消费者，它会拿到图片字节，调用 `BackgroundRemovalClient.remove(...)`，再把结果交给 `infra/image` 做本地像素处理。

这里最重要的概念要先定义清楚：

- “能力接口”是对上层暴露的稳定 API，例如 `BackgroundRemovalClient`。
- “provider”是外部供应商，例如 remove.bg、其他云厂商。
- “provider adapter”是把供应商 API 适配进内部统一契约的实现类。
- “通用 HTTP 内核”是与供应商无关的请求发送、响应读取、鉴权和签名扩展层。
- “韧性治理”是重试、熔断、限流、舱壁和超时这些单实例保护机制。
- “全局并发封顶”是跨实例共享许可，防止同一第三方被打爆。

为了快速定位参考设计文本，后续实施时可以用这些关键词在对应文档里搜索：

- 在 `docs/design-docs/design.md` 中搜索 `去背景`、`第三方韧性`、`9.3 并发保障`、`业务模块划分`、`DAG 确定性执行引擎`。
- 在 `docs/design-docs/infra/thirdparty.md` 中搜索 `供应商路由`、`调用管线：信号量 → Resilience4j → HTTP → 错误映射`、`韧性治理（Resilience4j）`、`全局并发封顶（infra/cache 信号量）`、`错误归一化与源头构造`、`对其他模块的契约`、`测试策略`。
- 在 `docs/design-docs/base/common.md` 中搜索 `ErrorCategory`、`RecoveryHint`、`PixFlowException`、`ErrorNormalizer`、`Sanitizer`、`infra 异常收口策略`。
- 在 `docs/design-docs/infra/cache.md` 中搜索 `DistributedSemaphore`、`RPermitExpirableSemaphore`、`与 Resilience4j 的分工`、`sem:thirdparty`、`降级分级策略`。
- 在 `docs/design-docs/infra/image.md` 中搜索 `关键边界：像素工具白名单横跨三个 infra 模块`、`remove_bg`、`compose_group`、`解码一次 / 编码一次流水线`。
- 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索 `infra/thirdparty`、`Wave 1`、`thirdparty --> dag`。

`infra/thirdparty.md` 是这次实现的主设计文档，`base/common.md` 和 `infra/cache.md` 给出错误模型和并发原语，`infra/image.md` 说明 `remove_bg` 不归 image，`module-dependency-dag-plan.md` 负责解释模块波次和依赖方向。实现时要始终以这些文档的正确说法为准，而不是沿用旧草稿里的供应商专用 client 命名。

## Plan of Work

先把模块骨架建起来。新增 `pixflow-infra-thirdparty` Maven module，并补进根 `pom.xml`。这个模块只依赖 `pixflow-common` 和 `pixflow-infra-cache`，再加 Spring Web、Actuator、Resilience4j、Micrometer 和用于 HTTP 桩测试的 MockWebServer 或 WireMock。这个阶段不引入 `infra/image`、`infra/ai`、`infra/storage`、任何 `module/*` 或 `harness/*`，保持依赖方向单向。

随后定义稳定的背景去除能力契约。新建 `com.pixflow.infra.thirdparty.bgremoval` 包，放入 `BackgroundRemovalClient`、`BackgroundRemovalRequest`、`BackgroundRemovalResult`、`BackgroundRemovalOptions`、`BackgroundRemovalOutputFormat` 和 `ThirdPartyUsage`。这些类型必须是不可变的、供应商无关的，只表达“输入图片、期望输出、结果图片、供应商用量”。

接着定义 provider SPI 和路由层。`BackgroundRemovalProvider` 负责描述某个 provider 的能力实现，`RoutingBackgroundRemovalClient` 则根据配置从 provider 列表里选择当前默认 provider。这个路由层是调用方看到的唯一入口，找不到 provider、provider 被禁用或能力不支持时，要以结构化错误返回，而不是让调用方直接面对某个厂商 SDK。

然后实现通用 HTTP 内核。这个内核要把请求发送、响应读取、请求构造、响应提取、简单鉴权和签名拆开，避免把厂商细节写进核心。provider adapter 只能组合这些原语，不应该反向复制一套自己的 HTTP 调用逻辑。remove.bg 适配器只做 multipart 投影；通用云厂商适配器只做可配置的 method、endpoint、headers、body mode 和 response mode；异步适配器负责把“提交任务 + 轮询 + 取结果”封装成一个同步 `remove`。

再实现韧性、错误和指标。`ThirdPartyCallTemplate` 负责把分布式信号量、Resilience4j、HTTP 调用和错误映射串成一条统一调用链。`ThirdPartyErrorMapper` 负责把 HTTP 状态码、超时、限流、熔断、响应体异常和 provider 业务错误转成 `PixFlowException`。`ThirdPartyMetrics` 负责低基数指标，不记录任务 id、图片 id、URL 或密钥。

最后补自动装配和配置。`ThirdPartyProperties` 用 `pixflow.thirdparty` 前缀承载能力级配置、provider 注册表、默认 provider、鉴权、HTTP 参数、轮询参数和 resilience 参数。`ThirdPartyAutoConfiguration` 负责把 `RestClient`、HTTP 内核、provider adapter、resilience 组件、路由 client 和指标装配到 Spring 容器里。没有任何 provider 配置时，应用仍应能启动，只有在真正调用 `BackgroundRemovalClient.remove` 时才报“未配置 provider”。

实现结束后，再把测试补齐。测试要覆盖请求投影、响应提取、路由选择、错误映射、重试、熔断、限流、超时、许可释放，以及配置装配。真实外部 API 冒烟测试要默认跳过，只在显式环境变量打开时运行，避免 CI 依赖外部服务。

## Concrete Steps

先在仓库根目录检查当前状态：

    git status --short

然后按下面顺序实施，每一步都应可重复执行。

1. 新建 `pixflow-infra-thirdparty` 模块目录和 `pom.xml`，并同步修改根 `pom.xml` 的模块列表与依赖管理。
2. 新建 `src/main/java/com/pixflow/infra/thirdparty/bgremoval` 及其子包，先把能力接口和路由层落地。
3. 新建 `src/main/java/com/pixflow/infra/thirdparty/http`，实现请求/响应载体、鉴权、签名、响应提取、HTTP 调用内核。
4. 新建 `src/main/java/com/pixflow/infra/thirdparty/resilience` 和 `src/main/java/com/pixflow/infra/thirdparty/error`，把信号量、重试、熔断、限流、错误映射接起来。
5. 新建 `src/main/java/com/pixflow/infra/thirdparty/config` 和 `src/main/java/com/pixflow/infra/thirdparty/observability`，完成配置与指标装配。
6. 新建 `src/test/java/com/pixflow/infra/thirdparty` 下的测试类，先把 MockWebServer 或 WireMock 桩跑通，再补异常和韧性场景。

执行时，关键验证命令应是：

    mvn -pl pixflow-infra-thirdparty test

然后再跑依赖一起构建：

    mvn -pl pixflow-infra-thirdparty -am test

## Validation and Acceptance

完成后，`mvn -pl pixflow-infra-thirdparty test` 应该通过，且能在测试输出里看到类似下面的结果：

    [INFO] Tests run: <N>, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

行为上至少要能证明这些场景：

- 默认 provider 指向 remove.bg 时，MockWebServer 收到 multipart 请求，客户端返回 PNG 字节和 `image/png`。
- 通用云厂商模式可以用 JSON/base64 请求和 JSON/base64 响应正确完成一次背景去除。
- 异步轮询 provider 能完成“提交任务 -> 轮询 -> 取结果”的完整同步封装。
- 429、401/403、5xx、超时、熔断、非法响应都能映射成结构化 `PixFlowException`。
- 任何成功、失败、超时或熔断路径都能释放 `DistributedSemaphore` 许可。
- Spring 容器可以在“没有 provider 配置”的情况下启动，但真正调用时会抛出“未配置 provider”的错误。

如果需要做真实外部 API 冒烟测试，默认必须跳过；只有在显式开启环境变量时才执行。这样可以保证本地开发和 CI 不被供应商凭证阻塞。

## Idempotence and Recovery

这份计划的步骤应当是幂等的。重复运行测试命令不应修改源码或生成需要手工清理的产物。新增模块如果还没有写完，也可以先让 Spring 测试和单测部分通过，再继续补充 adapter 和配置。

如果 Redis 不可用，`DistributedSemaphore.acquire` 应该由 `infra/cache` 的现有语义直接失败，而 thirdparty 不应吞掉这个异常继续打外部请求。这样做是故意的，因为失去全局并发封顶会把第三方压力放大。

如果配置缺失，不应该在应用启动阶段阻断，而应该在真正调用 `BackgroundRemovalClient.remove` 时再报结构化错误。这个恢复策略能保证无凭证环境、空配置环境和纯单测环境都能跑起来。

## Artifacts and Notes

最终实现后，仓库里应至少出现这些结构化产物：

    pixflow-infra-thirdparty/
      pom.xml
      src/main/java/com/pixflow/infra/thirdparty/
        bgremoval/
        config/
        error/
        http/
        observability/
        resilience/
      src/test/java/com/pixflow/infra/thirdparty/

关键调用链应该长成这样：`RoutingBackgroundRemovalClient` 选择 provider，provider 调用 `ThirdPartyCallTemplate`，模板先拿 `DistributedSemaphore` 许可，再进入 Resilience4j，再通过 `ThirdPartyHttpInvoker` 发请求，最后把异常交给 `ThirdPartyErrorMapper`。

不要再回到这些反模式：

    AliyunMattingClient 作为核心抽象
    module/dag 直接依赖某个供应商 SDK
    thirdparty 自己去读 MinIO
    thirdparty 直接调用 infra/image
    provider 代码 catch Exception 后返回 null
    在重试逻辑里按具体厂商异常类做判断

## Interfaces and Dependencies

实现结束时，以下类型必须存在并稳定：

    package com.pixflow.infra.thirdparty.bgremoval;

    public interface BackgroundRemovalClient {
        BackgroundRemovalResult remove(BackgroundRemovalRequest request);
    }

    public record BackgroundRemovalRequest(
            byte[] image,
            String contentType,
            URI sourceUri,
            BackgroundRemovalOptions options) {
    }

    public record BackgroundRemovalResult(
            byte[] image,
            String contentType,
            ThirdPartyUsage usage,
            Map<String, Object> metadata) {
    }

    public record BackgroundRemovalOptions(
            BackgroundRemovalOutputFormat outputFormat,
            boolean crop,
            Integer featherRadius,
            Map<String, Object> providerHints) {
    }

    public enum BackgroundRemovalOutputFormat {
        PNG, WEBP, KEEP_SOURCE
    }

    public record ThirdPartyUsage(
            Integer creditsCharged,
            Map<String, Object> raw) {
    }

    package com.pixflow.infra.thirdparty.bgremoval.provider;

    public interface BackgroundRemovalProvider {
        String providerId();
        BackgroundRemovalResult remove(BackgroundRemovalRequest request);
    }

    package com.pixflow.infra.thirdparty.http;

    public interface ThirdPartyHttpInvoker {
        ThirdPartyHttpResponse exchange(ThirdPartyHttpRequest request);
    }

    public interface ThirdPartyAuthStrategy {
        void apply(ThirdPartyMutableRequest request, ThirdPartyProviderProperties provider);
    }

    public interface ThirdPartyRequestSigner {
        void sign(ThirdPartyMutableRequest request, ThirdPartyProviderProperties provider, Clock clock);
    }

    public interface ThirdPartyResponseExtractor<T> {
        T extract(ThirdPartyHttpResponse response);
    }

    package com.pixflow.infra.thirdparty.resilience;

    public final class ThirdPartyCallTemplate {
        public <T> T execute(ThirdPartyCallContext context, Supplier<T> action);
    }

thirdparty 的直接依赖应保持为 `pixflow-common`、`pixflow-infra-cache`、Spring Web、Actuator、Resilience4j、Micrometer 和测试桩库。它不应依赖 `infra/image`、`infra/ai`、`infra/storage`，也不应反向依赖 `module/*` 或 `harness/*`。如果后续再看到依赖 DAG 计划里的旧边，先以这份计划和 `infra/thirdparty.md` 的正确说法为准，再同步修正文档。
