# 清零前后端历史 Lint 基线并保留严格质量门禁

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，就应能逐步修复 ESLint、Checkstyle 与 SpotBugs 已冻结的历史问题，把三个存量基线降为空，同时保持现有业务行为、现有规则版本和默认严格门禁不变。每个停止点都必须更新本文；改变范围、顺序、错误模型、批次边界、基线策略或验收命令时，必须同步更新 `Progress`、`Decision Log` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，开发者运行 `pnpm --dir pixflow-web lint` 和 `mvn -DskipTests verify` 时，不再依赖任何历史违规计数或历史缺陷过滤也能成功。前端不再把未处理 Promise、未校验的动态值、普通对象异常和 Vue 模板排版问题藏在 bulk suppression 中；后端不再把缺少大括号、无用或星号 import、布局问题和无效自赋值藏在 Checkstyle/SpotBugs 基线中。

用户可观察到的业务行为不应因本计划改变。文件页、任务详情、上传进度、Agent 确认与 HTTP 错误提示仍按现有设计工作，`ApiError` 仍携带 `status`、`errorCode`、`message`、`traceId`、`details` 和 `retryAfterMs`。本计划只修复静态检查揭示的类型、控制流、异常和可维护性问题，不引入新功能，也不升级 linter、框架或运行时依赖。

完成标志是 `pixflow-web/eslint-suppressions.json` 只保留空 JSON 对象，`config/checkstyle/suppressions.xml` 不再包含 `<suppress>`，`config/spotbugs/exclude-filter.xml` 不再包含 `<Match>`；前端 lint、typecheck、test、build 与后端严格 verify 均成功。若完整 `mvn verify` 仍被本机 Docker/Testcontainers 环境阻断，必须记录具体测试和首个相关栈帧，并用触达模块测试和 `mvn -DskipTests verify` 分别证明业务修改与 linter 门禁。

## Progress

- [x] (2026-07-16) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、当前执行计划、`docs/development/linting.md` 和三种 linter 基线。
- [x] (2026-07-16) 运行当前严格门禁：前端 ESLint 成功；后端 30 模块 Checkstyle/SpotBugs reactor 成功。
- [x] (2026-07-16) 统计并审阅历史问题：ESLint 647 条；Checkstyle 初始审计 4,940 条、当前 689 个精确 suppression；SpotBugs 3 条告警、2 个精确 filter。
- [x] (2026-07-16) 创建本 ExecPlan，固定风险优先、逐文件缩减基线和不做全仓一次性格式化的策略。
- [x] (2026-07-16 21:02+08:00) Milestone 1：删除三处无效自赋值并清空 SpotBugs filter；4 个定向构造测试和两模块严格 verify 通过。
- [x] (2026-07-16 21:40+08:00) Milestone 2：修复前端共享类型、结构化错误、异步和动态数据边界；前端四项验证通过，风险类 suppression 归零。
- [x] (2026-07-16 21:52+08:00) Milestone 3：清理 Vue 组件合同与模板布局问题，ESLint bulk suppression 已为空。
- [ ] Milestone 4：清理后端 correctness-adjacent Checkstyle 问题。
- [x] (2026-07-16 22:04+08:00) Milestone 4 首批：原子清理 permission、common、vision、eval、infra-ai 中 6 个低冲突文件，Checkstyle suppression 从 689 降到 681。
- [x] (2026-07-16 22:24+08:00) Milestone 4 第二批：原子清理 infra-thirdparty 中 3 个含无用 import 的文件，删除对应 7 个 suppression，剩余 674；模块严格 verify 与 13 项测试通过。
- [x] (2026-07-16 22:58+08:00) Milestone 4/5 第三批：完整清空 hooks、context、session、tools 四模块的 36 个 suppression，修复 124 个实际违规，剩余 638；11 模块严格 verify 成功，hooks/session/tools 33 项测试通过，context 的 2 个既有失败保持不变。
- [x] (2026-07-16 23:26+08:00) Milestone 4/5 第四批：完整清空 infra-auth 模块的 12 个 suppression，修复 50 个实际违规，剩余 626；5 模块严格 verify 成功，auth/cache/common 共 82 项测试通过。
- [x] (2026-07-17 00:11+08:00) Milestone 4/5 第五批：完整清空 infra-mq 模块的 41 个 suppression，覆盖 159 个历史违规行，剩余 585；3 模块严格 verify 成功，mq/common 共 35 项测试通过。
- [x] (2026-07-17 00:32+08:00) Milestone 4/5 第六批：完整清空 commerce 模块的 20 个 suppression，修复严格检查报告的 96 个问题，剩余 565；4 模块严格 verify 成功，commerce/mq/common 共 57 项测试通过。
- [x] (2026-07-17 00:44+08:00) Milestone 4/5 第七批：完整清空 infra-image 模块的 21 个 suppression，修复严格检查报告的 83 个问题，剩余 544；3 模块严格 verify 成功，image/common 共 48 项测试通过。
- [x] (2026-07-17 00:57+08:00) Milestone 4/5 第八批：完整清空 infra-storage 模块的 10 个 suppression，修复严格检查报告的 44 个问题，剩余 534；3 模块严格 verify 成功，storage/common 共 38 项测试通过。
- [x] (2026-07-17 01:29+08:00) Milestone 4/5 第九批：完整清空 agent 模块的 58 个 suppression，修复严格检查报告的 285 个问题，剩余 476；19 模块严格 verify 成功，agent 40 项测试通过。
- [x] (2026-07-17 01:47+08:00) Milestone 4/5 第十批：完整清空 eval 模块的 16 个 suppression，修复 45 个历史违规行，剩余 460；4 模块严格 verify 成功，eval/storage/common 共 44 项测试通过。
- [x] (2026-07-17 11:52+08:00) Milestone 4/5 第十一批：完整清空 infra-vector 模块的 6 个 suppression，修复 18 个历史违规行，剩余 454；3 模块严格 verify 成功，vector/common 共 34 项测试零失败，其中 2 项 Qdrant Testcontainers 测试因本机无 Docker 环境跳过。
- [x] (2026-07-17 12:12+08:00) Milestone 4/5 第十二批：完整清空 common 模块的 8 个 suppression，修复 22 个历史违规行，剩余 446；模块严格 verify 成功，clean test 23 项全部通过。
- [x] (2026-07-17 12:34+08:00) Milestone 4/5 第十三批：完整清空 file 模块的 33 个 suppression，修复严格检查报告的 135 个问题，剩余 413；File 模块 Checkstyle 严格门禁与 32 项测试全部通过。
- [x] (2026-07-17 12:45+08:00) Milestone 4/5 第十四批：原子清理 rubrics 的 `RubricsRunEntity`，删除 2 个 suppression 并修复 59 个历史违规行，剩余 411；Rubrics 模块严格 verify 与 12 项测试全部通过。
- [ ] Milestone 5：按模块和文件批次清理后端布局问题并清空 Checkstyle suppression。
- [ ] Milestone 6：完成文档、全仓验证和基线不可回增检查。

## Surprises & Discoveries

- Observation: 严格门禁当前全部成功，但成功依赖三个已审阅的历史基线，并不表示源码零问题。
  Evidence: `pixflow-web/eslint-suppressions.json` 含 87 个文件、174 个 file/rule pair、647 条违规；`config/checkstyle/suppressions.xml` 含 689 个条目；`config/spotbugs/exclude-filter.xml` 含 2 个 Match。

- Observation: 前端 647 条中 457 条约占 71%，只是 Vue 模板属性换行、内容换行、缩进、属性顺序和自闭合风格；应与行为修复分开。
  Evidence: `vue/max-attributes-per-line` 296、`vue/singleline-html-element-content-newline` 124、`vue/html-indent` 22、`vue/html-self-closing` 8、`vue/attributes-order` 7。

- Observation: 前端最集中的 unsafe 告警不是 36 个独立业务缺陷，而是从 Vue SFC 导出的 `ImageItem` 被 type-aware ESLint 视为无法解析的 error type，随后污染整个文件。
  Evidence: `FilesPage.vue:56` 与 `TaskDetailPage.vue:38` 从 `components/files/ImageGrid.vue` 导入类型；诊断从该类型继续传播到字符串访问、排序、ref 和事件参数。

