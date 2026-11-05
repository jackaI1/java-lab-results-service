package com.jacklai.labresults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean
    UserDetailsService users(@Value("${lab.security.reader-password}") String reader,
                             @Value("${lab.security.technician-password}") String technician) {
        if (reader.length() < 12 || technician.length() < 12 || reader.equals(technician)) {
            throw new IllegalStateException("Set distinct READER_PASSWORD and TECHNICIAN_PASSWORD, each at least 12 characters");
        }
        var encoder = new BCryptPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("reader").password("{bcrypt}" + encoder.encode(reader)).roles("READER").build(),
                User.withUsername("technician").password("{bcrypt}" + encoder.encode(technician)).roles("READER", "TECHNICIAN").build());
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/results", "/api/results/**").hasRole("READER")
                        .requestMatchers(HttpMethod.POST, "/api/results", "/api/results/*/corrections").hasRole("TECHNICIAN")
                        .anyRequest().denyAll())
                .build();
    }
}
