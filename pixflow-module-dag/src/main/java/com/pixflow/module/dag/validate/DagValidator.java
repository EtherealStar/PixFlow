package com.pixflow.module.dag.validate;

import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.validate.rule.AcyclicRule;
import com.pixflow.module.dag.validate.rule.EdgeRule;
import com.pixflow.module.dag.validate.rule.GroupBranchRule;
import com.pixflow.module.dag.validate.rule.NodeLimitRule;
import com.pixflow.module.dag.validate.rule.OpOrderRule;
import com.pixflow.module.dag.validate.rule.ParamsRule;
import com.pixflow.module.dag.validate.rule.StructureRule;
import com.pixflow.module.dag.validate.rule.WhitelistRule;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * DagValidator:无状态、可重复调用的服务端校验器。
 *
 * <p>规则顺序(对齐 dag.md §5):
 * <pre>
 *   S1 结构(id 唯一/引用完整) → S2 节点数(1..maxNodes) → S3 白名单 → S4 参数 schema
 *      → S5 边引用 → S6 无环 → S7 组支路规则 → S8 remove_bg 链首约束
 * </pre>
 *
 * <p>任一规则失败把错误项加入 {@link DagValidationResult.Builder},**不抛异常**;由调用方决定
 * 如何处理(SubmitImagePlanHandler 转 tool error / 确认边界拦截)。
 *
 * <p>校验器在两个时机被调用:<br>
 * ① 提案入队时(`submit_image_plan` handler);② 确认 REST 边界创建 process_task 前。
 * 无状态保证两侧校验结果一致。
 */
@Component
public class DagValidator {

    private final ParamSchemaRegistry schemaRegistry;

    private final StructureRule structureRule;

    private final NodeLimitRule nodeLimitRule;

    private final WhitelistRule whitelistRule;

    private final ParamsRule paramsRule;

    private final EdgeRule edgeRule;

    private final AcyclicRule acyclicRule;

    private final GroupBranchRule groupBranchRule;

    private final OpOrderRule opOrderRule;

    private final int maxNodes;

    public DagValidator(ParamSchemaRegistry schemaRegistry,
                        int maxNodes,
                        int minNodes) {
        this.schemaRegistry = schemaRegistry;
        this.maxNodes = maxNodes;
        this.structureRule = new StructureRule();
        this.nodeLimitRule = new NodeLimitRule(maxNodes, minNodes);
        this.whitelistRule = new WhitelistRule();
        this.paramsRule = new ParamsRule(schemaRegistry);
        this.edgeRule = new EdgeRule();
        this.acyclicRule = new AcyclicRule();
        this.groupBranchRule = new GroupBranchRule();
        this.opOrderRule = new OpOrderRule();
    }

    /**
     * 校验 DagDocument,产出 {@link DagValidationResult}。
     * 不抛异常,失败项聚合在 result.errors()。
     */
    public DagValidationResult validate(DagDocument doc) {
        DagValidationResult.Builder builder = DagValidationResult.builder();

        // S1 结构:id 唯一性、非空性
        structureRule.check(doc, builder);
        // S2 节点数限制
        nodeLimitRule.check(doc, builder);
        // S3 白名单:此处只校验"已知工具枚举",tool 必非 null 已在 DagJsonReader 浅解析中保证
        whitelistRule.check(doc, builder);
        // S4 参数 schema
        paramsRule.check(doc, builder);
        // S5 边引用完整性
        edgeRule.check(doc, builder);
        // S6 无环(拓扑可排序)
        acyclicRule.check(doc, builder);
        // S7 组支路规则
        groupBranchRule.check(doc, builder);
        // S8 remove_bg 链首约束
        opOrderRule.check(doc, builder);

        return builder.build();
    }

    /** 暴露 schemaRegistry 以便其他模块(如 SchemaRegistryValidator)启动期自检。 */
    public ParamSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    public int maxNodes() {
        return maxNodes;
    }

    /** 给测试 / handler 使用:返回所有规则的快捷访问。 */
    public List<String> ruleOrder() {
        List<String> order = new ArrayList<>(8);
        order.add(structureRule.name());
        order.add(nodeLimitRule.name());
        order.add(whitelistRule.name());
        order.add(paramsRule.name());
        order.add(edgeRule.name());
        order.add(acyclicRule.name());
        order.add(groupBranchRule.name());
        order.add(opOrderRule.name());
        return order;
    }
}