- Observation: `PackageExtractionProgress.vue` 使用的 `extractProgress`、`scanProgress`、`scanStage` 和多种状态只存在于该组件，既不在 `PackageDetail`，也不在后端或前端 API 合同中。
  Evidence: 全仓搜索只命中该组件；`types/upload.ts` 的真实字段是 `imageCount`、`extractedCount`、`errorSummary`，状态为 `UPLOADED | EXTRACTING | READY | PARTIAL | FAILED`。

- Observation: ESLint 的 `only-throw-error` 与权威前端设计的字面表述存在冲突。设计要求抛出结构化 `ApiError` 而不是原生 `Error`，现有实现因此抛普通对象；linter 要求所有 throw/reject 值继承 `Error`。
  Evidence: `docs/design-docs/web.md` 的错误归一化段落，以及 `useAgentTurn.ts` 的 9 条 `only-throw-error`、`api/client.ts` 的 5 条同类告警。

- Observation: Checkstyle 初始 4,940 条违规被归并为 689 个 file/check/lines 条目；XML 中共有 3,257 个唯一违规行，因为同一行可产生多个原始违规。
  Evidence: `linter-adoption-plan.md` 的原始审计记录；当前 suppression 逐项拆分行号后的统计。

- Observation: Checkstyle 的 3,055 个唯一违规行约占 94%，属于空行、大括号位置、行宽、空白和单行多语句等布局问题；风险更高的是 100 个 `NeedBraces`、42 个 `UnusedImports` 和 11 个 `AvoidStarImport`。
  Evidence: `config/checkstyle/suppressions.xml` 按 check 聚合。

- Observation: Checkstyle suppression 精确到行号。先在文件顶部插入或删除行、却不同时处理该文件的全部 suppression，会让后续行号整体错位并产生噪声。
  Evidence: `config/checkstyle/README.md` 要求 file/check/line 精确基线；当前 XML 的每个条目都显式列行号。

- Observation: SpotBugs 的三条 High finding 都是 compact record constructor 中无效果的自赋值，不是需要保留的兼容逻辑。
  Evidence: `CopyContext` 中 `skuId = skuId`、`productName = productName`，以及 `ImagegenPlan` 中 `prompt = prompt`。

- Observation: Milestone 1 的定向构造测试和空 SpotBugs filter 下的严格门禁均通过；按用户指示跳过了耗时的完整模块测试集。
  Evidence: `CopyContextTest` 与 `ImagegenPlanTest` 各 2 条测试通过；`mvn -pl pixflow-module-dag,pixflow-module-imagegen -am -DskipTests verify` 在 14 个 reactor 项目上 BUILD SUCCESS，DAG/Imagegen 的 SpotBugs `BugInstance size is 0`。

- Observation: Milestone 2 prune 后的 477 条 suppression 全部属于 Vue 组件合同或模板布局，非 Vue 风险规则已归零。
  Evidence: 当前基线为 65 个文件、107 个 file/rule pair；只剩 `vue/max-attributes-per-line` 296、`vue/singleline-html-element-content-newline` 124、`vue/html-indent` 22、`vue/require-default-prop` 19、`vue/html-self-closing` 8、`vue/attributes-order` 7、`vue/multi-word-component-names` 1。风险规则搜索和源码内联指令搜索均无输出。

- Observation: 多个既有 Vitest fetch mock 同步返回 `Response`，虽然浏览器 `fetch` 总是返回 Promise；超时包装器直接调用 `.then()` 会使这些合同测试失败。
  Evidence: 首轮验证有 6 个测试报 `p.then is not a function`；`withTimeout` 使用 `Promise.resolve(p)` 归一化后，24 个测试文件、116 个测试全部通过。

- Observation: Milestone 3 的 scoped ESLint fix 能机械修复全部 457 条 Vue 模板布局问题；组件合同的 20 条问题需要先显式补齐 undefined 默认值和组件名。
  Evidence: `vue/require-default-prop` 19 条通过不改变缺省语义的 `undefined` defaults 清零，`Composer.vue` 通过 `defineOptions({ name: 'ChatComposer' })` 清除单词组件名告警；随后按 ui、icons、files、upload、tasks、chat、layout、pages 显式文件清单修复，最终 suppression 文件为 `{}`。

- Observation: 同一工作树并行运行两个 Maven reactor 会争用共享上游模块的 `target/spotbugsTemp.xml`，可产生临时 XML 尾随内容错误。
  Evidence: infra-ai 与 eval 并行严格 verify 时，eval 成功而 infra-ai 在 common SpotBugs 阶段报 `spotbugsTemp.xml:119:1 尾随节中不允许有内容`；随后单独串行重跑 infra-ai 完全成功。

- Observation: 删除文件的精确 suppression 并整理已知长行后，严格 Checkstyle 仍可暴露编辑后新行上的超长表达式，因此文件级原子批次必须以真实门禁结果为准，不能只按旧行号清单验收。
  Evidence: infra-thirdparty 首轮严格 verify 在 `RestClientThirdPartyHttpInvoker.java:30` 报 124 字符；拆分 `contentType` 三元表达式后，同一 5 模块 reactor 严格 verify 成功。

- Observation: 一个 Checkstyle suppression 条目可覆盖许多违规行，因此“删除条目数”明显小于实际修复的问题数；本轮四模块 36 个条目对应 124 个严格 Checkstyle 错误。
  Evidence: 移除基线后模块独立检查分别报告 hooks 10、context 39、session 50、tools 25 个错误；修复后四模块独立检查和合并严格 reactor 均为零。

- Observation: context 的两个既有单元测试失败在纯布局清理后仍以相同测试名和断言重现，其他触达模块测试全部通过。
  Evidence: `ContextBudgetServiceTest.externalizesLargeToolResultAndMicrocompactsOldResults` 与 `ContextProjectorTest.backsUpWindowStartToKeepToolPair` 失败；hooks 13、session 19、tools 1 项测试零失败、零跳过，context 其余 13 项通过。

- Observation: infra-auth 的 12 个 suppression 精确覆盖 50 个违规，全部属于字段/常量声明间隔和 6 处超长表达式；模块可以在不触碰鉴权控制流的情况下完整清零。
  Evidence: 删除该模块全部 suppression 后，包含 contracts、common、infra-cache、infra-auth 的 5 模块严格 reactor 报告 Checkstyle 与 SpotBugs 均为零；auth 37、cache 22、common 23 项测试全部通过。

- Observation: infra-mq 的单行方法、单行 catch/if 和 lambda 同时触发多个规则，41 个 suppression 覆盖 159 个唯一历史违规行；完整展开控制流后，首次严格门禁还准确指出 `RocketMessagePublisher` 两个遗漏的字段分隔问题。
  Evidence: 补齐最后两个空行后，`mvn -pl pixflow-infra-mq -am -DskipTests verify` 在 root/common/mq 三模块报告零 Checkstyle 和零 SpotBugs；随后 common 23 项、mq 12 项测试全部通过，零失败、零错误、零跳过。

- Observation: commerce 的 20 个 suppression 对应 96 个真实问题，主要是连续字段缺少空行，另有 20 处长行/空白问题和 1 个无用 import；清理不需要改变查询、导入、MQ 或 SQL 语义。
  Evidence: 删除模块全部 suppression 后严格 Checkstyle 报告 96 条；修复后 `mvn -pl pixflow-module-commerce -am -DskipTests verify` 的 root/common/mq/commerce 四模块均为零 Checkstyle 与零 SpotBugs，随后包含 MySQL 8.4 Testcontainers 集成测试在内的 commerce 22、mq 12、common 23 项测试全部通过。

- Observation: infra-image 的 21 个 suppression 对应 83 个真实问题，其中包含 13 处 `NeedBraces`，其余是字段/方法间隔、单行方法与长表达式布局；全部可以在不改变 Pixel Pipeline、Pixel Budget 或编解码语义的前提下清零。
  Evidence: 删除模块全部 suppression 后严格 Checkstyle 报告 83 条；修复后 `mvn -pl pixflow-infra-image -am -DskipTests verify` 的 root/common/image 三模块 Checkstyle 与 SpotBugs 均为零，随后 image 25 项、common 23 项测试全部通过。

