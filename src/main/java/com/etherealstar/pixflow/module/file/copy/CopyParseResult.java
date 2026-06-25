package com.etherealstar.pixflow.module.file.copy;

import java.util.List;

/**
 * 文案文档解析结果（design.md「CopyDocumentParser」产出）。
 *
 * <p>聚合一次合法文案文档解析的产出：成功解析的数据行列表与被跳过行号清单。本对象为纯数据载体，
 * 不涉及 I/O 与持久化，便于上层组装入库与后续属性测试复用。</p>
 *
 * <ul>
 *   <li>{@code rows}：{@code id} 列非空白的数据行（需求 3.6）；</li>
 *   <li>{@code skippedRowNumbers}：{@code id} 单元格为空被跳过的行号（1 基，表头为第 1 行，需求 3.7）。</li>
 * </ul>
 *
 * @param rows              成功解析的文案数据行
 * @param skippedRowNumbers 被跳过（空 id）行号（1 基，含表头偏移）
 */
public record CopyParseResult(List<CopyRow> rows, List<Integer> skippedRowNumbers) {

    public CopyParseResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        skippedRowNumbers = skippedRowNumbers == null ? List.of() : List.copyOf(skippedRowNumbers);
    }
}
