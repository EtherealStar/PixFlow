package com.pixflow.module.dag.validate.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagNode;
import com.pixflow.module.dag.validate.DagValidationResult;
import com.pixflow.module.dag.validate.ParamSchemaRegistry;
import java.util.List;

/**
 * S4 参数 schema 规则:按工具名取对应 JSON Schema,networknt 校验 params 节点。
 *
 * <p>缺必填 / 类型错误 / enum 越界 / 互斥字段冲突 → DAG_INVALID_PARAMS。
 */
public class ParamsRule {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ParamSchemaRegistry registry;

    public ParamsRule(ParamSchemaRegistry registry) {
        this.registry = registry;
    }

    public String name() {
        return "PARAMS";
    }

    public void check(DagDocument doc, DagValidationResult.Builder builder) {
        for (int i = 0; i < doc.nodes().size(); i++) {
            DagNode node = doc.nodes().get(i);
            // tool 已浅解析保证非 null,但保留防御
            if (node.tool() == null) {
                continue;
            }
            var nodeJson = MAPPER.valueToTree(node.params());
            List<String> errors = registry.validate(node.tool(), nodeJson);
            for (String err : errors) {
                builder.add("DAG_INVALID_PARAMS",
                    "nodes[" + i + "](" + node.id() + ") " + err);
            }
        }
    }
}