- Observation: infra-storage 的 10 个 suppression 对应 44 个真实问题，均为连续字段缺少空行和超过 120 字符的声明、条件或异常构造；无需改变 ObjectStorage、StorageKeys、MinIO 调用或异常语义即可完整清零。
  Evidence: 删除模块全部 suppression 后严格 Checkstyle 报告 44 条；修复后 `mvn -pl pixflow-infra-storage -am -DskipTests verify` 的 root/common/storage 三模块 Checkstyle 与 SpotBugs 均为零，随后 storage 15 项（含真实 MinIO Testcontainers 集成测试）、common 23 项测试全部通过。

- Observation: agent 的 58 个 suppression 对应 285 个真实问题，主要集中在配置属性与持久化实体的单行访问器、连续字段、logger 常量命名和少量缺大括号、无用 import、长行；无需改变 Prompt、Session Memory、Skill 或 Subagent 合同即可完整清零。
  Evidence: 删除该模块全部 suppression 后独立 Checkstyle 首次报告 285 条；修复后 `mvn -pl pixflow-agent -am -DskipTests verify` 的 19 模块 Checkstyle 与 SpotBugs 均为零，`mvn -pl pixflow-agent test` 的 40 项测试全部通过。

- Observation: eval 的 16 个 suppression 精确覆盖 45 个历史违规行，全部属于连续字段缺少空行和超长构造、查询、轮询或布尔表达式；无需改变 trace 缓冲、回放、保留清理或外置存储语义即可完整清零。
  Evidence: 移除模块全部 suppression 并完成等价布局整理后，`mvn -pl pixflow-eval -am -DskipTests verify` 的 root/common/storage/eval 四模块 Checkstyle 与 SpotBugs 均为零；随后 eval 6、storage 15、common 23 项测试全部通过。

- Observation: infra-vector 的 6 个 suppression 覆盖 18 个历史布局问题；删除后首次严格 verify 还准确暴露了因前面字段换行而移动到新行号的 122 字符调用，证明文件级清理不能只依赖旧 suppression 行号。
  Evidence: 拆分 `collectionExistsAsync` 调用后，`mvn -pl pixflow-infra-vector -am -DskipTests verify` 在 root/common/vector 三模块报告零 Checkstyle 和零 SpotBugs；随后 common 23 项与 vector 11 项测试零失败，但 `QdrantVectorStoreIntegrationTest` 的 2 项因 Testcontainers 找不到有效 Docker 环境而跳过。

- Observation: common 的 8 个 suppression 覆盖 22 个历史布局问题；删除并初步整理后，严格 Checkstyle 继续指出 `ApiResponse` 另一个 123 字符构造调用，说明同文件原子清理必须以空 suppression 的真实报告收尾。
  Evidence: 补齐该构造调用的参数换行后，`mvn -pl pixflow-common -DskipTests verify` 报告零 Checkstyle 与零 SpotBugs；随后 `mvn -pl pixflow-common clean test` 强制重编译 22 个生产源文件和 8 个测试源文件，23 项测试全部通过。

- Observation: Rubrics 的 `RubricsRunEntity` 单一文件的 2 个 suppression 覆盖 59 个历史违规行，均为连续字段与单行 accessor 的布局问题；无需改变 Evaluation Run 的持久化字段、访问器或 MyBatis 映射即可清理。
  Evidence: 删除两项精确 suppression 并展开字段、getter、setter 后，`mvn -pl pixflow-module-rubrics -DskipTests verify` 报告零 Checkstyle 与零 SpotBugs；`mvn -pl pixflow-module-rubrics test` 的 12 项测试零失败、零错误、零跳过。

## Decision Log

- Decision: 最终清空三种基线，不把“减少一定比例”作为完成条件。
  Rationale: 门禁已稳定接入，历史问题也已分类；继续长期保留基线会让真实风险与机械债务混在一起，并增加触达代码时的维护成本。
  Date/Author: 2026-07-16 / Codex

- Decision: 按风险优先处理 SpotBugs、前端类型/异步/异常和后端控制流，再处理模板与 Java 布局。
  Rationale: 先降低可能影响错误传播、异步失败和控制流的风险；纯排版放在后续独立批次，便于审阅和回归定位。
  Date/Author: 2026-07-16 / Codex

- Decision: 新增 `pixflow-web/src/types/files.ts`，将供 Vue 组件、页面和 runtime 共享的图片展示类型定义为 `GalleryImageItem`；不再从 `.vue` 文件导出供 TypeScript 模块消费的类型，也不与上传 API 的 `ImageItem` 同名。
  Rationale: SFC 类型导出是 FilesPage/TaskDetailPage unsafe 级联的根因；独立 TS 模块让 ESLint、TypeScript 和组件模板使用同一可解析合同。区分 gallery item 与后端 upload item 可避免两个结构不同的 `ImageItem` 继续混淆。
  Date/Author: 2026-07-16 / Codex

- Decision: 把前端结构化 API 错误实现为自定义 `Error` 子类，保留全部 `ApiError` 字段，并同步更新 `docs/design-docs/web.md` 与相关 frontend 设计摘要。
  Rationale: 自定义错误同时满足 `only-throw-error` 和现有结构化错误合同；“不抛原生 Error”应解释为不把没有业务字段的普通原生错误泄漏给调用方，而不是要求抛普通对象。
  Date/Author: 2026-07-16 / Codex

- Decision: 删除 `PackageExtractionProgress.vue` 中不存在于服务端合同的 scan/extract 字段，使用 `extractedCount / imageCount` 计算可用进度，并只映射 `PackageDetail` 已声明的五种状态。
  Rationale: 全仓没有这些旧字段的生产者。凭空向 DTO 增加 optional 字段只会掩盖失配，不能产生真实进度。
  Date/Author: 2026-07-16 / Codex

- Decision: ExecPlan 所列 `docs/design-docs/frontend/transport-api.md` 在当前仓库不存在；结构化 API 错误合同改为同步到现有权威文档 `docs/design-docs/frontend/api.md`，并同时更新 `docs/design-docs/web.md`。
  Rationale: `frontend/api.md` 是当前 index 与源码引用的前端 API 合同，创建内容重叠的新文档会产生两个权威来源。
  Date/Author: 2026-07-16 / Codex

- Decision: 不执行一次性的仓库级 `lint:fix`、Java formatter 或全仓格式化。前端只允许对当前批次明确列出的文件使用 scoped ESLint fix，并必须审阅 diff；Java 按一个文件或一个紧密模块批次手工修正。
  Rationale: 仓库仍有 Rubrics 等并行执行计划和大量未提交工作。大范围机械修改会放大冲突，也会使 Checkstyle 行号基线难以安全缩减。
  Date/Author: 2026-07-16 / Codex

- Decision: 清理一个 Java 文件时，同时删除该文件的全部 Checkstyle suppression，并把文件修到零违规；不尝试逐行平移 suppression 行号。
  Rationale: 文件级原子清理最容易证明基线单调下降，也避免编辑造成后续 suppression 行号错位。
  Date/Author: 2026-07-16 / Codex

- Decision: 与当前 Rubrics、Task、DAG 或 App 执行计划重叠的文件放在最后处理；若这些计划仍在主动修改同一文件，则先完成或协调其业务改动，再做机械 lint 清理。
  Rationale: `rubrics-criterion-verdict-refactor-plan.md` 尚未完成，而 Rubrics、Task、DAG 又是 Checkstyle 债务最多的模块之一。避免并行修改同一文件比追求表面清理速度更重要。
  Date/Author: 2026-07-16 / Codex

- Decision: 后续 Maven reactor 验证在同一工作树内串行执行，不再并行共享 `target` 的模块组合。
  Rationale: SpotBugs 使用模块内固定临时报告路径；并行 reactor 会写入相同上游模块的报告文件，制造与源码无关的偶发解析失败。
  Date/Author: 2026-07-16 / Codex

- Decision: 第四批选择完整清空 infra-auth，而不是触碰仍有活动执行计划的 Task、DAG 或 Rubrics。
  Rationale: infra-auth 工作树无既有源码修改，50 个违规恰好满足本轮先修复再测试的数量要求，且修改只涉及布局，不会与当前领域重构冲突。
  Date/Author: 2026-07-16 / Codex

- Decision: 第六批选择完整清空 commerce，并在严格 verify 清零后才运行模块测试。
  Rationale: commerce 源码在当前脏工作树中没有既有修改，与 Execution/Rubrics 活跃计划无重叠；删除 20 个 suppression 会暴露 96 个独立问题，既满足用户要求的测试前修复门槛，又能形成可独立验收的完整模块边界。
  Date/Author: 2026-07-17 / Codex

