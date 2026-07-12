package com.pixflow.module.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.dag.exec.DefaultDagCompiler;
import com.pixflow.module.dag.exec.StepSpecCompiler;
import com.pixflow.module.dag.exec.TypedExecutionPlan;
import com.pixflow.module.dag.ir.CanonicalDagFactory;
import com.pixflow.module.dag.ir.DagJsonReader;
import com.pixflow.module.dag.ir.DagSchemaVersion;

public final class TestPlans {
    private TestPlans() {}

    public static TypedExecutionPlan compile(String json) {
        var document = new DagJsonReader().read(json);
        var canonical = new CanonicalDagFactory(new ObjectMapper())
                .fromDocument(document, new DagSchemaVersion("1.0"));
        return new DefaultDagCompiler(new StepSpecCompiler()).compile(canonical);
    }
}
