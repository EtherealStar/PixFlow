# infra —— 基础设施层

> 源码：`src/main/java/com/etherealstar/pixflow/infra/`

`infra` 层为上层业务模块提供三类横切能力：

1. **LLM 接入**（`ai/`）：把 Spring AI 封装在 `LlmClient` 接口之后；集中维护工具白名单与 DAG 解析提示词。
2. **图像处理**（`image/`）：字节 ↔ `BufferedImage` 编解码、像素工具执行器（6 种工具）、抠图客户端抽象 + 离线兜底实现。
3. **文件存储**（`storage/`）：相对路径抽象 + 路径穿越校验 + 目录约定。
4. **MyBatis Plus 配置**（`config/`）。

> 业务模块（`module.*`）可以依赖 `infra`；`infra` 绝不依赖任何 `module.*`，方向严格单向。

## 1. AI 子层（`infra/ai`）

### 1.1 `LlmClient` 抽象

```java
public interface LlmClient {
    String complete(String systemPrompt, String userPrompt);
}
```

- 系统提示词约束行为（如固定工具白名单与 schema），可为 `null` / 空。
- 用户提示词承载实际自然语言指令，不可为 `null` / 空白。
- 模型未配置 / 调用失败 / 返回空内容时抛 `LlmException`（`ai/LlmException.java`）。

### 1.2 `SpringAiLlmClient` 实现

`ai/SpringAiLlmClient.java`：

- 通过 `ObjectProvider<ChatModel>` 惰性获取 Bean —— 应用启动时不必强制装配 `ChatModel`，仅在真正调用时才校验可用性。
- 底层走 Spring AI `ChatModel.call(Prompt)`；`extractContent` 取出首个结果的文本。
- 任何底层异常统一包装为 `LlmException`，便于上层（DAG_Parser）翻译为业务错误码 `DAG_PARSE_FAILED`。

### 1.3 工具白名单与 schema

`ai/ToolCatalog.java` 是 MVP 7 个工具的**单一权威来源**：

| 工具名（wire） | 说明 | 必填参数 | 可选参数 |
|---|---|---|---|
| `remove_bg` | 去背景（抠图） | — | — |
| `set_background` | 设置纯色背景 | — | `color`（默认 `#FFFFFF`，须为合法颜色） |
| `resize` | 缩放 | `width` (>0), `height` (>0) | — |
| `compress` | 体积压缩 | `max_kb` (>0) | — |
| `watermark` | 水印 | `position`（枚举 9 个位置），`text` 或 `image` 二选一 | — |
| `convert_format` | 格式转换 | `format`（JPG/PNG/WebP） | — |
| `generate_copy` | 文案生成（独立分支） | — | `style` |

数据结构：

- `ToolDefinition(tool, description, params, notes)`：含说明、参数列表、跨参数约束说明（如 watermark 的「text 与 image 二选一」）。
- `ToolParam(name, type, required, constraint)`：单个参数定义。
- `ToolCatalog` 提供 `whitelist()` / `definitions()` / `isAllowed(tool)` / `find(tool)`。底层是固定顺序的 `LinkedHashMap`，保证提示词中工具列举稳定。

`requiredParamNames()` 在 `ToolDefinition` 中按 `required` 过滤产出。

> 这套目录与 DAG 模块的 `schema/ToolSchemaRegistry` / `schema/ToolType` 是同一份事实的两份表达：`ToolCatalog` 服务于「向 LLM 描述工具」，`ToolSchemaRegistry` 服务于「按 schema 校验」。

### 1.4 DAG 解析提示词 `DagPromptManager`

`ai/DagPromptManager.java` 构造两类提示词：

**系统提示词**（`buildSystemPrompt`）固定：

1. 工具白名单约束（只允许 `ToolCatalog` 内工具）。
2. 参数约束（每个节点 `params` 只能包含 schema 内定义的参数）。
3. 缺失必填参数**留空追问**（不猜测填充）。
4. `convert_format` 与 `compress` 共现时固定为「先 convert_format 后 compress」，由 LLM 落边固定顺序。
5. 严格输出 JSON 本身，不带 Markdown 代码块。

**用户提示词**（`buildUserPrompt`）：`"请将以下指令解析为 DAG JSON：\n" + instruction.trim()`，指令空白时抛 `IllegalArgumentException`。

### 1.5 `AiProperties`

