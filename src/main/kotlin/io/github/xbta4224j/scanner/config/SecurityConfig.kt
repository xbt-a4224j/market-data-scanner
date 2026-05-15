package io.github.xbta4224j.scanner.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * HTTP Basic auth on the admin sub-tree. Everything else (dashboard, SSE,
 * actuator health and prometheus) is public so the demo URL works without
 * a password.
 *
 * Single admin user provisioned from ADMIN_USERNAME and ADMIN_PASSWORD env
 * vars. Password is BCrypt-hashed in memory; never stored cleartext.
 */
@Configuration
class SecurityConfig(
    @Value("\${admin.username:admin}") private val adminUsername: String,
    @Value("\${admin.password:admin}") private val adminPassword: String,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun userDetailsService(encoder: PasswordEncoder): UserDetailsService {
        val admin = User.withUsername(adminUsername)
            .password(encoder.encode(adminPassword))
            .roles("REVIEWER")
            .build()
        return InMemoryUserDetailsManager(admin)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf { it.disable() }  // SSE + htmx posts; CSRF off per dashboard's threat model
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/admin/**").hasRole("REVIEWER")
                    .anyRequest().permitAll()
            }
            .httpBasic { }
            .formLogin { it.disable() }
            .build()
    }
}
