package com.pixflow.contracts.confirmation;

/**
 * 需要用户确认的动作类型。
 */
public enum ConfirmationAction {
    /** 提交确定性 DAG 执行，重跑也复用该动作。 */
    SUBMIT_DAG,

    /** 生成式重绘动作。 */
    IMAGEGEN
}
