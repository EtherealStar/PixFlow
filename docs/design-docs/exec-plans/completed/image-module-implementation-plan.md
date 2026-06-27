# 实现 `infra/image` 图像编解码与像素操作引擎

本文档必须按照仓库根目录 [PLANS.md](../../../PLANS.md) 维护。它是活文档，后续任何实现者推进、修正或完成本计划时，都必须同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`，并在文末 `Change Notes` 记录修改原因。

本计划只覆盖 `infra/image` 的生产级完整实现，不按 MVP 简化，也不把业务语义塞进像素层。读者不需要知道之前的讨论，只要拿到当前工作树和这份计划，就能从零开始把模块落地。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个独立的 `pixflow-infra-image` Maven 模块，负责把图片字节解码成内存栅格，在内存里完成缩放、压缩、水印、换底、格式转换和多图合成，再编码回字节。上层的 `module/dag` 只需要传入已经校验过的强类型参数，就能把一条确定性的像素处理链跑完，而不用碰 `BufferedImage`、ImageIO、Thumbnailator、scrimage 或任何对象存储 I/O。

从使用者角度看，这一步完成后，DAG 的图片支路可以稳定地做到“解码一次、处理多步、编码一次”，透明图转 JPEG 不会再出现黑底，超大图会在进入堆之前被拦截，WebP 写出有明确实现，组图拼接和背景合成都有可测试的生产级语义。本文档不写代码，只定义实现顺序、机制边界、验证方式和可快速定位设计原文的搜索关键词。

## Progress

- [x] (2026-06-27 17:40+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/design.md`、`docs/design-docs/module/image.md`、`docs/design-docs/module/common.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/references/error-handling-architecture.md`。
- [x] (2026-06-27 17:40+08:00) 确认用户要求的是生产级完整实现，不是 MVP 方案，也不接受把 image 模块做成轻量工具壳。
- [x] (2026-06-27 17:40+08:00) 确认当前仓库根 `pom.xml` 尚未包含 `pixflow-infra-image`，需要新增独立 Maven 子模块。
- [x] (2026-06-27 17:40+08:00) 确认当前设计文档实际位于 `docs/design-docs/design.md`，用户提到的 `.kiro/specs/pixflow/design.md` 在当前工作区不存在。
- [x] (2026-06-27 17:40+08:00) 创建本 ExecPlan，明确 image 模块的架构思路、机制、实施路径、验证方式与设计文档检索关键词。
- [x] (2026-06-27 18:12+08:00) 新增 `pixflow-infra-image` Maven 子模块并接入根工程。
- [x] (2026-06-27 18:18+08:00) 实现 `RasterImage`、`ImageFormat`、`ImageCodec`、`ImageProbe`、`EncodeSpec` 和 `ImageProcessingException` 这些核心抽象。
- [x] (2026-06-27 18:22+08:00) 实现 `DefaultImageCodec`、配置属性、自动装配、缩放、水印、换底、格式转换、压缩意图、组图合成和流水线编排。
- [x] (2026-06-27 18:26+08:00) 补齐像素炸弹防护、alpha 扁平化、WebP 写出、common 错误收口测试和单次编解码流水线测试。

## Surprises & Discoveries

- Observation: 用户给出的 `.kiro/specs/pixflow/design.md` 在当前工作区不存在，不能作为本计划的真实依据。
  Evidence: 读取该路径时返回 `Cannot find path`；实际可用的总设计文件是 `docs/design-docs/design.md`。

