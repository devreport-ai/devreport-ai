package ai.devreport.backend.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class GenerationAsyncConfig {

	@Bean("generationExecutor")
	ThreadPoolTaskExecutor generationExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("generation-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

	@Bean("pdfExportExecutor")
	ThreadPoolTaskExecutor pdfExportExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("pdf-export-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

	@Bean("passwordResetEmailExecutor")
	ThreadPoolTaskExecutor passwordResetEmailExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("password-reset-email-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}
}
