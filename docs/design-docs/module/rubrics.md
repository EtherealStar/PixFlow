# module/rubrics —— Rubrics 离线评估（Wave 6）

> 本文是 PixFlow 完整重写阶段 `module/rubrics` 模块的设计文档，对应 `design.md` 第十一章「Rubrics 评估（离线阶段）」与 `module-dependency-dag-plan.md` 的 **Wave 6**。
> 范围：消费 `harness/eval` 输出的 trace、`module/task` 的 `process_result` 与本地数据集，离线评估图片质量 / 文案质量 / 决策质量，**评分+解释+可追溯**写回 RAG 记忆层与 `sku_history`，并支持基线对比与回归检测。
> 本文面向**单机个人 PC**（个人项目），不做灰度发布、不做多租户、不做实时评分服务；评测运行以本地数据集驱动，可手动触发也可定时批量。
> 配套阅读：`design.md` §11、§13.1（`rubrics_score` 表）、`infra/ai.md`（`ChatModelClient` / `VisionModelClient`）、`infra/storage.md`（读待评图）、`infra/image.md`（元数据与降采样）、`module/memory.md`（评分写回与 `analysis_insight` 强化/抑制）、`harness/eval.md`（trace 数据源）、`harness/hooks.md`（`TaskCompleted` 事件订阅）。
> 本文不涉及 MVP 既有实现，从生产级需求重新推导，但**收敛到单机可落地的复杂度**。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、职责边界与不做什么](#二职责边界与不做什么)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、核心抽象与数据契约](#四核心抽象与数据契约)
- [五、RubricTemplate 与版本管理（本地 YAML）](#五rubrictemplate-与版本管理本地-yaml)
- [六、评分管线：LLM 二元判定 + 程序计分](#六评分管线llm-二元判定--程序计分)
- [七、规则验证器（确定性维度）](#七规则验证器确定性维度)
- [八、决策质量精细化拆解](#八决策质量精细化拆解)
- [九、基线对比与回归检测](#九基线对比与回归检测)
- [十、评分写回与可追溯链](#十评分写回与可追溯链)
- [十一、API 表面（运营/PM 自助）](#十一api-表面运营pm-自助)
- [十二、调度与触发](#十二调度与触发)
- [十三、可观测、错误与降级](#十三可观测错误与降级)
- [十四、配置](#十四配置)
- [十五、对其他模块的契约](#十五对其他模块的契约)
- [十六、测试策略](#十六测试策略)
- [十七、对 design.md 的细化](#十七对-designmd-的细化)
- [十八、暂不考虑](#十八暂不考虑)
- [十九、Revision Notes](#十九revision-notes)

---

## 一、文档定位与设计原则

`module/rubrics` 是 PixFlow 最末端的离线评估模块，处于依赖 DAG 的 **Wave 6**。它消费系统已经落库的事实数据（`process_result` / `agent_trace` / `commerce_data` / `asset_copy`）和 MinIO 上的图片，对每一次「任务执行」独立产出结构化评分与解释，再把评分结果写回 `rubrics_score` 与 RAG 记忆层。

模块专属设计原则（**最重要的方法论决策在第 1 条**）：

1. **LLM judge 做二元判定，程序做数值聚合**。LLM 不擅长连续数值的精细打分（LLM-as-judge 在 1~100 连续分上的标定一致性差、可重复性低）。rubrics 把每条评分项的 LLM 输出收敛为 **PASS / FAIL（必要时含 LOW/MED/HIGH 三个置信档位）**，所有「百分制分数」「加权总分」「维度均值」「基线 delta」一律由程序按确定性公式计算。这条原则贯穿模板结构、judge prompt、解析器与聚合器。
2. **完全离线、独立于主循环**。rubrics 不在 Agent 工具层暴露，不参与主回路决策流；通过订阅 `TaskCompleted` Hook 异步被触发，或运营/PM 手动 / 定时触发。
3. **不破坏可解释性**。每条 PASS/FAIL 都附 judge 的简短理由（1~2 句自然语言）+ 证据引用（图片 MinIO key / trace_id / 数据 id）；程序计算的分数同时给出**可分解的明细**（每条检查项的 PASS/FAIL 与权重），运营可逐项审查。
4. **LLM judge 与规则验证器并存**。能确定判定的（分辨率、文件大小、α 通道残留等）一律走规则验证器，毫秒级、低成本、可复现；只有开放式维度（构图美感、文案流畅、决策合理性）才送 LLM。两者在同一模板内可任意组合。
5. **模板与代码解耦，运营/PM 可改**。模板以本地 YAML 文件保存（classpath: `rubrics/templates/*.yaml` + 用户目录 `$PIXFLOW_HOME/rubrics/*.yaml`），系统启动时扫描合并；评分时不修改模板本身，仅按 `(templateId, version)` 引用，避免「改了模板历史评分解释不清」。
6. **基线对比与回归检测是本地化的事**。基线以「某次评估运行的 ID」为单位存在 MySQL `rubrics_baseline` 表，可被指定、可被替换、可被对比；不做灰度、不做 A/B 实验平台。
7. **写回 RAG 是单向写入**，不反向干扰主循环。评分写 `sku_history.rubrics_score`；对低分高频模式以人工 / 定时 Job 抽取为 `analysis_insight`（NEUTRAL 类别），由 `module/memory` 自己的衰减生命周期治理，本模块不直接操作向量库。
8. **单机可落地**。所有组件都在同一进程内，不引入额外中间件；数据集以本地文件 + MySQL 行级 ID 列表承载；并发由 JVM 线程池封顶；评测运行可断点续跑（按 `rubrics_run_item.status` 跳过已完成项）。

---

## 二、职责边界与不做什么

| 关注点 | 归属 | 本文是否覆盖 |
|---|---|---|
| 消费 `process_result` / `agent_trace` / `commerce_data` / `asset_copy` 与 MinIO 图片 | `module/rubrics` | ✅ |
| LLM judge 调用（文本 + 多模态） | `module/rubrics`（直连 `infra/ai` 的 `ChatModelClient` / `VisionModelClient`） | ✅ |
| 确定性规则验证器 | `module/rubrics`（内嵌实现，不下放 infra） | ✅ |
| 评分模板（YAML）+ 模板加载 | `module/rubrics` | ✅ |
| 基线 / 回归对比 | `module/rubrics` | ✅ |
| 评分写回 `rubrics_score` 与 `sku_history.rubrics_score` | `module/rubrics` | ✅ |
| 把低分模式抽取为 `analysis_insight`（NEUTRAL 类别） | `module/rubrics` 触发，`module/memory` 治理 | 部分（仅触发） |
| `agent_trace` 写入 | `harness/eval` | ❌ 不在本模块 |
| VLLM 多模态调用封装 | `infra/ai` | ❌ |
| Agent 主循环接入 | `agent` 层 | ❌（不在 Agent 工具层暴露） |
| 灰度发布 / A/B 实验 / 多人协作评审平台 | — | ❌（单机不做） |

**明确不做**：
- **不在 Agent 工具层暴露**。`evaluate_*` 类工具不进入 `harness/tools`，避免主循环非确定性扩散。
- **不做 LLM 直接打分**。所有连续数值都由程序计算。
- **不做 A/B 实验、多租户、权限分级**。单机个人项目不做。
- **不做实时评估服务**（不在 Agent 决策回路里同步调用 judge）。
- **不依赖滞后电商反馈**。design.md §16 仍生效，决策质量评估不等待电商数据回流。

---

## 三、模块结构与依赖位置

Maven 模块：`pixflow-module-rubrics`（需加入根 `pom.xml` `<modules>` 与 `dependencyManagement`）。源码包：`com.pixflow.module.rubrics`

```
module/rubrics/
├── RubricsService.java                  # 对外门面：runEvaluation(...)/latestScore(...)/regress(...)
├── run/
│   ├── EvaluationRunner.java            # 单次评估运行编排（线程池 + 异常隔离）
│   ├── EvaluationRunContext.java        # 一次运行的上下文（模板版本/数据集范围/触发原因）
│   ├── EvaluationItem.java              # 单条评分项（=一个 process_result）
│   ├── ItemEvaluator.java               # 单条编排：对模板每个 dimension 派给 Judge 或 RuleVerifier
│   └── RubricsRunRepository.java        # MyBatis-Plus：rubrics_run / rubrics_run_item
├── template/
│   ├── RubricTemplate.java              # 单模板（domain + dimensions + 规则 + 锚点）
│   ├── Dimension.java                   # 单维度（key/weight/scale/anchor/prompt 段）
│   ├── Anchor.java                      # PASS/FAIL 锚点描述
│   ├── VerifierSpec.java                # 维度是 LLM judge 还是规则（以及规则类名/参数）
│   ├── TemplateLoader.java              # classpath + $PIXFLOW_HOME 扫描合并，按 id+version 索引
│   └── TemplateRegistry.java            # 内存只读 registry，按 (id, version) 查询
├── judge/
│   ├── LlmJudge.java                    # 通用 LLM judge 调度（文本/多模态）
│   ├── JudgePromptBuilder.java          # 拼装 role + 维度描述 + 锚点表 + few-shot + 输出 schema
│   ├── JudgeVerdict.java                # LLM 输出：Verdict(PASS/FAIL, confidence, rationale)
│   ├── VerdictParser.java               # 防御性 JSON 解析 + 降级到 rawText
│   ├── FewShotSampler.java              # 从 template.examples 抽样 1~2 条（确定性，按 item id hash）
│   └── ImageAttachmentBuilder.java      # 从 MinIO key 取字节 → 降采样 → 图片 part（infra/image）
├── rule/
│   ├── RuleVerifier.java                # 规则验证器 SPI
│   ├── RuleCheckInput.java              # 输入（process_result / 图片字节 / 元数据）
│   ├── RuleCheckResult.java             # 输出 PASS/FAIL + 证据 + 可选数值
│   ├── ResolutionRuleVerifier.java      # 分辨率合规
│   ├── FormatRuleVerifier.java          # 输出格式合规（WebP/JPEG/PNG）
│   ├── FileSizeRuleVerifier.java        # 文件大小约束
│   ├── AlphaResidueRuleVerifier.java    # α 通道残留像素比例
│   └── BackgroundColorRuleVerifier.java # 底色 RGB 与白底标准差阈值
├── score/
│   ├── ScoreAggregator.java             # 把 dimension 级 PASS/FAIL + 权重聚合成 domain 总分（程序计算）
│   ├── DimensionScore.java              # 单维度结果：verdict/weight/confidence/rationale/evidence
│   ├── DomainScore.java                 # 单 domain 总分（0-100，加权聚合）
│   ├── RubricScore.java                 # 整条评分结果（rubrics_score 表行）
│   ├── Confidence.java                  # HIGH/MEDIUM/LOW（来自 judge.confidence 或规则置信度）
│   └── ScoreExplanation.java            # 一句话总结 + 各 dimension rationale 串接
├── baseline/
│   ├── BaselineService.java             # CRUD：rubrics_baseline + 选基线/替换基线
│   ├── RegressionComparator.java        # 与基线逐 dimension 比对，输出 RegressionReport
│   ├── RegressionReport.java            # per-dimension delta + 退化 Top-K + 整体走向
│   └── RegressionAlert.java             # 退化 > 阈值 → 写入 rubrics_alert 表（可选）
├── feedback/
│   ├── ScoreFeedbackWriter.java         # 写 sku_history.rubrics_score + 触发 memory feedback
│   ├── MemoryFeedbackTrigger.java       # 低分模式抽取为 analysis_insight(NEUTRAL) 的异步投递
│   └── EvidenceRef.java                 # 证据引用（IMAGE/TRACE/DATA/DOC）
├── error/
│   └── RubricsErrorCode.java            # implements common.ErrorCode
├── api/
│   ├── RubricsAdminController.java      # 模板/运行/基线/告警 REST
│   ├── RubricsReportController.java     # 评分查询 + 回归报告
│   └── dto/                             # 响应 DTO
└── config/
    └── RubricsProperties.java           # @ConfigurationProperties(pixflow.rubrics)
```

依赖方向：

```
module/rubrics ──► infra/ai        （ChatModelClient / VisionModelClient）
module/rubrics ──► infra/storage   （读待评图、读 tool-result 外置）
module/rubrics ──► infra/image     （降采样 + 元数据）
module/rubrics ──► harness/eval    （读 agent_trace，按 conversation/turn 查询）
module/rubrics ──► module/memory   （写 sku_history.rubrics_score + 触发 insight 抽取）
module/rubrics ──► harness/hooks   （订阅 TaskCompleted 事件）
module/rubrics ──► common          （错误归一 / Sanitizer / ApiResponse）
module/rubrics ──► MySQL/MyBatis-Plus（rubrics_score / rubrics_run / rubrics_run_item / rubrics_baseline / rubrics_alert）
```

**不依赖**：`agent`、`harness/loop`、`harness/tools`、`harness/session`、`harness/context`、`module/dag`、`module/vision`（rubrics 直连 `infra/ai` 的 `VisionModelClient`，不复用 `module/vision`，见 §十七对 `tools→vision` 边修正的同源理由）、`module/imagegen`、`module/commerce`（数据通过 `process_result` 间接触达，不直连）。

---

## 四、核心抽象与数据契约

### 4.1 评分维度与判定口径

每个 `Dimension` 的 LLM 输出被强制收口为 `JudgeVerdict`：

```java
public record JudgeVerdict(
    Verdict verdict,           // PASS | FAIL（必要时 LOW/MED/HIGH 三档置信用于聚合时降权）
    Confidence confidence,     // HIGH | MEDIUM | LOW
    String rationale,          // 1~2 句自然语言理由
    List<EvidenceRef> evidence // 证据引用（图 MinIO key / trace_id / data_id）
) {}
```

程序端聚合公式（**所有分数都在程序里计算**）：

```
dimensionWeightedScore(0~100) = 
    verdict == PASS ? (confidence == HIGH ? 100 : confidence == MED ? 80 : 60)
                    : (confidence == HIGH ? 0   : confidence == MED ? 20 : 40)

domainTotal(0~100) = Σ (dimensionWeightedScore × weight) / Σ weight
overallScore(0~100) = Σ (domainTotal × domainWeight) / Σ domainWeight
```

> 关键差异：这是**离散档位 + 程序映射**，不是 LLM 直接给 0~100。LLM 只负责 PASS/FAIL 判定 + 置信档位 + 理由，避免 LLM 在连续数值的标定漂移。

### 4.2 RubricScore（评分结果）

```java
public record RubricScore(
    UUID id,
    Long resultId,                 // 关联 process_result
    Long taskId,
    String templateId,
    String templateVersion,
    Map<Domain, DomainScore> domainScores,  // IMAGE_QUALITY / COPY_QUALITY / DECISION_QUALITY
    double overallScore,           // 程序聚合（0-100）
    ScoreExplanation explanation, // 一句话总结 + 各 dimension rationale 串接
    UUID runId,                    // 关联一次评估运行（用于回归）
    Instant createdAt
) {}
```

### 4.3 RubricsRun（评估运行实例）

```java
public record RubricsRun(
    UUID id,
    String templateId,
    String templateVersion,
    RubricsTrigger trigger,       // SCHEDULED / EVENT_DRIVEN / MANUAL
    UUID baselineRunId,           // 可选：用于回归对比的基线 run
    Long[] resultIds,             // 数据集：待评 process_result 列表
    Status status,                // PENDING / RUNNING / SUCCEEDED / FAILED / PARTIAL
    Instant startedAt,
    Instant finishedAt,
    RunStats stats                // 成功/失败/隔离/平均分/告警计数
) {}

public record RubricsRunItem(
    UUID runId,
    Long resultId,                // 唯一（runId, resultId）
    Status status,                // PENDING / RUNNING / SUCCEEDED / FAILED / ISOLATED
    String errorMsg,              // ≤1000 字
    Instant updatedAt
) {}
```

> **断点续跑**：`RubricsRunItem.status=SUCCEEDED` 是天然 checkpoint，重启扫描 `RUNNING` 的 run 把 `PENDING` / `FAILED` 项重新入队。

---

## 五、RubricTemplate 与版本管理（本地 YAML）

### 5.1 模板存储

- **内置模板**：`classpath:rubrics/templates/*.yaml`，与代码同版本管理。
- **用户/PM 自定义模板**：`$PIXFLOW_HOME/rubrics/*.yaml`（环境变量可覆盖，默认 `~/.pixflow/rubrics/`）。
- **加载时机**：应用启动时扫描合并，同 `id` 时用户目录版本覆盖内置版本。
- **版本号语义**：模板内显式 `version: 1.2.0`；评分时按 `(templateId, version)` 引用，**模板修改后历史评分仍可解释**（不重算、不改写）。

### 5.2 模板结构（YAML 示例）

```yaml
id: image_quality
version: 1.0.0
description: 电商主图质量离线评估
domains:
  - key: IMAGE_QUALITY
    weight: 0.6
    dimensions:
      - key: bg_cleanliness
        weight: 0.25
        verifier: llm                       # LLM judge
        prompt_role: 你是一名电商图片质量评估员
        prompt_goal: 判断去背景是否干净（边缘无残留、无明显锯齿）
        anchors:
          PASS: 边缘平滑，无可见残留像素或锯齿
          FAIL: 边缘有明显残留/锯齿/halo
        few_shots:
          - verdict: FAIL
            rationale: 商品右下角边缘可见明显锯齿
      - key: resolution_compliance
        weight: 0.15
        verifier: rule                      # 规则验证器
        rule_class: ResolutionRuleVerifier
        params: { minWidth: 800, minHeight: 800 }
      - key: alpha_residue
        weight: 0.10
        verifier: rule
        rule_class: AlphaResidueRuleVerifier
        params: { maxResidueRatio: 0.005 }
      - key: format_compliance
        weight: 0.10
        verifier: rule
        rule_class: FormatRuleVerifier
        params: { allowed: [WEBP, JPEG, PNG] }
```

> **判例规则**：模板的所有数值（weight、阈值、allowed）由人或运营/PM 写在 YAML 里；LLM 不知道具体分数，只读 prompt_goal + anchors。

---

## 六、评分管线：LLM 二元判定 + 程序计分

### 6.1 Judge Prompt 结构

每条 LLM judge prompt 由五段拼接，**所有连续数值都从模板参数填入**：

```
1. role：来自 prompt_role（"你是电商图片质量评估员"）
2. task：来自 prompt_goal（"判断去背景是否干净"）
3. anchors：来自 anchors.PASS / anchors.FAIL（两段锚点描述）
4. few-shot：从 few_shots 抽样 1~2 条，按 item id hash 确定性选取
5. output schema（强制）：
   {
     "verdict": "PASS" | "FAIL",
     "confidence": "HIGH" | "MEDIUM" | "LOW",
     "rationale": "1~2 句理由",
     "evidence": [{"type": "IMAGE", "ref": "<minio key>"}]
   }
```

**稳定性保障**：
- temperature 强制 0；seed 固定；同 item 重跑结果一致（除非模板改了）。
- 输出 schema 校验失败 → retry 一次（改写 prompt 强化 schema），仍失败 → 记 `VerdictParserException`，单条 `ISOLATED`，不中断批次。
- 多模态：图片 ≤ 3 张/调用，单张长边降采样到 1024px（infra/image），节省 token 与延迟。

### 6.2 程序聚合（核心）

`ScoreAggregator.aggregate(template, item, dimensionScores)`：

1. 对每条 dimension：
   - LLM 路径：`dimensionScore = JudgeVerdict → 程序映射（见 §4.1 公式）`
   - 规则路径：`dimensionScore = RuleCheckResult(PASS/FAIL + 可选 numericMetric) → 程序映射`
2. domain 总分：`Σ (dimScore × dimWeight) / Σ dimWeight`
3. overall：`Σ (domainScore × domainWeight) / Σ domainWeight`
4. 异常维度（confidence=LOW）按 0.5 系数降权（不剔除，让运营看见但拉低分数）

### 6.3 异常隔离

- 单条 item 失败 → `RubricsRunItem.status=ISOLATED`，记 `error_msg`，不中断批次
- 单条 dimension 失败 → 该 dimension 记 `null`，聚合时分母减 1（不污染总分）
- 整 run 失败 → `status=FAILED`，但已成功的 `RubricsRunItem` 持久化（断点续跑基础）

---

## 七、规则验证器（确定性维度）

`RuleVerifier` SPI：

```java
public interface RuleVerifier {
    String dimensionKey();                     // 对应 dimension.key
    RuleCheckResult verify(RuleCheckInput input);
}

public record RuleCheckInput(
    Long resultId,
    Long imageId,                             // 读 MinIO 的 image 引用
    ProcessResultRow result,                  // process_result 行（含 output_minio_key / generated_copy）
    AssetImageRow image,                      // asset_image 行
    Map<String, Object> params                // 来自模板 dimension.params
) {}

public record RuleCheckResult(
    Verdict verdict,                          // PASS / FAIL
    Confidence confidence,                    // 规则验证器始终 HIGH（确定性强）
    String rationale,                         // 程序生成的简短理由（如 "width=600 < minWidth=800"）
    List<EvidenceRef> evidence,
    OptionalDouble numericMetric              // 可选：用于展示，不参与评分
) {}
```

内置实现（见 §三包结构）；`TemplateLoader` 根据 `verifier: rule` + `rule_class` 反射实例化，参数来自 `dimension.params`。

**扩展边界**：单机项目不引入 Groovy/Script 引擎，运营/PM 不能写自定义规则类，只能：
- 改 YAML 参数（阈值、allowed 列表）
- 用 LLM judge 维度替代规则维度
- 给开发者提 PR 加新的 `RuleVerifier` 子类（合并进代码）

---

## 八、决策质量精细化拆解

依据 design.md §11 与您的方向（决策质量做精细化拆解），本期决策质量维度的 design：

| dimension key | verifier | 说明 | 数据来源 |
|---|---|---|---|
| `proposal_data_backed` | llm | Agent 建议是否附数据支撑（看 trace.input_json + LLM 解析建议文本） | agent_trace + DAG 提交时返回的建议 |
| `hitl_smoothness` | rule | 单任务二次确认次数（越少越好；但 >0 视为合理） | process_task.confirm_count / agent_trace |
| `coverage_completeness` | rule | 任务计划 SKU 数 vs 实际完成数 | process_task.plan_json vs process_result 计数 |
| `memory_utilization` | llm | 是否召回到相关历史洞察（看 trace.recall_json 是否非空、与本次 SKU 类目相关） | agent_trace.recall_json |
| `param_validity` | rule | DAG 参数无明显冗余/冲突（如同一参数被多次设置不同值） | process_task.dag_json |
| `consistency` | llm | 与历史相似任务的处理一致性（参考最近 N 次同 SKU 类目任务的参数均值/众数） | sku_history + commerce_data |

**说明**：
- `hitl_smoothness` 计分：**0 次 = 中性（60）**；1~2 次 = PASS（100）；≥3 次 = FAIL（0）。这是**唯一**对中性值做特殊处理的维度。
- `memory_utilization` 与 `consistency` 走 LLM，但 prompt 中明确告诉模型「只输出 PASS / FAIL + 简短理由」，不输出数字。
- 决策质量整体 domain 权重默认 0.3（与图片 0.6、文案 0.1 加和为 1.0，可在模板里调）。

---

## 九、基线对比与回归检测

### 9.1 基线模型

- 表 `rubrics_baseline`：`(id, name, runId, templateId, templateVersion, createdAt)`，存「某次评估运行」作为基线。
- 任何历史 run 都可以被「指定为基线」，可被替换。
- 同时只能有一个 active 基线（按 `templateId` 维度）；保留历史基线只读不删。

### 9.2 回归对比

`RegressionComparator.compare(currentRunId, baselineRunId)`：

1. 选同 `templateId` 的两次 run，提取 `rubrics_score` 维度明细
2. per-dimension delta：
   - `delta = current.dimScore - baseline.dimScore`
   - `degraded = delta < -5`（阈值可配）
3. 输出 `RegressionReport`：
   - per-dimension delta 列表
   - 退化维度 Top-K
   - domain 总分 delta 与 overall delta
   - 整体走向：UP / DOWN / STABLE

### 9.3 退化告警（轻量）

- 退化维度数 ≥ 2 或 overall delta < -10 → 写 `rubrics_alert` 表 + 可选通知（站内消息 / WebSocket 推送；不引入第三方通知通道）
- 告警有 acknowledged 标记，运营/PM 在 Web UI 标记后不再重复弹

> **不做**：A/B 实验、多基线横比、自动选最佳基线。

---

## 十、评分写回与可追溯链

### 10.1 三层写回

| 层级 | 目标 | 时机 | 说明 |
|---|---|---|---|
| 1 | `rubrics_score` 表 | 单条 item 完成 | 评分事实落库 |
| 2 | `sku_history.rubrics_score` | 单条 item 完成 | Agent 召回 SKU 历史时可见 |
| 3 | `analysis_insight(NEUTRAL)` 抽取 | 评分运行完成时（异步） | 低分高频模式 → NEUTRAL 类别分析结论，由 `module/memory` 治理衰减 |

**第 3 层触发条件**（运营可配，默认保守）：
- 同一 SKU 最近 5 次评分均 < 60 → 抽取为 negative_pattern
- 同一参数组合（如「白底 + 居中构图」）最近 10 次评分均 > 85 → 强化既有 positive 分析结论
- 抽取出的 insight 由 `module/memory.InsightIngestService` 接管（`module/rubrics` 只负责触发，不直接写向量库）

### 10.2 可追溯证据

每条 DimensionScore 都带 `EvidenceRef[]`：

```java
public record EvidenceRef(
    EvidenceType type,       // IMAGE | TRACE | DATA | DOC
    String ref,              // MinIO key / trace_id / commerce_data_id / doc_key
    String excerpt,          // 关键证据片段（≤ 200 字），大结果给引用
    int[] boundingBox        // 图片维度的可选 [x, y, w, h] 像素框（用于「这里扣分」可视化）
) {}
```

- 图片维度（如 `bg_cleanliness`）：ref = `process_result.output_minio_key`，boundingBox 由 VLLM 在 verdict 里给（不强求；缺失则不画框）。
- 数据维度（如 `coverage_completeness`）：ref = `process_task.id`，excerpt = 程序生成的对比文本。

---

## 十一、API 表面（运营/PM 自助）

| 端点 | 方法 | 用途 |
|---|---|---|
| `/api/rubrics/templates` | GET | 列出所有模板（含版本） |
| `/api/rubrics/templates/{id}/versions` | GET | 模板历史版本 |
| `/api/rubrics/runs` | POST | 手动触发评估运行（指定 template + 数据集 = resultIds） |
| `/api/rubrics/runs` | GET | 列出运行历史 |
| `/api/rubrics/runs/{id}` | GET | 运行详情 + 进度 |
| `/api/rubrics/runs/{id}/regression` | GET | 与基线的回归报告（需指定 baselineRunId） |
| `/api/rubrics/scores/by-result/{resultId}` | GET | 按 process_result 查评分明细 |
| `/api/rubrics/scores/by-sku/{skuId}` | GET | 按 SKU 查评分历史（走 sku_history 反查） |
| `/api/rubrics/baselines` | GET/POST | 基线 CRUD |
| `/api/rubrics/alerts` | GET | 评分预警列表 |

**不做**：模板可视化编辑（运营改 YAML 文件即可；不做 Web 编辑器）、多人评审流、权限分级。

---

## 十二、调度与触发

| 触发源 | 频率 | 实现 |
|---|---|---|
| 手动触发 | — | REST `/api/rubrics/runs` POST |
| 事件驱动 | 每次 `TaskCompleted` Hook | `RubricsTriggerListener`（订阅 hooks 事件），把 runnable 投递到本地线程池；线程池 size 可配（默认 2） |
| 定时批量 | 默认每日凌晨 03:00（@Scheduled + ShedLock 单节点防重） | 拉取近 24h `process_result` 跑一遍默认模板；可关 |

**断点续跑**：
- run 启动后逐 item 写 `rubrics_run_item`，`SUCCEEDED` 是天然 checkpoint
- 进程崩溃 → 重启后扫描 `status=RUNNING` 的 run，把未 `SUCCEEDED` 的 item 重新入队
- 单 item 失败重试 1 次，仍失败标 `ISOLATED`，不无限重试

---

## 十三、可观测、错误与降级

### 13.1 Micrometer 指标

| 指标 | 类型 | 标签 |
|---|---|---|
| `rubrics.judge.latency` | Timer | `domain`, `dimension`, `verifier_type=llm|rule` |
| `rubrics.judge.cost` | Counter | `domain`, `model`（token 数） |
| `rubrics.run.duration` | Timer | `trigger` |
| `rubrics.run.items` | Counter | `status=SUCCEEDED|FAILED|ISOLATED` |
| `rubrics.regression.alerts` | Counter | `template_id` |
| `rubrics.parser.failures` | Counter | `domain` |

### 13.2 错误归一

`RubricsErrorCode implements common.ErrorCode`，域内错误码：
- `RUBRICS_TEMPLATE_NOT_FOUND`
- `RUBRICS_JUDGE_TIMEOUT`
- `RUBRICS_JUDGE_PARSE_FAIL`（重试后仍失败）
- `RUBRICS_RULE_VERIFIER_INIT_FAIL`（rule_class 不存在/参数错误）
- `RUBRICS_BASELINE_NOT_FOUND`
- `RUBRICS_RUN_CANCELLED`

### 13.3 降级策略

| 失败 | 降级 |
|---|---|
| LLM judge 超时（> 60s） | retry 1 次 → 仍超时记 ISOLATED，不影响其他 item |
| LLM 输出 schema 校验失败 | retry 1 次（强化 schema 提示）→ 仍失败记 ISOLATED |
| 规则验证器抛异常 | 该 dimension 标 null（聚合时分母减 1） |
| 图片读 MinIO 失败 | 该 item 标 ISOLATED，记 error_msg |
| 模型整体不可用 | run 状态 PARTIAL，已完成的 score 仍落库 |

---

## 十四、配置

```yaml
pixflow:
  rubrics:
    enabled: true
    template-scan:
      classpath-prefix: "rubrics/templates/"
      user-home-dir: "${PIXFLOW_HOME:/tmp/pixflow}/rubrics/"
    scheduler:
      daily-batch-cron: "0 0 3 * * ?"    # 每日 03:00
      daily-batch-enabled: true
    event-trigger:
      enabled: true                       # 监听 TaskCompleted
      queue-size: 16
      worker-threads: 2
    runner:
      max-concurrent-items: 8             # 单 run 内并发 item 数
      item-retry: 1                       # 单 item 失败重试次数
      judge-timeout-seconds: 60
      judge-temperature: 0.0
      vision-max-edge-px: 1024            # 多模态降采样
    baseline:
      regression-dimension-threshold: -5  # 维度退化阈值
      regression-overall-threshold: -10   # overall 退化阈值
    feedback:
      negative-pattern-min-runs: 5        # 触发 negative_pattern 抽取的最少连续低分次数
      negative-pattern-score-cap: 60      # 低于此分视为低分
      positive-pattern-min-runs: 10       # 触发 positive_pattern 强化的高分连续次数
      positive-pattern-score-floor: 85
```

---

## 十五、对其他模块的契约

### 15.1 消费

- **`infra/ai`**：`ChatModelClient.chat(prompt)`、`VisionModelClient.chatWithImages(prompt, imageParts)`；温度强制 0，retry 由 `infra/ai` 负责。
- **`infra/storage`**：`ObjectStorage.getBytes(minioKey)` 读待评图；不写 MinIO。
- **`infra/image`**：`ImageMetadataReader.read(bytes)` 拿分辨率/格式；多模态前 `ImageDownsampler.downscale(bytes, maxEdgePx)`。
- **`harness/eval`**：`EvalTraceRepository.findByTaskId(taskId)` 拿 `agent_trace`，按 conversation + turn 顺序读 recall_json / input_json / tool_calls_json。
- **`module/memory`**：`SkuHistoryRepository.appendScore(skuId, scoreJson)`、`InsightIngestService.ingest(insight)`；仅本模块触发 insight 抽取时调用。

### 15.2 输出

- 写 `rubrics_score` 表（design.md §13.1 已定义）
- 写 `rubrics_run` / `rubrics_run_item` / `rubrics_baseline` / `rubrics_alert` 表（本文新增）
- 写 `sku_history.rubrics_score`（design.md §13.1 已有字段）
- 触发 `InsightIngestService.ingest(insight)`（不直连 Qdrant）

### 15.3 订阅事件

- `harness/hooks` 的 `TaskCompleted`：从事件 payload 拿到 taskId，自动创建 RubricsRun（默认模板）；可在配置关掉。

---

## 十六、测试策略

### 16.1 单元测试

- `ScoreAggregatorTest`：维度 PASS/FAIL × 权重 × 置信档位 → 确定性数值
- `JudgeVerdictParserTest`：正常 / 缺字段 / 错类型 / 含 markdown 包裹 / retry 后成功的多种样例
- `FewShotSamplerTest`：同 item id 抽到固定样本；不同 id 抽样有差异
- `RuleVerifierTest`：每个内置规则的 PASS / FAIL 边界（含参数边界）
- `RegressionComparatorTest`：基线退化阈值边界

### 16.2 集成测试

- `EvaluationRunnerTest`（Testcontainers）：MySQL + MinIO，注入 5 条 mock process_result，跑全模板，断言 rubrics_score 行数 + 维度 PASS/FAIL 计数
- `BaselineRegressionTest`：跑两次 run（模拟模板或参数不变），断言 regression 报告 STABLE

### 16.3 测试用固定 Mock

- LLM judge 用 WireMock stub 固定返回（输入 hash → 输出 verdict JSON）；规则验证器是纯 Java，不需要 mock
- `FewShotSampler` 用固定种子；`temperature=0` 让重跑结果可复现

### 16.4 ArchUnit 守护

- `rubrics` 模块不得被 `agent` / `harness/loop` / `harness/tools` / `harness/session` / `harness/context` / `module/dag` / `module/vision` / `module/imagegen` 引用
- `rubrics` 内不允许出现 `@Scheduled` 之外的隐藏调度入口（避免主循环被反向触发）

---

## 十七、对 design.md 的细化

1. **§11 评分机制细化**：原文「满分 100」「低于阈值预警」需补充本文核心方法论——**LLM judge 输出二元 PASS/FAIL + 置信档位，所有连续分数由程序按权重聚合**。LLM 不直接给 0~100 连续分。
2. **§11 数据模型细化**：原文 `rubrics_score` 表只有 `image_score / copy_score / decision_score / alert`；本期扩为：
   ```
   rubrics_score(
     id, result_id, task_id, run_id,
     template_id, template_version,
     overall_score,                              -- 程序聚合
     image_score, copy_score, decision_score,    -- 程序聚合
     dimension_scores_json,                      -- 每条 dimension 的 verdict/weight/confidence/rationale
     explanation_json,                           -- ScoreExplanation
     alert_flag,
     created_at,
     UNIQUE KEY uk_result (result_id),
     KEY idx_task (task_id),
     KEY idx_run (run_id)
   )
   ```
   同时新增 `rubrics_run` / `rubrics_run_item` / `rubrics_baseline` / `rubrics_alert` 四张表。
3. **§11 反馈闭环细化**：原文「评分写回 RAG」需补充本文三层写回路径（rubrics_score → sku_history → analysis_insight 异步抽取）。
4. **§13.1 表对齐**：保留 `rubrics_score` 表名但加列；其余新增表加入 §13.1。
5. **依赖图细化**：补充 `ai → rubrics / storage → rubrics / eval → rubrics / memory → rubrics / hooks → rubrics` 五条依赖边（Wave 6 在模块依赖 DAG 计划中已定位，不破坏既有顺序）。
6. **不做项对齐 §16**：原文 §16「Rubrics 具体评分细则待数据集确定后单独设计」由本文落地（详见 §五 模板结构、§八 决策质量维度）。

---

## 十八、暂不考虑

- A/B 实验平台、灰度发布、模板可视化编辑器、模板多人协作
- 自训练 evaluator LLM（如 Prometheus 思路）；本期直接用通用 LLM/VLLM judge
- 多租户、权限分级、审计日志
- 实时评估服务（不进入 Agent 工具层，不在主循环同步调用）
- 评分数据导出为训练集（RLHF/DPO 信号采集）
- 第三方评分模型集成（如第三方 API 评分）
- 视频评估（design.md §16 不做）

---

## 十九、Revision Notes

2026-07-01 / Kiro: 初版。基于 design.md §11 与 Wave 6 定位，明确单机个人 PC 范围，确立**「LLM 二元判定 + 程序计分」**核心方法论（LLM judge 输出 PASS/FAIL + 置信档位，所有连续分数由程序聚合）；决策质量做精细化拆解（6 个 dimension），引入本地 YAML 模板 + 内置规则验证器 + 基线回归 + 三层评分写回（rubrics_score → sku_history → analysis_insight 抽取）。