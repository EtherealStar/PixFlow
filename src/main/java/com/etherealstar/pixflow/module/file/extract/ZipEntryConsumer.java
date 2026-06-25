package com.etherealstar.pixflow.module.file.extract;

/**
 * zip 解压条目消费回调。
 *
 * <p>{@link ZipExtractor} 在流式解压过程中逐个文件回调本接口，使调用方可即时处理（识别 / 持久化）
 * 而无需将整个 zip 内容驻留内存。</p>
 */
@FunctionalInterface
public interface ZipEntryConsumer {

    /**
     * 处理一个解压出的文件条目。
     *
     * @param relativePath 文件相对 zip 根目录的相对路径（含子文件夹层级，正斜杠分隔）
     * @param content      该文件的字节内容
     */
    void accept(String relativePath, byte[] content);
}
