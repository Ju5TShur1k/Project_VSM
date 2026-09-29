package com.vsm.okno;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Login/session/CSRF behaviour of the demo accounts from application.yml.
// Does the CSRF handshake like the SPA (cookie -> X-XSRF-TOKEN header) instead
// of spring-security-test's csrf(), which mutates the shared cached context.
@SpringBootTest
@AutoConfigureMockMvc
class AuthTest {

    @Autowired
    MockMvc mvc;

    Cookie xsrf() throws Exception {
        return mvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    MockHttpServletRequestBuilder post(String url, Cookie xsrf) {
        return MockMvcRequestBuilders.post(url).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }

    @Test
    void anonymousIsRejectedButHealthIsPublic() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                // the SPA needs this cookie before its first POST (the login)
                .andExpect(cookie().exists("XSRF-TOKEN"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/login", xsrf()).param("username", "planner").param("password", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .param("username", "planner").param("password", "planner-demo"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginThenMeThenLogout() throws Exception {
        var session = new MockHttpSession();
        var token = xsrf();
        mvc.perform(post("/api/v1/auth/login", token).session(session)
                        .param("username", "planner").param("password", "planner-demo"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("planner"));
        mvc.perform(post("/api/v1/auth/logout", token).session(session))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    MockHttpSession login(String user, String password, Cookie token) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login", token).session(session)
                        .param("username", user).param("password", password))
                .andExpect(status().isOk());
        return session;
    }

    @Test
    void rolesLimitWhatEachAccountCanDo() throws Exception {
        var token = xsrf();
        var dispatcher = login("dispatcher", "disp-demo", token);
        mvc.perform(get("/api/v1/auth/me").session(dispatcher)).andExpect(jsonPath("$.role").value("DISPATCHER"));
        mvc.perform(get("/api/v1/current-plan").session(dispatcher)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/incidents", token).session(dispatcher).contentType("application/json")
                        .content("{\"train\":\"CASE-01\",\"kind\":\"URGENT_MAINTENANCE\",\"description\":\"стук в тележке\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportedBy").value("dispatcher"));
        mvc.perform(post("/api/v1/planning-jobs", token).session(dispatcher).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/plans/00000000-0000-0000-0000-000000000000/approve", token).session(dispatcher)
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());

        // technologist calculates but does not sign off
        var technologist = login("technologist", "tech-demo", token);
        mvc.perform(post("/api/v1/plans/00000000-0000-0000-0000-000000000000/approve", token).session(technologist)
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());

        // planner proposes, dispatcher decides: neither can take the other's step
        var planner = login("planner", "planner-demo", token);
        String proposal = "/api/v1/proposals/00000000-0000-0000-0000-000000000000";
        mvc.perform(post(proposal + "/approve", token).session(planner).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/scenarios/00000000-0000-0000-0000-000000000000/proposals", token).session(dispatcher)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/operations/00000000-0000-0000-0000-000000000000/replacements", token).session(planner)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
}
