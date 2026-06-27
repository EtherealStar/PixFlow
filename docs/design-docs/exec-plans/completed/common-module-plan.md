# common 模块实现计划（Wave 0 地基）

本 ExecPlan 必须按照仓库根目录的 [PLANS.md](../../../PLANS.md) 维护；它是活文档，后续在实现、验证、发现新约束时都要同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`。

## Purpose / Big Picture

`common` 是 PixFlow 的最底层契约层。实现完成后，后续的 `permission`、`infra`、`module`、`agent` 都可以依赖同一套错误模型、统一响应信封、分页约束和脱敏规则，而不必在各自模块里重复拼装 HTTP 错误体或各写一套异常翻译逻辑。

从用户视角看，这意味着任何接口出错时，前端都会收到稳定一致的结构化响应；任何流式出口、工具出口或后台消费出口，也都能用同一套归一化错误语义来决定“重试、跳过、终止还是压缩”。这一步不是增加业务功能，而是先把系统的“说话方式”钉牢。

## Progress

- [x] (2026-06-27) 已阅读 `PLANS.md`、`.kiro/specs/pixflow/design.md`、`docs/design-docs/module/common.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`，并完成本计划初稿。
- [x] (2026-06-27) 已在现有根包 `com.pixflow` 下创建 `common/error`、`common/web`、`common/sanitize`、`common/observability`、`common/error/render` 包骨架。
- [x] (2026-06-27) 已实现错误契约与归一化入口，覆盖 `ErrorCategory`、`RecoveryHint`、`ErrorCode`、`CommonErrorCode`、`PixFlowException`、`BusinessException`、`ErrorNormalizer`。
- [x] (2026-06-27) 已实现统一响应信封、分页对象和渲染器，覆盖 `ApiResponse`、`ErrorPayload`、`Pagination`、`PageResponse`、`HttpErrorRenderer`、`ToolErrorRenderer`、`StreamErrorRenderer`。
- [x] (2026-06-27) 已补齐单元测试与契约测试，并通过 `mvn test` 验证。

## Surprises & Discoveries

- 观察：`common` 不是“全局拦截器中心”，而是“归一化契约层”。  
  证据：`docs/design-docs/module/common.md` 中明确把 HTTP、Tool、Stream、MQ 都视作并列出口，而不是把错误全部收敛成一个 `@RestControllerAdvice`。
- 观察：`i18n` 不应该提前侵入核心契约。  
  证据：设计把 `ErrorCode` 只保留 `messageKey`，真正的语言选择留给渲染器，这样 `common` 可以保持 locale 无关。
- 观察：`infra` 的异常收口是分层的，不是单一翻译器一把抓。  
  证据：像 `ImageProcessingException` 这类纯领域异常应保留到边界再翻译；像第三方 `RATE_LIMIT`、`NETWORK`、`PROVIDER` 这类天然带治理语义的错误，可以在源头直接构造成带 category 的归一化异常。
- 观察：仓库现有根包是 `com.pixflow`，不是设计文档里的 `com.pixflow`。  
  证据：当前 `src/main/java/com/pixflow/PixFlowApplication.java` 和后续新增 common 代码都沿用了现有根包，优先保证构建一致性，再等待后续是否需要做全局包名迁移。

## Decision Log

- Decision: `common` 只负责契约、归一化、脱敏和响应封装，不承担任何业务模块逻辑。  
  Rationale: 这样它才能成为 Wave 0 地基，避免后续模块互相耦合到错误实现细节。  
  Date/Author: 2026-06-27 / Codex
- Decision: `messageKey` 留在 `ErrorCode`，具体文案解析放到各出口渲染器。  
  Rationale: 避免 `common` 依赖 locale 和 Web 上下文，同时让 HTTP、SSE、Tool 出口可以按需决定展示方式。  
  Date/Author: 2026-06-27 / Codex
- Decision: `ErrorRecorder` 只定义 SPI，不在 `common` 内实现落盘。  
  Rationale: 让 `common` 保持无上层依赖，观测实现交给后续 `harness/eval` 注入。  
  Date/Author: 2026-06-27 / Codex
- Decision: `ApiResponse<T>` 作为统一成功/失败信封，分页结果嵌套在 `PageResponse<T>` 中。  
  Rationale: 前端和测试都只需要一条解析路径，减少接口返回形状分裂。  
  Date/Author: 2026-06-27 / Codex
- Decision: 边界归一化采用“单一入口 + 允许源头早归一化”的混合策略。  
  Rationale: `ErrorNormalizer` 负责最终收口，但 `infra/thirdparty` 这类已知 category 的错误可以提前带着治理语义出现，避免信息丢失。  
  Date/Author: 2026-06-27 / Codex
- Decision: common 模块先落到仓库现有根包 `com.pixflow`，不立刻做包名大迁移。  
  Rationale: 仓库当前唯一应用入口已在该包下，先确保模块可编译、可测试、可迭代，避免把本次地基工作和全局重构绑定在一起。  
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

完成后，`common` 应当成为整套错误与响应规范的唯一来源。它的价值不是代码量，而是让后续模块在实现时只需要选择“抛什么语义”与“走哪个出口”，不需要重复决定“响应长什么样”。

如果这一步做得好，后续模块的集成成本会明显下降：控制器只返回 `ApiResponse<T>`，列表接口只接收 `Pagination`，异常只通过 `ErrorNormalizer` 和渲染器流转。当前实现已经把这条主线打通，下一步就是在 permission / infra / module 中逐步消费这些契约。若这一步做得不好，最先出现的问题不是编译错误，而是各模块开始各写一套错误体，最后无法稳定对齐前端和测试。

## Context and Orientation

当前仓库已经有设计文档，但还没有可执行的 `src/` 源码树。`common` 的权威设计在 [docs/design-docs/module/common.md](../module/common.md:1)，总架构与波次顺序在 [docs/design-docs/exec-plans/module-dependency-dag-plan.md](module-dependency-dag-plan.md:1)，更大的系统约束在 [.kiro/specs/pixflow/design.md](../../../.kiro/specs/pixflow/design.md:1)。

这里用到的几个术语，先用仓库里的语境解释清楚：

- “归一化”指把任意 `Throwable` 翻译成 `PixFlowException`，并补齐 `code`、`category`、`recovery`、`details`、`traceId` 等字段。
- “渲染器”指把归一化后的错误翻译成某个出口能消费的形态。HTTP 出口输出 `ApiResponse`，Tool 出口输出结构化 tool error，Stream 出口输出错误帧。
- “统一响应信封”指所有 controller 返回同一种外层结构，成功和失败只是同一信封里的不同状态。
- “脱敏”指在对外展示和落盘前，统一遮蔽 token、路径和超长内容。

为了快速定位设计文本，建议直接在对应文档里搜索这些关键词。

在 `docs/design-docs/module/common.md` 里，优先搜：

- `为什么不是「拦截器中心」模型`
- `归一化优先`
- `多出口平权`
- `ErrorCategory`
- `RecoveryHint`
- `ErrorNormalizer`
- `Sanitizer`
- `ApiResponse`
- `Pagination`
- `HttpErrorRenderer`
- `ToolErrorRenderer`
- `StreamErrorRenderer`
- `traceId`
- `错误码目录约定`
- `对其他模块的契约`

在 `.kiro/specs/pixflow/design.md` 里，优先搜：

- `统一错误响应结构`
- `错误处理`
- `错误码`
- `分页`
- `traceId`
- `对其他模块的契约`

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 里，优先搜：

- `Wave 0 地基`
- `common`
- `permission`
- `关键路径`

## Plan of Work

先把 `common` 拆成四个稳定子域，再逐个补齐契约。

第一步是建立 `src/main/java/com/etherealstar/pixflow/common/error`、`.../web`、`.../sanitize`、`.../observability` 这些包。目标不是先堆代码，而是先把边界画清楚：错误模型只放在 `error`，响应体只放在 `web`，脱敏只放在 `sanitize`，SPI 只放在 `observability`。

第二步是实现错误契约。这里的核心文件应当围绕 `ErrorCategory`、`RecoveryHint`、`ErrorCode`、`CommonErrorCode`、`PixFlowException`、`BusinessException`、`ErrorNormalizer` 组织。实现重点不是枚举数量，而是把“分类决定默认恢复策略”“错误码决定 HTTP 状态”“可覆盖但不打散”的关系固定下来。

第三步是实现脱敏与响应封装。`Sanitizer` 负责在对外展示前处理敏感内容；`ApiResponse<T>` 和 `PageResponse<T>` 负责把成功和失败收进统一外壳；`Pagination` 负责在入口处集中校验 page/size。这样列表接口、详情接口和错误接口都能共享同一种返回风格。

第四步是实现三个出口渲染器。`HttpErrorRenderer` 只负责 HTTP 状态码和响应体，`ToolErrorRenderer` 只负责给模型可消费的结构化错误，`StreamErrorRenderer` 只负责错误帧。它们都不重新定义错误，只消费 `PixFlowException`。

更具体一点，建议把实现拆成这些函数名所代表的动作：

- `ErrorNormalizer.normalize(Throwable throwable)`：统一入口，把任意异常翻译成 `PixFlowException`。
- `ErrorNormalizer.normalizeSpring(Exception ex)`：集中处理 Spring 校验、参数绑定、上传超限一类框架异常。
- `ErrorNormalizer.normalizeInfra(Exception ex)`：把基础设施异常映射到 `NETWORK`、`RATE_LIMIT`、`STORAGE`、`IMAGE_PROCESSING` 等稳定分类。
- `ErrorNormalizer.normalizeFallback(Throwable throwable)`：兜底翻译未识别异常，避免原始异常穿透到出口。
- `PixFlowException.withTraceId(String traceId)`：在出口层补充链路标识，不污染业务层构造逻辑。
- `PixFlowException.recovery()`：统一读取“默认恢复策略或覆盖策略”，让消费者只看这一处。
- `Sanitizer.sanitizeMessage(String raw)`：处理安全展示文本，负责 token、路径和超长内容的清洗。
- `Sanitizer.sanitizePath(String rawPath)`：把绝对路径相对化或替换成外部标记。
- `Sanitizer.truncate(String raw, int maxLength)`：把错误详情或长消息裁短到约定长度。
- `ApiResponse.ok(T data)`：封装成功响应。
- `ApiResponse.error(PixFlowException error, String safeMessage)`：封装失败响应，但保持同一外壳。
- `Pagination.of(Long page, Long size)`：集中校验分页入参并回退默认值。
- `PageResponse.of(List<T> records, long total, long page, long size)`：统一分页出参形态。
- `HttpErrorRenderer.render(PixFlowException error)`：把归一化错误翻译成 HTTP 状态和 `ApiResponse`。
- `ToolErrorRenderer.render(PixFlowException error)`：把错误翻译成模型可消费的结构化 tool error。
- `StreamErrorRenderer.render(PixFlowException error)`：把错误翻译成 SSE / WS 的 error 帧。
- `ErrorRecorder.record(PixFlowException error)`：只定义落观测侧的 SPI，不在 `common` 内实现存储。

第五步是补测试。测试要覆盖三类行为：一是错误映射和 category/recovery 的默认关系；二是脱敏与截断；三是分页和统一响应封装。测试写法尽量直接断言对外可见的行为，不去绑定内部实现细节。

## Concrete Steps

先在仓库根目录确认当前状态：

    cd D:\study\PixFlow
    rg --files -g 'docs/design-docs/module/common.md' -g 'docs/design-docs/exec-plans/module-dependency-dag-plan.md' -g '.kiro/specs/pixflow/design.md'

期望看到这三个参考文件都在，并且 `common.md`、`module-dependency-dag-plan.md`、`design.md` 可以直接打开。

然后在实现阶段，按模块逐步补文件。建议的顺序是：

1. 先创建 `src/main/java/com/etherealstar/pixflow/common/error` 下的契约与异常模型。
2. 再创建 `src/main/java/com/etherealstar/pixflow/common/sanitize/Sanitizer.java`。
3. 再创建 `src/main/java/com/etherealstar/pixflow/common/web/ApiResponse.java`、`ErrorPayload.java`、`Pagination.java`、`PageResponse.java`。
4. 再创建 `src/main/java/com/etherealstar/pixflow/common/error/render/HttpErrorRenderer.java`、`ToolErrorRenderer.java`、`StreamErrorRenderer.java`。
5. 最后补 `src/test/java/...` 下的契约测试。

如果项目最终使用 Maven，验证命令应当是：

    cd D:\study\PixFlow
    mvn test

如果项目最终使用 Gradle，则改为对应的测试任务；但测试目标不变，仍然是把 `common` 的契约测试跑通。

## Validation and Acceptance

这一步的验收不看“类有没有创建出来”，而看“系统是否已经开始统一说话”。

当 `common` 实现完成后，应当满足下面几件事：

- 任意已知异常都能被 `ErrorNormalizer` 翻译成稳定的 `PixFlowException`。
- 任何对外错误文案在落盘或返回前都经过 `Sanitizer` 处理。
- 任意 controller 的成功返回和失败返回都遵循同一种 `ApiResponse<T>` 外壳。
- 分页接口都通过 `Pagination` 统一校验 page 和 size，而不是各写各的规则。
- HTTP、Tool、Stream 三个出口对同一种错误的表现一致，区别只在输出形态，不在语义。

测试通过的标志应该是：新增的 `common` 契约测试全部通过，且专门针对错误映射、脱敏、分页、响应封装的测试在实现前失败、实现后通过。

## Idempotence and Recovery

这个计划是可重复执行的。创建包骨架、增加测试、补齐渲染器都属于增量操作，不需要删改已有业务代码。

如果某个错误码或 category 的设计后来需要调整，只改 `ErrorCode`、`ErrorNormalizer` 和对应测试即可，不要把变更散落到 controller 或业务模块里。这样回滚也简单：只回退 `common` 中的契约实现和测试，不影响后续模块的数据结构。

## Artifacts and Notes

实现完成后，建议保留以下可验证产物：

- `common` 的错误码与 category 映射测试。
- `Sanitizer` 的脱敏测试样例。
- `Pagination` 的边界测试。
- `HttpErrorRenderer` / `ToolErrorRenderer` / `StreamErrorRenderer` 的出口测试。

一个理想的结果是，读者只看这些测试名，就能明白 `common` 负责什么，不负责什么。

## Interfaces and Dependencies

本波次建议最终存在的稳定接口如下。

`src/main/java/com/etherealstar/pixflow/common/error/ErrorCode.java` 应定义稳定的错误码契约，至少暴露 `code()`、`category()`、`httpStatus()`、`messageKey()` 这类信息。

`src/main/java/com/etherealstar/pixflow/common/error/ErrorCategory.java` 和 `RecoveryHint.java` 应负责定义分类与控制流语义，前者决定默认 HTTP 映射和默认恢复策略，后者只表达“重试、跳过、终止、压缩”这类行为。

`src/main/java/com/etherealstar/pixflow/common/error/PixFlowException.java` 应成为统一错误载体，携带 `ErrorCode`、`details`、`recoveryOverride`、`retryAfter`、`traceId` 和原始 `cause`。

`src/main/java/com/etherealstar/pixflow/common/error/ErrorNormalizer.java` 应只做一件事：把任意 `Throwable` 翻译成 `PixFlowException`。

`src/main/java/com/etherealstar/pixflow/common/sanitize/Sanitizer.java` 应只处理脱敏、相对化和截断，不依赖具体业务模块。

`src/main/java/com/etherealstar/pixflow/common/web/ApiResponse.java`、`ErrorPayload.java`、`Pagination.java`、`PageResponse.java` 应构成统一响应和分页入口。

`src/main/java/com/etherealstar/pixflow/common/error/render/HttpErrorRenderer.java`、`ToolErrorRenderer.java`、`StreamErrorRenderer.java` 应把同一份错误模型翻译成不同出口需要的形态，不应再定义自己的错误体系。

`src/main/java/com/etherealstar/pixflow/common/observability/ErrorRecorder.java` 应只保留 SPI 形态，具体实现留给后续观测层。

为了让实现时的调用关系更清楚，建议最终形成下面这组稳定方法名：

- `ErrorCategory.defaultRecovery()` 和 `ErrorCategory.defaultHttpStatus()`：让 category 自己说清默认行为。
- `ErrorCode.code()`、`ErrorCode.category()`、`ErrorCode.httpStatus()`、`ErrorCode.messageKey()`：让每个错误码只声明自己。
- `PixFlowException.category()`、`PixFlowException.recovery()`、`PixFlowException.details()`：让渲染器和消费者只读模型，不碰原始异常类型。
- `CommonErrorCode.from(...)` 或等价静态工厂：当异常无法归类时走统一兜底码。
- `ApiResponse.ok(...)`、`ApiResponse.error(...)`：统一成功和失败外壳。
- `Pagination.of(...)`：统一分页参数解释和校验。
- `PageResponse.of(...)`：统一分页结果封装。
- `HttpErrorRenderer.render(...)`、`ToolErrorRenderer.render(...)`、`StreamErrorRenderer.render(...)`：出口层只负责翻译，不负责定规则。

## Note

本计划是根据 `common.md` 和 `module-dependency-dag-plan.md` 新写的 Wave 0 实施计划，重点补足了“如何实现”“先做什么”“用什么关键词定位设计文本”三部分，刻意避免写入细粒度代码。
