package com.pixflow.module.vision;

import com.pixflow.module.vision.analyze.VisionAnalysisRequest;
import com.pixflow.module.vision.analyze.VisionAnalysisResult;

/**
 * 视觉理解能力门面。同步执行一次小批量看图分析，不持有 Agent 循环。
 */
public interface VisionService {
    VisionAnalysisResult analyze(VisionAnalysisRequest request);
}
