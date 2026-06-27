package com.pixflow.common.observability;

import com.pixflow.common.error.PixFlowException;

/**
 * 仅定义观测侧 SPI，不在 common 内做任何落盘实现。
 */
public interface ErrorRecorder {
    void record(PixFlowException error);
}
