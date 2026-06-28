package com.pixflow.module.commerce.source;

import com.pixflow.module.commerce.query.PeriodType;
import com.pixflow.module.commerce.query.TimeWindow;
import java.util.List;

public record PlatformPullRequest(
        List<String> skuIds,
        TimeWindow window,
        PeriodType periodType,
        String platform) {
}
