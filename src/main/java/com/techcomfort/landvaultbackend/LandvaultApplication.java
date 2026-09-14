package com.techcomfort.landvaultbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration excluded: SecurityConfig's stateless
// JWT filter is the only authentication mechanism — without this exclusion,
// Boot still stands up an unused in-memory user (and logs a generated
// password) since no UserDetailsService bean is defined anywhere.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class LandvaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(LandvaultApplication.class, args);
    }

}
