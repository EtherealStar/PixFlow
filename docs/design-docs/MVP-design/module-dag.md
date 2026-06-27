# module-dag —— DAG 解析、校验与执行

> 源码：`src/main/java/com/etherealstar/pixflow/module/dag/`

DAG 模块是 PixFlow 的「指令 → 工具编排 → 像素处理」核心。包含五个子包：

```
dag/
├── domain/       Dag / DagNode / DagEdge / Branch —— 纯领域对象
├── parser/       DagParser / DagNormalizer / DagParseResult / MissingParam
├── schema/       ToolType / ToolSchemaRegistry / ToolParamSchema / ParamValidators / ...
├── validator/    DagValidator —— 服务端独立严格校验
├── engine/       BranchExpander / TopologicalSorter / DagExecutionEngine / ImageWorkerPool
│                 / FailureIsolator / CopyGenerator / ConcurrencyGauge / EngineProperties
└── DagJsonCodec  序列化往返
```

依赖方向：`infra.ai`（LLM 抽象、工具白名单）→ `module.dag` ← `infra.image` / `infra.storage`（执行期 I/O）。

## 1. 领域对象 `domain/`

不可变 POJO，仅承载数据与最基础的关系查询：

| 类 | 字段 | 说明 |
|---|---|---|
| `DagNode` | `id, tool, params: Map<String,Object>` | 工具节点；`params` 在构造时拷贝为 `Collections.unmodifiableMap` |
| `DagEdge` | `from, to` | 有向边（可变 setter，因为 Jackson 反序列化需要；`equals/hashCode` 基于值） |
| `Dag` | `nodes, edges, nodeById` | 提供 `successors(id)`、`sources()`（无入边节点）、`sinks()`（无出边节点） |
| `Branch` | `branchId, nodeSequence: List<String>` | source→sink 一条支路；`branchId` 在 DAG 内唯一 |

`Dag` 持有节点声明顺序，遍历（`successors` / `sources` / `sinks`）保留边声明顺序，方便行为确定化与属性测试。

## 2. 工具白名单与参数 schema `schema/`

`schema/ToolType` 是工具白名单的**单一权威枚举**：

- 8 个常量（`remove_bg / set_background / resize / compress / watermark / convert_format / generate_copy / compose_group`），每个携带 wire name（如 `"remove_bg"`）。
- `fromToolName(String)` 提供 case-sensitive 查找；`whitelist()` 一次性产出 set。

`schema/ToolSchemaRegistry` 是**按工具组织**的参数 schema 表：

| 工具 | 必填 | 可选 | 二选一 |
|---|---|---|---|
| `remove_bg` | — | — | — |
| `set_background` | — | `color`（默认 `#FFFFFF`，颜色校验） | — |
| `resize` | `width(>0)`, `height(>0)` | — | — |
| `compress` | `max_kb(>0)` | — | — |
| `watermark` | `position`（枚举 9 位置） | — | `text` 或 `image`（`nonBlankString` 校验） |
| `convert_format` | `format`（枚举 JPG/PNG/WebP） | — | — |
| `generate_copy` | — | `style`（任意字符串） | — |
| `compose_group` | `layout`（枚举 horizontal/vertical/grid） | `order`、`expected_count`（正整数）、`gap`、`background`（颜色校验） | — |

辅助类型：

- `ImageFormat` / `WatermarkPosition`：枚举 + `wireNames()` 集合。
- `ParamValueValidator`：`Optional<String> validate(name, value)` 函数式接口。
- `ParamValidators`：正整数、枚举、颜色、非空字符串、任意字符串等工厂方法。
- `ParamValidationResult(valid, errors)`：构造器 `passed()` / `invalid(errors)`。
- `ToolParamSchema`：通过 `Builder` 构造；`validate(Map)` 严格校验：未知参数 / 必填缺失 / 取值违规 / one-of 组均缺失四类全部报错；`withDefaults(Map)` 仅补全声明了 `defaultValue` 的可选参数。

## 3. DAG 解析 `parser/`

### 3.1 `DagParser`

`parser/DagParser.java` 串联：LLM 调用 → JSON 解析 → 规范化 → 缺参检查。

