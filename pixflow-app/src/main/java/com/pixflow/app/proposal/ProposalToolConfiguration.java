package com.pixflow.app.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.dag.propose.DagProposalService;
import com.pixflow.module.imagegen.proposal.ImagegenPlanService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProposalToolConfiguration {
    @Bean
    public SubmitImagePlanHandler submitImagePlanHandler(
            DagProposalService planService,
            ProposalService proposalService,
            ObjectMapper objectMapper,
            Clock clock) {
        return new SubmitImagePlanHandler(planService, proposalService, objectMapper, clock);
    }

    @Bean
    public ToolDescriptor submitImagePlanDescriptor(SubmitImagePlanHandler handler) {
        return handler.submitImagePlanDescriptor();
    }

    @Bean
    public SubmitImagegenPlanHandler submitImagegenPlanHandler(
            ImagegenPlanService planService,
            ProposalService proposalService,
            ObjectMapper objectMapper,
            Clock clock) {
        return new SubmitImagegenPlanHandler(planService, proposalService, objectMapper, clock);
    }

    @Bean
    public ToolDescriptor submitImagegenPlanDescriptor(SubmitImagegenPlanHandler handler) {
        return handler.submitImagegenPlanDescriptor();
    }
}
