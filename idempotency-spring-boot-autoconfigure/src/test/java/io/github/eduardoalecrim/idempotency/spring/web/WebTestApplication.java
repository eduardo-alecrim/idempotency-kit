// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.Idempotent;
import java.net.URI;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A web application with idempotent handlers, behind Spring Security with HTTP basic authentication. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(WebTestApplication.Orders.class)
class WebTestApplication {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice")
                        .password("{noop}secret")
                        .roles("USER")
                        .build(),
                User.withUsername("bob").password("{noop}secret").roles("USER").build());
    }

    @RestController
    static class Orders {

        final AtomicInteger executions = new AtomicInteger();

        @Idempotent
        @PostMapping("/orders")
        ResponseEntity<String> create(@RequestBody String body, @Nullable Principal principal) {
            int id = executions.incrementAndGet();
            return ResponseEntity.created(URI.create("/orders/" + id))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Set-Cookie", "session=" + id)
                    .header("X-Trace", "trace-" + id)
                    .body("{\"id\":" + id + ",\"owner\":\"" + (principal == null ? "" : principal.getName()) + "\"}");
        }

        @PostMapping("/plain")
        String plain() {
            return "plain-" + executions.incrementAndGet();
        }

        @PostMapping("/legacy")
        String legacy() {
            return "legacy-" + executions.incrementAndGet();
        }

        @Idempotent
        @PostMapping(value = "/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        String form(@RequestParam("name") String name) {
            return "hello " + name + " " + executions.incrementAndGet();
        }

        @Idempotent
        @PostMapping("/big")
        String big() {
            executions.incrementAndGet();
            return "x".repeat(4096);
        }

        @Idempotent
        @PostMapping("/rejected")
        String rejected() {
            executions.incrementAndGet();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rejected");
        }
    }
}
