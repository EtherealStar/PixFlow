# infra/image —— 图像编解码与像素操作引擎（Wave 1 基础设施）

> 本文是 PixFlow 完整重写阶段 `infra/image` 模块的设计文档，对应 `design.md` 第四章「技术栈选型」（TwelveMonkeys + Thumbnailator + scrimage）、第九章「DAG 确定性执行引擎」的像素工具白名单与 9.5「分组聚合」、第十五章「技术风险」的 WebP 写出兼容项，以及 `module-dependency-dag-plan.md` 的 **Wave 1 基础设施**。
> 范围：图像编解码、内存栅格抽象、本地像素操作（缩放/压缩/水印/底色合成/格式转换/多图合成）、解码-处理-编码流水线、生产级资源治理与错误收口。
> 本文不涉及 MVP 既有实现，从新架构需求重新推导，按生产级标准设计，不做 MVP 式简化。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、关键边界：像素工具白名单横跨三个 infra 模块](#二关键边界像素工具白名单横跨三个-infra-模块)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、核心抽象](#四核心抽象)
- [五、编解码层](#五编解码层)
- [六、像素操作详解](#六像素操作详解)
- [七、解码一次 / 编码一次流水线](#七解码一次--编码一次流水线)
- [八、生产级资源治理](#八生产级资源治理)
- [九、韧性与错误收口](#九韧性与错误收口)
- [十、配置](#十配置)
- [十一、安全与脱敏](#十一安全与脱敏)
- [十二、对其他模块的契约](#十二对其他模块的契约)
- [十三、测试策略](#十三测试策略)
- [十四、暂不考虑](#十四暂不考虑)

---

## 一、文档定位与设计原则

`infra/image` 在依赖 DAG 中处于 **Wave 1**，只依赖 `common`，被 `module/dag`（确定性执行引擎）消费。它承载图像处理的**纯计算原语**：把字节解码成内存栅格、对栅格施加本地像素操作、再编码回字节，不含任何业务领域知识，也不做任何对象存储/缓存 I/O。

图像专属设计原则：

1. **纯计算，无 I/O，无业务语义**。image 只认 `InputStream`/字节与内存栅格，不知道什么是「素材包」「支路」「组」「SKU」「DAG 节点」。它不读 MinIO、不碰 Redis；读写对象存储由 `module/dag` 执行引擎在两端缝合（依赖 DAG 中 `storage → dag`、`image → dag` 两条独立边）。
2. **领域无关**。模块内**不得出现** `dag`、`node`、`branch`、`group`、`sku`、`task` 等业务词（对标 `cache.md` §二、`mq.md` §二的硬约束）。「工具名（remove_bg/resize…）→ 操作」的映射与白名单属 `module/dag`，见 [§二](#二关键边界像素工具白名单横跨三个-infra-模块)。
3. **类型化操作，不解析 JSON**。对上暴露强类型操作 API + 类型化参数 record（`ResizeSpec`/`WatermarkSpec`…），不接受 `Map<String,Object>`、不解析 DAG 节点 JSON。调用方（`module/dag`）负责把已校验的节点参数映射为类型化 spec。
4. **解码一次、编码一次**。一条多步支路（如 `resize→watermark→compress`）全程在内存栅格上链式处理，绝不在中间步骤反复 decode/encode（见 [§七](#七解码一次--编码一次流水线)）。
5. **确定性底座的一环**。同一输入 + 同一参数产出视觉一致的结果，无内部自主决策、无随机性，契合 `design.md` 设计原则一/二「确定性底座不被污染」。
6. **无状态、线程安全**。`module/task` 的线程池按 `[图片×支路]` 高并发调用本模块；ImageIO 的 `ImageReader`/`ImageWriter` 本身非线程安全，封装内**每次调用新建实例并 `dispose()`**，模块对外无共享可变状态。
7. **底层只懂像素，错误在边界归一化**。image 抛独立的 `ImageProcessingException`（携操作/格式/尺寸上下文），由 `common` 的 `ErrorNormalizer` 在跨出 infra 边界时翻译为 `IMAGE_PROCESSING` 分类（默认 `SKIP`，供支路隔离），见 `common.md` §10。
8. **生产级，不简化**。像素炸弹防护、EXIF 朝向归正、alpha 扁平化策略、WebP 写出、目标体积压缩、能力矩阵、并发安全齐备，不走 MVP 捷径。

---

## 二、关键边界：像素工具白名单横跨三个 infra 模块

`design.md §9` 的 DAG 像素工具白名单（`remove_bg / set_background / resize / compress / watermark / convert_format / generate_copy / compose_group`）容易被误读为「全部归 infra/image」。实际上它横跨三个 infra 模块，`infra/image` 只拥有其中的**本地栅格运算**：

| DAG 像素工具 | 真正归属 | 说明 |
|---|---|---|
| `remove_bg` | **infra/thirdparty** | 第三方抠图 API（HTTP 调用），非本地栅格运算，image 不碰 |
| `generate_copy` | **infra/ai** | LLM 文案分支，根本不是图像，image 不碰 |
| `set_background` | **infra/image** | 对带 alpha 的图合成底色/底图 |
| `resize` | **infra/image** | Thumbnailator 高质量缩放 |
| `compress` | **infra/image** | 编码质量 / 目标体积压缩 |
| `watermark` | **infra/image** | 叠加图片水印 |
| `convert_format` | **infra/image** | 格式转换（编解码） |
| `compose_group` | **infra/image** | N 张 fan-in 合成 1 张（多输入栅格运算） |

**边界结论**：

- `infra/image` 只提供「本地像素操作 + 编解码 + 多图合成」的类型化能力。
- 「工具名 → 操作」的映射、白名单校验、分支/组支路展开与跨模块派发，全部在 `module/dag` 的执行引擎。一条 `remove_bg → set_background → resize` 支路由 dag 缝合：`infra/thirdparty.removeBg(bytes)` 产出抠图（带 alpha 的 PNG）→ 喂给 `infra/image` 的 `decode → setBackground → resize → encode`。
- `compose_group` 的「哪些图算一组、`expected_count` 的 HITL 张数校验、组支路 fan-in 编排」全在 `module/dag` + `module/file`；`infra/image` 只接收一组已就绪的栅格，按 layout 合成为单张。

> 因此本模块**不持有按字符串名索引的工具注册表**——白名单跨三个模块，强放残缺注册表反而割裂职责。image 暴露的是类型化操作 API。

---

## 三、模块结构与依赖位置

源码包：`com.pixflow.infra.image`

```
infra/image/
├── RasterImage.java              # 解码后的内存栅格：BufferedImage + 元信息（不可变句柄）
├── ImageFormat.java              # 格式枚举 + 能力位（canDecode / canEncode / supportsAlpha）
├── ImageCodec.java               # 核心编解码接口：decode / probe / encode
├── EncodeSpec.java               # 编码参数 record（目标格式 + 质量 + 背景色 + 目标体积）
├── op/
│   ├── ImageOp.java              # 单输入操作契约：apply(RasterImage) -> RasterImage
│   ├── MultiImageOp.java         # 多输入操作契约：apply(List<RasterImage>) -> RasterImage
│   ├── ResizeSpec.java           # record（width/height/mode/keepAspect/upscale）
│   ├── CompressSpec.java         # record（quality 或 targetBytes，二选一）
│   ├── WatermarkSpec.java        # record（水印图 + 九宫格位置 + 透明度 + 缩放 + 边距）
│   ├── SetBackgroundSpec.java    # record（背景色 或 背景图 + 适配模式）
│   ├── ConvertFormatSpec.java    # record（目标格式 + 质量 + alpha 扁平化底色）
│   ├── ComposeSpec.java          # record（layout/order/gap/background）
│   └── impl/                     # 各操作实现（Thumbnailator / Graphics2D 合成等）
├── pipeline/
│   ├── ImagePipeline.java        # 解码一次 → 链式 op → 编码一次 的编排器
│   └── PipelineStep.java         # 流水线步骤（ImageOp 的包装）
├── ImageProcessingException.java # 独立领域异常（边界归一化为 IMAGE_PROCESSING）
├── ImageProperties.java          # @ConfigurationProperties(pixflow.image)
└── config/
    └── ImageAutoConfiguration.java  # 注册 TwelveMonkeys/scrimage、装配 ImageCodec/ImagePipeline Bean
```

依赖方向：`infra/image → common`（抛出由 `common` 归一化的异常、文案经 `Sanitizer` 脱敏），**不反向依赖** `storage`/`thirdparty`/`ai`/任何 `harness`/`module`/`agent`。

新增 Maven 依赖（`pom.xml`）：

    <!-- ImageIO 格式补全：读 JPEG/PNG/WebP/TIFF/BMP 等，纯 Java 无原生依赖 -->
    <dependency>
        <groupId>com.twelvemonkeys.imageio</groupId>
        <artifactId>imageio-jpeg</artifactId>
        <version>3.12.0</version>
    </dependency>
    <dependency>
        <groupId>com.twelvemonkeys.imageio</groupId>
        <artifactId>imageio-webp</artifactId>   <!-- WebP 读 -->
        <version>3.12.0</version>
    </dependency>
    <!-- 高质量缩放 -->
    <dependency>
        <groupId>net.coobird</groupId>
        <artifactId>thumbnailator</artifactId>
        <version>0.4.20</version>
    </dependency>
    <!-- WebP 写出（libwebp 绑定，本模块唯一原生依赖） -->
    <dependency>
        <groupId>com.sksamuel.scrimage</groupId>
        <artifactId>scrimage-webp</artifactId>
        <version>4.3.0</version>
    </dependency>

> TwelveMonkeys 通过 ImageIO SPI 自动注册读取插件；缩放走 Thumbnailator；**WebP 写出是 ImageIO 的短板，由 scrimage(libwebp) 专门补**——这是本模块唯一的原生依赖（见 [§九](#九韧性与错误收口) 风险处理）。

---

## 四、核心抽象

### 4.1 `RasterImage` —— 内存栅格句柄

把 `BufferedImage` 与元信息包成不可变句柄，避免上层直接摸 AWT 细节，并携带处理链上需要的源信息（源格式、是否含 alpha、尺寸）。

```java
public final class RasterImage {
    BufferedImage buffer();      // 底层栅格（已按 EXIF 归正、已统一到处理色彩空间）
    int width();
    int height();
    boolean hasAlpha();
    ImageFormat sourceFormat();  // 解码来源格式（编码时若未指定则沿用）
    // 工厂：of(BufferedImage, ImageFormat)
}
```

`RasterImage` 在流水线步骤间传递；每步产出新的 `RasterImage`，不就地改源（便于失败定位与潜在复用）。

### 4.2 `ImageFormat` —— 格式与能力矩阵

**解码能力 ≠ 编码能力**，必须显式建模：

```java
public enum ImageFormat {
    JPEG(true, true,  false),   // canDecode, canEncode, supportsAlpha
    PNG (true, true,  true),
    WEBP(true, true,  true),    // 读经 TwelveMonkeys，写经 scrimage
    BMP (true, true,  false),
    TIFF(true, false, true),    // 读支持，编码本期不做 -> 作为目标格式时判“无法编码”
    GIF (true, false, true);    // 同上（动图见 §十四）

    boolean canDecode();
    boolean canEncode();
    boolean supportsAlpha();
}
```

`convert_format` / `EncodeSpec` 的目标格式若 `canEncode()==false`，直接抛类型化异常 → 归 `SKIP`（对应 `design.md §15`「无写出器格式按『无法编码』记入跳过清单」）。

### 4.3 `ImageCodec` —— 编解码核心

```java
public interface ImageCodec {

    // 解码：整帧入堆前先 probe 尺寸做像素炸弹防护（见 §八）；自动 EXIF 归正 + 统一 sRGB
    RasterImage decode(InputStream data);

    // 仅探测元信息（尺寸/格式），不解码像素 —— 供防护与快速校验
    ImageProbe probe(InputStream data);

    // 编码：按 EncodeSpec 出字节；带 alpha 图编码到无 alpha 格式时按策略扁平化（见 §五）
    byte[] encode(RasterImage image, EncodeSpec spec);
}

public record ImageProbe(ImageFormat format, int width, int height, boolean hasAlpha) {}
```

- 解码入参为 `InputStream`（来源是 dag 从 MinIO 取的流），编码出 `byte[]`（由 dag 写回 MinIO）。本模块两端都不接触对象存储。
- `EncodeSpec` 见 [§五](#五编解码层) 与 [§六](#六像素操作详解)。

### 4.4 操作契约

```java
@FunctionalInterface
public interface ImageOp { RasterImage apply(RasterImage src); }     // 逐图 1->1

@FunctionalInterface
public interface MultiImageOp { RasterImage apply(List<RasterImage> members); }  // N->1（compose）
```

所有操作以类型化 spec 构造（`ResizeSpec` → `ResizeOp` 等），参数全为不可变 record。`module/dag` 把校验过的 DAG 节点参数映射成 spec，再组装成流水线。

---

## 五、编解码层

### 5.1 读取（TwelveMonkeys + ImageIO SPI）

- TwelveMonkeys 插件经 SPI 注册，补齐 JPEG（含 CMYK/异常 JPEG 容错）、WebP、TIFF、BMP 等读取。
- 每次解码 `ImageIO.getImageReaders` 取**新 reader 实例**，用完 `dispose()`，杜绝跨线程复用（线程安全）。
- **EXIF 朝向归正**：手机/相机图常带 EXIF orientation，解码时读取并自动正向旋转，保证后续缩放/水印坐标正确。归正在 `decode` 内完成，上层拿到的 `RasterImage` 已是视觉正向。
- **色彩空间统一**：解码后统一转 sRGB（处理链与编码都在 sRGB 下进行），消除 ICC/CMYK 来源导致的偏色不一致。

### 5.2 写出（ImageIO + scrimage WebP）

- JPEG/PNG/BMP 走标准 ImageIO `ImageWriter` + `ImageWriteParam`（JPEG 设压缩质量）。
- **WebP 写出走 scrimage(libwebp)**，独立编码路径（不经 ImageIO writer）。
- 目标格式 `canEncode()==false` → 抛 `ImageProcessingException(UNSUPPORTED_ENCODE_FORMAT)` → SKIP。

### 5.3 Alpha 扁平化策略（已决策）

JPEG 等无 alpha 格式无法承载透明像素；抠图后的透明 PNG 若直接编码到 JPEG 会出现黑底/脏边。策略：

- **带 alpha 图编码到无 alpha 格式时，默认扁平化到白底**（电商主图常态）。
- 白底**可被覆盖**：`EncodeSpec` / `ConvertFormatSpec` 提供 `flattenBackground` 字段；上游若先执行了 `set_background`，则此时已无 alpha，扁平化为 no-op。
- 编码到支持 alpha 的格式（PNG/WebP）时保留透明通道，不扁平化。

> 这是「默认白底 + 可被 `set_background` 覆盖」的落点：隐式行为有明确默认值，且可被显式操作接管，不静默产出黑底。

### 5.4 `EncodeSpec`

```java
public record EncodeSpec(
    ImageFormat targetFormat,     // 目标编码格式（须 canEncode）
    Integer quality,              // 1..100，对 JPEG/WebP 有效；null 用配置默认
    Long targetBytes,             // 目标体积上限（字节）；非空走目标体积压缩（见 §6.2）
    Color flattenBackground       // alpha 扁平化底色；null 用默认白
) {
    // 约束：quality 与 targetBytes 不应同时强约束冲突；targetBytes 优先触发迭代压缩
}
```

---

## 六、像素操作详解

所有操作输入/输出均为 `RasterImage`（compose 为 N→1），全部无副作用、可独立单测。

### 6.1 `resize`（ResizeSpec）

- 基于 Thumbnailator，高质量重采样（Progressive bilinear）。
- 参数：`width`/`height`、`mode`（`FIT` 等比适配 / `FILL` 填满裁剪 / `EXACT` 强制拉伸）、`keepAspect`、`upscale`（是否允许放大，默认禁止放大以免糊）。
- 仅给单边时按等比推另一边。

### 6.2 `compress`（CompressSpec，两种语义都实现）

- **按质量**：`quality∈[1,100]`，直接以该质量编码。
- **按目标体积**：`targetBytes` 给定上限时，走**迭代质量搜索**——在质量区间内二分/递减编码，逼近但不超过目标体积，命中后返回；若最低质量仍超限，记 warn 并返回最小体积结果（不抛错，由上层决定是否接受）。迭代上限可配，避免无界重编码。
- 两种语义二选一（spec 层互斥），目标体积优先。压缩本质是编码行为，故 `compress` 操作内部复用 `ImageCodec.encode`。

### 6.3 `watermark`（WatermarkSpec，仅图片水印）

- 仅支持**图片水印**（logo），本期不做文字水印（见 [§十四](#十四暂不考虑)）。
- 参数：水印图（由 dag 解码后以 `RasterImage` 传入）、`position`（九宫格枚举：左上…正中…右下）、`opacity`（0..1）、`scale`（相对主图比例）、`margin`（边距像素）。
- 用 `Graphics2D` + `AlphaComposite` 叠加，按 position/margin 定位、按 scale 缩放水印。

### 6.4 `set_background`（SetBackgroundSpec）

- 把带 alpha 的图合成到背景之上，背景为**纯色**或**背景图**（背景图带 `fit` 适配模式：拉伸/平铺/居中）。
- 输出无 alpha 的实底图，是抠图（`remove_bg`）之后的常用下一步。
- 对不含 alpha 的输入：纯色背景为 no-op；背景图模式按语义合成（一般不会这样用，dag 侧把关）。

### 6.5 `convert_format`（ConvertFormatSpec）

- 目标格式转换；目标不可编码 → SKIP。
- 转到无 alpha 格式时套用 [§5.3](#53-alpha-扁平化策略已决策) 扁平化策略（默认白底，可覆盖）。
- 实质是「按目标 `EncodeSpec` 重新编码」，与 codec 编码路径同源。

### 6.6 `compose_group`（ComposeSpec，N→1）

- 多输入合成：一组 N 张栅格按 `layout`（`horizontal`/`vertical`/`grid`）拼成 1 张。
- 参数：`layout`（必填）、`order`（成员排序，可选）、`gap`（间隔像素）、`background`（画布底色）。
- `grid` 布局按成员数推导行列；尺寸不一的成员按统一规则对齐（如按最大宽/高留白居中）。
- **只接收一组已就绪的 `RasterImage`**：分组归属、`expected_count` 张数校验与 HITL 全在 `module/dag`+`module/file`，本操作不感知「组」的业务含义。

---

## 七、解码一次 / 编码一次流水线

性能与正确性双重要求：多步支路绝不在中间反复 decode/encode。`ImagePipeline` 编排：

```
（module/dag 调）storage.getStream → 本模块 codec.decode 一次
   → op1(RasterImage) → op2 → ... → opN     全程内存栅格
   → codec.encode 一次 →（module/dag 调）storage.put
```

```java
public interface ImagePipeline {
    // 单图链：decode 一次 → 顺序 apply → encode 一次
    byte[] run(InputStream source, List<ImageOp> ops, EncodeSpec encode);

    // 组链：N 路成员各自 decode/预处理 → compose fan-in → 合成后链 → encode 一次
    byte[] runComposed(List<InputStream> members, List<ImageOp> perMemberOps,
                       MultiImageOp compose, List<ImageOp> postOps, EncodeSpec encode);
}
```

- 单图链对应普通支路：解码一次、链式处理、编码一次。
- 组链对应组支路（`design.md §9.5`）：聚合节点之前的逐图节点对每个成员各自施加，`compose` fan-in 合成，聚合之后的节点作用于合成后的单图——这套**结构**由 `module/dag` 的 `BranchExpander` 决定并把对应 ops 列表传入；image 只按既定步骤执行，不理解「组」语义。
- 中间产物只在堆内 `RasterImage` 间流转，不落盘、不进缓存；真正需要落 MinIO 的中间产物由 dag/task 决定（`storage` 的 `TMP` 桶 + `cache` 的引用，均在上层）。

---

## 八、生产级资源治理

图像处理是内存与原生资源的高危区，MVP 不会做、本期必须做：

1. **像素炸弹防护**：`decode` 前先 `probe` 拿尺寸，`width*height` 超过 `max-source-pixels`（默认 40MP）或单边超 `max-dimension` 时，**不实际解码**，直接抛 `ImageProcessingException(SOURCE_TOO_LARGE)` → SKIP。防恶意/异常超大图瞬间 OOM（兼具稳定性与安全性）。
2. **堆占用边界**：`BufferedImage` 占约 `w*h*4` 字节。文档明确：单图堆占用 × `module/task` 并发线程数 是内存上限来源，二者需联动配置（task 并发默认 8，见 `design.md §9.3`）。
3. **原生资源释放**：scrimage/libwebp 编码路径确保 native buffer 用完即释，避免堆外泄漏；ImageIO `reader/writer` 用完 `dispose()`，`ImageInputStream`/`OutputStream` 以 try-with-resources 关闭。
4. **每调用新实例**：ImageIO reader/writer 非线程安全，封装内每次新建，模块对外零共享可变状态，满足高并发调用。
5. **迭代压缩有界**：目标体积压缩的质量搜索设迭代上限（默认 ≤8 次），避免病态输入导致无界重编码。

---

## 九、韧性与错误收口

### 9.1 与第三方韧性的边界

`infra/image` 是**纯本地 CPU 计算**，无网络调用，故**不引入 Resilience4j**（重试/熔断对纯计算无意义）。`design.md §4` 的「第三方韧性 Resilience4j」作用于 `infra/thirdparty`（抠图 API）与 `infra/ai`，不在本模块。

### 9.2 错误收口

`ImageProcessingException` 为独立领域异常，**不依赖 `common` 的分类体系**（保持底层纯粹），携带定位上下文：

```java
public class ImageProcessingException extends RuntimeException {
    private final Reason reason;     // DECODE_FAILED / ENCODE_FAILED / UNSUPPORTED_ENCODE_FORMAT
                                     // / SOURCE_TOO_LARGE / CORRUPTED_IMAGE / INVALID_OP_PARAM
    private final ImageFormat format;   // 涉及的格式（可空）
    private final Integer width;        // 涉及尺寸（可空）
    private final Integer height;
    // + message + cause
}
```

- 模块内部 catch ImageIO / scrimage 的 `IIOException`/`IOException`/解码异常，统一包成 `ImageProcessingException`，标注 `reason`。
- 跨出 infra 边界由 `common` 的 `ErrorNormalizer` 翻译为 `PixFlowException(category=IMAGE_PROCESSING)`（默认 `recovery=SKIP`），与 `common.md` §5.1 表、§10 一致；接线机制属 `common` 职责。
- `SKIP` 正好对接 `module/dag` 的 `FailureIsolator`：单「图×支路」处理失败 → 该 `process_result.status=2` + 脱敏 `error_msg`（≤1000 字），同图其余支路、批次其余图片继续（`design.md §9.4`）。
- 职责区分：**损坏图 / 不可编码格式 / 超大图 = SKIP**（隔离当前单元）；**非法操作参数**理论上已被 `module/dag` 的 `DagValidator` 参数 schema 拦截，真漏到执行期归 `INVALID_OP_PARAM`，本模块仅做防御式兜底校验（`width>0`、`quality∈[1,100]`、颜色可解析），不与 dag 的前置 schema 校验重复职责。
- 落日志/上报前，message 与上下文经 `common` 的 `Sanitizer` 脱敏（路径相对化、截断）。

---

## 十、配置

`@ConfigurationProperties(prefix = "pixflow.image")`：

```yaml
pixflow:
  image:
    max-source-pixels: 40000000     # 像素炸弹防护：宽*高 上限（默认 40MP）
    max-dimension: 12000            # 单边像素上限
    default-jpeg-quality: 85         # 未显式指定时的 JPEG 质量
    default-webp-quality: 80         # 未显式指定时的 WebP 质量
    flatten-background: "#FFFFFF"   # alpha 扁平化默认底色（白）
    target-size-max-iterations: 8    # 目标体积压缩迭代上限
    resize:
      allow-upscale: false           # 默认禁止放大
    color-space: sRGB               # 处理链统一色彩空间
```

- 配置只承载**护栏与编解码调优**（尺寸上限、默认质量、扁平化底色、迭代上限），**不承载业务参数**——具体 resize 尺寸、watermark 位置、compress 质量/目标体积来自 DAG 节点参数，由 `module/dag` 映射进 spec。
- 扁平化默认底色集中配置，单点可调；可被 `EncodeSpec.flattenBackground` 覆盖。

---

## 十一、安全与脱敏

1. **资源耗尽防护**：像素炸弹/超大图在解码前拦截（[§八](#八生产级资源治理)），防 DoS 式 OOM。
2. **解码容错**：对畸形/截断图，TwelveMonkeys 容错读取失败则归 `CORRUPTED_IMAGE` → SKIP，不崩进程。
3. **路径与上下文脱敏**：异常 message / 上下文落盘前经 `common` 的 `Sanitizer`（本模块不直接接触凭证，但统一走脱敏管线保持一致）。
4. **无外发**：纯本地计算，不发起任何网络请求，无数据外泄面。
5. **原生依赖审计**：scrimage(libwebp) 为唯一原生依赖，版本锁定、随构建审计平台二进制可用性（见 [§十四](#十四暂不考虑) 与 `design.md §15`）。

---

## 十二、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `module/dag` | 唯一主要消费方。把已校验的 DAG 节点参数映射为类型化 spec，组装 `ImagePipeline`；逐图支路调 `run`，组支路调 `runComposed`；解码源/编码结果的 MinIO I/O 由 dag 在两端缝合 |
| `infra/thirdparty` | 间接协作：`remove_bg` 抠图结果（带 alpha 的字节）由 dag 喂给本模块 `decode` 再 `set_background`/`resize` 等；本模块不直接依赖 thirdparty |
| `infra/storage` | 无直接依赖：image 不读写 MinIO；字节进出由 dag 用 `ObjectStorage` 缝合 |
| `common` | image 抛 `ImageProcessingException`，由 `ErrorNormalizer` 边界翻译为 `IMAGE_PROCESSING`（SKIP）；文案经 `Sanitizer` |
| 调用方统一 | 只依赖 `ImageCodec` / `ImagePipeline` 接口与类型化 spec，不直接接触 `BufferedImage`/`ImageIO`/`scrimage` |

**反向约束**：本模块对以上任何 module/harness/agent 零依赖、零业务词。

---

## 十三、测试策略

- **编解码往返（golden image）**：JPEG/PNG/WebP/BMP 解码→编码往返，断言尺寸/格式正确、视觉一致（结构相似度阈值），WebP 写出经 scrimage 可被回读。
- **能力矩阵**：目标格式 `canEncode==false`（TIFF/GIF）→ `UNSUPPORTED_ENCODE_FORMAT` → SKIP。
- **Alpha 扁平化**：透明 PNG → JPEG 默认白底（断言四角为白、无黑边）；带 `set_background` 后再转 JPEG 为 no-op；→ PNG/WebP 保留透明。
- **EXIF 归正**：带各 orientation 的样图解码后断言视觉正向。
- **像素炸弹防护**：构造超 `max-source-pixels`/`max-dimension` 的图（或伪造尺寸头），断言 `probe` 阶段即拒绝、**未实际解码**、归 `SOURCE_TOO_LARGE`。
- **各操作单测**：resize 三种 mode + 禁止放大；compress 按质量 / 按目标体积（断言不超目标且迭代有界）；watermark 九宫格定位/透明度/缩放；set_background 纯色/背景图；convert_format；compose 三种 layout + 尺寸不一对齐。
- **流水线**：多步支路断言**仅解码一次、编码一次**（用计数桩验证 codec 调用次数）；组链 perMember→compose→post 顺序正确。
- **并发安全**：多线程并发跑 pipeline，断言无共享状态污染、reader/writer 每次新建并 dispose。
- **错误收口**：损坏图 → `CORRUPTED_IMAGE`；断言跨边界后变成 `IMAGE_PROCESSING` 的 `PixFlowException`、`recovery=SKIP`、上下文（reason/format/dims）齐全。
- **错误码目录**：若引入 `ImageErrorCode` 则并入 `common` 启动期聚合测试（code 唯一 + i18n 齐全 + category 非空）。

> 原生依赖（scrimage WebP 写出）相关用例在 CI 无对应平台二进制时按环境跳过，开发本地必跑；纯 Java 路径（TwelveMonkeys 读、Thumbnailator、Graphics2D 合成）保证最低覆盖。

---

## 十四、暂不考虑

- **文字水印**：本期仅图片水印；文字水印需字体资源/排版，后续再加。
- **动图/多帧**（GIF/动画 WebP）：仅取首帧或判不支持，动图处理本期不做。
- **TIFF/GIF 写出**：仅支持读取，作为编码目标判「无法编码」记入跳过清单；后续按需补编码器。
- **GPU/原生加速管线**：纯 JVM 处理，未出现性能瓶颈前不引入。
- **智能裁剪 / 显著性检测 / 人脸对齐**：属视觉理解范畴，归 `module/vision`（VLLM），不在确定性像素层。
- **EXIF/元数据透传写出**：解码归正后默认丢弃源 EXIF（避免朝向二次旋转与隐私元数据外带），保留元数据需另设计。
- **色彩管理高级特性**（保留嵌入 ICC、广色域输出）：本期统一 sRGB，后续如需再扩。
