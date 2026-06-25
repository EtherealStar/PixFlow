package com.etherealstar.pixflow.module.file;

/**
 * SkuExtractor 是一个纯函数式工具类，负责从去扩展名后的文件名中提取 SKU ID。
 *
 * <p>提取规则（对应需求 2.1、2.2、2.3，详见 design.md「SKU 提取算法」）：
 * <ol>
 *   <li>从文件名起始位置开始向后扫描；</li>
 *   <li>取第一段连续的 {@code [A-Za-z0-9]} 字符作为 SKU；</li>
 *   <li>遇到首个非字母数字字符（如 {@code _}、{@code -}、空格、{@code .}）即终止该段；</li>
 *   <li>若 SKU 长度超过 {@value #MAX_LENGTH}，从末尾截断至 {@value #MAX_LENGTH}；</li>
 *   <li>若文件名中不含任何字母数字字符，则以完整的去扩展名文件名作为兜底 SKU；</li>
 *   <li>始终保留原始大小写。</li>
 * </ol>
 *
 * <p>本类为无状态纯函数，不持有任何可变状态，可安全地并发调用。
 */
public final class SkuExtractor {

    /** SKU ID 的最大长度（字符数），超出部分从末尾截断（需求 2.2）。 */
    public static final int MAX_LENGTH = 255;

    private SkuExtractor() {
        // 工具类不允许实例化
    }

    /**
     * 从去扩展名后的文件名中提取 SKU ID。
     *
     * @param baseName 去除扩展名后的文件名（不可为 {@code null}）
     * @return 依据提取规则得到的 SKU ID
     * @throws NullPointerException 当 {@code baseName} 为 {@code null} 时抛出
     */
    public static String extract(String baseName) {
        if (baseName == null) {
            throw new NullPointerException("baseName must not be null");
        }

        // 1~3：扫描首个连续的字母数字段
        int start = -1;
        int end = -1;
        for (int i = 0; i < baseName.length(); i++) {
            if (isAlphanumeric(baseName.charAt(i))) {
                start = i;
                int j = i;
                while (j < baseName.length() && isAlphanumeric(baseName.charAt(j))) {
                    j++;
                }
                end = j;
                break;
            }
        }

        // 5：无任何字母数字字符时，以完整去扩展名文件名兜底
        String sku = (start == -1) ? baseName : baseName.substring(start, end);

        // 4：超长时从末尾截断
        if (sku.length() > MAX_LENGTH) {
            sku = sku.substring(0, MAX_LENGTH);
        }

        // 6：保留原始大小写（substring 天然保留）
        return sku;
    }

    private static boolean isAlphanumeric(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
