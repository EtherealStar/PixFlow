package com.pixflow.module.dag.exec;

import com.pixflow.infra.image.op.SetBackgroundSpec;
import java.awt.Color;

public record SetBackgroundBindingSpec(Color color, String imageRef, SetBackgroundSpec.Fit fit) {
}
