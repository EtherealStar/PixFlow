package com.pixflow.module.rubrics.run;

import com.pixflow.module.rubrics.score.RubricScore;

public record ItemEvaluationResult(RubricScore score, String dimensionScoresJson, String explanationJson) {
}
