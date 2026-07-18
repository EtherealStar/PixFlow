package com.pixflow.module.file.api;

/** 解析资产时声明调用目的，避免调用方用布尔值绕过状态检查。 */
public enum AssetUse {
    BROWSE,
    INSPECT,
    PROCESS,
    DOWNLOAD
}