- Decision: 第七批选择完整清空 infra-image，并在严格 verify 清零后才运行模块测试。
  Rationale: infra-image 生产源码没有既有修改，模块边界清晰且不与活跃 Execution/Rubrics 计划重叠；21 个 suppression 暴露 83 个问题，能够形成独立、可验证的完整模块批次。
  Date/Author: 2026-07-17 / Codex

- Decision: 第八批选择完整清空 infra-storage，并在严格 verify 清零后才运行模块测试。
  Rationale: infra-storage 生产源码没有既有修改，也不与当前 Task/Rubrics/DAG/App 业务改动重叠；虽然严格检查只暴露 44 个问题，但完整模块清零满足用户要求的测试前边界，并由真实 MinIO 集成测试验证布局修改未改变存储行为。
  Date/Author: 2026-07-17 / Codex

- Decision: 第九批选择完整清空 agent，并在严格 verify 清零后才运行模块测试。
  Rationale: agent 生产源码没有既有修改，能够避开 Task、Rubrics、State、Vision 等活跃业务改动；58 个 suppression 暴露 285 个问题，既超过用户要求的测试前修复门槛，也形成了可独立验收的完整模块边界。
  Date/Author: 2026-07-17 / Codex

- Decision: 第十批选择完整清空 eval，并在严格 verify 清零后才运行模块测试。
  Rationale: eval 位于计划规定的低冲突清理顺序，除已完成批次中的 `EvalErrorRecorder` 外没有活动业务修改；完整清除 16 个 suppression 形成了满足测试前边界的独立模块成果，且修改只涉及布局。
  Date/Author: 2026-07-17 / Codex

- Decision: 第十一批选择完整清空 infra-vector，并在严格 verify 清零后才运行模块测试。
  Rationale: infra-vector 生产源码没有既有修改，位于计划规定的低冲突 infra 顺序，也不与 Task、DAG、Rubrics 或 App 活跃计划重叠；虽然只有 18 个历史问题，但完整模块清零满足用户要求的测试前边界，且修改只涉及字段间隔和长行拆分。
  Date/Author: 2026-07-17 / Codex

- Decision: 第十二批选择完整清空 common，并保留不在 suppression 清单内的既有 `ErrorNormalizer` 修改。
  Rationale: common 的剩余问题只涉及字段分隔与长行，完整模块清零满足用户要求的测试前边界；本批次不覆盖或重排工作树中已有的 `ErrorNormalizer` 变更，也不改变错误、脱敏、响应或分页合同。
  Date/Author: 2026-07-17 / Codex

- Decision: 第十三批选择完整清空 file，并避开已由其他工作移动的 active execution/rubrics plans。
  Rationale: file 的 33 个精确 suppression 覆盖的 135 个问题均可通过 import 删除、字段间隔、等价换行和 braces 清零，不改变上传、解压、素材身份或删除合同；完成一个模块后才运行该模块测试，符合用户指定的验证边界。

- Decision: 第十四批选择 Rubrics 模块中未触及的 `RubricsRunEntity` 作为单文件原子批次。
  Rationale: 两项 suppression 覆盖 59 条违规，已达到用户的测试前修复阈值；字段和 accessor 的格式化不会改变 Evaluation Run 的数据库或 JavaBean 合同，且避免与仍在演进的 Rubrics 迁移计划重叠。
  Date/Author: 2026-07-17 / Codex

## Outcomes & Retrospective

Milestone 1 和 2 已完成。SpotBugs filter 从 2 个 Match 降为空，三处无效自赋值已删除，nullable `CopyContext` 组件、`ImagegenPlan.prompt` 保留和 null 拒绝均有定向测试证据；严格 verify 成功且两个目标模块的 SpotBugs 均为零。完整 DAG/Imagegen 测试集按用户指示跳过。

前端通过独立 `GalleryImageItem` 类型边界、自定义 `ApiError extends Error`、unknown 归一化、显式异步失败处理和真实上传 DTO 字段，清除了全部非 Vue 风险 suppression。ESLint 基线从 647 条降到 477 条，剩余项仅为 Milestone 3 的 Vue 合同与模板布局。`pnpm --dir pixflow-web lint`、`typecheck`、`test` 和 `build` 均成功；Vitest 为 24 个文件、116 个测试通过。生产构建仍输出既有 Tailwind `darkMode: false` 迁移警告，不影响构建成功。

Milestone 3 也已完成：19 条 optional prop 默认值告警、1 条组件命名告警和 457 条模板布局告警全部清除，`pixflow-web/eslint-suppressions.json` 现为空对象。零 suppression 下 lint、typecheck、24 文件/116 测试和生产 build 再次全部通过，源码中 `eslint-disable`、`ts-ignore`、`ts-expect-error` 搜索无结果。前端基线清零目标已达成，后续进入 Checkstyle 清理。

Milestone 4 已开始。`PermissionSubject`、`ErrorNormalizer`、`VisionAnalysisRequest`、`EvalErrorRecorder`、`ToolCallAccumulator`、`DefaultVisionModelClient` 六个文件的全部 suppression 已删除，源码修复了无用 import、三处长行、字段空行和 logger 常量命名，Checkstyle suppression 由 689 降至 681。permission、common、vision、eval、infra-ai 的相关严格 verify 与测试均通过；vision reactor 测试中目标模块 37 项通过，infra-ai 26 项、eval 6 项通过，相关上游测试也成功。

Milestone 4 第二批从 infra-thirdparty 的 Aliyun Market provider、可配置 HTTP provider 和 RestClient invoker 删除 4 个无用 import，并完成字段间隔与长行整理；7 个对应 suppression 已删除，基线从 681 降到 674。5 模块严格 verify 的 Checkstyle 与 SpotBugs 均为零，thirdparty 自身 13 项测试零失败、零跳过。Milestone 4 仍需继续清理其他 correctness-adjacent 规则与 NeedBraces。

第三批完整清空了 hooks、context、session、tools 四模块的 Checkstyle 基线。26 个文件只做无用 import、字段空行、空白和长行整理，36 个 suppression 对应的 124 个实际违规全部归零，基线从 674 降到 638。包含 11 个项目的合并严格 reactor 编译、Checkstyle 与 SpotBugs 全部成功。hooks、session、tools 共 33 项测试通过；context 的 15 项中 13 项通过，两个失败与其他执行计划已记录的既有断言完全相同，未由本轮格式调整引入。

第四批完整清空了 infra-auth 模块的 Checkstyle 基线。11 个文件只增加字段或常量声明间的空行并拆分超长表达式，12 个 suppression 对应的 50 个实际违规全部归零，基线从 638 降到 626。包含 5 个项目的严格 reactor 编译、Checkstyle 与 SpotBugs 全部成功；auth 37、cache 22、common 23 项测试合计 82 项，零失败、零错误、零跳过。

第五批完整清空了 infra-mq 模块的 Checkstyle 基线。12 个文件只做字段/方法分隔、单行控制流展开、NeedBraces、常量命名和长行拆分，41 个 suppression 覆盖的 159 个历史违规行全部归零，基线从 626 降到 585。root/common/mq 三模块严格 reactor 的 Checkstyle 与 SpotBugs 均为零；common 23 项、mq 12 项测试合计 35 项，零失败、零错误、零跳过。

第六批完整清空了 commerce 模块的 Checkstyle 基线。15 个文件只做字段分隔、长行与 SQL 文本换行、空构造器空白和无用 import 删除，20 个 suppression 对应的 96 个实际违规全部归零，基线从 585 降到 565。root/common/mq/commerce 四模块严格 reactor 的 Checkstyle 与 SpotBugs 均为零；commerce 22 项（含真实 MySQL 8.4 Testcontainers 集成测试）、mq 12 项、common 23 项合计 57 项，零失败、零错误、零跳过。

第七批完整清空了 infra-image 模块的 Checkstyle 基线。11 个文件只做字段/方法分隔、单行方法与控制流展开以及长行拆分，21 个 suppression 对应的 83 个实际违规全部归零，基线从 565 降到 544。root/common/image 三模块严格 reactor 的 Checkstyle 与 SpotBugs 均为零；image 25 项、common 23 项合计 48 项，零失败、零错误、零跳过。

