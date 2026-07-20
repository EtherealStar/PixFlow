package com.pixflow.app.web.auth;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {
    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AuthProperties properties = new AuthProperties();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, properties)).build();
    }

    @Test
    void registerRouteDoesNotExist() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pixflow\",\"password\":\"password-1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousLogoutAlwaysClearsRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        verify(authService).logout(null, null);
    }
}
