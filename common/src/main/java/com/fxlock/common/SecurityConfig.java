package com.fxlock.common;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig 
{
  @Bean
  UserDetailsService users(Environment e) 
  {
    var b = new BCryptPasswordEncoder();
    
    return new InMemoryUserDetailsManager(
        User.withUsername("alice")
            .password("{bcrypt}" + b.encode(e.getRequiredProperty("fx.auth.alice")))
            .roles("USER")
            .build(),
        User.withUsername("bob")
            .password("{bcrypt}" + b.encode(e.getRequiredProperty("fx.auth.bob")))
            .roles("USER")
            .build(),
        User.withUsername("ops")
            .password("{bcrypt}" + b.encode(e.getRequiredProperty("fx.auth.ops")))
            .roles("OPS")
            .build(),
        User.withUsername("svc")
            .password("{bcrypt}" + b.encode(e.getRequiredProperty("fx.auth.service")))
            .roles("SERVICE")
            .build());
  }

  @Bean
  SecurityFilterChain security(HttpSecurity h) throws Exception 
  {
    return h.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/", "/app.js", "/style.css", "/actuator/health", "/error")
                    .permitAll()
                    .requestMatchers("/internal/**")
                    .hasAnyRole("SERVICE", "OPS")
                    .requestMatchers("/ops/**")
                    .hasRole("OPS")
                    .requestMatchers("/api/**")
                    .hasRole("USER")
                    .anyRequest()
                    .denyAll())
        .httpBasic(Customizer.withDefaults())
        .build();
  }
}