第八批完整清空了 infra-storage 模块的 Checkstyle 基线。6 个文件只做字段分隔与长行拆分，10 个 suppression 对应的 44 个实际违规全部归零，基线从 544 降到 534。root/common/storage 三模块严格 reactor 的 Checkstyle 与 SpotBugs 均为零；storage 15 项（含真实 MinIO Testcontainers 集成测试）、common 23 项合计 38 项，零失败、零错误、零跳过。

第九批完整清空了 agent 模块的 Checkstyle 基线。32 个文件只做无用 import 删除、logger 常量命名、字段与方法分隔、单行访问器和控制流展开、长行与 SQL 文本换行，58 个 suppression 对应的 285 个实际违规全部归零，基线从 534 降到 476。包含 19 个项目的严格 reactor 编译、Checkstyle 与 SpotBugs 全部成功；agent 40 项测试零失败、零错误、零跳过。

第十批完整清空了 eval 模块的 Checkstyle 基线。10 个文件只做字段分隔、import 顺序和长表达式换行，16 个 suppression 覆盖的 45 个历史违规行全部归零，基线从 476 降到 460。root/common/storage/eval 四模块严格 reactor 的 Checkstyle 与 SpotBugs 均为零；eval 6、storage 15（含真实 MinIO Testcontainers）、common 23 项合计 44 项，零失败、零错误、零跳过。

第十一批完整清空了 infra-vector 模块的 Checkstyle 基线。5 个文件只做字段分隔和长行拆分，6 个 suppression 覆盖的 18 个历史违规行全部归零，基线从 460 降到 454。root/common/vector 三模块严格 reactor 的 Checkstyle 与 SpotBugs 均为零；common 23 项与 vector 11 项合计 34 项零失败、零错误，vector 中 2 项真实 Qdrant Testcontainers 集成测试因本机找不到有效 Docker 环境而跳过。

第十二批完整清空了 common 模块的 Checkstyle 基线。7 个文件只做字段分隔与长构造/正则表达式换行，8 个 suppression 覆盖的 22 个历史违规行全部归零，基线从 454 降到 446；工作树中既有的 `ErrorNormalizer` 修改保持原样。模块严格 verify 报告零 Checkstyle 与零 SpotBugs，clean test 强制重编译当前源码后 23 项全部通过。

第十三批完整清空了 file 模块的 Checkstyle 基线。20 个文件只做无用 import 删除、字段/常量间隔、长表达式换行和既有单语句控制流的大括号展开，33 个 suppression 对应的 135 个严格检查问题全部归零，基线从 446 降到 413。独立绑定的 Checkstyle 严格门禁为零；`mvn -pl pixflow-module-file test` 的 32 项测试零失败、零错误、零跳过。完整 12 模块 `-am -DskipTests verify` 在当前桌面工具的 124 秒进程上限中未取得结果，遗留的两条 Maven Java 进程已停止，未将超时计为通过。

第十四批原子清理了 Rubrics 的 `RubricsRunEntity`。该文件只做字段间隔和 getter/setter 的等价展开，2 个 suppression 覆盖的 59 个严格检查问题全部归零，基线从 413 降到 411。首次带 `-am` 的 reactor 达到桌面 124 秒命令上限，短暂遗留的 Maven 进程使结果 XML 损坏；进程退出后串行 `mvn -pl pixflow-module-rubrics -DskipTests verify` 成功，随后 12 项模块测试零失败、零错误、零跳过。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。前端位于 `pixflow-web`，严格命令 `pnpm --dir pixflow-web lint` 使用 `pixflow-web/eslint.config.js` 与 `pixflow-web/eslint-suppressions.json`。bulk suppression 按文件和规则记录允许的历史数量；修复使数量下降后，执行 `pnpm --dir pixflow-web lint:prune-suppressions` 删除过量计数。源码中不得新增 `eslint-disable`，也不得扩大 ignore。

前端共享 HTTP 入口是 `pixflow-web/src/api/client.ts`，结构化错误合同当前位于 `pixflow-web/src/types/api.ts`，错误解析还会经过 `pixflow-web/src/transport/httpError.ts`。Agent SSE/HITL 错误传播集中在 `pixflow-web/src/runtime/useAgentTurn.ts`。图片展示类型当前错误地定义并导出自 `pixflow-web/src/components/files/ImageGrid.vue`，被 `FilesPage.vue`、`TaskDetailPage.vue` 和 `ResultPreview.vue` 消费。上传包合同位于 `pixflow-web/src/types/upload.ts`，adapter 位于 `pixflow-web/src/api/packages.ts`。

后端是根 `pom.xml` 管理的 30 项 Maven reactor。Checkstyle 使用 `config/checkstyle/alibaba-checkstyle.xml` 检查 production Java source，历史基线为 `config/checkstyle/suppressions.xml`。SpotBugs 使用 High threshold 检查 production class，历史过滤为 `config/spotbugs/exclude-filter.xml`。两者都绑定 Maven `verify`；`mvn -DskipTests verify` 跳过测试但仍编译并运行严格 Checkstyle/SpotBugs。

Checkstyle XML 的一个 `<suppress>` 只对应一个文件、一个 check 和显式行号。清理批次的正确含义不是“移动行号”，而是从 XML 删除该文件的条目，再修复所有暴露出的违规，直到模块严格 verify 成功。SpotBugs filter 同样只能删除，不能用更宽的 class/package/category 规则替代。

本计划建立在已完成的 `docs/design-docs/exec-plans/linter-adoption-plan.md` 之上。该计划负责引入工具和冻结存量；本文只负责消除存量，不更改 ESLint 9.39.2、Checkstyle 13.8.0、SpotBugs 4.10.3 或 Maven 插件版本。

## Scope and Non-Goals

范围包括 ESLint 基线中的全部 647 条问题、Checkstyle 基线中的全部 689 个 suppression 和 SpotBugs filter 中的全部 2 个 Match；包括为修复这些问题所必需的前端共享类型、错误归一化、单元测试与权威设计文档更新。

范围不包括启用新规则、升级工具、引入 Prettier/Spotless、扩大到测试 Java source、修改业务 API、重新设计文件页或任务页、引入新的上传进度协议、重构无关模块、配置 CI workflow。修复过程中发现独立业务缺陷时，先在 `Surprises & Discoveries` 记录证据；只有阻断当前 lint 修复时才在本文内处理，否则另立任务。

## Plan of Work

### Milestone 1：删除无效自赋值并清空 SpotBugs filter

在 `pixflow-module-dag/src/main/java/com/pixflow/module/dag/exec/CopyContext.java` 删除 compact constructor 中的 `skuId = skuId` 和 `productName = productName`。两者被允许为 null，不赋值也会由 record compact constructor 自动把参数写入同名组件，因此行为不变。在 `pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/proposal/ImagegenPlan.java` 删除 `prompt = prompt`；前一行 `Objects.requireNonNull(prompt, "prompt")` 和 record 自动赋值继续保留。

同时删除 `config/spotbugs/exclude-filter.xml` 中针对 `CopyContext.<init>` 与 `ImagegenPlan.<init>` 的两个 Match，保留合法空 `FindBugsFilter` 根元素和说明文件。增加或调整构造测试，明确 nullable CopyContext 字段保持原值、ImagegenPlan prompt 保持原值且 null 仍被拒绝。运行两个模块及其依赖的测试和严格 verify，确认不再生成 `SA_LOCAL_SELF_ASSIGNMENT_INSTEAD_OF_FIELD`。

### Milestone 2：修复前端共享类型、结构化错误、异步和动态数据边界

新增 `pixflow-web/src/types/files.ts`，定义 `GalleryImageItem`，字段与当前 ImageGrid 展示合同一致：`id`、`src`、`filename` 必填，`alt`、`size`、`failed` 可选。`ImageGrid.vue`、`ResultPreview.vue`、`FilesPage.vue` 和 `TaskDetailPage.vue` 全部从该 TS 文件导入类型，删除 SFC 中的导出 interface。上传 API 在 `types/upload.ts` 中已有结构不同的 `ImageItem`，将其重命名为 `PackageImageItem` 并同步 consumers，避免同名但不同语义。

修复类型源头后，删除 FilesPage/TaskDetailPage 中由 error type 诱发的断言与 unsafe 操作；如果仍有动态 API 值，必须在 `api/*.ts` adapter 中解析成 DTO，不能在页面中使用 `any` 或重复 `as`。删除 FilesPage 未使用的 router，把 `let groups` 改为 `const`。`onMounted` 中两个加载操作必须显式处理失败：可以用一个 async wrapper `await Promise.all(...)`，也可以分别 `void load...().catch(...)`，但 rejection 必须进入现有 toast/log 错误路径，不能只加 `void` 消音。

