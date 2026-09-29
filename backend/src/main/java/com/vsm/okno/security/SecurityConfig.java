package com.vsm.okno.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import jakarta.servlet.Filter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

import java.util.UUID;

// Session-cookie login for the SPA: POST /api/v1/auth/login (form params
// username/password), POST /api/v1/auth/logout, GET /api/v1/auth/me.
// Everything under /api/v1 requires a session; /actuator/health stays public
// for container health checks. State-changing calls need the CSRF header
// (X-XSRF-TOKEN, value from the XSRF-TOKEN cookie).
@Configuration
@EnableConfigurationProperties(SecurityConfig.AuthProps.class)
public class SecurityConfig {

    @ConfigurationProperties("okno.auth")
    public record AuthProps(java.util.Map<String, String> users) {}

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/v1/scenarios/*/rule-versions").hasRole("TECHNOLOGIST")
                        .requestMatchers(HttpMethod.POST,"/api/v1/scenarios/*/source-fact-versions").hasRole("TECHNOLOGIST")
                        .requestMatchers(HttpMethod.POST,"/api/v1/scenarios/*/requests").hasAnyRole("DISPATCHER","PLANNER")
                        .requestMatchers(HttpMethod.POST,"/api/v1/change-requests").hasAnyRole("DISPATCHER","PLANNER","TECHNOLOGIST")
                        .requestMatchers(HttpMethod.GET,"/api/v1/requests/**","/api/v1/change-requests/**","/api/v1/scenarios/**","/api/v1/planning-jobs/*").hasAnyRole("DISPATCHER","PLANNER","TECHNOLOGIST")
                        // Reading/reporting a need is distinct from calculating or signing off.
                        .requestMatchers("/api/v1/auth/**", "/api/v1/incidents", "/api/v1/current-plan").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/plans/*", "/api/v1/plans/*/calendar", "/api/v1/plans/*/export").authenticated()
                        // Only the planner signs a plan off.
                        .requestMatchers(HttpMethod.POST, "/api/v1/plans/*/approve").hasRole("PLANNER")
                        // Loading data and calculating: planner and technologist.
                        .anyRequest().hasAnyRole("PLANNER", "TECHNOLOGIST"))
                .csrf(c -> c.spa())
                // The CSRF token is deferred: without reading it here the XSRF-TOKEN
                // cookie is never written on the first (401) response, and the
                // SPA's login POST would be rejected with 403.
                .addFilterAfter((Filter) (req, res, chain) -> {
                    if (req.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken t) t.getToken();
                    chain.doFilter(req, res);
                }, CsrfFilter.class)
                .formLogin(f -> f
                        .loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()))
                        .failureHandler((req, res, ex) -> unauthorized(res)))
                .logout(l -> l
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> unauthorized(res)))
                .build();
    }

    // Same error shape as ApiExceptionHandler; the filter chain runs before
    // controller advice, so 401s have to be written here.
    private static void unauthorized(jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\",\"traceId\":\""
                + UUID.randomUUID() + "\",\"details\":[]}");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // ponytail: users live in config and are BCrypt-hashed at startup; the role is the
    // demo login itself (planner/technologist/dispatcher). Swap for a users table with
    // a role column (D1) when real accounts are needed.
    @Bean
    UserDetailsService userDetailsService(AuthProps props, PasswordEncoder encoder) {
        var users = props.users().entrySet().stream()
                .map(u -> User.withUsername(u.getKey()).password(encoder.encode(u.getValue()))
                        .roles(u.getKey().toUpperCase(java.util.Locale.ROOT)).build())
                .toList();
        return new InMemoryUserDetailsManager(users);
    }
}
