package com.etherealstar.pixflow.infra.storage;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * 文件存储抽象接口。
 *
 * <p>统一封装 PixFlow 的文件读写与相对路径管理，使上层模块（素材管理、执行引擎、任务下载）
 * 无需感知底层存储介质。当前实现为本地磁盘，后续可替换为 OSS/MinIO。</p>
 *
 * <p>除特别说明外，所有 {@code relativePath} 均为相对于存储根目录、以正斜杠分隔的相对路径，
 * 且不得越出存储根目录（禁止路径穿越）。</p>
 */
public interface StorageService {

    /**
     * 将相对路径解析为存储根目录下的绝对路径。
     *
     * @throws StorageException 当路径越出存储根目录（路径穿越）时
     */
    Path resolve(String relativePath);

    /**
     * 以流方式写入文件，必要时自动创建父目录。写入完成后会关闭输入流。
     *
     * @param content      数据来源输入流
     * @param relativePath 目标相对路径
     * @return 实际写入的相对路径
     */
    String write(InputStream content, String relativePath);

    /**
     * 写入字节内容，必要时自动创建父目录。
     *
     * @return 实际写入的相对路径
     */
    String write(byte[] content, String relativePath);

    /**
     * 打开一个用于读取的输入流（流式读取，适用于大文件）。调用方负责关闭。
     */
    InputStream openInputStream(String relativePath);

    /**
     * 打开一个用于写入的输出流（流式写出，适用于打包下载等场景），必要时自动创建父目录。
     * 调用方负责关闭。
     */
    OutputStream openOutputStream(String relativePath);

    /**
     * 一次性读取全部字节内容。仅适用于小文件，大文件应使用 {@link #openInputStream}。
     */
    byte[] readAllBytes(String relativePath);

    /**
     * 判断文件或目录是否存在。
     */
    boolean exists(String relativePath);

    /**
     * 返回文件大小（字节）。
     */
    long size(String relativePath);

    /**
     * 创建目录（含所有缺失的父目录）。
     */
    void createDirectories(String relativePath);

    /**
     * 删除单个文件。
     *
     * @return 文件存在并被删除返回 true；文件原本不存在返回 false
     */
    boolean delete(String relativePath);

    /**
     * 递归删除目录及其全部内容。
     *
     * @return 目录存在并被删除返回 true；原本不存在返回 false
     */
    boolean deleteRecursively(String relativePath);
}
