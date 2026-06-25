package com.etherealstar.pixflow.infra.storage;

/**
 * 存储层运行时异常，封装文件读写、路径解析等过程中的底层错误。
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
