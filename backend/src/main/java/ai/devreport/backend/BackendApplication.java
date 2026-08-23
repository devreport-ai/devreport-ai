package ai.devreport.backend;

import ai.devreport.backend.credential.application.AiCredentialProperties;
import ai.devreport.backend.integration.ai.AiModelProperties;
import ai.devreport.backend.usage.application.UsageEventProperties;
import ai.devreport.backend.usage.application.UsageLimitProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({UsageEventProperties.class, UsageLimitProperties.class, AiModelProperties.class,
	AiCredentialProperties.class})
@EnableAsync
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
