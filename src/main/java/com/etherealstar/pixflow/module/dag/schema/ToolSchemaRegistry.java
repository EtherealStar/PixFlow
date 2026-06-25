package com.etherealstar.pixflow.module.dag.schema;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 7 个工具的参数 Schema 注册表（需求 7.5，设计「工具参数 Schema」表）。
 *
 * <p>集中定义工具白名单内每个工具的必填/可选参数及取值约束，作为 DAG_Validator（任务 10.2）
 * 参数校验与 DAG_Parser 缺参追问的唯一数据来源：
 *
 * <table border="1">
 *   <caption>工具参数 Schema</caption>
 *   <tr><th>工具</th><th>必填参数</th><th>可选参数</th></tr>
 *   <tr><td>remove_bg</td><td>—</td><td>—</td></tr>
 *   <tr><td>set_background</td><td>—</td><td>color（默认 #FFFFFF，须为合法颜色）</td></tr>
 *   <tr><td>resize</td><td>width(&gt;0), height(&gt;0)</td><td>—</td></tr>
 *   <tr><td>compress</td><td>max_kb(&gt;0)</td><td>—</td></tr>
 *   <tr><td>watermark</td><td>position(枚举), text 或 image 二选一</td><td>—</td></tr>
 *   <tr><td>convert_format</td><td>format(JPG/PNG/WebP)</td><td>—</td></tr>
 *   <tr><td>generate_copy</td><td>—</td><td>style</td></tr>
 * </table>
 */
public final class ToolSchemaRegistry {

    private static final Map<ToolType, ToolParamSchema> SCHEMAS = buildSchemas();

    private ToolSchemaRegistry() {
    }

    private static Map<ToolType, ToolParamSchema> buildSchemas() {
        Map<ToolType, ToolParamSchema> map = new EnumMap<>(ToolType.class);

        // remove_bg：无参数
        map.put(ToolType.REMOVE_BG,
                ToolParamSchema.builder(ToolType.REMOVE_BG).build());

        // set_background：color 可选，默认 #FFFFFF，须为合法颜色
        map.put(ToolType.SET_BACKGROUND,
                ToolParamSchema.builder(ToolType.SET_BACKGROUND)
                        .optional("color", "#FFFFFF", ParamValidators.color())
                        .build());

        // resize：width(>0)、height(>0) 必填正整数
        map.put(ToolType.RESIZE,
                ToolParamSchema.builder(ToolType.RESIZE)
                        .required("width", ParamValidators.positiveInteger())
                        .required("height", ParamValidators.positiveInteger())
                        .build());

        // compress：max_kb(>0) 必填正整数
        map.put(ToolType.COMPRESS,
                ToolParamSchema.builder(ToolType.COMPRESS)
                        .required("max_kb", ParamValidators.positiveInteger())
                        .build());

        // watermark：position 必填枚举，text 或 image 二选一
        map.put(ToolType.WATERMARK,
                ToolParamSchema.builder(ToolType.WATERMARK)
                        .required("position", ParamValidators.enumValue(WatermarkPosition.wireNames(), true))
                        .oneOf(ParamValidators.nonBlankString(), "text", "image")
                        .build());

        // convert_format：format 必填枚举（JPG/PNG/WebP）
        map.put(ToolType.CONVERT_FORMAT,
                ToolParamSchema.builder(ToolType.CONVERT_FORMAT)
                        .required("format", ParamValidators.enumValue(ImageFormat.wireNames(), true))
                        .build());

        // generate_copy：style 可选
        map.put(ToolType.GENERATE_COPY,
                ToolParamSchema.builder(ToolType.GENERATE_COPY)
                        .optional("style", ParamValidators.anyString())
                        .build());

        return Collections.unmodifiableMap(map);
    }

    /** 获取指定工具的参数 schema。 */
    public static ToolParamSchema get(ToolType toolType) {
        return SCHEMAS.get(toolType);
    }

    /**
     * 按 DAG JSON 中的工具名查找参数 schema。
     *
     * @param toolName 节点 {@code tool} 字段值
     * @return 对应 schema；工具名不在白名单内时为空
     */
    public static Optional<ToolParamSchema> findByToolName(String toolName) {
        return ToolType.fromToolName(toolName).map(SCHEMAS::get);
    }

    /** 工具名是否在白名单内。 */
    public static boolean isWhitelisted(String toolName) {
        return ToolType.fromToolName(toolName).isPresent();
    }

    /** 全部工具的参数 schema（只读）。 */
    public static Map<ToolType, ToolParamSchema> all() {
        return SCHEMAS;
    }
}
