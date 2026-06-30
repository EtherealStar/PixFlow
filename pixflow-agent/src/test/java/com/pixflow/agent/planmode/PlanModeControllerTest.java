package com.pixflow.agent.planmode;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.harness.loop.RuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanModeControllerTest {

    private PlanModeController controller;
    private RuntimeState state;

    @BeforeEach
    void setUp() {
        controller = new PlanModeController(new AgentProperties());
        state = new RuntimeState();
    }

    @Test
    void initial_state_is_OFF() {
        assertEquals(PlanModeState.OFF, controller.readPlanMode(state));
    }

    @Test
    void enter_changes_state_to_ACTIVE() {
        controller.enter(state);
        assertEquals(PlanModeState.ACTIVE, controller.readPlanMode(state));
    }

    @Test
    void double_enter_throws() {
        controller.enter(state);
        assertThrows(IllegalStateException.class, () -> controller.enter(state));
    }

    @Test
    void exit_changes_state_to_OFF() {
        controller.enter(state);
        controller.exit(state, "draft plan content");
        assertEquals(PlanModeState.OFF, controller.readPlanMode(state));
    }

    @Test
    void exit_without_active_throws() {
        assertThrows(IllegalStateException.class, () -> controller.exit(state, "draft"));
    }

    @Test
    void exit_saves_draft_plan() {
        controller.enter(state);
        controller.exit(state, "my draft");
        Object draft = state.metadataOrDefault("lastPlanDraft", null);
        assertEquals("my draft", draft);
    }
}