主流程：

```
parse(instruction)
   │
   ├── instruction 为空 → DAG_PARSE_FAILED
   │
   ├── promptManager.buildSystemPrompt() / buildUserPrompt(instruction)
   │
   ├── llmClient.complete(...)              ── LlmException → DAG_PARSE_FAILED
   │
   ├── toDag(rawOutput)                     ── 去 ``` 围栏、读 JSON、nodes/edges 必填数组
   │                                          非白名单 tool → DAG_PARSE_FAILED
   │
   ├── normalizer.normalize(dag)            ── 见 §3.2
   │
   ├── detectMissingParams(dag)             ── 缺必填 / one-of → 列出 MissingParam
   │       │
   │       └── 存在缺失 → DagParseResult.missing(...)
   │
   └── 否则 → DagParseResult.preview(dag, ...)
```

`DagParseResult` 是 record：`needConfirm, missingParams, dagPreview, reply, taskId`；`taskId` **恒为 null**（任务仅在用户 `/confirm` 后由 Task_Manager 创建）。

`MissingParam(nodeId, param)`：`param` 对于 one-of 组以 `"text|image"` 形式表达「任一即可」。

### 3.2 `DagNormalizer`

`parser/DagNormalizer.java` 解决 LLM 输出非确定性。Kahn 拓扑选择 + 一致全序：

- 入度为 0 的节点入「就绪集合」。
- 每步选择：
  1. 若仍有 `convert_format` 节点未放置，则在「非 compress」的就绪节点中按原始声明位置选最小者；
  2. 若就绪集合中只剩 `compress`，则被迫选择 `compress`；
  3. 否则按原始声明位置选最小者。
- 边去重并按 `(from, to)` 字典序排序。
- 环不在此抛错（`DagValidator` 阶段负责）。

> 这层规范化是「先 convert_format 后 compress」在服务端的二次保障，与 `DagPromptManager` 提示词约束相辅。

## 4. DAG 校验 `validator/DagValidator`

`validator/DagValidator.java` 是 DAG 的服务端**独立严格校验**入口，**不信任前端回传结构**。校验顺序固定（fail-fast）：

| # | 校验 | 错误码 |
|---|---|---|
| 1 | JSON 可解析 + 顶层对象 + `nodes`/`edges` 数组 + 各节点含 `id` | `DAG_STRUCTURE_INVALID` |
| 2 | 节点数 ∈ [1, maxNodeCount] | `DAG_NODE_COUNT_INVALID` |
| 3 | 每个节点 `tool` 在白名单内 | `DAG_INVALID_TOOL` |
| 4 | 每个节点 `params` 满足对应 schema（错误含 `nodeId`） | `DAG_PARAM_INVALID` |
| 5 | 每条边 `from`/`to` 引用存在的节点 | `DAG_EDGE_INVALID` |
| 6 | DAG 可拓扑排序（无环） | `DAG_CYCLE_DETECTED` |

`DagValidationProperties` 绑定 `pixflow.dag.maxNodeCount`（默认 50）。

## 5. 序列化 `DagJsonCodec`

`DagJsonCodec.write(Dag)` 产出 `{nodes:[{id,tool,params}], edges:[{from,to}]}`：

- 保留节点 / 边声明顺序；`params` 以 `ObjectMapper.valueToTree` 原样写出（`null` 视为空对象）。
- 序列化失败抛 `INTERNAL_ERROR`。
- 与 `DagValidator.validateJson` 的解析互逆：`parse(write(dag)).equals(dag)` 恒成立（Property 35）。

`TaskService.createTask` 持久化时**只接受已校验通过的 `Dag`**，因此入库的 `dag_json` 一律来自规范序列化。

## 6. 执行引擎 `engine/`

### 6.1 总体流程

`engine/DagExecutionEngine.execute(task, dag, images)`：

```
loadCopyContext(packageId)            ── 按 sku_id 索引 AssetCopy
branchExpander.expand(dag)            ── 展开为 source→sink 支路，分配 branchId
build workUnits[image × branch]       ── processUnit Callable
workerPool.runAll(workUnits, gauge)   ── 固定大小线程池，最大并发 ≤ maxConcurrency
finalizeTask(task, imageCount, ...)   ── 终态 2/3，写 finished_at
```

### 6.2 `BranchExpander`

`engine/BranchExpander.java`：

- 按 `Dag.sources()` 顺序从每个 source 出发深度优先遍历。
- 「走到 sink」即收集为一条 `Branch`：`branchId = "branch-{n}"`，`n` 单调递增保证全局唯一。
- 单次遍历内通过 `onPath` 防御环（虽然校验已拒绝环）。
- 顺序确定：同 DAG 永远产出同样的 `branchId` 序列。

### 6.3 `TopologicalSorter`

`engine/TopologicalSorter.java`：

- 分层 Kahn 算法，每层为当前入度 0 的节点集合。
- 同一层节点 `id` 升序、跨层严格先后 —— 满足「每个节点在其全部前驱完成之后才执行」（需求 8.1）。
- 不能完成全量分层 → 抛 `CycleDetectedException(remainingNodeIds)`（`engine/CycleDetectedException.java`）。
- `hasCycle(dag)` 供 `DagValidator` 复用。

### 6.4 `ImageWorkerPool` + `ConcurrencyGauge`

`engine/ImageWorkerPool.java`：

- 每次批处理创建独立 `Executors.newFixedThreadPool(maxConcurrency, namedThreadFactory)`，完成后 `shutdown`。
- 线程名 `pixflow-image-worker-{n}`、daemon 线程。
- `runAll(tasks, gauge)`：提交全部 `Callable`、逐 `Future.get` 取结果。
- `instrument(task, gauge)` 包装进入 / 退出，便于校验「最大并发 ≤ maxConcurrency」不变量（Property 26）。

`ConcurrencyGauge`（`engine/ConcurrencyGauge.java`）用 `AtomicInteger` 记录实时并发与峰值，线程安全。

### 6.5 `ImageToolExecutor` 像素工具应用

执行引擎在 `runImageBranch` 中按支路节点序列**顺序**调用 `infra/image/ImageToolExecutor.apply`，最终在汇节点 `ImageCodec.encode` 写出：

```java
ImageData data = new ImageData(decode(sourceBytes), initialFormat(...));
for (String nodeId : branch.getNodeSequence()) {
    data = imageToolExecutor.apply(dag.getNode(nodeId), data);
}
byte[] output = imageCodec.encode(data);   // 在此按 format + maxKb 实际写盘
storageService.write(output, StoragePaths.taskResult(taskId, fileName));
```

结果文件名 `outputFileName(image, branch, format)` = `{sanitize(skuId)}_{imageId}_{branchId}.{ext}`，保证同图多支路命名互不冲突。

`initialFormat` 依据原图扩展名推断初始 wire 格式（jpg→JPG、webp→WebP、其它→PNG），`convert_format` 在流水线中可覆盖。

### 6.6 文案分支 `CopyGenerator`

`engine/CopyGenerator.java`：

- 检测支路含 `generate_copy` → 走 `runCopyBranch`（独立分支，不依赖像素输出）。
- 输入仅为该 SKU 的 `AssetCopy` 上下文（有则用，无则回退到「以图片本身为依据」），可选 `style` 来自 `generate_copy.params.style`。
- 调用 `LlmClient.complete(system, user)` 生成文案，截断到 `engine.copyMaxLength`（默认 2000）。
- LLM 失败由 `FailureIsolator` 隔离。

### 6.7 `FailureIsolator`

`engine/FailureIsolator.java` 统一处理单工作单元异常：

- 任何 `Throwable`（不仅是 `ImageProcessingException`）都会被捕获。
- `result.status = 2`（失败）、`result.errorMsg = truncate(throwable.getMessage())`（默认截断到 1000 字符）、`result.outputPath = null`（不保留半成品）。
- 同图其余支路、批次其余图片、其余 SKU 的文案生成均继续处理。

### 6.8 任务终态

`finalizeTask`：

- 至少一条 `status=1` → 任务 `status=2`（完成）。
- 所有结果均失败 → 任务 `status=3`（失败）。
- 无任何结果（极端情况）→ `status=2`（无可处理项）。
- 写 `doneCount = imageCount`、`finishedAt = now()`。

返回 `DagExecutionSummary(taskId, status, totalCount, doneCount, resultCount, successCount, failureCount)`。

### 6.9 `EngineProperties`

`engine/EngineProperties.java` 绑定 `pixflow.engine.*`：

| 字段 | 默认 | 含义 |
|---|---|---|
| `maxConcurrency` | 8 | 线程池大小（最大并发） |
| `copyMaxLength` | 2000 | 生成文案最大字符数 |
| `errorMsgMaxLength` | 1000 | 失败原因最大字符数 |

## 6.10 分组聚合（组支路）

支持「三视图 → 合成图」一类 N→1 加工，组维度与支路正交。分组由文件名编号决定（`asset_image.group_key`，见 `module-file.md` §4.3.1），引擎按组自动展开，**Agent 不枚举成员**。

- **组支路识别**：`BranchExpander` 将含 `compose_group` 节点的支路标记为组支路。`DagValidator` 增校验：组支路内 `compose_group` 唯一、其前驱均为逐图工具。
- **工作单元**：普通支路仍按 `[图片 × 支路]`；组支路按 `[组 × 支路]`。组支路单元内部——聚合节点之前的逐图节点对组内每张成员各自施加 → `compose_group` fan-in 合成单图 → 之后的节点作用于合成图 → 写出 `results/{taskId}/group_{groupKey}_{branchId}.{ext}`。
- **张数预期（HITL，确认时）**：`compose_group.params.expected_count` 来自用户指令（如「三张拼接」）。`TaskService.confirm` 前按组比对实际成员数，不一致则不创建任务、回 HITL 二次确认；未指定 `expected_count` 则以实际成员为准。
- **断点缓存**：组成员中间产物缓存键 `group:cache:{taskId}:{groupKey}:{branchId}:{imageId}`，**整组聚合成功后统一删**；非组支路沿用 `branch:cache:*` 即删。
- **失败隔离**：`FailureIsolator` 在组粒度生效——组支路内任一成员读取/解码/处理失败 → 整条组支路 `status=2`、记 `error_msg`、不留 `output_path`；其他组、普通支路、其余图片继续。
- **结果与恢复**：组结果写 `process_result`（`group_key` 非空、`image_id` 空），成员明细写 `process_result_member`；恢复时按组结果存在与否整组幂等重算/跳过。

## 7. 执行期时序（一张图 × 一条支路）

```
ImageWorkerPool thread
  │
  ├── gauge.enter()
  │
  ├── processUnit(task, dag, branch, image, copy)
  │     ├── isCopyBranch(branch) ?
  │     │     ├── 是 → CopyGenerator.generate(copy, image, style)
  │     │     │         → result.setGeneratedCopy(text)
  │     │     └── 否 → runImageBranch
  │     │              ├── storageService.readAllBytes(sourcePath)
  │     │              ├── imageCodec.decode(bytes)
  │     │              ├── 沿支路节点顺序 apply(node, data)
  │     │              │     - remove_bg / set_background / resize / watermark 等
  │     │              ├── imageCodec.encode(data)   // 按 format + maxKb 实际写盘
  │     │              └── storageService.write(bytes, outputPath)
  │     │                   → result.setOutputPath(path)
  │     │
  │     ├── 成功 → result.status = 1
  │     └── 失败 → FailureIsolator.markFailed(result, t)
  │
  ├── persist(result)              ── processResultMapper.insert
  │
  └── gauge.exit()
```

## 8. 与其它模块的接口

- **被 `module.conversation` 依赖**：`ConversationController.send` 调 `DagParser.parse`；`Message.task_id` 由 `attachTask` 后续填充。
- **被 `module.task` 依赖**：`TaskService.confirm` 调 `DagValidator.validateJson`、`DagJsonCodec.write` 持久化、`DagExecutionEngine.execute`。
- **被 `infra.image` 引用**：`ImageToolExecutor` 读取 `DagNode.getTool()` 与 `ToolType.fromToolName`（**单向**：infra 不被 module 反向依赖）。