在 `types/api.ts` 把结构化 API 错误落为自定义 `ApiError extends Error`，constructor 接收现有业务字段并设置 `name` 与 `cause`。`transport/httpError.ts`、`api/client.ts` 和 `runtime/useAgentTurn.ts` 的所有错误创建、throw、reject、catch 归一化都只传递该实例；收到 unknown 时通过 schema/类型守卫提取字段，失败时创建 `NETWORK_ERROR` 或 `STREAM_ERROR`，不直接断言 `as ApiError`。`response.json()` 先保存为 `unknown` 再做 envelope 判断。把 `BodyInit | unknown`、`ApiError | Error | unknown` 等被 `unknown` 覆盖的 union 简化为 `unknown`。

`PackageExtractionProgress.vue` 不再引用不存在的 scan/extract DTO 字段。进度在 `imageCount > 0` 时按 `extractedCount / imageCount` 计算并限制在 0..100；没有分母时显示 0。badge 只处理 `UPLOADED`、`EXTRACTING`、`READY`、`PARTIAL`、`FAILED`，文案与 tone 延续现有视觉语义。删除无效果的 `void computed(() => props.pkg.scanProgress)`。

逐项处理剩余行为规则：所有 `no-floating-promises` 都明确 await 或带 rejection handler；`eqeqeq` 改为严格比较并显式表达 nullish 语义；`only-throw-error` 与 `prefer-promise-reject-errors` 归零；无必要 assertion 和 redundant union 删除。测试中的 `any`/unsafe mock 使用 `unknown`、`satisfies`、Vitest typed mock 或最小完整 fixture，不用双重断言绕过。

更新 `docs/design-docs/web.md` 和 `docs/design-docs/frontend/transport-api.md`：明确调用方收到的是继承 Error 的结构化 `ApiError`，而不是丢失业务字段的普通原生 Error；JSON error envelope 必须先转换后抛出。若 `docs/design-docs/frontend/files.md` 仍描述 SFC 导出展示类型，同步改为 `types/files.ts`。

本里程碑结束时，前端所有非 Vue 排版类的历史风险规则应为零；运行 lint、typecheck、全部 Vitest 与 build，并执行 prune。提交中同时包含对应 suppression 减少，禁止用新增 suppression 维持原计数。

### Milestone 3：清理剩余前端 ESLint 问题并把 bulk suppression 降为空

先处理组件合同类规则：`vue/require-default-prop` 要么通过 `withDefaults(defineProps<...>(), ...)` 给出真实默认值，要么在类型和运行语义都允许缺省时显式声明；不能填入改变 UI 行为的占位值。`vue/multi-word-component-names` 只对确有单词名称的组件改名或记录与路由页面约定一致的最小规则决定，不在源码加入 disable。

随后按目录分批处理模板布局：先 `components/ui` 与 `components/icons`，再 files/upload/tasks/chat/layout，最后 pages。每批只对明确文件执行 scoped ESLint fix 或手工修改，检查模板 attribute 顺序、事件、slot 和 conditional 是否保持不变。图标 SFC 可作为一个机械批次；复杂页面不得与图标批次混在同一提交。

每个目录批次运行 `pnpm --dir pixflow-web lint:prune-suppressions`，审阅 `eslint-suppressions.json` 只减少。最后文件内容应为 `{}`，保留该空文件以维持脚本和基线位置稳定。运行前端四项命令，证明零 suppression 下 lint、类型、测试和生产构建全部通过。

### Milestone 4：清理后端 correctness-adjacent Checkstyle 问题

先处理不会大规模改版式的规则：`UnusedImports`、`AvoidStarImport`、`ConstantName`、`MethodName` 和 `MultipleVariableDeclarations`。星号 import 展开为实际使用的具体类型；无用 import 直接删除；多变量声明拆成一行一个声明但不改变初始化顺序；常量和方法重命名必须用 IDE/编译器可验证的全引用修改，并运行相关测试。

再处理 100 个 `NeedBraces`。所有 if/else/for/while 分支加大括号，保持原条件、返回值和语句顺序。优先抽查并测试 `EvaluationRunCoordinator`、`RedisUploadSessionStore`、`TaskWorker`、`DefaultImageCodec`、`SkillFrontmatterParser`、`TerminalStateJudge` 和 `WorkUnitResultRepository` 等高集中度文件。禁止借加大括号之机改写分支或抽取方法。

每次选定一个文件时，先从 `config/checkstyle/suppressions.xml` 删除该文件的全部条目，再修复严格 Checkstyle 暴露的所有问题，最后运行所属模块 `mvn -pl <module> -am -DskipTests verify` 和对应定向测试。这样该文件后续不再依赖任何行号 suppression。小型、稳定且同属一个模块的文件可以合并批次；Rubrics、Task、DAG、Agent 和 App 中与其他执行计划重叠的文件留到 Milestone 5 最后阶段。

### Milestone 5：按模块和文件批次清理布局问题并清空 Checkstyle suppression

布局清理顺序从冲突风险低的叶模块开始：permission、hooks、common、storage、eval、context、session、state、tools；再处理各 infra 模块；然后处理 conversation、commerce、memory、vision、imagegen、file；最后处理 agent、dag、task、rubrics 和 app。每个模块开始前检查 `git status --short` 和当前执行计划，发现同文件仍有未完成业务修改时延后，不覆盖或重排用户工作。

对每个 Java 文件按相同原子流程处理：删除该文件全部 suppression；修复 `EmptyLineSeparator`、`LeftCurly`、`RightCurly`、`WhitespaceAround`、`WhitespaceAfter`、`OneStatementPerLine` 和 `LineLength`；运行模块严格 verify；审阅 diff 只含等价布局变化。长行优先在参数、链式调用和布尔表达式的自然边界换行，不通过缩短领域命名、删除注释信息或引入临时变量改变语义。

模块完成后，运行该模块测试并确认 XML 中不再出现模块路径。所有模块完成后，`config/checkstyle/suppressions.xml` 只保留 XML 声明、DTD、说明注释和空 `<suppressions>`。连续两次运行全 reactor 严格 verify，结果都必须为零 Checkstyle violation 和零 SpotBugs warning。

### Milestone 6：同步文档并完成全仓验证

更新 `docs/development/linting.md`、`config/checkstyle/README.md` 和 `config/spotbugs/README.md`，把“现有基线”改为“空基线占位文件”，说明任何新增条目都必须作为例外审查，正常修复流程不得回增。更新已完成的 `linter-adoption-plan.md` 只追加交叉引用或结果记录，不重写其历史实施事实。

运行前端 lint、typecheck、test、build，运行后端 `mvn -DskipTests verify` 与 `mvn verify`，再运行 `git diff --check`。检查三个基线为空，搜索源码不存在 `eslint-disable`，Checkstyle/SpotBugs 配置没有新增宽泛排除。将最终数量、测试结果、耗时、环境阻断与实际偏差写入本文四个 living sections 和 Revision Notes。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。实施前记录工作树和基线，不清理用户已有修改：

    git status --short
    git diff -- pixflow-web/eslint-suppressions.json config/checkstyle/suppressions.xml config/spotbugs/exclude-filter.xml
    pnpm --dir pixflow-web lint
    mvn -DskipTests verify

完成 Milestone 1 后运行：

    mvn -pl pixflow-module-dag,pixflow-module-imagegen -am test
    mvn -pl pixflow-module-dag,pixflow-module-imagegen -am -DskipTests verify
    rg -n "skuId = skuId|productName = productName|prompt = prompt|SA_LOCAL_SELF_ASSIGNMENT_INSTEAD_OF_FIELD" pixflow-module-dag pixflow-module-imagegen config/spotbugs

最后一条预期无输出。若注释仍需要说明 nullable 字段，把说明放在 record component Javadoc 或 constructor 注释中，不保留无效赋值。

Milestone 2 每个行为批次运行：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    pnpm --dir pixflow-web lint:prune-suppressions
    git diff -- pixflow-web/eslint-suppressions.json

