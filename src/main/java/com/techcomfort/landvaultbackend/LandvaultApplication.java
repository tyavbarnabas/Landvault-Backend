package com.techcomfort.landvaultbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
public class LandvaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(LandvaultApplication.class, args);
    }

}
