package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.ir.CanonicalDag;

@FunctionalInterface public interface DagCompiler { TypedExecutionPlan compile(CanonicalDag dag); }