在 prune 前，`lint` 可能因为 suppression 过量而失败并提示 prune；这是基线减少的预期信号。执行 prune 后必须再次运行 lint。若 lint 报告新的未抑制规则，修复源码，不重新生成 bulk baseline。

检查前端风险规则归零：

    rg -n 'no-floating-promises|no-unsafe-|only-throw-error|prefer-promise-reject-errors|eqeqeq|no-explicit-any' pixflow-web/eslint-suppressions.json
    rg -n 'eslint-disable|ts-ignore|ts-expect-error' pixflow-web/src

第一条在 Milestone 2 完成后预期无输出。第二条发现既有指令时要逐项审阅；本计划不得新增任何命中，不能未经证据删除与本计划无关的既有 TypeScript 指令。

Milestone 3 每个目录批次使用相同四项前端验证。若使用自动修复，只能显式列文件，例如从 `pixflow-web` 目录运行：

    pnpm exec eslint src/components/icons/IconAlertCircle.vue src/components/icons/IconCopy.vue --fix --suppressions-location eslint-suppressions.json

不得运行无文件清单的仓库级 `lint:fix`。执行后立即检查 diff，确认 SVG path、事件表达式、slot 和条件渲染未改变。

Milestone 4 和 5 为每个模块运行：

    mvn -pl <module> -am -DskipTests verify
    mvn -pl <module> -am test
    git diff -- config/checkstyle/suppressions.xml <module>/src/main/java

把 `<module>` 替换成实际 Maven artifact，例如 `pixflow-session`。若 `-am test` 因无关上游集成测试或 Docker 环境失败，记录具体失败，再运行该模块定向测试；不得把 `-DskipTests verify` 写成测试成功。

每个 Checkstyle 批次后确认 suppression 只减少：

    git diff --numstat -- config/checkstyle/suppressions.xml
    rg -n 'files=".*<module>.*"' config/checkstyle/suppressions.xml

模块完全清理后第二条预期无输出。不要用脚本重新生成全量 suppression，不要把行号整体平移。

最终运行：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

检查空基线：

    rg -n '"count"' pixflow-web/eslint-suppressions.json
    rg -n '<suppress ' config/checkstyle/suppressions.xml
    rg -n '<Match>' config/spotbugs/exclude-filter.xml

三条命令都预期无输出。空文件仍需是合法 JSON/XML，并被默认严格命令实际加载。

## Validation and Acceptance

验收首先看行为而不是基线文件大小。打开文件页后，素材和产物仍可加载、搜索、排序、选择、预览、下载与删除；页面首次加载的两个请求任一失败时有既有错误记录或 toast，不产生 unhandled rejection。任务详情仍可刷新结果、取消、失败重试、预览与下载。上传进度组件只展示后端实际提供的状态和计数，不显示虚构 scan 阶段。

HTTP 401、普通 4xx/5xx、网络错误、超时和 SSE proposal/challenge 错误都抛出 `instanceof Error` 为 true 的结构化 ApiError；调用方仍能读取 `errorCode`、`traceId` 与 details。相关测试必须分别覆盖 JSON envelope、非 JSON body、网络异常、AUTH_TOKEN_EXPIRED 刷新、challenge failed/expired 和 Promise rejection。日志与 toast 不得退化成只有通用 `Error.message`。

所有从 ImageGrid 跨组件共享的值使用 `GalleryImageItem`，type-aware ESLint 不再报告 error typed value。上传 API 的 `PackageImageItem` 与 gallery 类型不能互相冒充；adapter 明确完成字段映射。前端 suppression 最终为空，严格 lint 无输出且 typecheck、全部 Vitest 和 build 成功。

CopyContext 允许的 nullable component 与修改前一致，keywords 仍做不可变空列表/拷贝；ImagegenPlan 的 prompt 非空约束、sourceImageIds 拷贝和 params 不可变拷贝保持不变。SpotBugs 空 filter 下全 reactor High threshold 为零。

Java 控制流测试在增加 braces 后结果不变。每个清理文件已删除全部对应 Checkstyle suppression；最终空 suppression 下 30 模块严格 verify 连续两次成功。不存在用规则关闭、模块跳过、package wildcard 或扩大 filter 换来的假通过。

完整 `mvn verify` 应成功。若当前机器仍没有 Docker engine，允许把结果记录为环境阻断，但必须同时满足：触达模块非 Docker 测试成功、需要 Docker 的测试明确列名、Docker pipe 证据已记录、`mvn -DskipTests verify` 全 reactor 成功。环境阻断不能成为删除或跳过测试的理由。

## Idempotence and Recovery

所有批次可以重复运行。lint、typecheck、test、build、verify 和搜索命令本身不修改 production source；ESLint prune 会修改基线，因此只能在修复源码并审阅 diff 后运行。重复 prune 应不再产生 diff。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。开始一个文件前先检查工作树；遇到已有不属于本计划的修改时，在其上做最小补丁或延后该文件。若自动 fix 产生超范围改动，只用精确 `apply_patch` 撤销本计划产生的行，不覆盖用户原改动。

Java 文件的恢复单位是“源码修改 + 该文件全部 suppression”。若中途停止而源码尚未清零，不得提交只有 suppression 删除或只有行号平移的状态。继续时运行模块严格 verify 取得当前真实违规，再完成同一文件；不要重新生成基线。

前端错误模型迁移必须保持每一步可编译。先增加新的 ApiError class 和兼容构造 helper，再逐个迁移 producer，最后收紧 consumer 类型和删除旧对象构造。若某批测试失败，保留兼容 helper 继续修复，不恢复普通对象 throw 或添加 suppression。

空基线文件仍保留在仓库，使配置路径稳定且负向探针可以证明门禁有效。未来确有误报需要新增 filter 时，必须精确到现有文档要求的粒度，并在独立审查中记录证据；本计划实施期间基线只减不增。

## Artifacts and Notes

起始基线摘要：

    ESLint
      87 files
      174 file/rule pairs
      647 violations
      599 production + 48 test

    Checkstyle
      4,940 original violations
      689 file/check/lines suppression entries
      3,257 unique suppressed lines

    SpotBugs
      3 SA_LOCAL_SELF_ASSIGNMENT_INSTEAD_OF_FIELD findings
      2 pattern/class/constructor filters

前端风险集中点：

    FilesPage.vue                  36 type/async findings
    uploadJob.test.ts             32 unsafe test findings
    TaskDetailPage.vue            16 type/equality findings
    useAgentTurn.ts               11 error/rejection findings
    api/client.ts                  7 error/dynamic JSON findings
    PackageExtractionProgress.vue  5 stale-contract findings

Checkstyle 唯一违规行主要分布：

    pixflow-module-rubrics  850
    pixflow-module-task     560
    pixflow-agent           279
    pixflow-module-dag      231
    pixflow-infra-mq        159
    pixflow-module-file     134

目标基线形态：

    pixflow-web/eslint-suppressions.json
      {}

    config/checkstyle/suppressions.xml
      XML declaration + DTD + <suppressions> without <suppress>

    config/spotbugs/exclude-filter.xml
      XML declaration + <FindBugsFilter> without <Match>

## Interfaces and Dependencies

在 `pixflow-web/src/types/files.ts` 定义：

    export interface GalleryImageItem {
      id: string
      src: string
      filename: string
      alt?: string
      size?: string
      failed?: boolean
    }

在 `pixflow-web/src/types/api.ts` 提供继承 Error 的结构化异常。具体 constructor 可按现有调用点调整，但最终公共字段和语义必须满足：

    export class ApiError extends Error {
      readonly status: number
      readonly errorCode: string
      readonly traceId: string
      readonly details?: Record<string, unknown>
      readonly retryAfterMs?: number
    }

类必须保留正确 prototype，使 `instanceof Error` 与 `instanceof ApiError` 在目标浏览器和 Vitest 中成立。JSON envelope、网络异常和 SSE payload 不能直接当实例使用，必须由一个集中 factory/constructor 转换。不要在 Vue component 内重复实现错误解析。

`PackageDetail` 继续以 `types/upload.ts` 和 `api/packages.ts` adapter 为边界。进度只由真实字段推导，不增加服务端未提供的 scan/extract optional 字段。`GalleryImageItem` 是 UI view model，`PackageImageItem`/`AssetImageView` 是 API DTO，二者通过页面或 adapter 显式映射。

