package com.synctank.platform.config;

import com.synctank.platform.agent.AgentProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Day 07 — the dashboard is the first browser client this service has ever had. Everything
 * before today was curl or a CI runner, where CORS does not apply, so the absence of this
 * file was invisible rather than correct.
 *
 * Scoped to /agent/** and /registry/** only: /specs and /diff take whole OpenAPI documents
 * and have no business being reachable from a web page.
 */
@Configuration
public class DashboardCorsConfig implements WebMvcConfigurer {

    private final String[] origins;

    public DashboardCorsConfig(AgentProperties props) {
        String configured = props.dashboardOrigins();
        this.origins = (configured == null || configured.isBlank())
                ? new String[]{"http://localhost:4200"}
                : configured.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/agent/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");

        registry.addMapping("/registry/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*");

        // Day 08 -- the spec timeline needs to read version history from a browser.
        //
        // Deliberately NOT /specs/**: that would also expose PUT /specs/{repo}/{commit} and
        // PUT /specs/{repo}/baseline, making the published contract baseline overwritable
        // from any page served on an allowed origin. The single star matches exactly one
        // path segment, so this covers /specs/orders-backend/history and nothing else,
        // and GET-only means the write routes stay unreachable even on that path.
        registry.addMapping("/specs/*/history")
                .allowedOrigins(origins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*");
    }
}