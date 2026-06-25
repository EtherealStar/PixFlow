package com.etherealstar.pixflow.infra.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具白名单与参数 schema 的单一权威来源（single source of truth）。
 *
 * <p>固定 MVP 范围内允许的 7 个工具节点及其参数定义，与需求 1（术语表）、设计文档「工具参数 Schema」
 * 表保持一致。提示词管理（{@link DagPromptManager}）据此向 LLM 固定可用工具与参数，后续 DAG_Validator
 * 也可复用本目录做服务端校验，避免白名单/schema 在多处重复定义而漂移。</p>
 *
 * <p>对应需求 6.1（解析出的节点 {@code tool} 仅限白名单内取值）。</p>
 */
public final class ToolCatalog {

    /** 工具名常量，避免硬编码字符串散落。 */
    public static final String REMOVE_BG = "remove_bg";
    public static final String SET_BACKGROUND = "set_background";
    public static final String RESIZE = "resize";
    public static final String COMPRESS = "compress";
    public static final String WATERMARK = "watermark";
    public static final String CONVERT_FORMAT = "convert_format";
    public static final String GENERATE_COPY = "generate_copy";

    /** 以插入顺序保存，保证白名单与提示词中工具列举顺序稳定。 */
    private static final Map<String, ToolDefinition> DEFINITIONS = buildDefinitions();

    private ToolCatalog() {
    }

    private static Map<String, ToolDefinition> buildDefinitions() {
        Map<String, ToolDefinition> map = new LinkedHashMap<>();

        map.put(REMOVE_BG, new ToolDefinition(
            REMOVE_BG,
            "去除图片背景（抠图）",
            List.of(),
            List.of()));

        map.put(SET_BACKGROUND, new ToolDefinition(
            SET_BACKGROUND,
            "为图片设置纯色背景",
            List.of(ToolParam.optional("color", "string", "合法颜色值（如 #FFFFFF），缺省默认 #FFFFFF")),
            List.of()));

        map.put(RESIZE, new ToolDefinition(
            RESIZE,
            "按指定宽高缩放图片",
            List.of(
                ToolParam.required("width", "integer", ">0，单位像素"),
                ToolParam.required("height", "integer", ">0，单位像素")),
            List.of()));

        map.put(COMPRESS, new ToolDefinition(
            COMPRESS,
            "将图片体积压缩至指定上限",
            List.of(ToolParam.required("max_kb", "integer", ">0，目标体积上限（KB）")),
            List.of()));

        map.put(WATERMARK, new ToolDefinition(
            WATERMARK,
            "为图片添加文字或图片水印",
            List.of(
                ToolParam.required("position", "enum", "枚举：top-left、top-right、bottom-left、bottom-right、center"),
                ToolParam.required("text", "string", "文字水印内容；与 image 二选一"),
                ToolParam.required("image", "string", "图片水印来源；与 text 二选一")),
            List.of("text 与 image 二选一：二者必须且只需提供其一")));

        map.put(CONVERT_FORMAT, new ToolDefinition(
            CONVERT_FORMAT,
            "转换图片文件格式",
            List.of(ToolParam.required("format", "enum", "枚举：JPG、PNG、WebP")),
            List.of()));

        map.put(GENERATE_COPY, new ToolDefinition(
            GENERATE_COPY,
            "依据 SKU 文案上下文生成营销文案（独立分支，不依赖像素处理结果）",
            List.of(ToolParam.optional("style", "string", "文案风格，可选")),
            List.of()));

        return map;
    }

    /** 工具白名单（按固定顺序）。 */
    public static List<String> whitelist() {
        return List.copyOf(DEFINITIONS.keySet());
    }

    /** 全部工具定义（按固定顺序）。 */
    public static List<ToolDefinition> definitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    /** 工具名是否在白名单内。 */
    public static boolean isAllowed(String tool) {
        return tool != null && DEFINITIONS.containsKey(tool);
    }

    /** 按工具名查找定义。 */
    public static Optional<ToolDefinition> find(String tool) {
        return Optional.ofNullable(DEFINITIONS.get(tool));
    }
}