Java public interface、数据库 schema、REST API 和 Maven 依赖不因本计划改变。SpotBugs 修复只删除无效表达式；Checkstyle 修复只做 import、命名、声明拆分、braces 和布局调整。若重命名 public symbol 才能满足 `ConstantName`/`MethodName`，必须先搜索所有调用方和序列化/反射使用，并以编译和测试证明兼容；不能为了 lint 擅自改变外部合同。

ESLint、Checkstyle 与 SpotBugs 的版本、规则、threshold 和 lifecycle 保持 `linter-adoption-plan.md` 定义。audit 与严格命令必须继续使用同一规则集；本计划不能通过降低 severity、改变 threshold 或跳过模块来达成空基线。

## Revision Notes

2026-07-17 / Codex: 按用户要求先修复至少 50 条问题再运行测试。选择工作树未触及的 rubrics `RubricsRunEntity` 做原子清理，删除 2 个 suppression 并修复 59 个历史违规行，剩余 411；修改只包含字段和 accessor 的等价布局整理，不改变 Evaluation Run 持久化合同。首次 `-am` reactor 在桌面 124 秒命令上限中断且遗留 Maven 进程短暂竞争导致 Checkstyle 结果 XML 损坏，进程退出后串行模块严格 verify 成功；随后模块 12 项测试全部通过。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择未与当前业务修改重叠的 file，删除该模块全部 33 个 suppression 并修复严格检查报告的 135 个问题，剩余 413；修改仅包含无用 import、字段/常量间隔、长表达式换行和控制流大括号，不改变上传、解压、素材身份或删除合同。绑定的 File Checkstyle 严格门禁零违规；模块 32 项测试零失败、零错误、零跳过。完整 `-am -DskipTests verify` 两次受桌面命令 124 秒上限阻断，未记作成功，残留 Maven 进程已清理。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择 common，删除该模块全部 8 个 suppression 并修复 22 个历史违规行，剩余 446；修改仅包含字段间隔与长表达式换行，并保留既有 `ErrorNormalizer` 工作树修改。严格 verify 清零后才运行 clean test，23 项零失败、零错误、零跳过。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择生产源码无既有修改且不与活跃业务计划重叠的 infra-vector，删除 6 个 suppression 并修复 18 个历史违规行，剩余 454；修改仅包含字段间隔和长行拆分。3 模块严格 verify 成功后才运行测试，vector/common 共 34 项零失败，其中 2 项 Qdrant Testcontainers 集成测试因本机无有效 Docker 环境跳过。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择位于低冲突顺序且没有活动业务修改的 eval，删除 16 个 suppression 并修复 45 个历史违规行，剩余 460；修改仅包含字段间隔、import 顺序和长行拆分。4 模块严格 verify 成功后才运行测试，eval/storage/common 共 44 项全部通过，其中包含真实 MinIO Testcontainers 集成测试。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择生产源码无既有修改且避开 Task/Rubrics/State/Vision 活跃业务改动的 agent，删除 58 个 suppression 并修复严格检查报告的 285 个问题，剩余 476；修改仅包含无用 import、logger 常量名、字段/方法间隔、单行访问器与控制流展开、长行及 SQL 文本换行。19 模块严格 verify 成功后才运行测试，agent 40 项全部通过。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择生产源码无既有修改且不与活跃业务计划重叠的 infra-storage，删除 10 个 suppression 并修复严格检查报告的 44 个问题，剩余 534；修改仅包含字段间隔与长行拆分。3 模块严格 verify 成功后才运行测试，storage/common 共 38 项全部通过，其中包含真实 MinIO Testcontainers 集成测试。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择生产源码无既有修改且不与活跃业务计划重叠的 infra-image，删除 21 个 suppression 并修复严格检查报告的 83 个问题，剩余 544；修改仅包含字段/方法间隔、单行方法与控制流展开和长行拆分。3 模块严格 verify 成功后才运行测试，image/common 共 48 项全部通过。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择工作树无既有修改且不与活跃业务计划重叠的 commerce，删除 20 个 suppression 并修复严格检查报告的 96 个问题，剩余 565；修改仅包含字段间隔、长行/SQL 文本换行、空白和 1 个无用 import。4 模块严格 verify 成功后才运行测试，commerce/mq/common 共 57 项（含 MySQL 8.4 Testcontainers）全部通过。

2026-07-17 / Codex: 按用户要求先完整解决一个模块再运行测试。选择未与活跃业务计划重叠的 infra-mq，清除 41 个 suppression 和 159 个历史违规行，剩余 585；修改仅包含字段/方法间隔、控制流大括号、常量命名和长行拆分。首次严格 verify 暴露并修复两个遗漏字段间隔，最终 3 模块严格 verify 成功，mq/common 共 35 项测试全部通过。

2026-07-16 / Codex: 按用户要求在运行测试前先完整清空 infra-auth 模块。11 个文件仅做字段/常量间隔与长行拆分，删除 12 个 suppression 并修复 50 个实际违规，剩余 626。5 模块 `-DskipTests verify` 成功；随后 auth 37、cache 22、common 23 项测试全部通过。

2026-07-16 / Codex: 按用户要求先完成足量修复再测试。完整清空 hooks、context、session、tools 四模块，删除 36 个 suppression 并修复严格检查实际报告的 124 个问题，剩余 638。11 模块 `-DskipTests verify` 成功；随后 hooks 13、session 19、tools 1 项测试通过，context 仍只有计划已记录的两个既有失败。所有改动限于 import、字段间隔、空白和长行拆分。

2026-07-16 / Codex: 继续 Milestone 4，选择未与当前业务重构重叠的 infra-thirdparty 三个文件做原子清理。删除 4 个无用 import，同时修复这些文件的字段间隔和全部长行，共删除 7 个 suppression，剩余 674。首轮严格 verify 额外发现编辑后 124 字符行，修正后 5 模块 reactor 严格 verify 成功；thirdparty 13 项测试全部通过。

2026-07-16 / Codex: 开始 Milestone 4，先选择不与 Task/Rubrics 活跃改动重叠的六个文件做原子清理，共删除 8 个 Checkstyle suppression，剩余 681。对应模块严格 verify 与测试通过。确认 Maven reactor 不能在共享工作树并行运行，否则会争用 SpotBugs 临时 XML；后续改为串行验证。

2026-07-16 / Codex: 完成 Milestone 3。用保持缺省为 undefined 的 defaults 清除 19 条 prop 合同告警，为 `Composer.vue` 声明 `ChatComposer` 组件名；再严格按显式文件清单分 ui、icons、files、upload、tasks、chat、layout、pages 执行 scoped ESLint fix。647 条前端基线最终降为 `{}`，没有新增源码禁用指令。零 suppression 下 lint、typecheck、24 文件/116 测试和 build 全部成功。

2026-07-16 / Codex: 完成 Milestone 2。新增并迁移 `GalleryImageItem`，区分 `PackageImageItem`；把结构化错误实现为 `ApiError extends Error` 并统一 HTTP/SSE/WS/runtime 边界；删除虚构上传进度字段，修复异步和动态数据告警。权威 API 文档实际是 `frontend/api.md`，计划中不存在的 `frontend/transport-api.md` 未创建。prune 后基线从 647 条降到 477 条，且只剩 7 种 Vue 合同/排版规则。lint、typecheck、build 与 24 文件/116 测试全部通过；同步 fetch mock 暴露的超时包装问题通过 `Promise.resolve` 归一化修复。

2026-07-16 / Codex: 完成 Milestone 1。删除 `CopyContext` 与 `ImagegenPlan` 的三处无效自赋值，清空 SpotBugs filter，增加 nullable/value/null-rejection 构造测试。定向 4 测试和 14 项目严格 verify 成功；完整模块测试集按用户指示跳过，因此本轮只将定向测试记为行为证据，不把 `-DskipTests verify` 记为测试成功。

2026-07-16 / Codex: 创建本计划。依据已完成的 linter 接入结果和本轮历史问题审阅，固定三阶段风险顺序：先删除 SpotBugs 无效自赋值，再修复前端共享类型/结构化错误/异步边界和后端 correctness-adjacent 规则，最后分目录与模块清理纯布局。计划选择自定义 Error 子类兼容 `ApiError` 设计与 ESLint，选择独立 `GalleryImageItem` TS seam 消除 SFC 类型污染，并要求一个 Java 文件的源码与全部行号 suppression 原子清理。最终验收是三个基线为空，而不是继续在历史计数内通过。
