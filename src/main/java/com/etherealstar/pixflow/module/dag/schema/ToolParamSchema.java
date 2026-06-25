package com.etherealstar.pixflow.module.dag.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个工具的参数 Schema 定义（需求 7.5，设计「工具参数 Schema」表）。
 *
 * <p>描述某工具节点 {@code params} 所允许的参数集合及其约束，包括：
 * <ul>
 *   <li>必填参数（缺失则校验失败）；</li>
 *   <li>可选参数（可携带默认值，如 {@code set_background.color} 默认 {@code #FFFFFF}）；</li>
 *   <li>取值约束（如正整数、枚举、合法颜色）；</li>
 *   <li>二选一约束（one-of，如 {@code watermark} 的 {@code text} 或 {@code image} 至少其一）。</li>
 * </ul>
 *
 * <p>校验语义（{@link #validate(Map)}）：
 * <ol>
 *   <li>未知参数：出现不在已知参数集合内的键 → 失败（schema 严格约束，防止不可信结构）。</li>
 *   <li>缺失必填参数 → 失败。</li>
 *   <li>已出现参数的取值不满足其校验器 → 失败。</li>
 *   <li>任一 one-of 组未提供任何成员 → 失败。</li>
 * </ol>
 *
 * <p>本类不可变，通过 {@link Builder} 构造。
 */
public final class ToolParamSchema {

    /** 单个参数规格。 */
    private record ParamSpec(String name, boolean required, Object defaultValue, ParamValueValidator validator) {
    }

    private final ToolType toolType;
    private final Map<String, ParamSpec> params;
    private final List<Set<String>> oneOfGroups;
    private final Set<String> knownParamNames;

    private ToolParamSchema(ToolType toolType, Map<String, ParamSpec> params, List<Set<String>> oneOfGroups) {
        this.toolType = toolType;
        this.params = Collections.unmodifiableMap(params);
        this.oneOfGroups = List.copyOf(oneOfGroups);
        this.knownParamNames = Collections.unmodifiableSet(new LinkedHashSet<>(params.keySet()));
    }

    public ToolType toolType() {
        return toolType;
    }

    /** 必填参数名集合（不含 one-of 组成员，组成员为条件必填，见 {@link #oneOfGroups()}）。 */
    public Set<String> requiredParamNames() {
        Set<String> result = new LinkedHashSet<>();
        for (ParamSpec spec : params.values()) {
            if (spec.required()) {
                result.add(spec.name());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 可选参数名集合（不含必填，亦不含 one-of 组成员）。 */
    public Set<String> optionalParamNames() {
        Set<String> oneOfMembers = oneOfMembers();
        Set<String> result = new LinkedHashSet<>();
        for (ParamSpec spec : params.values()) {
            if (!spec.required() && !oneOfMembers.contains(spec.name())) {
                result.add(spec.name());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 全部已知参数名集合（必填 ∪ 可选 ∪ one-of 组成员）。 */
    public Set<String> knownParamNames() {
        return knownParamNames;
    }

    /** 二选一（one-of）约束组：每组要求至少提供其中一个成员。 */
    public List<Set<String>> oneOfGroups() {
        return oneOfGroups;
    }

    private Set<String> oneOfMembers() {
        Set<String> members = new LinkedHashSet<>();
        for (Set<String> group : oneOfGroups) {
            members.addAll(group);
        }
        return members;
    }

    /**
     * 校验给定参数 map 是否符合本工具的参数 schema。
     *
     * @param rawParams 工具节点的 {@code params}（可为 null，视作空）
     * @return 校验结果；不通过时携带全部失败原因
     */
    public ParamValidationResult validate(Map<String, Object> rawParams) {
        Map<String, Object> effective = rawParams == null ? Map.of() : rawParams;
        List<String> errors = new ArrayList<>();

        // 1. 未知参数
        for (String key : effective.keySet()) {
            if (!knownParamNames.contains(key)) {
                errors.add("出现未知参数: " + key);
            }
        }

        // 2. 必填参数缺失 + 3. 取值校验
        for (ParamSpec spec : params.values()) {
            boolean present = isPresent(effective, spec.name());
            if (spec.required() && !present) {
                errors.add("缺少必填参数: " + spec.name());
                continue;
            }
            if (present && spec.validator() != null) {
                spec.validator().validate(spec.name(), effective.get(spec.name()))
                        .ifPresent(errors::add);
            }
        }

        // 4. one-of 组约束
        for (Set<String> group : oneOfGroups) {
            long providedCount = group.stream().filter(name -> isPresent(effective, name)).count();
            if (providedCount == 0) {
                errors.add("参数 " + group + " 须至少提供其一");
            }
        }

        return errors.isEmpty() ? ParamValidationResult.passed() : ParamValidationResult.invalid(errors);
    }

    /**
     * 在原始参数基础上补全可选参数的默认值，返回新的参数 map（不修改入参）。
     *
     * <p>仅对定义了非 null 默认值且未出现的参数进行填充（如 {@code set_background.color → #FFFFFF}）。
     * 该方法供执行引擎在校验通过后取得带默认值的参数使用，不用于「自动猜测必填参数」（需求 6.5）。
     */
    public Map<String, Object> withDefaults(Map<String, Object> rawParams) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawParams != null) {
            result.putAll(rawParams);
        }
        for (ParamSpec spec : params.values()) {
            if (spec.defaultValue() != null && !isPresent(result, spec.name())) {
                result.put(spec.name(), spec.defaultValue());
            }
        }
        return result;
    }

    private static boolean isPresent(Map<String, Object> params, String name) {
        return params.containsKey(name) && params.get(name) != null;
    }

    public static Builder builder(ToolType toolType) {
        return new Builder(toolType);
    }

    /** {@link ToolParamSchema} 构造器。 */
    public static final class Builder {

        private final ToolType toolType;
        private final Map<String, ParamSpec> params = new LinkedHashMap<>();
        private final List<Set<String>> oneOfGroups = new ArrayList<>();

        private Builder(ToolType toolType) {
            this.toolType = toolType;
        }

        /** 声明一个必填参数及其取值校验器。 */
        public Builder required(String name, ParamValueValidator validator) {
            params.put(name, new ParamSpec(name, true, null, validator));
            return this;
        }

        /** 声明一个可选参数（无默认值）及其取值校验器。 */
        public Builder optional(String name, ParamValueValidator validator) {
            params.put(name, new ParamSpec(name, false, null, validator));
            return this;
        }

        /** 声明一个带默认值的可选参数及其取值校验器。 */
        public Builder optional(String name, Object defaultValue, ParamValueValidator validator) {
            params.put(name, new ParamSpec(name, false, defaultValue, validator));
            return this;
        }

        /**
         * 声明一组二选一（one-of）参数：组内成员均作为已知可选参数，且要求至少提供其一。
         *
         * @param validator 应用于每个成员（出现时）的取值校验器
         * @param names     组内成员参数名
         */
        public Builder oneOf(ParamValueValidator validator, String... names) {
            Set<String> group = new LinkedHashSet<>();
            for (String name : names) {
                params.put(name, new ParamSpec(name, false, null, validator));
                group.add(name);
            }
            oneOfGroups.add(Collections.unmodifiableSet(group));
            return this;
        }

        public ToolParamSchema build() {
            return new ToolParamSchema(toolType, params, oneOfGroups);
        }
    }
}
