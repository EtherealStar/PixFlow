# PixFlow 项目描述实现审计（2026-07-13）

## 结论

**项目尚未实现描述中的所有功能。** 忽略吞吐、时延、文件容量等量化数字后，电商数据查询、MySQL + Qdrant 记忆治理、RocketMQ 异步分发、Redis 出站令牌桶与模型指数退避已有较完整的生产代码；ReAct 风格 Agent、图片 DAG、视觉分析、分片上传、任务 checkpoint 和 Rubrics 均有真实实现，但仍存在功能边界缺口或未闭环路径。

本报告以当前工作树的生产源码、Spring 装配、活动 ExecPlan 和定向测试为依据。“有类/接口”不等同于用户路径可用；设计文档中的目标也不等同于已经实现。

## 判定摘要

| 描述项 | 判定 | 关键原因 |
|---|---|---|
| Spring Boot / MySQL / Redis / RocketMQ / MinIO / Qdrant / Vue | 已实现 | 依赖、开发配置和 Compose 服务齐全 |
| Spring AI | 未实质使用 | 仅声明依赖，生产代码没有 `org.springframework.ai` API 引用 |
| ReAct Agent 与 Harness | 部分实现 | tool-call 循环、持久 transcript、上下文压缩存在，但 AgentLoop 明确没有迭代上限 |
| 图片处理 DAG | 部分实现 | 背景、resize、压缩等可编译执行；没有智能裁剪节点，AI 重绘是独立 imagegen 流程 |
| VLM 结构化理解、文案与重绘 Prompt | 部分实现 | 结构化视觉结果及预处理存在；未形成“持久结构化描述 -> Agent 自动生成文案和重绘 Prompt”的完整闭环 |
| RocketMQ 异步解压与图片任务 | 已实现 | 上传完成发布解压消息，任务经 MQ 消费执行 |
| Redis + SHA-256 去重/幂等 | 部分实现 | 后端机制存在，但前端整包 hash 与后端算法不一致 |
| 分片上传与断点续传 | 部分实现 | 会话、分片状态、重复分片校验齐全；hash 协议缺口阻断正常完成路径 |
| checkpoint 异常恢复 | 部分实现 | 实现的是 Work Unit SUCCESS checkpoint，不是 Agent 推理链/token 级 checkpoint |
| Redis 令牌桶与指数退避 | 已实现（出站范围） | Redis Lua token bucket 已接入 AI/第三方 provider attempt；不是用户级 HTTP 防滥用 |
| 电商数据导入、查询、类目对比、趋势 | 已实现 | REST、导入服务和聚合 SQL 齐全 |
| MySQL + Qdrant 长期记忆 | 已实现 | 向量+关键词混合召回、RRF、衰减、强化、抑制/过期均有实现 |
| Rubrics + LLM judge 闭环 | 部分实现 | Image Result 切片和多次 judge 已实现；Copy/Decision、dataset 校准、正式回归和 Promotion 未闭环 |

## 逐项证据

### 1. 技术栈

根 POM 声明 Spring AI、Redisson、Qdrant、RocketMQ 等版本（`pom.xml:53-64`, `pom.xml:239-241`）；Compose 启动 MySQL、RocketMQ、Qdrant、MinIO（`docker-compose.yml:2-100`）；Vue 3 位于 `pixflow-web/package.json:25`。

但全仓生产 Java 源码没有 `org.springframework.ai` import；只有 `pixflow-infra-ai/pom.xml:20-21` 引入 `spring-ai-model`。实际模型访问由项目自建 OpenAI-compatible HTTP 客户端承担。因此“Spring AI”目前只能算依赖占位，不能算核心调用链技术事实。配置也不能证明服务端一定是 VLLM；它只允许把 base URL 指向兼容服务。

### 2. ReAct Agent 与 Harness

`AgentLoop` 根据模型 tool calls 执行工具、把结果追加到上下文并继续下一轮（`pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java:238-388`），`AgentOrchestrator` 每次 turn 组装并驱动该循环（`pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java:337-387`）。session transcript 和 compaction 事实持久化到 MySQL（`pixflow-session/src/main/java/com/pixflow/harness/session/persistence/TranscriptService.java:116-137`），context 层也实现预算、裁剪和摘要压缩。

不过 `RuntimeState` 明确说明 iteration 只用于 trace，**不做 capping、没有 maxTurns**（`pixflow-loop/src/main/java/com/pixflow/harness/loop/RuntimeState.java:19-20`）。provider retry 是有界的，但模型持续产出工具调用时，Agent 决策循环本身缺少硬上限。因此“解决失控重试”只能限定为模型/provider attempt 重试治理，不能覆盖整个 AgentLoop。

### 3. 图片 DAG、背景处理、裁剪与重绘

