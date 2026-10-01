package com.orchard.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class MallApplication {
    public static void main(String[] args) { SpringApplication.run(MallApplication.class, args); }
}
