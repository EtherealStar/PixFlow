package com.pixflow.app.web.vision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pixflow.module.vision.api.AnalysisStatus;
import com.pixflow.module.vision.api.ReanalyzeVisualFactsCommand;
import com.pixflow.module.vision.api.ReplaceVisualFactsCommand;
import com.pixflow.module.vision.api.VisualFactsAdministrationService;
import com.pixflow.module.vision.api.VisualFactsView;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VisionFactsControllerTest {
    private VisualFactsAdministrationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(VisualFactsAdministrationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new VisionFactsController(service)).build();
    }

    @Test
    void exposesFactsStatusSeparatelyFromNullableFacts() throws Exception {
        when(service.get(7L, "SKU-1")).thenReturn(view());

        mockMvc.perform(get("/api/vision/packages/7/skus/SKU-1/facts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.facts").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void projectsReplaceAndReanalyzeCommands() throws Exception {
        when(service.replace(any(Long.class), any(String.class), any(ReplaceVisualFactsCommand.class)))
                .thenReturn(view());
        when(service.reanalyze(any(Long.class), any(String.class), any(ReanalyzeVisualFactsCommand.class)))
                .thenReturn(view());
        String emptyFacts = "{\"common\":{\"categoryAppearance\":\"\",\"dominantColors\":[],"
                + "\"visibleMaterials\":[],\"shapes\":[],\"visibleComponents\":[],\"patterns\":[],"
                + "\"visibleText\":[],\"background\":\"\",\"viewTypes\":[]},\"attributes\":[],"
                + "\"limitations\":[],\"conflicts\":[]}";

        mockMvc.perform(put("/api/vision/packages/7/skus/SKU-1/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"facts\":" + emptyFacts + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/vision/packages/7/skus/SKU-1/reanalyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedGeneration\":2,\"requestId\":\"request-1\"}"))
                .andExpect(status().isOk());

        verify(service).replace(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("SKU-1"), any(ReplaceVisualFactsCommand.class));
        verify(service).reanalyze(7L, "SKU-1", new ReanalyzeVisualFactsCommand(2L, "request-1"));
    }

    private static VisualFactsView view() {
        return new VisualFactsView(7L, "SKU-1", AnalysisStatus.RUNNING, 2L,
                null, 0L, null, Instant.parse("2026-07-19T10:00:00Z"), null);
    }
}
