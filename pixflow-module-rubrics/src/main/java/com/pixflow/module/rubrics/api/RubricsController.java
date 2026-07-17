package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.template.LoadedTemplate;
import com.pixflow.module.rubrics.template.TemplateRegistry;
import jakarta.validation.Valid;
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
public class RubricsController {
 private final RubricsEvaluationService service; private final TemplateRegistry templates;
 public RubricsController(RubricsEvaluationService service,TemplateRegistry templates){this.service=service;this.templates=templates;}
 @GetMapping("/templates") public List<LoadedTemplate> templates(){return templates.list();}
 @GetMapping("/templates/{id}/versions") public List<LoadedTemplate> versions(@PathVariable String id){return templates.versions(id);}
 @PostMapping("/runs") public RubricsRunView start(@Valid @RequestBody RunEvaluationCommand command){return service.start(command);}
 @PostMapping("/runs/{id}/resume") public RubricsRunView resume(@PathVariable long id){return service.resume(id);}
 @GetMapping("/runs/{id}") public RubricsRunView run(@PathVariable long id){return service.getRun(id);}
 @GetMapping("/evaluations/{id}") public EvaluationView evaluation(@PathVariable long id){return service.getEvaluation(id);}
 @GetMapping("/evaluations/by-subject/{type}/{subjectId}") public List<EvaluationSummaryView> history(@PathVariable SubjectType type,@PathVariable String subjectId){return service.history(type,subjectId);}
}
