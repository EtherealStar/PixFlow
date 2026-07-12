package com.pixflow.module.dag.expand;

import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.dag.exec.ExecutionStep;
import com.pixflow.module.dag.exec.GroupStep;
import java.util.List;

/**
 * 展开后的可执行支路。
 *
 * <p>形态(对齐 dag.md §7.2):
 * <ul>
 *   <li>普通支路(UnitKind.BRANCH):perMemberOps=整条链;composeNode=null;postOps=empty</li>
 *   <li>组支路(UnitKind.GROUP):perMemberOps=组内逐图节点;composeNode=compose_group;postOps=聚合后节点</li>
 * </ul>
 *
 * <p>encode 是末端编码目标(convert_format 节点或隐式默认 JPEG);由展开器推断。
 */
public record ExecutableBranch(
    UnitKind kind,
    String branchId,
    String memberId,
    List<ExecutionStep> perMemberOps,
    GroupStep composeStep,
    List<ExecutionStep> postOps,
    EncodeTarget encode
) {
    public ExecutableBranch {
        kind = kind == null ? UnitKind.BRANCH : kind;
        perMemberOps = perMemberOps == null ? List.of() : List.copyOf(perMemberOps);
        postOps = postOps == null ? List.of() : List.copyOf(postOps);
    }

    /** 末端编码目标(由 convert_format 节点决定,或默认 JPEG)。 */
    public record EncodeTarget(String format, Integer quality) {
        public static EncodeTarget defaultJpeg() {
            return new EncodeTarget("JPEG", null);
        }
    }
}