`ai/AiProperties.java` 绑定 `pixflow.ai.*`，当前仅含 `timeoutMillis`（默认 60 秒）。模型具体连接信息（apiKey / baseUrl / model）由 `application.yml` 中的 `spring.ai.openai.*` 等 starter 配置项承担。

## 2. 图像子层（`infra/image`）

### 2.1 节点间中间态 `ImageData`

`image/ImageData.java` 不可变 record 风格 POJO，承载三件事：

- `image: BufferedImage`（当前解码图像）
- `format: String`（目标输出格式，wire 名如 `JPG/PNG/WebP`）
- `maxKb: Integer`（可选压缩目标体积）

提供 `withImage` / `withFormat` / `withMaxKb` 三个不可变更新方法。

### 2.2 `ImageCodec` 编解码

`image/ImageCodec.java` 是字节 ↔ 图像转换器：

**`decode(byte[])`**：

- 空字节流 / `ImageIO.read` 返回 `null` / 抛 `IOException` 一律抛 `ImageProcessingException`。

**`encode(ImageData)`**：

1. 依据 `format` 选写出器；`ensureCompatible` 对 `JPG/JPEG` 含 alpha 的输入做「铺白底转 RGB」处理，避免写出异常或黑底。
2. 若指定 `maxKb` 且为有损格式（`jpg/jpeg/webp`），进入 `encodeWithMaxKb`：固定 `MIN_QUALITY=0.1`、`QUALITY_STEP=0.1` 逐步降质循环，直到 ≤ `maxBytes` 或达到最低质量仍不满足时返回最小体积结果（尽力而为）。
3. 失败统一抛 `ImageProcessingException`，由执行引擎的 `FailureIsolator` 捕获并标记该支路失败。

### 2.3 `ImageProcessingException`

`image/ImageProcessingException.java` 继承 `RuntimeException`，是图像处理域的统一异常类型。`FailureIsolator` 仅识别这一类型及其原因，转换为「支路失败」语义。

### 2.4 `BackgroundRemovalClient` 抽象

`image/BackgroundRemovalClient.java`：

```java
public interface BackgroundRemovalClient {
    BufferedImage removeBackground(BufferedImage input);
}
```

第三方抠图 API（remove.bg / 阿里云智能抠图）实现此接口；调用失败 / 不可达时抛 `ImageProcessingException`，仍由 `FailureIsolator` 隔离。

### 2.5 `NaiveBackgroundRemovalClient` 离线兜底

`image/NaiveBackgroundRemovalClient.java`：

- 用图像四角像素均值估计背景色（`estimateBackgroundColor`）。
- 遍历像素，颜色距离 ≤ `COLOR_DISTANCE_THRESHOLD=60` 的置为透明（ARGB 0x00000000），其余保留。
- 是占位实现，非生产级抠图质量。
- 由 `ImageInfraConfig` 通过 `@ConditionalOnMissingBean` 注册：只要业务方提供任何 `BackgroundRemovalClient` Bean，本实现就自动让位。

### 2.6 `ImageToolExecutor` 像素工具执行器

`image/ImageToolExecutor.java` 是 DAG 引擎与图像处理的对接点：

```java
public ImageData apply(DagNode node, ImageData data);
```

按 `ToolType.fromToolName(node.getTool())` 派发到六个方法：

| 工具 | 实现要点 |
|---|---|
| `remove_bg` | 调用 `BackgroundRemovalClient`，输出切到 `PNG`（保留 alpha） |
| `set_background` | 新建 `TYPE_INT_RGB` 缓冲，铺 `parseColor` 解码后的纯色后绘制原图 |
| `resize` | Thumbnailator `forceSize(width, height)` |
| `compress` | 仅记录 `max_kb` 到 `data.withMaxKb`（**实际压缩在 encode 时发生**） |
| `watermark` | 文字走 `SansSerif` 字号 `max(12, min(w,h)/20)`、Alpha 0.5 描边；图片走 `storageService.readAllBytes` + `imageCodec.decode`，按 9 宫格锚点定位 |
| `convert_format` | 仅 `data.withFormat(format)`（**实际转码在 encode 时发生**） |

参数解析辅助：

- `stringParam`：可选字符串参数，缺省走默认值。
- `positiveIntParam`：必填正整数，类型支持 `Number` 与可解析字符串，越界抛 `ImageProcessingException`。
- `parseColor`：支持 `#RGB` / `#RRGGBB` 与具名颜色。

`generate_copy` 在本执行器中**显式拒绝**（`"generate_copy 为独立文案分支，不应作为像素节点执行"`）——它由 `CopyGenerator` 单独处理。

