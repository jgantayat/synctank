package com.synctank.platform;

import com.synctank.platform.config.secrets.SecretsInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class ContractPlatformApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(ContractPlatformApplication.class);

		// Day 09 — runs inside prepareContext(), before any bean definition is bound, which is
		// the only window in which a fetched credential can still reach AgentProperties and
		// S3Config. Wired here rather than through META-INF/spring.factories so the mechanism
		// is visible in the file everyone opens first, and cannot be broken by a change in
		// framework component discovery.
		application.addInitializers(new SecretsInitializer());

		application.run(args);
	}
}