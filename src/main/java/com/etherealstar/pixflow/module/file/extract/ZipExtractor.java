package com.etherealstar.pixflow.module.file.extract;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * ZipExtractor：流式解压 zip，并在解压过程中实施 zip-bomb 防护（需求 1.4、1.5）。
 *
 * <p>核心特性：
 * <ul>
 *   <li>使用 {@link ZipInputStream} 流式逐条目解压，单条目按块读取并即时累计「文件总数」与「累计解压大小」；</li>
 *   <li>一旦累计大小超过 {@link AssetProperties#getExtractedMaxSize()} 或文件数超过
 *       {@link AssetProperties#getExtractedMaxCount()}，立即终止解压并抛出
 *       {@link ErrorCode#ASSET_ZIP_BOMB}（含阈值上下文）；</li>
 *   <li>zip 结构无法解析（截断 / 非法字节）时抛出 {@link ErrorCode#ASSET_ZIP_INVALID}；</li>
 *   <li>zip 内子文件夹层级天然体现在条目名中，遍历全部非目录条目即实现递归扫描所有层级（需求 1.5）。</li>
 * </ul>
 */
@Component
public class ZipExtractor {

    private static final int BUFFER_SIZE = 8192;

    private final AssetProperties properties;

    public ZipExtractor(AssetProperties properties) {
        this.properties = properties;
    }

    /**
     * 流式解压 zip，逐个非目录条目回调 {@code consumer}。本方法会关闭传入的输入流。
     *
     * @param zipStream zip 字节输入流
     * @param consumer  条目消费回调（相对路径 + 内容）
     * @throws BusinessException zip 非法（{@link ErrorCode#ASSET_ZIP_INVALID}）或触发 zip-bomb 阈值
     *                           （{@link ErrorCode#ASSET_ZIP_BOMB}）
     */
    public void extract(InputStream zipStream, ZipEntryConsumer consumer) {
        long totalSize = 0L;
        int totalCount = 0;
        long maxSize = properties.getExtractedMaxSize();
        int maxCount = properties.getExtractedMaxCount();

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zipStream))) {
            ZipEntry entry = nextEntry(zis);
            while (entry != null) {
                if (!entry.isDirectory()) {
                    String relativePath = normalize(entry.getName());
                    if (!relativePath.isEmpty()) {
                        totalCount++;
                        if (totalCount > maxCount) {
                            throw bomb(maxSize, maxCount, "文件总数超过阈值");
                        }
                        byte[] content = readEntry(zis, totalSize, maxSize, maxCount);
                        totalSize += content.length;
                        consumer.accept(relativePath, content);
                    }
                }
                zis.closeEntry();
                entry = nextEntry(zis);
            }
        } catch (ZipException e) {
            throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                    "无法解析 zip：" + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                    "读取 zip 内容失败：" + e.getMessage());
        }
    }

    private static ZipEntry nextEntry(ZipInputStream zis) throws IOException {
        return zis.getNextEntry();
    }

    /**
     * 读取单个条目内容，按块累计大小并在越界时立即终止（防止单个超大条目耗尽资源）。
     */
    private byte[] readEntry(ZipInputStream zis, long sizeBefore, long maxSize, int maxCount)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[BUFFER_SIZE];
        long running = sizeBefore;
        int n;
        while ((n = zis.read(chunk)) != -1) {
            running += n;
            if (running > maxSize) {
                throw bomb(maxSize, maxCount, "累计解压文件总大小超过阈值");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private BusinessException bomb(long maxSize, int maxCount, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("maxExtractedSizeBytes", maxSize);
        details.put("maxExtractedCount", maxCount);
        return new BusinessException(ErrorCode.ASSET_ZIP_BOMB,
                "压缩包异常（zip-bomb 防护）：" + reason, details);
    }

    /**
     * 规范化 zip 条目路径：统一分隔符、去除前导斜杠，并防御路径穿越。
     */
    private static String normalize(String entryName) {
        if (entryName == null) {
            return "";
        }
        String path = entryName.replace('\\', '/');
        // 去除前导斜杠
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        // 防御路径穿越：包含 .. 段的条目视为非法 zip 内容
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                throw new BusinessException(ErrorCode.ASSET_ZIP_INVALID,
                        "zip 条目路径非法（疑似路径穿越）：" + entryName);
            }
        }
        return path;
    }
}
