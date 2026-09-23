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
}