## 3. 存储子层（`infra/storage`）

### 3.1 `StorageService` 抽象

`storage/StorageService.java`：

```java
Path  resolve(String relativePath);
String write(InputStream content, String relativePath);
String write(byte[] content, String relativePath);
InputStream openInputStream(String relativePath);
OutputStream openOutputStream(String relativePath);
byte[] readAllBytes(String relativePath);
boolean exists(String relativePath);
long    size(String relativePath);
void    createDirectories(String relativePath);
boolean delete(String relativePath);
boolean deleteRecursively(String relativePath);
```

所有 `relativePath` 均为相对存储根、以正斜杠分隔，**不得越出根目录**。

### 3.2 `LocalFileStorageService` 本地实现

`storage/LocalFileStorageService.java`：

- `@PostConstruct init()` 把 `pixflow.storage.root`（默认 `./data/pixflow-storage`）规范化为绝对路径并 `createDirectories`。
- `resolve(relativePath)` 统一分隔符、规范化、断言 `resolved.startsWith(root)`，否则抛 `StorageException`（路径穿越防护）。
- `write` / `openInputStream` / `openOutputStream` 必要时自动 `createParent`。
- `deleteRecursively` 用 `Files.walk` + `Comparator.reverseOrder()` 自底向上删除。

### 3.3 `StoragePaths` 目录约定

`storage/StoragePaths.java` 集中定义相对路径规则，避免散落：

```
{root}/
  packages/{packageId}/source.zip
  packages/{packageId}/doc/{fileName}
  packages/{packageId}/images/{relPath}      # relPath 为 zip 内相对路径
  results/{taskId}/{fileName}                # 处理结果图
```

API：`packageDir / packageZip / packageDoc / packageImage / taskResultDir / taskResult`。
`join(String...)` 统一以正斜杠拼接、清理多余分隔符。

> 结果目录 `results/{taskId}` 不在 `packages/{packageId}/` 之下，因此 `PackageDeleter` 删除素材包时**天然不会**误删结果图。

### 3.4 `StorageProperties` 配置

`storage/StorageProperties.java` 绑定 `pixflow.storage.root`，默认 `./data/pixflow-storage`。`application.yml` 中可改成绝对路径。

### 3.5 `StorageException`

`storage/StorageException.java` 是存储层运行时的统一异常类型，包装文件 I/O 与路径解析错误。上层通常让它向上抛，由 `GlobalExceptionHandler` 兜底为 `INTERNAL_ERROR`。

## 4. 配置子层（`infra/config`）

### 4.1 `MyBatisPlusConfig`

`config/MyBatisPlusConfig.java`：

- `@MapperScan("com.pixflow.module.**.mapper")` 扫描所有业务模块的 mapper。
- 注册 `PaginationInnerInterceptor(DbType.MYSQL)`，单页 `setMaxLimit(500L)`。业务层 `size ≤ 100` 由 `Pagination` 工具保证，分页插件作为 DB 端最终兜底。
- 配合 MyBatis Plus `BaseMapper<T>` 提供零样板 CRUD。

## 5. 横切关系图

```
                              ┌───────────────────────┐
                              │     module.dag        │
                              │  (DagParser/Validator │
                              │   /ExecutionEngine)  │
                              └─────────┬─────────────┘
                                        │ 调用
        ┌───────────────────────────────┼───────────────────────────────┐
        │                               │                               │
        ▼                               ▼                               ▼
┌────────────────┐              ┌────────────────┐              ┌────────────────┐
│  infra.ai      │              │  infra.image   │              │  infra.storage │
│  LlmClient     │              │  ImageCodec    │              │  StorageService│
│  ToolCatalog   │              │  ImageToolExec │              │  StoragePaths  │
│  DagPromptMgr  │              │  BGClient      │              │  LocalFS       │
└────────┬───────┘              └────────┬───────┘              └────────┬───────┘
         │                               │                               │
         └───────────  全部被  ───────────┴────────────  全部被  ─────────┘
                                  │
                                  ▼
                            module.file / module.conversation / module.task
```

`infra` 内部各子包也基本无环：

- `infra.ai` 不依赖 `infra.image` / `infra.storage`。
- `infra.image` 依赖 `infra.storage`（水印图片读取）+ `module.dag.domain.DagNode` + `module.dag.schema.ToolType`。
- `infra.storage` 是纯 I/O，不依赖任何 `infra` 子包。
