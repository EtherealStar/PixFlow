package com.pixflow.module.dag.exec;

import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.dag.error.DagErrorCode;
import java.util.List;
import java.util.Objects;

/**
 * 单元执行产物(中立 record):task 据此写 process_result。
 *
 * <p>SUCCESS:outputObjectKey/generatedCopy/members 至少一项有值;FAILED:error 非空。
 * 详情见 dag.md §8.1。
 */
public record UnitOutcome(
    UnitKind kind,
    String branchId,
    String memberId,
    Status status,
    String outputObjectKey,
    String generatedCopy,
    List<MemberRef> members,
    DagErrorView error
) {

    public enum Status {SUCCEEDED, FAILED}

    public UnitOutcome {
        // 不可变 record 已隐式支持;此处仅防御性拷贝 list
        members = members == null ? List.of() : List.copyOf(members);
    }

    public static UnitOutcome succeeded(UnitKind kind, String branchId, String memberId,
                                         String outputObjectKey, List<MemberRef> members) {
        return new UnitOutcome(kind, branchId, memberId, Status.SUCCEEDED,
            outputObjectKey, null, members, null);
    }

    public static UnitOutcome copySucceeded(UnitKind kind, String branchId, String memberId,
                                             String generatedCopy) {
        return new UnitOutcome(kind, branchId, memberId, Status.SUCCEEDED,
            null, generatedCopy, List.of(), null);
    }

    public static UnitOutcome failed(UnitKind kind, String branchId, String memberId,
                                     DagErrorView error) {
        return new UnitOutcome(kind, branchId, memberId, Status.FAILED,
            null, null, null, Objects.requireNonNull(error, "error"));
    }

    /** 组支路成功:N 张源成员明细 → process_result_member。 */
    public record MemberRef(String imageId, String viewId, String sourceObjectKey) {
        public MemberRef {
            Objects.requireNonNull(imageId, "imageId");
            Objects.requireNonNull(sourceObjectKey, "sourceObjectKey");
        }
    }

    /** 失败脱敏视图(只含 code + safeMessage + category)。 */
    public record DagErrorView(DagErrorCode code, String safeMessage,
                                com.pixflow.common.error.ErrorCategory category) {
        public DagErrorView {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(category, "category");
        }
    }
}