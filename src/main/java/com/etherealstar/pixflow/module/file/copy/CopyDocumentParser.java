package com.etherealstar.pixflow.module.file.copy;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.module.file.config.AssetProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * 文案文档解析器（Asset_Manager 内部组件，需求 3）。
 *
 * <p>基于 Apache POI 解析 {@code .xls/.xlsx} 与手写 CSV 解析 {@code .csv}，统一规约为「表头行 + 数据行」
 * 的字符串矩阵后执行校验与解析：
 * <ol>
 *   <li>后缀校验：仅接受 {@code .xls/.xlsx/.csv}（不区分大小写），否则拒绝（需求 3.1、3.2）；</li>
 *   <li>体积校验：超过 {@link AssetProperties#getDocMaxSize()} 拒绝（需求 3.2）；</li>
 *   <li>表头匹配：表头单元格去首尾空白 + 不区分大小写匹配列名，缺 {@code id} 列拒绝（需求 3.4、3.5）；</li>
 *   <li>行数校验：数据行数超过 {@link AssetProperties#getDocMaxRows()} 拒绝（需求 3.3）；</li>
 *   <li>逐行解析：{@code id} 为空的行跳过并记录行号，其余行写出 {@code id/product_name/keywords/description}
 *       （缺列写空值，需求 3.6、3.7）。</li>
 * </ol>
 *
 * <p>本组件只负责解析与校验，不触碰数据库；入库与 SKU 软关联由上层服务完成（需求 3.8）。</p>
 */
@Component
public class CopyDocumentParser {

    /** 文案文档允许的后缀（小写，需求 3.1、3.2）。 */
    static final String EXT_XLS = "xls";
    static final String EXT_XLSX = "xlsx";
    static final String EXT_CSV = "csv";

    /** 表头列名（小写，需求 3.4）。 */
    static final String COL_ID = "id";
    static final String COL_PRODUCT_NAME = "product_name";
    static final String COL_KEYWORDS = "keywords";
    static final String COL_DESCRIPTION = "description";

    private final AssetProperties assetProperties;
    private final DataFormatter dataFormatter = new DataFormatter(Locale.ROOT);

    public CopyDocumentParser(AssetProperties assetProperties) {
        this.assetProperties = assetProperties;
    }

    /**
     * 解析并校验文案文档。
     *
     * @param fileName 原始文件名（用于后缀判定与错误提示）
     * @param content  文档字节内容
     * @return 解析结果（合法数据行 + 被跳过行号）
     * @throws BusinessException 当后缀 / 体积 / 行数不合法或缺少 {@code id} 列时
     */
    public CopyParseResult parse(String fileName, byte[] content) {
        String ext = extension(fileName);
        validateExtension(ext, fileName);
        validateSize(content);

        List<List<String>> matrix = EXT_CSV.equals(ext) ? readCsv(content) : readExcel(content);
        return parseMatrix(matrix);
    }

    /**
     * 对已规约为字符串矩阵的文档执行表头匹配、行数校验与逐行解析（需求 3.3–3.7）。
     *
     * <p>该方法不依赖具体文件格式，便于纯逻辑属性测试复用。</p>
     *
     * @param matrix 行列字符串矩阵，第 0 行为表头，其余为数据行
     */
    public CopyParseResult parseMatrix(List<List<String>> matrix) {
        if (matrix == null || matrix.isEmpty()) {
            throw new BusinessException(ErrorCode.DOC_MISSING_ID_COLUMN,
                    "文案文档为空，首行表头缺少 id 列");
        }

        Map<String, Integer> headerIndex = resolveHeader(matrix.get(0));
        Integer idIndex = headerIndex.get(COL_ID);
        if (idIndex == null) {
            throw new BusinessException(ErrorCode.DOC_MISSING_ID_COLUMN);
        }

        int dataRowCount = matrix.size() - 1;
        int rowLimit = assetProperties.getDocMaxRows();
        if (dataRowCount > rowLimit) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("maxRows", rowLimit);
            details.put("actualRows", dataRowCount);
            throw new BusinessException(ErrorCode.DOC_ROWS_EXCEEDED,
                    "文案文档数据行数超过上限 " + rowLimit + " 行", details);
        }

        Integer nameIndex = headerIndex.get(COL_PRODUCT_NAME);
        Integer keywordsIndex = headerIndex.get(COL_KEYWORDS);
        Integer descIndex = headerIndex.get(COL_DESCRIPTION);

        List<CopyRow> rows = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        for (int i = 1; i < matrix.size(); i++) {
            List<String> row = matrix.get(i);
            String id = valueAt(row, idIndex);
            // 行号为 1 基，表头为第 1 行（需求 3.7）
            int lineNumber = i + 1;
            if (id == null) {
                skipped.add(lineNumber);
                continue;
            }
            rows.add(new CopyRow(id,
                    valueAt(row, nameIndex),
                    valueAt(row, keywordsIndex),
                    valueAt(row, descIndex)));
        }
        return new CopyParseResult(rows, skipped);
    }

    /**
     * 解析表头行，去首尾空白 + 不区分大小写匹配，返回列名到列索引的映射（需求 3.4）。
     *
     * <p>同名列出现多次时保留首个出现的索引。</p>
     */
    private Map<String, Integer> resolveHeader(List<String> header) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String raw = header.get(i);
            if (raw == null) {
                continue;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            index.putIfAbsent(key, i);
        }
        return index;
    }

    /**
     * 取数据行指定列的值，去首尾空白；越界、列缺失或空白时返回 {@code null}（缺列写空值，需求 3.6）。
     */
    private static String valueAt(List<String> row, Integer index) {
        if (index == null || row == null || index < 0 || index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateExtension(String ext, String fileName) {
        if (!EXT_XLS.equals(ext) && !EXT_XLSX.equals(ext) && !EXT_CSV.equals(ext)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fileName", fileName);
            details.put("allowed", List.of(EXT_XLS, EXT_XLSX, EXT_CSV));
            throw new BusinessException(ErrorCode.DOC_FORMAT_INVALID,
                    "文案文档后缀必须为 .xls/.xlsx/.csv", details);
        }
    }

    private void validateSize(byte[] content) {
        long limit = assetProperties.getDocMaxSize();
        long actual = content == null ? 0 : content.length;
        if (actual > limit) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("maxDocSizeBytes", limit);
            details.put("actualSizeBytes", actual);
            throw new BusinessException(ErrorCode.DOC_FORMAT_INVALID,
                    "文案文档体积超过上限 " + limit + " 字节", details);
        }
    }

    /**
     * 用 POI 读取 {@code .xls/.xlsx} 首个工作表为字符串矩阵。
     *
     * <p>使用 {@link DataFormatter} 将单元格统一格式化为字符串（数字型 id 如 {@code 1001} 会得到
     * {@code "1001"}），保证与 CSV 行为一致。</p>
     */
    private List<List<String>> readExcel(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            List<List<String>> matrix = new ArrayList<>();
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    int lastCell = row.getLastCellNum();
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? null : dataFormatter.formatCellValue(cell));
                    }
                }
                matrix.add(cells);
            }
            return matrix;
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(ErrorCode.DOC_FORMAT_INVALID,
                    "文案文档无法解析为合法的 Excel 文件：" + e.getMessage());
        }
    }

    /**
     * 手写 CSV 解析（UTF-8），支持双引号包裹字段、字段内逗号/换行、{@code ""} 转义双引号。
     *
     * <p>忽略 UTF-8 BOM；以 {@code \r\n}、{@code \n}、{@code \r} 作为记录分隔；末尾空行不计入。</p>
     */
    static List<List<String>> readCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        List<List<String>> matrix = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                }
                case '\r' -> {
                    // 吞掉紧随的 \n，按单条记录分隔处理
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    current.add(field.toString());
                    field.setLength(0);
                    matrix.add(current);
                    current = new ArrayList<>();
                    rowHasContent = false;
                }
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    matrix.add(current);
                    current = new ArrayList<>();
                    rowHasContent = false;
                }
                default -> {
                    field.append(ch);
                    rowHasContent = true;
                }
            }
        }
        // 收尾：最后一个字段/记录
        if (field.length() > 0 || rowHasContent || !current.isEmpty()) {
            current.add(field.toString());
            matrix.add(current);
        }
        return matrix;
    }

    /**
     * 提取文件名后缀并转小写，无后缀返回空串。
     */
    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
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
}
