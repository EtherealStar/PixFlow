package com.pixflow.module.rubrics.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.rubrics.baseline.BaselineService;
import com.pixflow.module.rubrics.persistence.RubricsAlertEntity;
import com.pixflow.module.rubrics.persistence.RubricsAlertMapper;
import com.pixflow.module.rubrics.persistence.RubricsBaselineEntity;
import com.pixflow.module.rubrics.run.EvaluationRunner;
import com.pixflow.module.rubrics.run.ManualRunRequest;
import com.pixflow.module.rubrics.run.RubricsRunView;
import com.pixflow.module.rubrics.template.RubricTemplate;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rubrics")
public class RubricsAdminController {
    private final TemplateRegistry templateRegistry;
    private final EvaluationRunner runner;
    private final BaselineService baselineService;
    private final RubricsAlertMapper alertMapper;
    private final Clock clock;

    public RubricsAdminController(
            TemplateRegistry templateRegistry,
            EvaluationRunner runner,
            BaselineService baselineService,
            RubricsAlertMapper alertMapper,
            Clock clock) {
        this.templateRegistry = templateRegistry;
        this.runner = runner;
        this.baselineService = baselineService;
        this.alertMapper = alertMapper;
        this.clock = clock;
    }

    @GetMapping("/templates")
    public ApiResponse<List<RubricTemplate>> templates() {
        return ApiResponse.ok(templateRegistry.list());
    }

    @GetMapping("/templates/{id}/versions")
    public ApiResponse<List<RubricTemplate>> versions(@PathVariable String id) {
        return ApiResponse.ok(templateRegistry.versions(id));
    }

    @PostMapping("/runs")
    public ApiResponse<RubricsRunView> startRun(@RequestBody ManualRunRequest request) {
        return ApiResponse.ok(runner.startManual(request));
    }

    @GetMapping("/runs")
    public ApiResponse<List<RubricsRunView>> runs(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(runner.list(limit));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<RubricsRunView> run(@PathVariable long id) {
        return ApiResponse.ok(runner.view(id));
    }

    @PostMapping("/baselines")
    public ApiResponse<RubricsBaselineEntity> createBaseline(@RequestBody BaselineRequest request) {
        return ApiResponse.ok(baselineService.create(request.name(), request.runId()));
    }

    @GetMapping("/baselines")
    public ApiResponse<RubricsBaselineEntity> activeBaseline(@RequestParam String templateId) {
        return ApiResponse.ok(baselineService.active(templateId));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<RubricsAlertEntity>> alerts(@RequestParam(defaultValue = "false") boolean acknowledged,
                                                        @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(alertMapper.findByAcknowledged(acknowledged, limit));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ApiResponse<Void> acknowledgeAlert(@PathVariable long id) {
        alertMapper.acknowledge(id, clock.instant());
        return ApiResponse.ok(null);
    }

    public record BaselineRequest(String name, long runId) {
    }
}
