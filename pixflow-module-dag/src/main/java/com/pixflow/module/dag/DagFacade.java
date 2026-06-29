package com.pixflow.module.dag;

import com.pixflow.module.dag.expand.BranchExpander;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.GroupPreflight;
import com.pixflow.module.dag.expand.GroupPreflight.PreflightDifference;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.dag.ir.DagDocument;
import com.pixflow.module.dag.ir.DagSchemaVersion;
import com.pixflow.module.dag.ir.ValidatedDag;
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

    public DagFacade(DagValidator validator, BranchExpander expander, GroupPreflight preflight) {
        this.validator = validator;
        this.expander = expander;
        this.preflight = preflight;
    }

    public DagValidationResult validate(DagDocument doc) {
        return validator.validate(doc);
    }

    public ValidatedDag validateToDag(DagDocument doc, DagSchemaVersion schemaVersion) {
        return validator.toValidated(doc, schemaVersion);
    }

    public List<ExecutableBranch> expand(ValidatedDag dag, List<ImageDescriptor> images) {
        return expander.expand(dag, images);
    }

    public List<PreflightDifference> preflightGroups(ValidatedDag dag,
                                                       Map<String, Integer> actualGroupCounts) {
        return preflight.preflight(dag, actualGroupCounts);
    }
}