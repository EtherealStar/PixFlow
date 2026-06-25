package com.etherealstar.pixflow.module.dag.engine;

import java.util.List;

/**
 * 当 DAG 中存在环导致无法完成拓扑排序时抛出（对应需求 7.3）。
 */
public class CycleDetectedException extends RuntimeException {

    private final List<String> remainingNodeIds;

    public CycleDetectedException(List<String> remainingNodeIds) {
        super("DAG 中检测到环，无法完成拓扑排序，涉及节点: " + remainingNodeIds);
        this.remainingNodeIds = remainingNodeIds;
    }

    /**
     * 返回参与环（或被环阻塞）的节点 id 集合。
     */
    public List<String> getRemainingNodeIds() {
        return remainingNodeIds;
    }
}