当前 DAG 已具备 Canonical DAG、typed plan、白名单校验、分支展开和真实 storage/image/third-party 执行适配；活动执行计划记录旧 runtime stub 已删除并接入真实 adapter（`docs/design-docs/exec-plans/execution-domain-refactor-plan.md`, Progress Milestone 3）。`submit_image_plan` 允许模型从自然语言上下文提交 DAG 提案，服务端再校验与确认。

但白名单只有 `remove_bg`、`set_background`、`resize`、`compress`、`watermark`、`convert_format`、`compose_group`、`generate_copy`（`pixflow-module-dag/src/main/java/com/pixflow/module/dag/ir/PixelTool.java:11-27`）。没有独立的智能裁剪工具；`remove_bg` 的 `crop` 参数也不等价于内容感知智能裁剪。AI 重绘由 `pixflow-module-imagegen` 的 `submit_imagegen_plan` / `DefaultImageGenExecutor` 独立完成，不是同一 DAG 的可编排节点。

恢复粒度是 Work Unit。节点是单元内 attempt，不是持久 checkpoint；因此“异常节点恢复”应改写为“异常后跳过已成功 Work Unit，其余单元重算”。

### 4. VLM、结构化描述、文案和重绘 Prompt

`DefaultVisionService` 对图片采样、预处理、调用视觉模型并解析为 `VisionAssessment`（`pixflow-module-vision/src/main/java/com/pixflow/module/vision/DefaultVisionService.java:57-107`）；该结构包含构图、背景、水印、卖点、问题等字段（`pixflow-module-vision/src/main/java/com/pixflow/module/vision/analyze/VisionAssessment.java:8-19`）。`VisionImagePreprocessor` 做最长边 resize、格式转换、质量设置和透明背景归一（`pixflow-module-vision/src/main/java/com/pixflow/module/vision/image/VisionImagePreprocessor.java:25-43`）。

项目也有基于视觉调用补全商品名、关键词和描述的 `ProductCopyExtractor`（`pixflow-module-vision/src/main/java/com/pixflow/module/vision/enrich/ProductCopyExtractor.java:25-46`），以及独立 imagegen 提案工具。但当前生产源码未显示一个稳定闭环把持久化视觉描述交给主 Agent，再自动同时生成营销文案和重绘 Prompt。该描述应降级为“支持结构化视觉分析，并可为文案补全和重绘提案提供上下文”。

### 5. RocketMQ 异步调度

分片上传完成后先持久化素材包，再通过 `ExtractionPublisher` 发布解压消息（`pixflow-module-file/src/main/java/com/pixflow/module/file/upload/UploadSessionService.java:188-195`）。task 模块有 RocketMQ publisher/consumer、worker 和恢复重投；commerce 和 vision enrichment 也注册了独立 topic/binding。忽略时延数字后，“解压与图片任务异步调度”成立。

### 6. SHA-256 去重、分片上传与断点续传

后端按 fileHash 查找已就绪素材包并用分布式锁串行初始化（`UploadSessionService.java:74-94`）；Redis store 保存 upload session、fileHash 映射和 chunk metadata（`pixflow-module-file/src/main/java/com/pixflow/module/file/upload/RedisUploadSessionStore.java:35-107`）；分片流式校验 SHA-256，重复 index + 相同 hash 可幂等返回。

但前端 `sha256File` 计算的是“每个固定块的 SHA-256 hex 拼接后再次 SHA-256”（`pixflow-web/src/upload/hasher.ts:44-80`），后端完成上传时计算的是“合并后原始文件字节的标准 SHA-256”（`UploadSessionService.java:298-319`），并在不一致时拒绝完成（`UploadSessionService.java:178-186`）。两种算法对同一文件通常不同。这是跨端契约缺陷，意味着后端组件测试通过仍不能证明浏览器上传闭环可用。

### 7. checkpoint 与恢复

task 使用 `process_result.status=SUCCESS` 作为唯一 Work Unit checkpoint，`RecoveryCoordinator` 读取可跳过单元；stale heartbeat 扫描只负责重新投递，新 worker 取得锁并 claim 更高 `run_epoch`（`pixflow-module-task/src/main/java/com/pixflow/module/task/internal/recovery/RecoveryService.java:32-38`, `TaskWorker.java:75-94`）。结果提交同时受 RLock ownership 与 epoch fencing 保护。

这能避免已成功图片/支路重复计算，但没有证据表明模型上下文、Agent iteration、工具调用和 token 消耗被作为可恢复 checkpoint 持久化。因此不能表述为“Agent 整链路从最近断点恢复并避免 token 重算”。

### 8. Redis 令牌桶与指数退避

