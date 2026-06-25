package com.etherealstar.pixflow.infra.storage;

/**
 * 存储目录约定。
 *
 * <p>集中定义 PixFlow 各类文件的相对路径规则，避免散落在各业务模块中产生不一致。
 * 所有方法返回相对于存储根目录的、以正斜杠分隔的相对路径。</p>
 *
 * <pre>
 * {root}/
 *   packages/{packageId}/source.zip          原始上传的 zip
 *   packages/{packageId}/doc/{fileName}       文案文档
 *   packages/{packageId}/images/{relPath}     解压后的原图（relPath 为 zip 内相对路径）
 *   results/{taskId}/{fileName}               任务产出的处理结果图
 * </pre>
 */
public final class StoragePaths {

    public static final String PACKAGES_DIR = "packages";
    public static final String RESULTS_DIR = "results";
    public static final String IMAGES_SUBDIR = "images";
    public static final String DOC_SUBDIR = "doc";
    public static final String SOURCE_ZIP_NAME = "source.zip";

    private StoragePaths() {
    }

    /**
     * 某素材包的根目录。
     */
    public static String packageDir(long packageId) {
        return join(PACKAGES_DIR, String.valueOf(packageId));
    }

    /**
     * 某素材包原始 zip 的存储路径。
     */
    public static String packageZip(long packageId) {
        return join(packageDir(packageId), SOURCE_ZIP_NAME);
    }

    /**
     * 某素材包文案文档的存储路径。
     */
    public static String packageDoc(long packageId, String fileName) {
        return join(packageDir(packageId), DOC_SUBDIR, fileName);
    }

    /**
     * 某素材包内一张图片的存储路径。
     *
     * @param relativePathInZip 图片相对 zip 根目录的相对路径
     */
    public static String packageImage(long packageId, String relativePathInZip) {
        return join(packageDir(packageId), IMAGES_SUBDIR, relativePathInZip);
    }

    /**
     * 某任务结果目录。
     */
    public static String taskResultDir(long taskId) {
        return join(RESULTS_DIR, String.valueOf(taskId));
    }

    /**
     * 某任务下一个结果文件的存储路径。
     */
    public static String taskResult(long taskId, String fileName) {
        return join(taskResultDir(taskId), fileName);
    }

    /**
     * 以正斜杠拼接路径段，并清理多余/重复的分隔符。
     */
    public static String join(String... segments) {
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            String normalized = segment.replace('\\', '/');
            // 去除段首尾的斜杠，统一由拼接逻辑补全
            int start = 0;
            int end = normalized.length();
            while (start < end && normalized.charAt(start) == '/') {
                start++;
            }
            while (end > start && normalized.charAt(end - 1) == '/') {
                end--;
            }
            if (start >= end) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(normalized, start, end);
        }
        return sb.toString();
    }
}
