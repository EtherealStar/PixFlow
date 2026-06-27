package com.pixflow.contracts.confirmation;

/**
 * 用户确认级别。
 */
public enum ConfirmationLevel {
    /** 常规确认。 */
    NORMAL,

    /** 超过批量阈值时使用的二次确认。 */
    BULK
}
