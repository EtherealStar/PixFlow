package com.pixflow.module.dag.exec;

import static org.assertj.core.api.Assertions.assertThat;
import com.pixflow.module.dag.ir.PixelTool;
import org.junit.jupiter.api.Test;

class StepBindingRegistryTest {
    @Test
    void everyPixelToolHasExactlyOneBinding() {
        StepBindingRegistry registry = new StepBindingRegistry();
        assertThat(PixelTool.values()).allSatisfy(tool -> assertThat(registry.require(tool)).isNotNull());
    }
}
