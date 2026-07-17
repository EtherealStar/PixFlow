# module/dag —— DAG 编译/校验/分支展开/确定性单元执行（Wave 3 确定性核心）

> 本文是 PixFlow 完整重写阶段 `module/dag` 模块的设计文档，对应 `design.md` 第二章设计原则一/二（两层循环分离、确定性底座不被污染）、第五章 §5.2（两套工具严格分离）、第九章「DAG 确定性执行引擎」（§9.1 编译校验分支展开、§9.4 断点恢复与失败隔离、§9.5 分组聚合）、第十三章数据模型，以及 `module-dependency-dag-plan.md` 的 **Wave 3 横切组合 + 确定性核心**。
> 范围：DAG 中间表示与校验、`submit_image_plan` 提案接线、分支/组支路展开、把一条已展开支路缝合为 `infra/{thirdparty,image,ai,storage}` 调用链的**确定性单元执行器**、`generate_copy` 独立文案分支、失败的源头归一化。
> 配套阅读：`harness/tools.md`（`submit_image_plan` 工具边界）、`infra/image.md`（类型化像素操作与 `ImagePipeline`）、`infra/thirdparty.md`（抠图 `BackgroundRemovalClient`）、`infra/ai.md`（`generate_copy` 走 `ChatModelClient`）、`harness/state.md`（`RunStateRefStore`/`UnitKey`/恢复协调）、`module/file.md`（Asset Reference 与图片来源）、`base/asset-references.md`（统一素材身份）、`base/common.md`（错误归一化/脱敏）。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、核心边界：dag 是确定性库，task 是异步外壳](#二核心边界dag-是确定性库task-是异步外壳)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、DAG 中间表示与像素工具白名单](#四dag-中间表示与像素工具白名单)
- [五、DagValidator：可重复调用的服务端校验](#五dagvalidator可重复调用的服务端校验)
- [六、Proposal 发布与确认边界](#六proposal-发布与确认边界)
- [七、BranchExpander：分支与组支路展开](#七branchexpander分支与组支路展开)
- [八、确定性单元执行器（缝合 infra 的核心）](#八确定性单元执行器缝合-infra-的核心)
- [九、generate_copy 独立文案分支](#九generate_copy-独立文案分支)
- [十、失败隔离边界与错误归一化](#十失败隔离边界与错误归一化)
- [十一、中间产物引用与恢复（走 state，缓存收窄到组支路）](#十一中间产物引用与恢复走-state缓存收窄到组支路)
- [十二、配置](#十二配置)
- [十三、对其他模块的契约](#十三对其他模块的契约)
- [十四、测试策略](#十四测试策略)
- [十五、对 design.md 与依赖计划的细化](#十五对-designmd-与依赖计划的细化)
- [十六、暂不考虑](#十六暂不考虑)
- [Revision Notes](#revision-notes)

---

## 一、文档定位与设计原则

`module/dag` 在依赖 DAG 中处于 **Wave 3**，依赖 `infra/image`（类型化像素操作）、`infra/ai`（`generate_copy` 文案）、`infra/thirdparty`（抠图）、`infra/storage`（MinIO I/O 缝合）、`harness/state`（中间产物引用 `RunStateRefStore`）、`common`（错误/脱敏）。被 `harness/tools`（`submit_image_plan` handler bean）、`module/task`（异步外壳逐单元调用执行器）、`module/conversation`（确认 REST 边界调 preflight）消费。

模块专属设计原则：

1. **确定性底座不被污染**。dag 是 `design.md` 设计原则一/二里「下层确定性执行引擎」的落点：可预测、可测试、无自主迭代、无随机性。dag 内**绝不出现** think-act-observe、LLM 决策续轮、线程池、MQ 消费、Redis 锁/进度自增。它是一组**纯函数式 + 无状态**的服务（校验器、展开器、单元执行器），由 `module/task` 的异步外壳逐单元调用（见 [§二](#二核心边界dag-是确定性库task-是异步外壳)）。
2. **两套工具严格分离**。dag 拥有 **DAG 级像素工具**白名单（`remove_bg`/`set_background`/`resize`/`compress`/`watermark`/`convert_format`/`generate_copy`/`compose_group`）的语义、参数 schema 与派发；它们**绝不进入** `harness/tools` 的 Agent 级注册表（`design.md §5.2`、`tools.md §一`）。Agent 永不直接调像素工具，只通过 `submit_image_plan` 提交 DAG 提案。
3. **dag 不拥有用户决策闸门**。dag 提供 Proposal 发布前的确定性校验、展开、preflight 与单元执行能力；Conversation/permission 拥有一次确认的硬边界。系统没有确认令牌或二次确认。
4. **像素语义集中、I/O 在两端缝合**。`infra/image` 是「纯计算无 I/O」（`image.md §一`），`infra/thirdparty`/`infra/ai` 是「存储无感」。把「从 MinIO 取字节 → 调第三方/像素/模型 → 写回 MinIO」的缝合收口在 dag 的单元执行器，是 dag 不可替代的职责（`image.md §二` 明确「缝合在 module/dag」）。
5. **领域输入靠中立投影喂入，不直连业务表**。dag 不依赖 `module/file`、不直连 `asset_image`/`asset_copy`/`process_*` 表。分组成员、文案上下文由调用方（task）以 dag 自有的中立 record 喂入（见 [§七](#七branchexpander分支与组支路展开)、[§九](#九generate_copy-独立文案分支)）。这保持 dag 只懂「DAG 语义 + 像素缝合」，与它「无状态确定性库」的定位一致。
6. **生产级、不简化**。节点上限、无环校验、组支路 fan-in、确定性 branchId、失败源头归一化、像素炸弹兜底（委托 image）、确认边界独立重校验齐备，不走 MVP 捷径。

---

## 二、核心边界：dag 是确定性库，task 是异步外壳

`design.md §12` 把「执行引擎」列在 `module/dag`，又把「fan-out / 进度 / 断点恢复 / 失败隔离」列在 `module/task`，字面上「执行引擎」与「失败隔离」归属含混。本模块据 `design.md` 设计原则一/二（两层循环分离、确定性底座不被污染）与各 infra 文档的缝合描述，把这条线**明确钉死**：

| 维度 | `module/dag`（本模块） | `module/task`（Wave 4） |
|---|---|---|
| 性质 | **无状态、线程无关、确定性** 的库/服务 | **有状态、并发、异步** 的编排外壳 |
| 拥有 | DagValidator、CanonicalJson、DagCompiler、像素工具白名单/schema、提案、BranchExpander、typed 单元执行器、GroupPreflight | MQ、成员加载、Work Unit fan-out、结果/进度、Redisson RLock、run_epoch/heartbeat、取消、恢复与下载 |
| 不碰 | 线程池、MQ、Redis 进度/锁/取消、`process_*` 落库、任务终态判定 | 像素语义、DAG 结构解析、节点→工具映射、缝合细节 |
| 失败隔离 | 单元执行器返回含结构化最终 `UnitFailure` 的 FAILED，不保存节点 checkpoint/history | 当前 epoch fenced 落 FAILED + 继续兄弟；全成功→COMPLETED，混合→PARTIAL，全失败→FAILED |

一句话：**dag 回答「这条支路对这张图/这组图跑出来是什么」，task 回答「这一批要跑哪些单元、并发多少、断点怎么续、进度怎么报」。** dag 的「执行引擎」是一个被 task worker 逐单元调用的纯服务，不是一个自己跑批的东西。这样「确定性底座不被污染」才真正落地：dag 里没有任何 while/线程/MQ。

> 设计取舍：为什么不把 fan-out 也放 dag？因为 fan-out 必然绑定线程池、进度计数、取消检查、`process_result` checkpoint 这些**有状态运行态**，一旦进 dag，dag 就不再是可独立属性测试的确定性库，且会与 `harness/state` 的运行态读模型、`module/task` 的恢复扫描职责重叠。把并发与状态全留在 task，dag 专注「单元 → 结果」的确定性映射，是两层循环分离原则的硬要求。

---

## 三、模块结构与依赖位置

Maven 模块：`pixflow-module-dag`（需加入根 `pom.xml` `<modules>` 与 `dependencyManagement`）。源码包：`com.pixflow.module.dag`

```
module/dag/
├── DagFacade.java                    # 对外门面：validate / proposeImagePlan / expand / executeUnit / preflightGroups
├── ir/
│   ├── DagDocument.java              # 解析后的 DAG IR（nodes + edges，未校验）
│   ├── DagNode.java                  # record(id, tool, params:Map<String,Object>)
│   ├── DagEdge.java                  # record(from, to)
│   ├── PixelTool.java                # 像素工具白名单枚举 + 基数语义（N→1 / 1→1 / 文案）
│   ├── CanonicalDag.java             # 校验+规范化后的持久化事实
│   └── DagJsonReader.java            # JSON → DagDocument（浅解析，不信任结构）
├── compile/
│   ├── CanonicalJson.java            # 唯一 canonical bytes/hash
│   ├── DagCompiler.java              # CanonicalDag → TypedExecutionPlan（纯函数）
│   ├── TypedExecutionPlan.java       # typed steps + real dependency edges
│   ├── ExecutionStep.java            # External/LocalImage/Group/Copy sealed types
│   └── StepBindingRegistry.java      # PixelTool 全集 mapper/executor binding；启动 fail-fast
├── validate/
│   ├── DagValidator.java             # 服务端独立严格校验（多次可调用、无状态）
│   ├── ParamSchemaRegistry.java      # 每像素工具的 JSON Schema（参数校验单一事实源）
│   ├── DagValidationResult.java      # ok | 逐项错误（结构/白名单/参数/边/环/组规则）
│   └── rule/                         # 结构 / 节点数 / 白名单 / 参数 / 边引用 / 无环 / 组支路 规则
├── propose/
│   ├── ValidatedImagePlan.java       # canonical references + member snapshot + canonical DAG + hash
│   └── SubmitImagePlanHandler.java   # 深校验 + preflight + 返回 validated Proposal payload
├── expand/
│   ├── BranchExpander.java           # TypedExecutionPlan + 图片成员 → List<ExecutableBranch>
│   ├── ExecutableBranch.java         # 一条可执行支路：有序 op 计划 + 单元身份 + 类型
│   ├── BranchId.java                 # 确定性 branchId 派生（同 DAG 同路径 → 同 id）
│   ├── ImageDescriptor.java          # 中立输入 record(imageId, skuId, groupKey, viewId, objectKey, contentType)
│   └── GroupPreflight.java           # expected_count vs 实际成员数比对结果（发布前事实校验）
├── exec/
│   ├── UnitExecutor.java             # 单元执行器接口：execute(ExecutableBranch, UnitInput) -> UnitOutcome
│   ├── PipelineUnitExecutor.java     # 逐图支路：storage.get → 缝合 → image.run → storage.put
│   ├── GroupUnitExecutor.java        # 组支路：N 成员 → image.runComposed → storage.put
│   ├── CopyUnitExecutor.java         # generate_copy 文案支路：asset_copy 上下文 → ai → 文本
│   ├── UnitInput.java                # 单元输入（图片字节来源引用 + 可选 CopyContext）
│   ├── CopyContext.java              # 中立输入 record(skuId, productName, keywords, description)
│   ├── UnitOutcome.java              # SUCCEEDED(...) | FAILED(UnitFailure)
│   └── UnitFailure.java              # 最终节点故障快照（非 history/checkpoint）
├── error/
│   └── DagErrorCode.java             # enum implements common.ErrorCode
└── config/
    └── DagProperties.java            # @ConfigurationProperties(pixflow.dag)
```

依赖方向：

```
module/dag ──► infra/image      （类型化像素操作 + ImagePipeline.run/runComposed）
module/dag ──► infra/thirdparty  （remove_bg → BackgroundRemovalClient.remove）
module/dag ──► infra/ai          （generate_copy → ChatModelClient）
module/dag ──► infra/storage     （两端缝合：取源图字节、写结果/中间产物）
module/dag ──► harness/state     （中间产物引用 RunStateRefStore；仅组支路用，见 §十一）
module/dag ──► common            （PixFlowException / ErrorCode / Sanitizer / ErrorNormalizer 边界）
module/dag ──► base Asset Reference API（canonical key 解析后的中立成员快照）

harness/tools ──► module/dag     （收集 SubmitImagePlanHandler 的 ToolDescriptor/ToolHandler bean）
module/task   ──► module/dag     （调 DagCompiler/BranchExpander/UnitExecutor；喂中立输入）
module/conversation ──► module/dag（确认 REST 边界调 DagValidator 重校验 + GroupPreflight HITL）
```

**反向约束**：dag **不依赖** `module/file` 内部实现（分组成员/文案以中立 record 喂入）、**不依赖** `module/task`/`harness/loop`/`agent`、**不依赖** `infra/cache`（中间产物引用经 `harness/state`）。dag 不拥有 Proposal 存储，也不依赖 confirmation/pending-plan contracts。

> `harness/tools` 对 dag 零编译依赖；`SubmitImagePlanHandler` 在 dag 实现 `ToolHandler` 并把 `ToolDescriptor` 暴露为 `@Bean`，由 Tool Registry 自动收集。
---

## 四、DAG 中间表示与像素工具白名单

### 4.1 DAG IR

Agent 直接产出 DAG JSON（nodes/edges）作为 `submit_image_plan` 入参（`design.md §9.1`，省掉单独 Agent tool 的二次 LLM 往返）。这个 JSON 是 `ImagePlan` 提案；dag 先把它解析、校验并规范化为不可变 Canonical DAG，再由纯函数编译器生成类型化执行计划：

```java
public record DagNode(String id, PixelTool tool, Map<String,Object> params) {}
public record DagEdge(String from, String to) {}
public record DagDocument(List<DagNode> nodes, List<DagEdge> edges) {}     // 浅解析，未校验
public record CanonicalDag(byte[] canonicalJson, String schemaVersion,
                           List<DagNode> nodes, List<DagEdge> edges) {}     // 校验+规范化，持久化事实
public sealed interface ExecutionStep permits ExternalStep, LocalImageStep, GroupStep, CopyStep {}
public record TypedExecutionPlan(String canonicalHash, String schemaVersion,
                                 List<ExecutionStep> steps, List<ExecutionEdge> edges) {}
```

- `DagJsonReader` 只做浅解析（合法 JSON object、含 `nodes`/`edges` 顶层键、节点 `tool` 可识别为白名单枚举），结构正确性交 `DagValidator`。
- `CanonicalJson` 在校验通过后生成唯一 canonical bytes；`process_task.dag_json` 保存这份 Canonical DAG，不保存 Agent 原始 JSON，也不把 Java 运行时对象序列化为事实源。
- `DagCompiler.compile(CanonicalDag)` 一次性把 params 映射为类型化 spec，并保留真实依赖边；编译结果可在 worker 启动/恢复时确定性重建，不作为新的持久事实表。
- `tools` 层对 `submit_image_plan.dag` 只做「是合法 JSON object、含 nodes/edges」的浅校验（`tools.md §十四`），**深度校验在本模块的 handler 内**——这是「tools 不理解 DAG 语义」的兑现点。

### 4.2 像素工具白名单（PixelTool 枚举）

`design.md §9` 白名单的语义、参数 schema、派发目标、输入基数集中在 `PixelTool`：

| tool | 输入基数 | 派发目标（§八） | 参数（映射到的 infra spec） |
|---|---|---|---|
| `remove_bg` | 1→1 | `infra/thirdparty` | `BackgroundRemovalOptions`（outputFormat/crop/featherRadius） |
| `set_background` | 1→1 | `infra/image` | `SetBackgroundSpec`（纯色或背景图 + 适配模式） |
| `resize` | 1→1 | `infra/image` | `ResizeSpec`（width/height/mode/keepAspect/upscale） |
| `compress` | 1→1 | `infra/image` | `CompressSpec`（quality 或 targetBytes，互斥） |
| `watermark` | 1→1 | `infra/image` | `WatermarkSpec`（水印图引用 + 九宫格/透明度/缩放/边距） |
| `convert_format` | 1→1 | `infra/image` | `ConvertFormatSpec`（目标格式 + 质量 + alpha 扁平化底色） |
| `compose_group` | **N→1** | `infra/image` | `ComposeSpec`（layout/order/gap/background）+ 可选 `expected_count` |
| `generate_copy` | 文案（不串像素流水线） | `infra/ai` | 文案生成参数（风格/长度约束等） |

- 输入基数是分支展开与校验的关键：`compose_group` 是唯一 N→1 节点（组支路 fan-in），其余逐图工具 1→1；`generate_copy` 不在像素链上（独立文案分支，见 [§九](#九generate_copy-独立文案分支)）。
- 白名单是**封闭枚举**：未知 `tool` 名在校验期即拒，不存在运行时动态注册像素工具（与像素工具「确定性处理单元」定位一致）。

---

## 五、DagValidator：可重复调用的服务端校验

`DagValidator` 是**无状态、可被多次调用**的服务端独立严格校验器。它在两个时机各跑一次（`design.md §9.1/§14`「服务端独立校验、不信任前端回传」）：① `submit_image_plan` 提案入队前；② 确认 REST 边界创建任务前。两次都用同一份校验逻辑；第二次校验后立即 canonicalize + compile，保证「确认内容、持久化内容、执行内容」一致。

校验顺序（任一失败即收集为 `DagValidationResult` 的逐项错误，不创建任何后续产物）：

```mermaid
flowchart TD
  P["DagDocument（浅解析）"] --> S1["结构：nodes/edges 非空、id 唯一、引用完整"]
  S1 --> S2["节点数：1 ≤ |nodes| ≤ 50"]
  S2 --> S3["白名单：每节点 tool ∈ PixelTool"]
  S3 --> S4["参数 schema：ParamSchemaRegistry 按 tool 校验 params"]
  S4 --> S5["边引用：from/to 指向存在节点"]
  S5 --> S6["无环：拓扑可排序（DAG）"]
  S6 --> S7["组支路规则：compose_group 唯一/前驱均逐图工具"]
  S7 --> S8["链首约束：remove_bg 必须是首个非 source 节点"]
  S8 --> Ok["CanonicalDag → DagCompiler → TypedExecutionPlan"]
```

要点：

- **节点数上限 50**（`design.md §9.1`），防超大图编译。
- **参数 schema 单一事实源**：`ParamSchemaRegistry` 持有每个 `PixelTool` 的 JSON Schema（networknt 校验，与 `tools.md §十四` 的校验栈一致）。缺必填参数不自动猜测——LLM 在编译期就应逐项追问用户（`design.md §9.1`「不自动猜测」）；漏到校验期则列为逐项错误。
- **组支路规则**（对应 `design.md §9.5`、`image.md §6.6`）：任一含 `compose_group` 的支路里，`compose_group` 必须唯一，且其所有前驱节点必须是**逐图工具**（不可在聚合前再放一个聚合）。
- **链首约束（remove_bg 必须是逐图节点序列的第一个）**：`remove_bg` 是第三方抠图，输出带 alpha 的 PNG；其后再串其他像素操作（resize/watermark 等）等于在抠图结果上再处理，语义正确但资源浪费（先把图整张发送第三方，再让 image 二次处理）。约束：`remove_bg` 必须出现在该路径的**第一个**非 source 节点位置（普通支路整条链的第一个节点；组支路 perMemberOps 的第一个节点）。违例 → `DAG_INVALID_OP_ORDER`。
- **无环**：以拓扑排序判定；存在环则 `DAG_HAS_CYCLE`。
- `DagValidator` 只看 DAG 结构与参数；`GroupPreflight` 在 Proposal 发布前使用已解析成员快照比对 `expected_count`，两者职责分离。

---

## 六、Proposal 发布与确认边界

dag owns deterministic validation and compilation for `submit_image_plan`; Conversation owns the ephemeral Proposal runtime and user decision API. There is no `pending_plan` table, shared pending-plan port, confirmation token, or second-confirmation state.

### 6.1 Proposal publication

```text
Agent calls submit_image_plan(referenceKeys, dag, note?)
  -> parse canonical Asset References
  -> backend expands PACKAGE/SKU/IMAGE and deduplicates concrete images
  -> DagJsonReader parses
  -> DagValidator validates structure and tool parameters
  -> CanonicalJson normalizes and DagCompiler compiles
  -> GroupPreflight compares expected_count with the resolved member snapshot
  -> permission/readiness/count checks complete
  -> on any technical error: return a structured tool error to the same Agent turn
  -> on success: Conversation assigns proposalId, stores an ephemeral value in the active runtime,
     and emits proposal_ready
```

The browser receives a summary and `proposalId`, never the execution payload. Multiple calls create independent Proposals. Ordered pixel steps belong in one DAG; unrelated alternatives may be separate Proposals.

If `expected_count` conflicts with real material facts, no Proposal is published. The tool returns the difference and the Agent asks the user through normal conversation. A later corrected request creates a new Proposal; there is no post-confirmation question.

### 6.2 Ephemeral payload and schema evolution

The active runtime value contains `proposalId`, conversation identity, ordered `referenceKeys`, resolved immutable member snapshot, canonical DAG, schema version, payload hash, summary, and expiry. It is not persisted in MySQL, Redis, message metadata, or a DAG-owned table. Reload/process exit may therefore lose an unresolved Proposal.

Schema rules remain:

1. additive optional fields with compiler-filled defaults are backward compatible;
2. destructive changes require a schema major version;
3. the Proposal captures the schema version and canonical payload hash used at publication;
4. confirmation rechecks current authorization, resource availability, payload hash, and schema compatibility internally;
5. incompatible or expired values are deleted and the user must ask the Agent to create a new Proposal.

### 6.3 Direct confirmation

```text
POST .../proposals/{proposalId}/confirm
  -> Conversation resolves the ephemeral value
  -> ownership + permission + hash + readiness + schema + pending CAS recheck
  -> TaskCommandService.createAndEnqueue(idempotencyIdentity = proposalId, canonical payload)
  -> repeated confirm returns the task already bound to proposalId
  -> remove the ephemeral Proposal
```

Reject simply deletes the ephemeral value. Defense-in-depth validation is not another user confirmation. dag signs and consumes no token and owns no Proposal state machine.

---

## 七、BranchExpander：分支与组支路展开

`BranchExpander` 把一个 `TypedExecutionPlan` + 一批图片成员展开成一组可独立执行的支路。这是 Work Unit Identity（`UnitKey`）的定义者；它不再接收原始节点参数 Map。

### 7.1 中立输入：成员由调用方喂入（决策 2=B）

dag **不读 `asset_image`**。展开所需的图片成员由调用方（`module/task`，它本就「按 package_id 加载图片」）以 dag 自有的中立 record 喂入：

```java
public record ImageDescriptor(
        String imageId, String skuId,
        String groupKey,        // 非空=分组成员（对齐 asset_image.group_key）
        String viewId,          // 组内排序（对齐 compose_group 的 order）
        String objectKey, String contentType
) {}

List<ExecutableBranch> expand(TypedExecutionPlan plan, List<ImageDescriptor> images);
```

这样 dag 只认「成员列表」这个中立投影，零 file 依赖、零 `asset_image` 表知识，符合 [§一](#一文档定位与设计原则) 原则五。`groupKey`/`viewId` 的来源（文件名驱动解析）是 `module/file` 的事（`file.md §七`）。

> 取舍：相较定义 `ImageGroupingPort` SPI，直接「参数喂入中立 record」更简单——调用方 task 本就持有这批数据，无需再绕一层 SPI 注入。dag 只拥有 `ImageDescriptor` 的形状，不拥有取数逻辑。

### 7.2 普通支路 vs 组支路

- **普通支路 `[图片×支路]`**：DAG 中不含 `compose_group` 的 source→sink 路径，对每张图片各展开为一条 `ExecutableBranch`（`design.md §9.1` 分支展开：单节点后可分多条并行路径，如去背景后同出 800×800 主图与 200×200 缩略图，各自一条支路）。
- **组支路 `[组×支路]`**：含 `compose_group` 的支路（`design.md §9.5`）。同 `(packageId, groupKey)` 的成员构成一组；聚合节点之前的逐图节点对**每个成员各自施加**，`compose_group` fan-in 合成，聚合之后的节点作用于合成后的单图。展开为 `perMemberOps → compose → postOps` 的有序计划（直接对应 `image.md §七` 的 `runComposed` 形态）。

```java
public record ExecutableBranch(
        UnitKind kind,               // BRANCH | GROUP（对齐 state.UnitKind）
        String branchId,             // 确定性派生（见 7.3）
        String memberId,             // BRANCH: imageId；GROUP: groupKey
        List<ExecutionStep> perMemberSteps, // 已类型化且保留依赖顺序
        GroupStep composeStep,              // 仅组支路；普通支路 null
        List<LocalImageStep> postSteps,      // 仅组支路；普通支路空列表
        EncodeTarget encode          // 末端编码目标（convert_format/隐式默认）
) {}
```

### 7.3 确定性 branchId（恢复对齐的关键）

`branchId` 必须**确定性派生**：同一 Canonical DAG 的同一条 source→sink 路径，每次编译/展开得到同一 `branchId`。否则崩溃恢复时 `harness/state` 的 `UnitKey(taskId, kind, memberId, branchId)` 对不上，成功 Work Unit Checkpoint 无法被正确跳过。

- 派生方式：对路径的节点序列（id + tool + 规范化 params）做稳定哈希，或用规范化的节点 id 路径串。要求与 `process_result.branch_id`、`UnitKey.branchId` 三处一致。
- `BranchExpander` 是纯函数：同输入 → 同输出（含 branchId 集合），可属性测试。

### 7.4 Canonical Form 规则（branchId 与 Proposal payload hash 的共同基石）

branchId 与 ephemeral Proposal 的 `payloadHash` 都基于“规范化形式（Canonical Form）”派生，二者**共用同一套规范化函数**，避免恢复语义与确认一致性口径漂散。

**规范化目标**：对同一份 DAG 语义，无论 JSON 字段顺序如何、空白如何、嵌套层级如何，都能得到**逐字节一致**的字节序列，作为哈希输入。

**规则集**：

1. **对象键按字典序排序**（递归）：Jackson 的 `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` + 自定义 `ObjectMapper`；不允许任何"保留原始顺序"的形态。
2. **数组顺序保留**：数组是有序的（节点的 edges、compose_group 的 order 等），不重排。
3. **数字**：JSON 数字统一为最简形式（如 `1.0` → `1`，`-0` → `0`，禁止尾随零）；整数无小数点。
4. **字符串**：禁止前后空白，UTF-8 NFC 规范化；不区分 Unicode 是否组合字符。
5. **布尔/null**：保留字面量，不归一化。
6. **缺失字段 vs null**：两者等价——序列化前把缺失字段补 null，再做规范化。
7. **默认值不参与哈希**：dag 校验过的必填字段必现，但若 schema 标注 optional 的字段未提供，序列化时**不补默认值**（保持原样），哈希基于"实际传入"。
8. **不规范化内容**：参数值若是 MinIO key、URL 等业务标识符，原样保留（不去重、不规范化大小写，hash 是 fingerprint 不是归一化）。

**branchId 的拼接形式**：

```
branchId = sha256(canonicalJson(sourceSinkPath) + "|" + sha256(canonicalJson(nodeParams)))
       其中：sourceSinkPath = source nodeId → ... → sink nodeId 的 id 序列
             nodeParams    = 该路径上每节点按出现顺序的 canonical params 拼接
```

实现位置：`com.pixflow.module.dag.expand.CanonicalJson`（纯函数，零依赖，单测覆盖所有规则）。`BranchExpander` 与 `ValidatedImagePlan.payloadHash()` 都依赖它。

**为什么不用 Jackson `writeValueAsString` 默认形态？** 因为 Jackson 默认不排序键、不处理缺失字段、不去尾随零——必须用专用 `ObjectMapper`（封装在 `CanonicalJson` 内）。

---

## 八、确定性单元执行器（缝合 infra 的核心）

`UnitExecutor` 是 dag 的「执行引擎」本体——被 `module/task` 的 worker **逐单元**调用，把一条 `ExecutableBranch` 缝合成 infra 调用链，产出 `UnitOutcome`。它**无线程、无 MQ、无锁、无 process_result 落库**。

### 8.1 接口与产物

```java
public interface UnitExecutor {
    UnitOutcome execute(ExecutableBranch branch, UnitInput input);   // 从不抛业务异常打断批次
}

public record UnitOutcome(
        UnitKind kind, String branchId, String memberId,
        Status status,                 // SUCCEEDED | FAILED
        String outputObjectKey,        // 成功：结果图 MinIO key（确定性/组支路）
        String generatedCopy,          // 成功：文案支路产物（generate_copy）
        List<MemberRef> members,       // 组支路成功：N 张源成员明细（→ process_result_member）
        UnitFailure failure            // 失败：最终失败快照，不是节点 checkpoint/history
) { enum Status { SUCCEEDED, FAILED } }

public record UnitFailure(
        String code, String safeMessage, ErrorCategory category, Recovery recovery,
        String failedNodeId, PixelTool failedTool, int attemptCount,
        Map<String,Object> safeDetails
) {}
```

`UnitOutcome` 是中立产物：task 据它写 `process_result`（`design.md §13.1`：组支路 `group_key` 非空、`image_id` 置空；`members` → `process_result_member`）。dag 不知道 `process_result` 表，只回这个 record。

### 8.2 逐图支路缝合（PipelineUnitExecutor）

```
storage.getStream(objectKey)                      // 取源图字节（dag 缝合 I/O）
  → 按 Typed Execution Plan 的真实依赖顺序执行：
       ExternalStep(remove_bg) → thirdparty.removeBg(bytes) → 抠图 PNG 字节
       LocalImageStep(...)     → 已持有类型化 ImageOp/spec
  → image.run(reopenableSource, typedOps, EncodeSpec) // probe/准入→解码一次→编码一次
  → storage.put(StorageKeys.resultUnit(taskId, unitKeyHash, runEpoch, ext), bytes)
  → UnitOutcome.SUCCEEDED(outputObjectKey)
```

关键：**解码一次、编码一次**由 `infra/image` 的 `ImagePipeline.run` 保证（`image.md §七`）；dag 不在节点间反复 decode/encode。`remove_bg` 是链中可能的第一步（第三方抠图），其输出（带 alpha 字节）作为 `image.decode` 的输入继续缝合——这正是 `image.md §二` / `thirdparty.md §十三` 描述的 `remove_bg → set_background → resize` 缝合。

> 边界细节：`infra/image` 是「字节进 / 字节出」的纯计算，dag 负责把 `remove_bg` 的第三方结果字节喂给 image，把 image 的最终字节落 MinIO。watermark 的水印图本身也由 dag 先取出字节、解码为 `RasterImage` 传入 `WatermarkSpec`（`image.md §6.3`）。

### 8.3 组支路缝合（GroupUnitExecutor）

```
对组内每个成员 m（按 viewId 排序）：
   storage.getStream(m.objectKey) → 缝合 perMemberOps → 得到预处理后的成员栅格
   （成员预处理中间产物引用按需经 RunStateRefStore 暂存，见 §十一）
  → image.runComposed(members, perMemberOps, ComposeOp(ComposeSpec), postOps, EncodeSpec)
  → storage.put(StorageKeys.resultUnit(taskId, unitKeyHash, runEpoch, ext), bytes)
  → UnitOutcome.SUCCEEDED(outputObjectKey, members=[...view 明细])
```

`image.md §七` 的 `runComposed(members, perMemberOps, compose, postOps, encode)` 正是此形态：聚合前逐成员施加 `perMemberOps`、`compose` fan-in、聚合后 `postOps`、末端编码一次。dag 决定**结构**（哪些 op 在聚合前/后），image 只按既定步骤执行、不懂「组」语义。

### 8.4 DagCompiler 与执行装配 fail-fast

- `DagCompiler` 是纯函数：`CanonicalDag → TypedExecutionPlan`。它在编译期把节点 Map 映射为 `BackgroundRemovalOptions`、`ResizeSpec`、`CompressSpec`、`ComposeSpec`、`CopySpec` 等类型化值，并把 edges 编译成显式依赖；编译后执行器不再读取 JSON Map，也不通过 `NodeDispatcher` 返回占位 `ImageOp`。
- 每一种白名单 `PixelTool` 必须在 compiler registry 中恰有一个 typed mapper 和一个真实 executor binding。启动期测试枚举全集；缺项、重复项或依赖 bean 缺失直接让 `DagAutoConfiguration` 创建失败。
- `pixflow.dag.enabled=true` 时，`ObjectStorage`、`ImagePipeline`、`BackgroundRemovalClient` 和文案分支需要的 `ChatModelClient` 均为必需依赖。禁止注入会在运行期抛 `UnsupportedOperationException` 的 stub，也禁止用 nullable/noop binding 让应用假启动。
- compiler 仍由封闭枚举驱动，无反射、无运行时插件；新增工具必须同时增加 schema、typed mapper、executor binding 和合同测试，白名单本期不扩展智能裁剪或 AI redraw 节点。

### 8.5 资源生命周期（执行器的隐性 SLA）

dag 单元执行器只拥有它打开的 storage stream、外部调用结果 bytes 和当前执行 context；`RasterImage`、ImageIO reader/writer、native encoder buffer 与 Pixel Budget permit 的生命周期全部由 `infra/image` 的 `ImagePipeline` 封装。dag 不通过 `getGraphics().dispose()` 伪释放 `BufferedImage`，也不复制整帧来规避所有权问题。

**统一原则**：每个单元入口创建轻量 `BranchExecutionContext`，只登记 dag 自己打开的 `InputStream`、临时对象 key 与取消后的清理动作；try-finally 保证成功/失败都关闭。进入 `ImagePipeline.run/runComposed` 后，image 自己以 try-with-resources 管理 raster 和全局像素许可。

**步骤级清理责任**：

| 阶段 | 持有的资源 | 失败后清理责任 |
|---|---|---|
| `storage.getStream` | `InputStream` | try-with-resources 自动关；失败则只关流，无其他残留 |
| `thirdparty.removeBg` | HTTP 调用产生的 bytes（dag 喂给 image 之前是 `byte[]`） | 失败时该 `byte[]` 由 GC 回收；成功时该字节进入 image 处理链 |
| `image.run/runComposed` | probe/decode/raster/native buffer/Pixel Budget permit | 全由 image pipeline 在方法返回或抛错前释放；dag 不借出 raster |
| `storage.put` | 输出 `byte[]` + HTTP 流 | 失败时输出 `byte[]` 不落 MinIO；当前 Work Unit 返回最终失败，崩溃恢复/derived retry 时按确定性路径重新生成，不能产生半成功 |
| 异常 finally | —— | ctx.close() 触发 LIFO 清理 |

**关键约束**：

- `RasterImage` 是 image 模块的显式所有权句柄，构造/借用不做隐式全帧复制；上游句柄在下游产出就绪后立即 close。
- scrimage WebP 编码路径的 native buffer 与 Pixel Budget permit 由 image 内部 `try-with-resources` 保证释放；dag 不二次介入。
- watermark / 第三方调用的中间 bytes 数组用完置 null（便于 GC）。
- `BranchExecutionContext.close()` 幂等（`closed.compareAndSet` 守护）。

**单元测试覆盖**：

- 注入各阶段失败，断言 dag stream/context 与 image raster/Pixel Budget permit 各自释放，所有权不交叉。
- 多线程并发跑 `PipelineUnitExecutor`，断言活跃加权像素不超过 `pixflow.image.pixel-budget.max-in-flight-pixels`。
- 注入 `storage.put` 失败（fake 抛异常），断言上游字节不残留、上游 RasterImage 已 dispose。

---

## 九、generate_copy 独立文案分支

`generate_copy` 是与像素流水线**解耦的独立分支**（`design.md §9.1`「文案分支解耦，不串接像素流水线末端」），以 SKU 的 `asset_copy` 为上下文。

- **不在像素链上**：`BranchExpander` 把 `generate_copy` 节点展开为独立的文案支路（`CopyUnitExecutor`），不与 remove_bg/resize 等串接。
- **中立文案上下文喂入（决策 2 同理）**：dag 不读 `asset_copy` 表；`CopyContext(skuId, productName, keywords, description)` 由调用方（task）喂入 `UnitInput`。
- **缝合 infra/ai**：`CopyUnitExecutor` 调 `infra/ai.ChatModelClient`（`ModelRole.PRIMARY_CHAT`），把 `CopyContext` + 文案参数组装为 prompt，产出文案文本 → `UnitOutcome.generatedCopy`（task 写入 `process_result.generated_copy`）。
- HITL 与本分支无关：文案生成是确定性工具调用（非生成式重绘路径），随 DAG 一同确认执行。

---

## 十、失败隔离边界与错误归一化

「失败隔离」按 [§二](#二核心边界dag-是确定性库task-是异步外壳) 分两半：**单元内归一化在 dag，批次内继续在 task**。

- **retry 属于发生失败的边界**：thirdparty/AI/storage 各自在一次节点调用内执行有界 retry，并在最终异常中提供 attempt count；纯本地图像处理不重试。dag 不在节点外再套通用 retry，task 也不对正常 FAILED Work Unit 自动重跑。
- **dag 单元执行器从不抛业务异常打断批次**：单元内任一步骤最终失败（抠图 5xx 重试耗尽、像素解码失败、不可编码格式、组支路成员缺失/解码失败……）→ 捕获并经 `ErrorNormalizer` 归一化 → 返回 `UnitOutcome.FAILED(UnitFailure)`。`UnitFailure` 保存 code/category/recovery、最终 failedNodeId/failedTool、边界 attemptCount 与脱敏 details；它是最终失败快照，不是节点 attempt history/checkpoint。
- **task 据 FAILED outcome 落库 + 继续**：以当前 `run_epoch` fenced 写入结构化失败列与兼容 `error_msg`，不发布半成品 output key，同图其余支路 / 批次其余图片 / 其他组继续。全部所选单元成功才 `COMPLETED`；成功与失败/跳过混合为 `PARTIAL`；全失败为 `FAILED`。

错误来源与归一化对接（各 infra 已在边界归一化，dag 透传/再归一化为 SKIP 隔离）：

| 来源 | infra 归一化（已有） | dag 单元结果 | 任务终态影响 |
|---|---|---|---|
| 抠图限流/网络/5xx 重试耗尽、熔断 | `thirdparty` → `PROVIDER/NETWORK/RATE_LIMIT`，熔断 `SKIP`（`thirdparty.md §九`） | `FAILED`（隔离该支路） | 该支路失败，整批其他支路继续 |
| 损坏图/不可编码格式/超大图 | `image` → `IMAGE_PROCESSING`（默认 `SKIP`，`image.md §9.2`） | `FAILED`（隔离该支路） | 该支路失败，其他继续 |
| 文案模型调用失败 | `ai` → `PROVIDER/NETWORK`（`ai.md §八`） | `FAILED`（隔离该文案支路） | 该文案支路失败，其他继续 |
| **组支路源 MinIO 读失败（storage STORAGE/RETRY）** | storage 抛 `StorageException`，`ErrorNormalizer` 归 `STORAGE`/`RETRY` | 整条组支路 FAILED，`details.missingViews` 列出失败成员 | **整组 FAILED**，其他组/支路继续；终态后可由 derived retry task 选择 |
| **组支路源 MinIO 读失败（NON-retryable / 4xx）** | storage `BucketType`/`NoSuchKey` → 上抛 `STORAGE`/`TERMINATE` | 整条组支路 FAILED，但**整批任务 FAILED**（数据不完整） | **整批失败**，不再继续 |
| **组支路成员数据质量违规**（viewId 重复、groupKey 不一致） | dag 内部校验抛 `DAG_INVALID_GROUP_BRANCH`（VALIDATION/TERMINATE） | 整批任务 FAILED | **整批失败** |
| 组支路图像处理失败（损坏图 / 不可编码格式） | image → `IMAGE_PROCESSING/SKIP` | 整条组支路 FAILED，`details.missingViews` 标注 | 整组 SKIP，其他组/支路继续 |
| 非法操作参数漏到执行期 | image `INVALID_OP_PARAM`（应已被 DagValidator 拦） | `FAILED`（防御兜底，正常不发生） | 该支路失败 |
| 单元 deadline 耗尽 | dag 在步骤边界生成 `DAG_UNIT_TIMEOUT`（DEPENDENCY/RETRY） | FAILED | 本任务不自动重试；可由终态后的 derived retry task 选择 |
| 原始字节超大（dag pre-stat 超阈值） | dag 内部抛 `DAG_SOURCE_BYTES_TOO_LARGE`（VALIDATION/TERMINATE） | FAILED | 该支路失败 |

> **关键区分**：组支路失败按 `STORAGE retryable` vs `VALIDATION/不可恢复 STORAGE` 记录不同 recovery；前者可在终态后由用户创建 derived retry task，后者保持不可恢复失败。当前 task 不因 recovery=RETRY 自动重跑。

dag 自有错误码 `DagErrorCode implements common.ErrorCode`（并入 `common` 启动期聚合测试：code 唯一 + i18n 齐全 + category 非空）：

| code | category | recovery | 场景 |
|---|---|---|---|
| `DAG_INVALID_STRUCTURE` | VALIDATION | TERMINATE | nodes/edges 缺失、id 重复、引用断裂 |
| `DAG_NODE_LIMIT_EXCEEDED` | VALIDATION | TERMINATE | 节点数 >50 或 <1 |
| `DAG_UNKNOWN_TOOL` | VALIDATION | TERMINATE | tool 不在白名单 |
| `DAG_INVALID_PARAMS` | VALIDATION | TERMINATE | 参数 schema 校验失败 |
| `DAG_HAS_CYCLE` | VALIDATION | TERMINATE | 存在环 |
| `DAG_INVALID_GROUP_BRANCH` | VALIDATION | TERMINATE | compose_group 非唯一/前驱非逐图工具 / 成员数据质量违规 |
| `DAG_INVALID_OP_ORDER` | VALIDATION | TERMINATE | remove_bg 不在逐图节点序列首位 |
| `DAG_PAYLOAD_HASH_MISMATCH` | VALIDATION | TERMINATE | 确认时 ephemeral Proposal payload hash 与已校验 payload 不一致 |
| `DAG_SCHEMA_INCOMPATIBLE` | VALIDATION | TERMINATE | Proposal 捕获的 schema version 与当前不兼容 |
| `DAG_UNIT_EXECUTION_FAILED` | （由 ErrorNormalizer 定，多为 SKIP） | SKIP | 单元执行失败的归一化外壳 |
| `DAG_UNIT_TIMEOUT` | DEPENDENCY | RETRY | 单元执行超时（image/thirdparty/ai 任一阶段） |
| `DAG_SOURCE_BYTES_TOO_LARGE` | VALIDATION | SKIP | 源字节超过 dag 大图防护阈值（避免浪费下载带宽） |
| `DAG_GROUP_MEMBER_MISSING` | NOT_FOUND | SKIP | 组支路成员缺失视图（业务已确认按现有处理） |

> 校验类错误（VALIDATION/TERMINATE）发生在提案/确认边界（不创建任务）；单元执行类（SKIP）发生在 task worker 调用执行器时（隔离支路）。两类不混淆。

---

## 十一、中间产物引用与恢复（走 state，缓存收窄到组支路）

### 11.1 运行态引用经 harness/state，不直连 cache（决策 4）

dag 需要的组成员中间产物**运行态引用**（MinIO key，非字节）经 `harness/state` 的 `RunStateRefStore` 读写 `RuntimeArtifactRef`，**不直连 `infra/cache`**。键包含 `taskId + runEpoch + unitKeyHash + memberId`；原始字节一律落 MinIO。引用只服务当前 epoch 内的 fan-in，不包含“节点已 checkpoint”的语义。

> 这把 `module-dependency-dag-plan.md` 中 `cache → dag` 的直连边修正为 `state → dag`（见 [§十五](#十五对-designmd-与依赖计划的细化)）。dag 仍可能在键命名上引用 cache 的 `CacheNamespace` 约定，但读写一律经 state facade。

### 11.2 缓存收窄到组支路（决策 5）

普通逐图支路是 `infra/image` 的「解码一次→链式 op→编码一次」**全程内存**（`image.md §七`），单条支路内部没有跨节点的中间字节需要落盘。因此：

- **普通支路不做节点级中间缓存**：恢复就是整条 Work Unit 幂等重算。普通支路的唯一 checkpoint 是 MySQL `process_result.status=SUCCESS`，dag 不写 Redis 完成标记。
- **组支路只在当前 epoch 使用 `runref:group:*`**：组内成员预处理产物在同一次 group fan-in 内可暂存引用，compose 完成后删除，TTL 兜底。worker 崩溃产生新 epoch 后旧引用一律忽略并清理，不能据此跳过成员节点。

### 11.3 恢复语义对齐

- **可跳过单元 = MySQL 成功 `process_result`**（`state.md §十` `RecoveryCoordinator`）。dag 的确定性 `branchId` 与 task 的显式 `unit_key` 保证恢复对齐。
- **支路/组支路整体幂等重算**：新 epoch 中所有非 SUCCESS 所选单元从源输入整条重跑，不复用旧 epoch 的 Runtime Reference。FAILED 只记录最终故障信息，不是“从失败节点继续”的位置。
- 组结果 `process_result` 落库即视为整组完成，恢复时整组跳过（`design.md §9.5` 恢复段）。

### 11.4 任务级共享素材缓存（watermark 与同类资源）

§11.2 收窄到组支路的是「**单支路中间产物**」，但像素链路上还有一类**任务级共享素材**——典型是 watermark 节点使用的水印图（一张图被 N 张主图复用），未来可能还有：通用底图、画布、印章等。

这两类资源的缓存**性质完全不同**，不能复用当前 epoch 的 `runref:group:*`：

| 维度 | 单支路中间产物（§11.2） | 任务级共享素材（本节） |
|---|---|---|
| 缓存对象 | 当前 epoch 组成员临时 MinIO key | watermark 压缩源字节 |
| 共享范围 | 单条支路内部避算 | 同一 task 内所有支路共享 |
| 生命周期 | 当前 epoch compose/单元结束时删除；epoch 结束与 task 终态统一兜底清理 | task 结束时统一清理 |
| 存储 | Redis 运行态引用 + MinIO 字节 | dag 进程内有界**压缩字节缓存**（不是 Redis） |
| 持久性 | 进程崩溃后由 MinIO 重建 | 进程崩溃后从 MinIO 重新拉取（task 重新入队时按需） |

**关键决策**：watermark 的压缩源字节缓存放在 **dag 进程内存**（Caffeine），不缓存 `RasterImage`、不写 Redis。理由：

- 同一 watermark 图被 N 张主图引用，进程内复用避免重复 MinIO 拉取；每个 Work Unit 仍在取得 Pixel Budget 后自行 decode，避免缓存长期占有未计入预算的全帧 raster；
- 任务生命周期与 dag worker 进程同尺度，进程退出 = task 也退出，缓存自然清空；
- 写 Redis 等于把"可能被多机 worker 共享"的隐含假设带进来，但本设计 task worker 跑在多节点，每节点各自读 MinIO 即可（MinIO 已经是共享存储），进程内缓存足够。

**实现**：

```java
@Component
public class TaskAssetCache {
    // 缓存：taskId -> (assetRef -> immutable compressed bytes)
    // 按 entry bytes 和 task 总 bytes 双重有界；不缓存 RasterImage
    // task 结束时由 TaskCleanupHook 调 evict(taskId)
}
```

**关键约束**：

- 缓存 key = `(taskId, assetRef)`，assetRef 是 watermark 节点的 `params.watermarkImage`（MinIO key）。
- **不缓存栅格跨线程持有**：cache hit 返回只读压缩 bytes/source factory；禁止通过 `RasterImage.copy()` 为每次命中复制整帧。解码与 raster 生命周期仍在 image pipeline 和全局 Pixel Budget 内。
- **缓存容量上限**：同时限制每 task entries、单 entry bytes 与全局 total bytes，LRU 淘汰；超限时直接从 MinIO 重新读取，不扩大堆。
- **task 结束清理**：当 task 终态写入（成功/失败/取消）后，dag 监听 task 终态事件（来自 `module/task` 的 SPI 回调）→ `TaskAssetCache.evict(taskId)`。

**为什么不让 `infra/image` 自带 watermark 缓存？** 因为 watermark 图属于 dag 编排层面的"被节点引用资源"，不在 `infra/image` 的纯计算边界内（`image.md §一`原则一）。dag 拥有该缓存的责任是天然的。

**单元测试覆盖**：

- 同一 task 两条支路用同一 watermark → MinIO 读取一次，但两次 decode 都在各自 Pixel Budget permit 内完成。
- 不同 task 用同一 watermark → 各自 cache miss，各自 decode（不跨任务复用）。
- 断言缓存中不存在 `RasterImage`，cache hit 不产生全帧复制，bytes 上限可触发淘汰。
- 注入 task 结束事件 → 缓存清空，缓存字节计数归零。

---

## 十二、配置

`@ConfigurationProperties(prefix = "pixflow.dag")`：

```yaml
pixflow:
  dag:
    validate:
      max-nodes: 50                 # 节点数上限（design §9.1）
      min-nodes: 1
    runtime-ref:
      ttl: 2h                       # 当前 run_epoch 组成员引用 TTL；不是 checkpoint
    execution:
      unit-deadline: 5m             # 传播到步骤边界的总 deadline；不创建 dag 私有线程池
      source-bytes-limit: 209715200 # 200MB；超过则 DAG_SOURCE_BYTES_TOO_LARGE → SKIP
    asset-cache:
      max-entries-per-task: 5       # 任务级 watermark 缓存容量上限
      max-entry-bytes: 10485760     # 只缓存有界压缩源字节，不缓存 RasterImage
      max-total-bytes: 104857600
      enabled: true                 # 关闭后每次重新读取 MinIO（仅排障用）
```

- 配置只承载**校验护栏、运行态引用生命周期、执行 deadline、共享素材压缩字节容量**；具体像素参数来自 DAG 节点，不在此配置。Proposal 过期属于 Conversation active runtime，不在 dag 配置或清理任务中出现。
- 像素工具的执行调优（质量默认、像素炸弹阈值、全局 Pixel Budget）属 `infra/image`，线程池属 `module/task`，provider timeout/retry 属 thirdparty/AI/storage 边界；dag 不重复定义。

### 十二.2 超时与可中断

dag 是同步、阻塞式执行器，**没有私有线程池、没有取消监听器、没有通用 retry loop**。task 在自己的 worker pool 调用它；dag 创建 `ExecutionDeadline` 并在每个 typed step 前后检查剩余时间，同时把剩余时间传给 thirdparty/AI/storage。各边界负责让自己的 HTTP/存储调用真正超时并执行有界 retry，不能靠 `Future.cancel(true)` 假装已经终止底层 I/O。

- deadline 在 step 边界耗尽时返回 `UnitOutcome.FAILED(UnitFailure{code=DAG_UNIT_TIMEOUT,recovery=RETRY,...})`，task 记录最终失败但不在当前正常执行中重跑。
- `recovery=RETRY` 只表示该失败适合用户在终态后创建 derived retry task，或在 worker 崩溃后的新 epoch 作为非 SUCCESS 单元整体重算；它不授权 task 自动 attempt loop。
- provider/storage 的实际 attempt timeout、退避和 attempt count 由其 owning boundary 决定；dag 只把最终失败节点写入 `UnitFailure`。

**大图防护**（避免下载带宽浪费）：

- 单元执行器在 `storage.getStream` 之前先调 `storage.stat(loc)`，拿 size 与 `source-bytes-limit` 比对；
- 超限则立即返回 `UnitOutcome.FAILED(DAG_SOURCE_BYTES_TOO_LARGE)`（VALIDATION/SKIP），**不发起下载**；
- 与 `infra/image` 的像素炸弹防护（看 width×height）互补：前者看字节大小防带宽浪费，后者看像素尺寸防 OOM。

**取消协议**（dag 不持取消标志）：

- 取消是**协作式**（cooperative），状态查询走 `harness/state` 的 `CancellationReader`（`state.md §九`）；
- task worker 在调用 `UnitExecutor.execute(...)` **之前**先调 `cancellationReader.throwIfCancelled(taskId)`（`state.md §九` 接口）；
- **不在**单元执行**内部**检查（避免 `state` 模块被引入 dag 单元热路径）；
- 取消发生时 task 已停止调度新单元，正在跑的单元跑完自然结束。

**幂等性保证**：

- 单元超时/最终失败照常写 `process_result.status=FAILED` 与结构化 `UnitFailure`；FAILED 是诊断事实但不是 Work Unit Checkpoint。
- 仅当父 task 仍为 RUNNING 且 worker 取得更高 `run_epoch` 时，崩溃恢复才能覆盖非 SUCCESS 行并整支路重跑；终态 task 不变。

**单元测试覆盖**：

- fake boundary 接收 deadline 并返回超时，断言 dag 生成结构化 FAILED、没有第二次单元调用；task 持久化失败由 task 集成测试覆盖。
- 注入 `storage.stat` 返回超大 size，断言不发起 `getStream` 调用（计数桩）。
- 注入 task 调用前 `throwIfCancelled` 命中，断言 `UnitExecutor.execute` 不被调用。

---

## 十三、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `harness/tools` | dag 贡献 `SubmitImagePlanHandler` 的 `ToolDescriptor`/`ToolHandler` bean，tools 自动收集；`submit_image_plan.dag` 深度校验在 dag handler；tools 对 dag 零编译依赖 |
| `module/task` | 从 Canonical DAG 调 `DagCompiler.compile`，再调 `BranchExpander.expand(plan, images)` 与 `UnitExecutor.execute`；据 `UnitOutcome/UnitFailure` fenced 写结果，持线程池、Redisson task lock、run_epoch、取消与恢复扫描 |
| `module/conversation` | 持有 ephemeral Proposal runtime 与 direct confirm/reject API；以 `proposalId` 调 Task，不把 Proposal 存到 dag |
| `module/imagegen` | 独立校验并发布单图 IMAGEGEN Proposal；不经 dag 持久化或共享 pending-plan port |
| `infra/image` | 调 `ImageCodec`/`ImagePipeline.run`（逐图）/`runComposed`（组），传类型化 spec；字节 I/O 由 dag 在两端缝合；image 不依赖 dag |
| `infra/thirdparty` | dag 是唯一直接消费方：`remove_bg` 节点调 `BackgroundRemovalClient.remove(源图字节)`，结果喂给 image |
| `infra/ai` | `generate_copy` 调 `ChatModelClient`（PRIMARY_CHAT）；ai 不理解 DAG 语义 |
| `infra/storage` | 两端缝合：`getStream` 取源图、`put` 写 `StorageKeys.resultUnit(...)` epoch 对象与当前组 runtime reference 对应字节 |
| `harness/state` | 当前 epoch 组成员引用经 `RunStateRefStore`；它们不是 checkpoint。确定性 `branchId` 与 `UnitKey` 对齐；恢复可跳过集只含 MySQL SUCCESS |
| `module/file` | **无直接依赖**：分组成员（`group_key`/`view_id`）、文案（`asset_copy`）由 task 以中立 record 喂入；文件名分组规则属 file |
| `common` | 单元失败经 `ErrorNormalizer` 归一化为 SKIP 的 `UnitOutcome.FAILED`；`DagErrorCode implements ErrorCode`；文案经 `Sanitizer` |

**反向约束**：dag 不依赖 task/conversation/loop/agent、不依赖 file 内部实现、不依赖 cache（经 state），也不依赖 confirmation 或 pending-plan contracts。

---

## 十三.五、可观测性

dag 通过 Micrometer 暴露指标，**不依赖 `harness/eval`**（与 `harness/tools` 同款做法，honor 依赖 DAG 中 `dag` 无 `→ eval` 边）。指标由 Spring Boot Actuator 端点暴露，Prometheus/Grafana 直接消费。

**指标清单**：

| 指标名 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `pixflow.dag.unit.exec` | counter | `tool`, `outcome=success\|failed\|timeout` | 单元执行计数（按 tool + outcome 维度） |
| `pixflow.dag.unit.exec.duration` | timer | `tool`, `outcome` | 单元执行耗时分布（应 P99 < 5s 普通支路 / 10s 组支路） |
| `pixflow.dag.op.call` | counter | `tool=remove_bg\|set_background\|resize\|...` | 单个像素工具的调用次数（穿透到 infra 层） |
| `pixflow.dag.op.call.duration` | timer | `tool` | 单节点耗时（infra 层调用耗时） |
| `pixflow.dag.validate` | counter | `result=ok\|reject`, `code=DAG_*` | 校验结果分布（reject 按错误码细分） |
| `pixflow.dag.validate.duration` | timer | —— | 校验耗时（应 P99 < 5ms） |
| `pixflow.dag.expand` | counter | `type=branch\|group` | 展开结果分布（多少普通支路、多少组支路） |
| `pixflow.dag.expand.duration` | timer | —— | 展开耗时 |
| `pixflow.dag.cache.asset` | gauge | `taskId`（动态注册） | 任务级 watermark 缓存当前条目数 |
| `pixflow.dag.cache.group.refs` | gauge | `taskId` | 组支路中间产物引用条目数（来自 state 的对账） |
| `pixflow.dag.resource.buffered_image` | gauge | —— | 活跃 `BufferedImage` 数（每次 `RasterImage` 创建 +1，dispose -1） |

**关键约束**：

- 指标**不带业务字段**（skuId、imageId 不入标签），避免高基数打爆指标存储。
- `pixflow.dag.resource.buffered_image` 是生产级**核心健康度指标**：与 `infra/image` 的同指标对齐（image.md §十六应有同名指标），两数对照可发现"dispose 漏掉"的隐患。
- `outcome=failed` 指标按 `DagErrorCode` 细分 label，便于定位"哪些错误码高频出现"。
- timer 直方图分位点（SLO 友好）默认启用 `publishPercentileHistogram()`，Actuator 端点 `/actuator/prometheus` 暴露分位数据。

**trace 与日志**：

- dag **不写** `agent_trace` 表（属 harness/eval 职责），但关键错误会通过 SLF4J + traceId 输出（traceId 由 `common.PixFlowException.traceId` 提供）。
- 与 `infra/image` 同样经 `common.Sanitizer` 脱敏，凭证/绝对路径不进日志。

**与 `infra/image` 指标的边界**：

- `pixflow.image.*`（image.md 自身）记录 image 内部细节（decode 次数、像素炸弹拦截数）。
- `pixflow.dag.*` 记录 dag 编排层（支路数、单元结果、校验通过率、缓存状态）。
- 二者**不重叠**：dag 指标按「支路/单元」维度，image 指标按「像素操作」维度，prod 排查时按层级下钻。

---

## 十四、测试策略

- **DagValidator 单测**：结构缺失/id 重复/引用断裂、节点数越界、未知 tool、参数 schema 失败、环、组支路规则（compose_group 非唯一/前驱非逐图）逐项断言；无状态可重复调用同输入同结果。
- **DagCompiler 合同测试**：同 canonical bytes + schema version 多次编译得到相同 typed steps/edges/branchId；每个 PixelTool 恰有 typed mapper + real executor binding；缺依赖、重复 binding 或未知 step 在启动/编译期 fail-fast，生产装配中不存在 `UnsupportedOperationException` stub。
- **ParamSchemaRegistry 单测**：每像素工具参数 schema 命中/越界（resize mode 枚举、compress quality∈[1,100]/targetBytes 互斥、compose layout 枚举、watermark position 九宫格）。
- **BranchExpander 单测（纯函数）**：单节点后分多支路；组支路 perMemberOps/compose/postOps 拆分正确；普通支路无 compose；**确定性 branchId**（同 DAG 同路径多次展开 id 稳定、与 process_result.branch_id 口径一致）；中立 `ImageDescriptor` 输入驱动、零 file 依赖。
- **GroupPreflight 单测（纯函数）**：expected_count 与实际成员数一致/不一致的差异结果正确；无 expected_count 时不产生差异。
- **PipelineUnitExecutor 测试**：缝合 remove_bg→set_background→resize（用 fake thirdparty/image 桩），断言 image.run 仅解码一次/编码一次（计数桩）、结果落正确 StorageKeys；失败注入（image 抛 IMAGE_PROCESSING）→ `UnitOutcome.FAILED`、不抛出、error 脱敏。
- **GroupUnitExecutor 测试**：组内成员排序（viewId）、runComposed 顺序（perMember→compose→post）、members 明细回填；成员缺失 → 整组 FAILED 标注缺失视图。
- **CopyUnitExecutor 测试**：CopyContext 注入 prompt、ChatModelClient 桩返回文案 → generatedCopy；零 asset_copy 表依赖。
- **SubmitImagePlanHandler 测试**：合法 references + DAG → 返回 validated payload；非法 reference/DAG/preflight → 结构化 tool error 且不发布 Proposal；payload hash 稳定。
- **Proposal 边界合同测试**：dag 无持久化写入；Conversation 只在完整校验成功后分配 `proposalId`；确认重放绑定同一 task。
- **失败隔离边界测试**：单元执行器对最终异常返回 `UnitFailure`，断言 code/category/recovery/failedNodeId/failedTool/attemptCount/details 完整且无堆栈泄露；boundary retry 次数不会被 dag/task 再乘一层。
- **恢复对齐测试**：确定性 branchId 使 UnitKey 可匹配 SUCCESS checkpoint；新 epoch 整体重算所有非 SUCCESS；旧 epoch Runtime Reference 不被读取；不存在节点 checkpoint/history 写入。
- **真实依赖故障注入集成测试**：使用真实 MySQL、Redis、MinIO 和实际 auto-configuration，验证 MinIO 写后 MySQL fenced 提交、Redis lock 丢失后的 stale worker、运行态引用丢失与结果对象孤儿清理；fake-only 测试不能替代该验收。
- **边界守护（ArchUnit）**：`com.pixflow.module.dag..` 不依赖 `module/file` 内部、`module/task`、`module/conversation`、`infra/cache`、confirmation/pending-plan packages、`harness/loop`、`agent`；不出现线程池/MQ/Redisson/process_result 实体直连。
- **错误码目录一致性**：`DagErrorCode` 并入 `common` 启动期聚合测试。

---

## 十五、对 design.md 与依赖计划的细化

本模块对既有设计做如下**细化（非冲突）**，需同步记录（落地时在相应文档同步）：

1. **明确 dag/task 的「执行引擎」边界**：`design.md §12` 字面上「执行引擎」在 dag、「失败隔离」在 task 含混。本文钉死为：dag 是**无状态确定性单元执行器 + 校验 + 展开**，task 是**有状态异步外壳**（fan-out/进度/锁/恢复/process_result 落库）。失败隔离分两半——单元内归一化在 dag（返回 FAILED outcome），批次内继续在 task。
2. **Proposal 不由 dag 持久化**：dag 返回完整校验后的中立 payload，Conversation 仅在当前 active runtime 中短暂持有并分配 `proposalId`；无 `pending_plan` 表或共享存储 port。
3. **分组成员/文案以中立 record 喂入，dag 不依赖 file**：`file.md §十三` 契约表写「dag 读 asset_image」，本文改为 task 以 `ImageDescriptor`/`CopyContext` 喂入，**不新增 file→dag 边**。需回写 `file.md` 契约表（把「dag 读 asset_image」改为「dag 经 task 以中立投影消费 group_key/view_id」）。
4. **中间产物引用走 `harness/state`，依赖边 `cache → dag` 改为 `state → dag`**：`dag-plan` 依赖图画的是 `cache → dag` 直连，与 `state.md §七`（dag 经 `RunStateRefStore`）冲突。本文采纳 state facade，建议 `dag-plan` 删 `cache → dag`、补 `state → dag`（state Wave2 / dag Wave3，无环）。
5. **中间产物缓存收窄为当前 epoch 的组运行态引用**：普通逐图支路不做节点级缓存；组成员引用键包含 run_epoch，只服务同次 fan-in，新 epoch 不读取。它不进入恢复可跳过判定，也不构成节点 checkpoint。
6. **GroupPreflight 是 Proposal 发布前纯函数校验**：dag 计算“期望 vs 实际”差异；不一致时 tool error 回到 Agent 询问用户，修正后再发布新 Proposal，确认边界不二次询问。

> 以上为对既有设计的补充落地，不改变 `design.md §9` 的两条产图路径、§9.5 文件名分组规则、§13 数据模型与「MySQL 事实源」原则。

---

## 十六、暂不考虑

- **节点级断点续传/attempt history 表**：恢复以 Work Unit 整体重算，节点仅在最终 `UnitFailure` 中记录定位字段，不建 checkpoint/history 表。
- **动态注册像素工具 / 自定义工具插件**：白名单是封闭枚举，不支持运行时扩展（确定性底座要求）。
- **DAG 可视化编辑 / 前端编排器**：本期 DAG 由 Agent 产出 JSON，不做可视化编辑面板。
- **组内参数联合推导**（如取组内最大尺寸回填各图）：`design.md §9.5` 本期不做；静态固定参数的「各自处理保持一致」沿用逐图支路同参数处理。
- **生图 Proposal 的业务执行细节**：`submit_imagegen_plan` 的单图校验、payload hash 与执行属 `module/imagegen`；dag 不充当生图 Proposal 存储。
- **DAG 模板/预设方案库**：本期不做常用方案模板沉淀，后续按运营需求评估。
- **跨任务 DAG 复用/缓存编译结果**：DAG 编译是轻量纯函数，不缓存编译产物。

---

## Revision Notes

2026-07-12 / Codex: 将 Agent Image Plan、Canonical DAG 与 Typed Execution Plan 明确分层；新增纯函数 `DagCompiler`，在运行前完成类型化参数映射与真实依赖装配，启用 DAG 时缺 bean/stub 直接 fail-fast。失败重试收敛到 provider/AI/storage owning boundary，dag 只返回结构化最终 `UnitFailure`。恢复只认 Work Unit SUCCESS，组中间引用限定当前 run_epoch，新增 epoch 后整支路重算；watermark 缓存改为有界压缩字节，避免无预算 raster 与每次命中的全帧复制。

2026-07-17 / Codex: Proposal 改为 Conversation active runtime 中的非持久值；删除 dag 的 pending-plan 表/状态机/共享 port 与确认令牌依赖。`submit_image_plan` 现在接收 canonical reference keys，并在发布前完成解析、深校验、编译和 GroupPreflight；事实冲突返回 Agent，不产生确认后的第二次确认。

2026-06-29 / Kiro: 第二轮细化（与用户讨论后落实生产级细节）。本轮新增/细化如下：
- §5 校验顺序新增 S8「remove_bg 必须是首个非 source 节点」约束，新增错误码 `DAG_INVALID_OP_ORDER`（避免「先 resize 再抠图」的资源浪费）。
- Schema 演进维持“加法兼容 + 大版本升级”，版本由 ephemeral Proposal 捕获；不兼容 Proposal 直接删除并重新生成。
- §7.4 branchId Canonical Form 规则集：节点路径 + 每节点 canonical JSON 哈希拼接；Proposal payload hash 复用同一套规范化函数（`CanonicalJson`）。
- §8.5 资源生命周期：`BranchExecutionContext`（AutoCloseable）保证 native 资源（`BufferedImage`、`ImageInputStream`、scrimage native buffer）严格 LIFO 清理；每 op 完成后立即 dispose 上游 RasterImage。
- §10 错误归一化细化：组支路源 MinIO 读失败按 `retryable`/`non-retryable` 分两条路径（当前实现将前者记录为可由 derived retry 选择的失败，后者整批失败）；新增 8 个错误码覆盖 timeout/大图/数据质量/schema 不兼容等场景。
- §11.4 任务级共享素材缓存：watermark 压缩源字节走 dag 进程内 Caffeine 缓存（不写 Redis），与当前 epoch `runref:group:*` 性质不同；task 结束时统一清理。
- §十二.2 超时与可中断：单元总超时 / 节点超时 / COPY 超时三档；dag 不持取消标志，task 在调用 `UnitExecutor.execute` 前后经 `CancellationReader` 协作；`source-bytes-limit` 在 stat 阶段拦截大图避免下载带宽浪费。
- §十三.5 可观测性：列出 11 类指标（unit.exec、validate、expand、cache、resource 等），与 `infra/image` 指标按层级区分；`buffered_image` gauge 是生产级健康度核心指标。

