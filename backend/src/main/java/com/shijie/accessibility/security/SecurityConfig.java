package com.shijie.accessibility.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {
    @org.springframework.beans.factory.annotation.Value("${app.hosting.worker-only:false}") private boolean workerOnly;
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,AuthService users) throws Exception {
        if(workerOnly)return http.csrf(value->value.disable()).authorizeHttpRequests(value->value.requestMatchers("/actuator/health").permitAll().anyRequest().denyAll()).build();
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("SHIJIE_PLATFORM_XSRF");
        csrf.setCookiePath("/");
        http
                .addFilterAfter(new SessionVersionFilter(users),org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterAfter(new com.shijie.accessibility.service.IdempotencyRequestFilter(),org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .csrf(value -> value.csrfTokenRepository(csrf))
                .cors(value -> {})
                .sessionManagement(value -> value.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(value -> value
                        .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/auth/captcha", "/api/auth/register", "/actuator/health", "/assets/**", "/", "/index.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(value -> value.disable())
                .httpBasic(value -> value.disable())
                .logout(value -> value.disable())
                .exceptionHandling(value -> value.authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"请先登录\"}");
                }));
        return http.build();
    }
}
