package com.pixflow.module.vision.analyze;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.vision.error.VisionErrorCode;

public class VisionAnalysisRequestValidator {

    public void validate(VisionAnalysisRequest request) {
        if (request == null || !request.hasImages()) {
            throw new PixFlowException(VisionErrorCode.VISION_EMPTY_REQUEST, "vision request contains no images");
        }
    }
}