`RedisLuaTokenBucket` 通过 Redis Lua 原子补充/消费额度并在 Redis 故障时 fail-closed（`pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/tokenbucket/RedisLuaTokenBucket.java:14-112`）。app 的 `RedisModelQuotaLimiter` 把它适配到 AI role/provider/quotaGroup；第三方调用模板也在每个真实 attempt 前消费令牌。

`RetryPolicy` 计算带上限和 jitter 的指数延迟（`pixflow-infra-ai/src/main/java/com/pixflow/infra/ai/resilience/RetryPolicy.java:22-35`），`ModelRetryRunner` 只对可重试 provider 错误继续 attempt（`ModelRetryRunner.java:36-49`, `53-91`）。该功能已实现，但范围是出站 provider attempt，不是按用户/IP/租户限制入站恶意 HTTP 请求。

### 9. 电商数据与报告支撑

`CommerceController` 提供本地导入、异步 API 导入、作业查询和数据查询（`pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/web/CommerceController.java:20-52`）。`CommerceQueryService` 组合单 SKU 聚合、类目 benchmark、趋势和 freshness/degraded 信息；`CommerceDataMapper` 提供对应聚合 SQL（`pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/store/CommerceDataMapper.java:30-119`）。

因此“为 Agent 生成运营分析报告提供结构化数据支撑”成立。项目没有单独的持久化运营报告领域；报告正文主要依赖通用 Agent 根据查询结果生成，描述不应暗示存在独立报告引擎。

### 10. MySQL + Qdrant 记忆层

analysis insight 在 MySQL 保存事实和生命周期字段，同时写入 Qdrant（`pixflow-module-memory/src/main/java/com/pixflow/module/memory/ingest/InsightIngestService.java:75-108`）。`HybridInsightRecallService` 并行执行向量与关键词搜索并以 RRF 融合（`pixflow-module-memory/src/main/java/com/pixflow/module/memory/insight/HybridInsightRecallService.java:45-72`）。生命周期服务计算 decay，执行强化、抑制、过期并删除无效向量（`DefaultInsightLifecycleService.java:35-45`, `63-103`）。

这部分与描述基本一致，且 AgentOrchestrator 在 turn 开始时执行 memory recall。

### 11. Rubrics 评估闭环

当前 v2 已有版本化静态模板、Evidence Pack、规则 verifier、专用 text/vision judge role、三次 rollout、多数归并、四态 verdict、Quality Gate、持久化与任务完成事件触发。定向模块测试 12 项通过。

但 `EvaluationRunCoordinator` 对非 `IMAGE_RESULT` 直接拒绝（`pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/run/EvaluationRunCoordinator.java:54`）。活动计划仍把 Evaluation Dataset、gold-label 校准、同 dataset 正式回归、baseline/alert、人工 Promotion、Copy Result 和 Task Decision 列为未完成（`docs/design-docs/exec-plans/rubrics-criterion-verdict-refactor-plan.md`, Progress）。所以不能宣称已对“图片质量、文案质量和决策质量”形成完整闭环；当前只闭环了图片结果的首个纵向切片。

## 验证记录与限制

- `mvn -pl pixflow-module-file test`：30 tests passed。
- `mvn -pl pixflow-module-rubrics test`：12 tests passed。
- 两次 Maven 输出均显示类已是 up-to-date，不是 clean reactor rebuild。
- 上传测试只覆盖后端标准 SHA-256，没有覆盖前端 `sha256File` 与后端 complete 的跨端契约。
- 没有执行全仓测试、真实浏览器上传、真实模型调用或真实 Redis/MySQL/MinIO/RocketMQ 故障注入。

## 建议改写的项目描述

> PixFlow 是一个面向电商运营场景的 ReAct 风格 Agent 项目，基于 Spring Boot、MySQL、Redis/Redisson、RocketMQ、MinIO、Qdrant 和 Vue。项目实现了带工具调用的 Agent 循环、持久会话与上下文压缩，支持素材包分片上传、异步解压、图片处理 DAG、视觉结构化分析、生成式重绘提案、电商数据导入与聚合查询，以及 MySQL + Qdrant 的长期记忆治理。出站模型和第三方调用使用 Redis Lua 令牌桶、并发许可和有界指数退避；图片任务以 Work Unit SUCCESS 为 checkpoint，并通过 Redisson 锁和 MySQL execution epoch 支持异常后恢复。Rubrics 当前已实现 Image Result 的 evidence-grounded 多次 LLM judge 评估切片，Copy Result、Task Decision、版本化数据集回归与人工 Promotion 仍在开发中。

在修复浏览器与后端 fileHash 协议、为 AgentLoop 增加硬迭代/预算边界、补齐智能裁剪与 DAG 内重绘编排，以及完成 Rubrics 剩余 subject 和回归闭环前，不建议使用原始描述中的“全部支持”措辞。
