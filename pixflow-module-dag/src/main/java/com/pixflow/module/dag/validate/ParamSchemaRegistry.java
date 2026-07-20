package com.pixflow.module.dag.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.pixflow.module.dag.ir.PixelTool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 每像素工具的参数 JSON Schema 注册中心。
 *
 * <p>从 classpath:schemas/dag/*.json 资源文件加载;每个文件顶层包含 {@code tool}/{@code version}
 * 字段。schema 资源文件是 dag 行为的契约唯一事实源,新增字段需兼容既有 DAG(加法兼容,见 dag.md §6.2.1)。
 *
 * <p>校验路径: {@link ParamsRule} 在 DagValidator S4 阶段调 {@link #validate(PixelTool, JsonNode)}。
 */
@Component
public class ParamSchemaRegistry {

    /** 工具 → schema 句柄 + 大版本号 */
    private final Map<PixelTool, Entry> entries;

    /** 启动期扫描发现的资源 raw 文本(供 SchemaRegistryValidator 自检版本单调性) */
    private final Map<PixelTool, String> rawSchemas;

    public ParamSchemaRegistry() {
        this("classpath*:schemas/dag/*.json");
    }

    /** 测试用:允许注入自定义扫描路径。 */
    public ParamSchemaRegistry(String locationPattern) {
        Map<PixelTool, Entry> tmp = new EnumMap<>(PixelTool.class);
        Map<PixelTool, String> raw = new EnumMap<>(PixelTool.class);
        ObjectMapper mapper = new ObjectMapper();

        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(locationPattern);
            for (Resource resource : resources) {
                String content;
                try (InputStream in = resource.getInputStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                JsonNode tree = mapper.readTree(content);
                String toolName = textOrThrow(tree, "tool", resource.getFilename());
                PixelTool tool = PixelTool.fromWireName(toolName);
                if (tool == null) {
                    throw new IllegalStateException(
                        "schema 文件 " + resource.getFilename() + " 含未知 tool=" + toolName);
                }
                String version = textOrThrow(tree, "version", resource.getFilename());
                JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
                SchemaValidatorsConfig config = new SchemaValidatorsConfig();
                config.setFailFast(false);
                JsonSchema schema = factory.getSchema(tree, config);
                tmp.put(tool, new Entry(schema, version, toolName));
                raw.put(tool, content);
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 DAG schema 资源失败: " + locationPattern, e);
        }
        // 防御:每工具必须有 schema;缺失启动期报错(避免运行期 NPE)
        for (PixelTool tool : PixelTool.values()) {
            if (!tmp.containsKey(tool)) {
                throw new IllegalStateException(
                    "缺少工具 " + tool.wireName() + " 的参数 schema 资源文件");
            }
        }
        this.entries = tmp;
        this.rawSchemas = raw;
    }

    /** 校验指定工具的参数 JSON 节点;失败返回错误信息集合,成功返回空集。 */
    public java.util.List<String> validate(PixelTool tool, JsonNode params) {
        Entry entry = entries.get(tool);
        if (entry == null) {
            return java.util.List.of("DAG_UNKNOWN_TOOL: " + tool.wireName());
        }
        var messages = new java.util.ArrayList<String>();
        var errors = entry.schema.validate(params);
        for (var error : errors) {
            // 把内部 JSON Pointer 转为相对路径:$.params.foo → params.foo
            String pointer = error.getInstanceLocation() == null ? "$"
                : error.getInstanceLocation().toString();
            messages.add("DAG_INVALID_PARAMS: " + tool.wireName() + " " + pointer + " " + error.getMessage());
        }
        return messages;
    }

    public String schemaVersion(PixelTool tool) {
        Entry entry = entries.get(tool);
        return entry == null ? null : entry.version;
    }

    /** 启动期自检用:返回所有 tool → version 的有序视图。 */
    public Map<String, String> allVersions() {
        Map<String, String> view = new TreeMap<>();
        for (PixelTool tool : PixelTool.values()) {
            Entry entry = entries.get(tool);
            if (entry != null) {
                view.put(tool.wireName(), entry.version);
            }
        }
        return view;
    }

    /** 启动期自检用:返回所有 tool → raw schema 的视图(供 SchemaRegistryValidator 校验单调性)。 */
    public Map<PixelTool, String> rawSchemas() {
        return Map.copyOf(rawSchemas);
    }

    public Set<PixelTool> registeredTools() {
        return entries.keySet();
    }

    private static String textOrThrow(JsonNode tree, String field, String filename) {
        JsonNode node = tree.get(field);
        if (node == null || !node.isTextual()) {
            throw new IllegalStateException(
                "schema 文件 " + filename + " 缺少顶层 " + field + " 字段");
        }
        return node.asText();
    }

    private record Entry(JsonSchema schema, String version, String toolName) {
    }
}