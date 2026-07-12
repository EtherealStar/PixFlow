package com.pixflow.module.dag.ir;

/**
 * 像素工具白名单枚举。
 *
 * <p>dag 模块拥有的 8 个像素工具,封闭不可扩展。每种工具的派发目标、输入基数、参数 spec 映射关系
 * 在此集中声明,由 DagCompiler 与 StepBindingRegistry 穷举消费。
 *
 * <p>新增工具需修改此处编译期枚举 + 新增 JSON Schema 资源,严禁运行时注册。
 */
public enum PixelTool {
    /** 第三方抠图(infra/thirdparty),输出带 alpha 的字节;1→1 */
    REMOVE_BG("remove_bg", Arity.ONE_TO_ONE, Target.THIRDPARTY),
    /** 设置背景色或背景图(infra/image);1→1 */
    SET_BACKGROUND("set_background", Arity.ONE_TO_ONE, Target.IMAGE),
    /** 缩放(infra/image);1→1 */
    RESIZE("resize", Arity.ONE_TO_ONE, Target.IMAGE),
    /** 压缩(infra/image);1→1 */
    COMPRESS("compress", Arity.ONE_TO_ONE, Target.IMAGE),
    /** 水印叠加(infra/image);1→1 */
    WATERMARK("watermark", Arity.ONE_TO_ONE, Target.IMAGE),
    /** 格式转换(infra/image);1→1 */
    CONVERT_FORMAT("convert_format", Arity.ONE_TO_ONE, Target.IMAGE),
    /** 多图合成 1 张(infra/image);N→1(唯一组支路入口) */
    COMPOSE_GROUP("compose_group", Arity.N_TO_ONE, Target.IMAGE),
    /** 文案生成(infra/ai);不在像素链上,独立文案分支 */
    GENERATE_COPY("generate_copy", Arity.TEXT, Target.AI);

    private final String wireName;
    private final Arity arity;
    private final Target target;

    PixelTool(String wireName, Arity arity, Target target) {
        this.wireName = wireName;
        this.arity = arity;
        this.target = target;
    }

    /** JSON 中使用的 snake_case 名称,作为 schema 的 tool 字段与白名单匹配键 */
    public String wireName() {
        return wireName;
    }

    /** 输入基数语义(决定分支/组支路展开) */
    public Arity arity() {
        return arity;
    }

    /** 派发目标(infra 子模块) */
    public Target target() {
        return target;
    }

    /** 按 wireName 反查枚举;未命中返回 null 用于校验器报 DAG_UNKNOWN_TOOL */
    public static PixelTool fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (PixelTool tool : values()) {
            if (tool.wireName.equals(name)) {
                return tool;
            }
        }
        return null;
    }

    public enum Arity {
        /** 1 输入 1 输出(逐图工具) */
        ONE_TO_ONE,
        /** N 输入 1 输出(仅 compose_group) */
        N_TO_ONE,
        /** 不串接像素链(generate_copy) */
        TEXT
    }

    public enum Target {
        /** infra/thirdparty 第三方 HTTP 调用 */
        THIRDPARTY,
        /** infra/image 本地像素运算 */
        IMAGE,
        /** infra/ai 模型调用 */
        AI
    }
}
