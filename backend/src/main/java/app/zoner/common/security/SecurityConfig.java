package app.zoner.common.security;

import app.zoner.common.ratelimit.AuthRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final ApiAuthEntryPoint entryPoint;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, AuthRateLimitFilter authRateLimitFilter,
                          ApiAuthEntryPoint entryPoint) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.entryPoint = entryPoint;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/_probe/**").permitAll()
                .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                .anyRequest().authenticated())
            // jwtAuthFilter를 먼저 등록해야 그 클래스를 기준으로 다른 필터를 앞에 둘 수 있다
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(authRateLimitFilter, JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
