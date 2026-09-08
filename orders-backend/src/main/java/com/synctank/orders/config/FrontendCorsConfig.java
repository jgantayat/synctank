package com.synctank.orders.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The Angular app calls this service directly at :8080 (BASE_PATH in app.config.ts),
 * so it is a cross-origin caller. Day 07 added the equivalent for contract-platform
 * on :8081 -- this service never got one, which is why the order-detail panel has
 * been silently blank in the browser while its unit test passed.
 */
@Configuration
public class FrontendCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:4200")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}