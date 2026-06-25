package com.etherealstar.pixflow.module.file.image;

/**
 * 图片解码探针抽象。
 *
 * <p>用于判断一段字节内容是否能被解码为图片，从而剔除损坏或内容与扩展名不符的文件（需求 1.7）。
 * 抽象为接口以将「解码 I/O」与「白名单过滤等纯逻辑」解耦，便于后续属性测试注入内存替身。</p>
 */
@FunctionalInterface
public interface ImageDecoder {

    /**
     * 尝试将字节内容解码为图片。
     *
     * @param content 图片文件字节内容
     * @return 能成功解码返回 {@code true}，损坏 / 不支持 / 解码失败返回 {@code false}
     */
    boolean canDecode(byte[] content);
}
