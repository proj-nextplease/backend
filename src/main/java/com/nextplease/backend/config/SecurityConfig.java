package com.nextplease.backend.config;

import com.nextplease.backend.security.SupabaseJwtAuthenticationConverter;
import com.nextplease.backend.security.ratelimit.RateLimitFilter;
import com.nextplease.backend.security.ratelimit.RateLimitProperties;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({AppCorsProperties.class, RateLimitProperties.class})
public class SecurityConfig {

    private final AppCorsProperties corsProperties;
    private final boolean jwtEnabled;
    private final SupabaseJwtAuthenticationConverter jwtAuthenticationConverter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(
            AppCorsProperties corsProperties,
            @Value("${app.security.jwt.enabled:false}") boolean jwtEnabled,
            SupabaseJwtAuthenticationConverter jwtAuthenticationConverter,
            RateLimitFilter rateLimitFilter
    ) {
        this.corsProperties = corsProperties;
        this.jwtEnabled = jwtEnabled;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Throttle abusive traffic after authorization so the authenticated
                // principal (when present) can key per-user write limits.
                .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);

        if (jwtEnabled) {
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/jobs/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/skills").permitAll()
                            // Public discovery (read-only). Write actions on these resources
                            // stay authenticated via their own POST/PUT/PATCH/DELETE matchers.
                            .requestMatchers(HttpMethod.GET, "/api/v1/companies", "/api/v1/companies/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/quests", "/api/v1/quests/exp-config").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/profiles/*/public").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/candidates/register/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/b2b/register").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/company/invitations/preview").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/company/invitations/register").permitAll()
                            .requestMatchers("/actuator/health").permitAll()
                            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                            .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }

    /**
     * Prevent Spring Boot from auto-registering {@link RateLimitFilter} into the servlet
     * chain — we add it explicitly inside the Security filter chain above, so without this
     * it would run twice and double-count requests.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> allowedOrigins() {
        if (corsProperties.allowedOrigins() == null || corsProperties.allowedOrigins().isBlank()) {
            return List.of("http://localhost:5173");
        }

        return Arrays.stream(corsProperties.allowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }
}
