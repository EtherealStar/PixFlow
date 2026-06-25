package com.etherealstar.pixflow.module.file.image;

import java.util.Set;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 图片识别与过滤器（需求 1.5、1.6、1.7）。
 *
 * <p>对解压出的单个文件做两级判定：
 * <ol>
 *   <li>扩展名白名单过滤（JPG/JPEG/PNG/WebP，不区分大小写），非白名单直接跳过（需求 1.5、1.6）；</li>
 *   <li>白名单文件再尝试解码，无法解码（损坏或内容与扩展名不符）则跳过（需求 1.7）。</li>
 * </ol>
 *
 * <p>白名单过滤为纯逻辑（静态方法），解码探测通过可注入的 {@link ImageDecoder} 完成，
 * 二者解耦以便后续属性测试。</p>
 */
@Component
public class ImageValidator {

    /** 图片格式扩展名白名单（小写，需求 1.5）。 */
    public static final Set<String> WHITELIST = Set.of("jpg", "jpeg", "png", "webp");

    static final String REASON_NON_WHITELIST = "扩展名不在图片格式白名单内（JPG/JPEG/PNG/WebP）";
    static final String REASON_UNDECODABLE = "图片无法解码（已损坏或内容与扩展名不符）";

    private final ImageDecoder decoder;

    /** 生产环境构造：使用基于 ImageIO 的默认解码器。 */
    public ImageValidator() {
        this(ImageIoImageDecoder.INSTANCE);
    }

    /** 可注入解码器的构造（用于测试或替换解码实现）。 */
    public ImageValidator(ImageDecoder decoder) {
        this.decoder = decoder;
    }

    /**
     * 提取文件名（或路径）的扩展名，统一转为小写。
     *
     * @return 扩展名（不含点），无扩展名时返回空字符串
     */
    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        // 仅取末段文件名，避免目录中的点干扰
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 判断文件扩展名是否位于图片白名单内（不区分大小写，需求 1.5）。
     */
    public static boolean isWhitelisted(String fileName) {
        return WHITELIST.contains(extension(fileName));
    }

    /**
     * 对单个文件进行图片识别判定。
     *
     * @param fileName 文件名或相对路径（用于扩展名判定与跳过记录）
     * @param content  文件字节内容
     * @return 识别结果（识别成功 / 跳过 + 原因）
     */
    public ImageCheckResult classify(String fileName, byte[] content) {
        if (!isWhitelisted(fileName)) {
            return ImageCheckResult.ofSkipped(REASON_NON_WHITELIST);
        }
        if (!decoder.canDecode(content)) {
            return ImageCheckResult.ofSkipped(REASON_UNDECODABLE);
        }
        return ImageCheckResult.ofRecognized();
    }

    /**
     * 图片识别判定结果。
     *
     * @param recognized 是否成功识别为合法图片
     * @param reason     被跳过原因（识别成功时为 {@code null}）
     */
    public record ImageCheckResult(boolean recognized, String reason) {

        static ImageCheckResult ofRecognized() {
            return new ImageCheckResult(true, null);
        }

        static ImageCheckResult ofSkipped(String reason) {
            return new ImageCheckResult(false, reason);
        }
    }
}
