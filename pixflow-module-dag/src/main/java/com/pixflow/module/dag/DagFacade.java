package com.pixflow.module.dag;

import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.GroupPreflight;
import com.pixflow.module.dag.expand.GroupPreflight.PreflightDifference;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.CanonicalDag;
import com.pixflow.module.dag.ir.CanonicalDagFactory;
import com.pixflow.module.dag.exec.DagCompiler;
import com.pixflow.module.dag.exec.TypedExecutionPlan;
import com.pixflow.module.dag.validate.DagValidationResult;
import com.pixflow.module.dag.validate.DagValidator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DagFacade:对外门面(对齐 dag.md §三)。
 *
 * <p>聚合 validate / expand / preflightGroups 三大能力;实际执行单元(UnitExecutor)由调用方
 * (task)持有,facade 不持有执行器引用,避免状态污染。
 */
@Component
public class DagFacade {

    private final DagValidator validator;

    private final BranchExpander expander;

    private final GroupPreflight preflight;

    private final CanonicalDagFactory canonicalDagFactory;

    private final DagCompiler compiler;

    public DagFacade(DagValidator validator, BranchExpander expander, GroupPreflight preflight,
                     CanonicalDagFactory canonicalDagFactory, DagCompiler compiler) {
        this.validator = validator;
        this.expander = expander;
        this.preflight = preflight;
        this.canonicalDagFactory = canonicalDagFactory;
        this.compiler = compiler;
    }

    public DagValidationResult validate(DagDocument doc) {
        return validator.validate(doc);
    }

    public CanonicalDag validateToCanonical(DagDocument doc, DagSchemaVersion schemaVersion) {
        DagValidationResult result = validator.validate(doc);
        if (!result.ok()) {
            throw new IllegalArgumentException("DAG 校验未通过: " + String.join("; ", result.errors()));
        }
        return canonicalDagFactory.fromDocument(doc, schemaVersion);
    }

    public TypedExecutionPlan compile(CanonicalDag dag) {
        return compiler.compile(dag);
    }

    public List<ExecutableBranch> expand(TypedExecutionPlan dag, List<ImageDescriptor> images) {
        return expander.expand(dag, images);
    }

    public List<PreflightDifference> preflightGroups(TypedExecutionPlan dag,
                                                       Map<String, Integer> actualGroupCounts) {
        return preflight.preflight(dag, actualGroupCounts);
    }
}
