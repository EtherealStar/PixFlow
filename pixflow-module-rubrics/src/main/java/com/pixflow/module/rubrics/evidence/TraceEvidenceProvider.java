package com.pixflow.module.rubrics.evidence;

import com.pixflow.module.rubrics.subject.TaskDecisionSubject;
import java.util.List;

/**
 * 为 {@link TaskDecisionSubject} 选择与 criterion 有关的 Eval trace span。
 *
 * <p>trace 是 best-effort 证据：缺失、过期或读取失败时返回空列表。此时声明
 * {@link com.pixflow.module.rubrics.model.EvidenceType#TRACE_SPAN} 的 criterion 得到
 * {@code INCONCLUSIVE(MISSING_EVIDENCE)}，但绝不伪造 span，也不把 trace 故障升级为 Subject 失败。
 * Rubrics 只通过 Eval 的只读 {@code TraceQuery}/{@code TraceReplay} 读取 span，不反向写入。
 */
public interface TraceEvidenceProvider {

    /** 返回该 Subject 的 TRACE_SPAN 证据条目；无可用 trace 时返回空列表。 */
    List<EvidenceEntry> trace(TaskDecisionSubject subject);
}
