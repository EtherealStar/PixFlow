package com.pixflow.module.rubrics.automation;
import com.pixflow.module.rubrics.template.TemplateLifecycle;
public final class AutomationAdmissionPolicy {
 public boolean allows(boolean enabled, TemplateLifecycle lifecycle){return enabled && lifecycle!=TemplateLifecycle.EXPERIMENTAL;}
}
