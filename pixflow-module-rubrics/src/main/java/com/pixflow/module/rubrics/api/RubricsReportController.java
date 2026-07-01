package com.pixflow.module.rubrics.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.rubrics.baseline.RegressionAlertService;
import com.pixflow.module.rubrics.baseline.RegressionComparator;
import com.pixflow.module.rubrics.baseline.RegressionReport;
import com.pixflow.module.rubrics.persistence.RubricsScoreEntity;
import com.pixflow.module.rubrics.persistence.RubricsScoreMapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rubrics")
public class RubricsReportController {
    private final RubricsScoreMapper scoreMapper;
    private final RegressionComparator regressionComparator;
    private final RegressionAlertService alertService;

    public RubricsReportController(
            RubricsScoreMapper scoreMapper,
            RegressionComparator regressionComparator,
            RegressionAlertService alertService) {
        this.scoreMapper = scoreMapper;
        this.regressionComparator = regressionComparator;
        this.alertService = alertService;
    }

    @GetMapping("/runs/{id}/regression")
    public ApiResponse<RegressionReport> regression(@PathVariable long id,
                                                    @RequestParam long baselineRunId,
                                                    @RequestParam(required = false) String templateId) {
        RegressionReport report = regressionComparator.compare(id, baselineRunId);
        if (templateId != null && !templateId.isBlank()) {
            alertService.persistIfDegraded(report, templateId);
        }
        return ApiResponse.ok(report);
    }

    @GetMapping("/scores/by-result/{resultId}")
    public ApiResponse<RubricsScoreEntity> byResult(@PathVariable long resultId) {
        return ApiResponse.ok(scoreMapper.findByResultId(resultId));
    }

    @GetMapping("/scores/by-sku/{skuId}")
    public ApiResponse<List<RubricsScoreEntity>> bySku(@PathVariable String skuId,
                                                       @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(scoreMapper.findBySkuId(skuId, limit));
    }
}