- Observation: 当前 Maven reactor 还没有 `pixflow-infra-image`，所以这不是“补测试”，而是一个需要先创建模块边界的新增基础设施工作。
  Evidence: 根 `pom.xml` 的 `<modules>` 只有 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-cache`、`pixflow-infra-mq`、`pixflow-infra-ai`、`pixflow-app`。

- Observation: `docs/design-docs/module/image.md` 明确把 image 定义为“纯计算、无 I/O、无业务语义”的底层像素引擎，不允许把 DAG、SKU、group、task 之类业务词放进模块内部。
  Evidence: 文档多处强调“解码一次、编码一次”“只认 InputStream/字节与内存栅格”“compose_group 只接收一组已就绪的 RasterImage”。

- Observation: image 的边界横跨多个模块，真正归 `infra/image` 的只有本地像素处理和编解码，不是所有 DAG 像素工具。
  Evidence: `docs/design-docs/module/image.md` 的工具归属表把 `remove_bg` 放在 `infra/thirdparty`，把 `generate_copy` 放在 `infra/ai`，把 `resize`、`compress`、`watermark`、`set_background`、`convert_format`、`compose_group` 留给 `infra/image`。

- Observation: scrimage 4.3.0 的 WebP 写出 API 不是 `ImmutableImage.bytes(writer)`，而是 `ImmutableImage.fromAwt(...).forWriter(WebpWriter.DEFAULT.withQ(q)).bytes()`。
  Evidence: 使用 `javap` 检查本地 Maven 缓存中的 `WebpWriter` 和 `WriteContext` 后修正实现；随后 `mvn -pl pixflow-infra-image -am test` 中 WebP 写出测试通过。

- Observation: 当前 Windows 开发环境可以执行 scrimage WebP 原生写出。
  Evidence: 测试日志出现 `Installing binary at C:\Users\rowla\AppData\Local\Temp\cwebp...binary`，`DefaultImageCodecTest` 的 6 个用例全部通过。

## Decision Log

- Decision: 新模块名采用 `pixflow-infra-image`，源码包采用 `com.pixflow.infra.image`。
  Rationale: 与仓库现有 `pixflow-infra-ai`、`pixflow-infra-cache` 等命名保持一致，能直接映射到 `docs/design-docs/module/image.md` 的模块结构。
  Date/Author: 2026-06-27 / Codex

- Decision: image 只实现本地像素处理、编解码和流水线编排，不引入任何对象存储、缓存、MQ、权限或 DAG 业务知识。
  Rationale: 这是总设计和模块设计共同约束的边界；把业务语义塞进像素层会污染确定性底座，也会破坏模块可测性。
  Date/Author: 2026-06-27 / Codex

- Decision: 解码前必须先 probe，所有处理链必须遵守“解码一次、处理多步、编码一次”。
  Rationale: 这同时解决像素炸弹防护、内存占用控制和多步支路性能问题，是生产级 image 引擎的底座语义。
  Date/Author: 2026-06-27 / Codex

- Decision: `set_background` 的背景图适配不做隐式裁切，必须显式选择适配模式。
  Rationale: 生产场景需要稳定、可预测的输出；隐式裁切会让图像语义在不同输入尺寸下漂移，不利于测试和回归。
  Date/Author: 2026-06-27 / Codex

- Decision: `compose_group` 的 `grid` 默认按近似正方形布局推导，不让模块自己猜业务排版。
  Rationale: 这样能保持结果稳定、便于 golden image 测试，也能让上层在需要时显式传入排序和布局参数。
  Date/Author: 2026-06-27 / Codex

- Decision: `ImageProcessingException` 保持为 image 模块自己的领域异常，跨边界后的分类翻译交给 `common`。
  Rationale: 底层模块应先保持纯粹；`common` 负责把它归一化为 `IMAGE_PROCESSING` 和 `SKIP` 语义，职责更清晰。
  Date/Author: 2026-06-27 / Codex

- Decision: `CompressOp` 与 `ConvertFormatOp` 作为类型化流水线步骤保留意图，但不在 `apply` 中执行中间编码。
  Rationale: 压缩和格式转换本质属于最终编码策略；把它们合并进 `EncodeSpec` 可以保持“解码一次、处理多步、编码一次”的设计约束。
  Date/Author: 2026-06-27 / Codex

- Decision: WebP 写出使用 scrimage 的直接类型 API，不再使用反射。
  Rationale: Maven 编译已经拥有 scrimage 依赖，直接类型调用能让 API 变化在编译期暴露，减少运行时失败面。
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

本轮已完成 `pixflow-infra-image` 的首版生产级骨架和主要本地像素能力。模块已经加入 Maven reactor，提供强类型核心 API、ImageIO/TwelveMonkeys 解码探测、scrimage WebP 写出、alpha 扁平化、像素炸弹防护、缩放、水印、换底、组图合成和流水线编排。`common` 对 `ImageProcessingException` 的归一化路径也通过测试证明会得到 `IMAGE_PROCESSING` 与 `SKIP`。

完成后回看，这个模块已经证明三件事：第一，image 不需要知道任何业务词也能支撑上层图片处理链；第二，透明图、WebP、组图、换底和缩放都能在同一条流水线上稳定工作；第三，错误、性能和资源边界可以通过测试和配置被明确控制，而不是靠经验值碰运气。仍可继续增强的是更多 golden image 视觉相似度断言、完整 EXIF 样图覆盖、目标体积压缩的更多边界样本和并发压力测试。

## Context and Orientation

`infra/image` 位于依赖 DAG 的 Wave 1 基础设施层。它被 `module/dag` 消费，并为确定性执行引擎提供图像编解码与本地像素运算能力。它只依赖 `common`，不依赖 `storage`、`cache`、`mq`、`ai`、`thirdparty`、`harness` 或任何业务模块。

这里先定义几个后面会反复出现的术语。`RasterImage` 指解码后的内存栅格，也就是 `BufferedImage` 加少量元信息后的不可变句柄。`probe` 指只读元数据、不真正解码像素的探测步骤，用来提前判断格式和尺寸。`flatten background` 指把带透明通道的图合成到不透明底色上，避免把 alpha 直接扔进 JPEG 这类无 alpha 格式。`pipeline` 指从解码开始、经过多个像素操作、最终编码结束的一条处理链。

当前仓库根目录是 `D:\study\PixFlow`。和本计划最相关的文件是 `docs/design-docs/design.md`、`docs/design-docs/module/image.md`、`docs/design-docs/module/common.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/references/error-handling-architecture.md`，以及后续要新增的 `pixflow-infra-image/pom.xml` 与其源码目录。

为了在设计文档里快速定位 image 相关内容，可以直接搜索这些关键词。

在 `docs/design-docs/design.md` 中搜索 `第四章 技术栈选型`、`第九章 DAG 确定性执行引擎`、`9.5 分组聚合`、`第十五章 技术风险`、`Wave 1 基础设施`，可以定位 image 的技术选型、DAG 边界、组图语义和 WebP 风险。

在 `docs/design-docs/module/image.md` 中搜索 `只认 InputStream/字节与内存栅格`、`解码一次、编码一次`、`像素炸弹防护`、`EXIF 朝向归正`、`Alpha 扁平化策略`、`compose_group`、`WebP 写出`、`set_background`、`目标体积压缩`，可以直接跳到本模块最关键的设计段落。

在 `docs/design-docs/module/common.md` 中搜索 `infra 异常收口策略`、`IMAGE_PROCESSING`、`SKIP`、`Sanitizer`、`ErrorNormalizer`，可以定位 image 异常跨边界后的统一归一化方式。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索 `Wave 1 安全边界 + 基础设施`、`infra/image`、`关键路径`，可以确认 image 的实现顺序和它在整体模块拓扑里的位置。

在 `docs/references/error-handling-architecture.md` 中搜索 `分类驱动行为`、`Trace 与 Error 日志分离`、`安全与脱敏`，可以复核为什么底层异常不能直接带着库原文裸奔到上层。

## Plan of Work

第一步先把模块边界立住。新增 `pixflow-infra-image/pom.xml`，把它纳入根 `pom.xml` 的 `<modules>` 和 `dependencyManagement`，并让它依赖 `pixflow-common`、TwelveMonkeys、Thumbnailator、scrimage WebP、Spring Boot 自动装配、Micrometer 和测试依赖。这个阶段不写任何业务工具，只把模块变成可编译、可装配的基础设施单元。

第二步实现核心抽象。先定义 `RasterImage`、`ImageFormat`、`ImageCodec`、`ImageProbe`、`EncodeSpec` 和 `ImageProcessingException`，再实现 `ImageProperties` 与 `ImageAutoConfiguration`。这一步的重点不是功能数量，而是把“格式能力矩阵、解码前探测、编码策略、默认扁平化底色、像素炸弹阈值”这些语义锁死。

第三步实现编解码层。解码必须先 probe 再 decode，图像读取要显式处理 EXIF 朝向归正和色彩空间统一，编码要分成标准 ImageIO 路径和 WebP 专用路径。这里要把 reader/writer 的生命周期控制好，确保每次调用新建实例、用完 dispose，不保留共享可变状态。

第四步实现像素操作。`resize` 用高质量缩放，`compress` 用质量压缩和目标体积压缩两种语义，`watermark` 只支持图片水印，`set_background` 支持纯色和背景图，`convert_format` 只做目标格式转换，`compose_group` 只做 N 张图的合成，不承担组业务判断。每个操作都应是纯函数式转换，输入是 `RasterImage`，输出也是 `RasterImage`。

第五步实现流水线编排。`ImagePipeline` 只做一件事：把“单图解码一次 → 多步 op → 单次编码”或“成员图逐个预处理 → compose fan-in → 后续 op → 单次编码”串起来。它不应该知道 MinIO、Redis、DAG JSON、任务状态或任何上层业务，只负责把上层给定的步骤按顺序执行。

第六步实现错误、配置和资源治理。超大图必须在进入像素堆之前拦截，编码到无 alpha 格式时必须按默认白底或显式底色扁平化，目标体积压缩必须有迭代上限，`ImageProcessingException` 必须带上下文但不能泄露敏感路径。跨出 infra 边界后，统一由 `common` 归一化为 `IMAGE_PROCESSING` 和 `SKIP`。

第七步补测试和验证。单测覆盖格式能力矩阵、探测拒绝、EXIF 归正、alpha 扁平化、缩放、压缩、水印、换底、格式转换、组图合成和流水线的单次解码/编码语义。再用集成测试验证 WebP 写出、真实编码往返和并发安全，最后跑 Maven 全量验证。

## Concrete Steps

从仓库根目录开始：

    cd D:\study\PixFlow
    Get-Content AGENTS.md
    Get-Content docs\design-docs\exec-plans\module-dependency-dag-plan.md
    Get-Content docs\design-docs\module\image.md
    rg --files pixflow-infra-image

预期在实现前看不到 `pixflow-infra-image` 目录或 `pom.xml`。如果未来执行时模块已经存在，先阅读现有文件，再决定是补完、重构还是只补测试，不要直接覆盖。

创建模块骨架后，先做最小编译验证：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-image -am test

模块骨架阶段的预期结果是 Maven 能识别新模块并完成编译。若后续接入了真实 WebP 写出或图像集成测试，最终仍应保持 `BUILD SUCCESS`，只是某些平台相关测试可能按条件跳过。

完成核心抽象后，单独跑像素模型测试：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-image -Dtest=*Image*Test test

预期能看到对 `RasterImage`、`ImageFormat`、`ImageCodec`、`EncodeSpec` 和异常模型的单测通过。这里的重点是验证类型和能力矩阵，不是验证 UI 结果。

完成像素操作后，跑操作级测试：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-image -Dtest=*Op*Test test

预期可以在测试中观察到缩放、水印、换底、格式转换、组图合成都按文档语义工作，尤其是透明图转 JPEG 时四角应为白底而不是黑底。

完成流水线后，跑全模块测试和全量 reactor：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-image -am test
    mvn test

最终预期都是 `BUILD SUCCESS`。本轮验证命令为 `mvn -pl pixflow-infra-image -am test`，实际结果是 common 15 个测试、image 13 个测试全部通过。当前 Windows 开发机可执行 scrimage WebP 写出；如果某些平台没有可用的本地 WebP 原生环境，相关测试应按条件跳过，但纯 Java 路径不能失败。

## Validation and Acceptance

第一层验收是模块边界正确。`pixflow-infra-image/pom.xml` 必须存在，根 `pom.xml` 必须识别它，`pixflow-infra-image` 不能依赖 `storage`、`cache`、`mq`、`ai`、`thirdparty`、`harness`、`module` 或 `agent`。如果后续 `pixflow-app` 需要显式装配该模块，再把它作为运行时依赖接入，但不要把业务模块反向塞进 image。

第二层验收是类型化模型完整。源码里必须存在 `RasterImage`、`ImageFormat`、`ImageCodec`、`ImageProbe`、`EncodeSpec`、`ImageOp`、`MultiImageOp`、`ResizeSpec`、`CompressSpec`、`WatermarkSpec`、`SetBackgroundSpec`、`ConvertFormatSpec`、`ComposeSpec`、`ImagePipeline`、`ImageProperties`、`ImageAutoConfiguration` 和 `ImageProcessingException`，并且这些类型不能暴露业务词。

第三层验收是编解码语义正确。JPEG、PNG、BMP、WebP 的读写往返应能通过测试证明，WebP 写出应走专用路径，不是伪装成 ImageIO writer。透明 PNG 转 JPEG 时默认白底生效，显式 `set_background` 之后再转 JPEG 应保持无 alpha 且不再重复扁平化。

第四层验收是资源治理生效。超大图必须在 probe 阶段被拒绝，不能真的进入高成本像素解码；reader/writer 必须每次调用新建并释放；目标体积压缩必须有迭代上限，不能无限重编码。

第五层验收是流水线行为正确。单图链必须证明只解码一次、只编码一次；组图链必须证明成员图先各自处理、再合成、再继续后续步骤。`compose_group` 的布局和排序必须稳定，不能依赖非确定性的集合遍历。

第六层验收是错误收口正确。损坏图、格式不支持、尺寸超限、非法参数都应在 image 模块内抛出 `ImageProcessingException`，跨出边界后由 `common` 翻译成 `IMAGE_PROCESSING`，并按照 `SKIP` 处理单个工作单元，不拖垮整条处理链。

## Idempotence and Recovery

本计划的实现步骤应当是可重复执行的。新增 Maven 模块和 POM 依赖时，重复执行只应看到相同条目，不应产生重复 module。源码目录如果已经存在，先读现状再继续，不要推倒重来。

像素操作和流水线实现要保持幂等语义。相同输入字节和相同参数必须产生相同输出；不同输入只在可解释维度上不同。这样才能支持 golden image 测试、回归比较和后续 DAG 重跑。

如果 WebP 原生库或平台环境不稳定，测试策略应该区分“纯 Java 编解码通过”和“原生写出集成测试跳过”，不要把环境问题误报成实现失败。若后续发现某个平台无法稳定执行 WebP 写出，应把这个发现记录到 `Surprises & Discoveries`，再决定是条件跳过还是改写验证方式。

## Artifacts and Notes

后续实现者最容易先看的结论是下面几句。

    image 只提供纯计算原语，不承载业务知识。
    解码前先 probe，处理链只解码一次、编码一次。
    透明图转无 alpha 格式时必须显式扁平化。
    compose_group 只做合成，不做组业务判断。
    错误在 image 内部保持独立，跨边界后由 common 归一化。

建议把这些测试作为最终证据保留下来。

    ImageFormatTest
    ImageCodecRoundTripTest
    ImageBombGuardTest
    ResizeOpTest
    CompressOpTest
    WatermarkOpTest
    SetBackgroundOpTest
    ConvertFormatOpTest
    ComposeGroupOpTest
    ImagePipelineTest
    ImageProcessingExceptionNormalizationTest

理想的测试输出应能说明这些事实。

    ImageFormatTest > marks WEBP as encodable PASSED
    ImageBombGuardTest > rejects oversized source before decode PASSED
    SetBackgroundOpTest > flattens alpha image to white background PASSED
    ComposeGroupOpTest > keeps grid layout stable for 5 members PASSED
    ImagePipelineTest > decodes once and encodes once PASSED

## Interfaces and Dependencies

最终模块路径应是 `pixflow-infra-image`。主源码根包是 `com.pixflow.infra.image`，测试路径是 `pixflow-infra-image/src/test/java/com/pixflow/infra/image`。

`pixflow-infra-image/pom.xml` 应依赖：

    com.pixflow:pixflow-common
    com.twelvemonkeys.imageio:imageio-jpeg
    com.twelvemonkeys.imageio:imageio-webp
    net.coobird:thumbnailator
    com.sksamuel.scrimage:scrimage-webp
    org.springframework.boot:spring-boot-autoconfigure
    org.springframework:spring-context
    io.micrometer:micrometer-core
    org.springframework.boot:spring-boot-starter-test (test)

如果后续验证发现 scrimage WebP 写出需要额外平台兼容层，优先把兼容逻辑封装在 image 模块内部，不要把原生细节泄漏给上层。

`ImageProperties` 应绑定 `pixflow.image.*`，字段至少包括 `max-source-pixels`、`max-dimension`、`default-jpeg-quality`、`default-webp-quality`、`flatten-background`、`target-size-max-iterations`、`resize.allow-upscale` 和 `color-space`。

`ImageAutoConfiguration` 应注册 `ImageCodec`、`ImagePipeline`、各个 op 的实现和配置属性，不应注册任何业务工具白名单，也不应知道 DAG JSON。

`RasterImage` 应持有 `BufferedImage` 与最少的元信息。`ImageFormat` 应表达解码能力、编码能力和 alpha 支持。`ImageCodec` 应提供 `probe`、`decode`、`encode`。`ImagePipeline` 应只负责编排，不负责业务判断。`ImageProcessingException` 应携带原因、格式和尺寸上下文，但不要把敏感路径或对象存储 key 原样暴露到错误消息里。

各个操作类应位于 `com.pixflow.infra.image.op` 及其 `impl` 子包。`ResizeSpec`、`CompressSpec`、`WatermarkSpec`、`SetBackgroundSpec`、`ConvertFormatSpec`、`ComposeSpec` 都应是不可变类型，调用方先校验，模块内部只做防御式兜底。

## Public API Surface

这一节把 image 模块最终应暴露的 Java API 说得更具体一些。实现时可以在签名细节上微调，但职责边界和方法含义不要变。

`RasterImage` 是所有操作共享的图像载体。它应该提供图像宽高、是否含 alpha、来源格式和底层 `BufferedImage` 访问能力，且本身不可变。

    public final class RasterImage {
        BufferedImage buffer();
        int width();
        int height();
        boolean hasAlpha();
        ImageFormat sourceFormat();
    }

`ImageFormat` 是格式能力矩阵。它必须把“能否解码、能否编码、是否支持 alpha”这三件事说清楚，供上层在编译 DAG 方案时和运行期编码时做判断。

    public enum ImageFormat {
        JPEG, PNG, WEBP, BMP, TIFF, GIF
    }

`ImageCodec` 是编解码核心。`probe` 只探测元信息，不真正解码像素；`decode` 负责 EXIF 归正、色彩空间统一和尺寸防护；`encode` 负责按目标格式输出字节，并根据 `EncodeSpec` 决定是否扁平化。

    public interface ImageCodec {
        ImageProbe probe(InputStream data);
        RasterImage decode(InputStream data);
        byte[] encode(RasterImage image, EncodeSpec spec);
    }

`ImageProbe` 只保存最小必要元数据，不保存任何业务上下文。

    public record ImageProbe(ImageFormat format, int width, int height, boolean hasAlpha) { }

`EncodeSpec` 是编码请求参数。它应该明确目标格式、质量、目标体积和扁平化底色四类信息，其中 `targetBytes` 优先于质量值触发目标体积压缩。

    public record EncodeSpec(
        ImageFormat targetFormat,
        Integer quality,
        Long targetBytes,
        Color flattenBackground
    ) { }

`ImageOp` 和 `MultiImageOp` 是操作契约。前者处理单张图，后者处理多张图合成，不要把二者混成一个可变的万能接口。

    @FunctionalInterface
    public interface ImageOp {
        RasterImage apply(RasterImage src);
    }

    @FunctionalInterface
    public interface MultiImageOp {
        RasterImage apply(List<RasterImage> members);
    }

`ResizeOp`、`CompressOp`、`WatermarkOp`、`SetBackgroundOp`、`ConvertFormatOp`、`ComposeGroupOp` 都应是面向 spec 的工厂式操作对象，典型形态是 `of(spec)` 或构造器接收 spec，然后暴露 `apply(...)`。这能让 DAG 侧把节点参数映射成类型化 spec，再直接拼入流水线，而不是把原始 map 继续往下传。

`ImagePipeline` 是编排器，不是像素操作本身。它应该支持单图链和组图链两种入口，前者只处理一条 decode-op-encode 路径，后者先处理成员图，再通过 `compose` 合成，最后接后续单图操作。

    public interface ImagePipeline {
        byte[] run(InputStream source, List<ImageOp> ops, EncodeSpec encode);
        byte[] runComposed(List<InputStream> members,
                           List<ImageOp> perMemberOps,
                           MultiImageOp compose,
                           List<ImageOp> postOps,
                           EncodeSpec encode);
    }

`ImageProcessingException` 是模块级异常。它需要保留失败原因、格式和尺寸信息，便于跨边界后由 `common` 统一归一化，但不要把底层库的原始 message 直接暴露为外部安全文案。

    public class ImageProcessingException extends RuntimeException {
        enum Reason { DECODE_FAILED, ENCODE_FAILED, UNSUPPORTED_ENCODE_FORMAT, SOURCE_TOO_LARGE, CORRUPTED_IMAGE, INVALID_OP_PARAM }
    }

## Change Notes

2026-06-27 / Codex: 创建本计划。原因是用户要求按照 `PLANS.md` 的格式撰写中文计划文档，并明确 image 模块的实现架构、机制、验证方式以及参考设计文档的快速检索关键词；本次只新增计划，不实现代码。

2026-06-27 / Codex: 执行本计划并更新状态。原因是已新增 `pixflow-infra-image` 模块，实现核心编解码、像素操作、流水线、自动装配与测试，并通过 `mvn -pl pixflow-infra-image -am test` 验证。
