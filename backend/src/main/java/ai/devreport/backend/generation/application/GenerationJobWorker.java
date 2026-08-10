package ai.devreport.backend.generation.application;

import java.util.Optional;

import ai.devreport.backend.generation.domain.GenerationQueuedEvent;
import ai.devreport.backend.integration.ai.AiServiceClient;
import ai.devreport.backend.integration.ai.AiServiceException;
import ai.devreport.backend.integration.ai.GenerationBundle;
import ai.devreport.backend.integration.ai.GenerationBundleFactory;
import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.report.domain.ReportDocument;
import ai.devreport.backend.report.domain.ReportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class GenerationJobWorker {

	private static final Logger log = LoggerFactory.getLogger(GenerationJobWorker.class);

	private final GenerationJobService jobs;
	private final AiServiceClient aiService;
	private final GenerationBundleFactory bundles;

	GenerationJobWorker(GenerationJobService jobs, AiServiceClient aiService, GenerationBundleFactory bundles) {
		this.jobs = jobs;
		this.aiService = aiService;
		this.bundles = bundles;
	}

	@Async("generationExecutor")
	void process(GenerationQueuedEvent event) {
		Optional<GenerationRequest> request = jobs.start(event.jobId());
		if (request.isEmpty()) {
			return;
		}
		try {
			ReportDocument result;
			try (GenerationBundle bundle = bundles.create(request.get())) {
				result = aiService.generate(request.get(), bundle);
			}
			jobs.complete(event.jobId(), result);
		} catch (AiServiceException exception) {
			jobs.fail(event.jobId(), exception.code(), exception.getMessage());
		} catch (ReportException exception) {
			jobs.fail(event.jobId(), exception.code(), exception.getMessage());
		} catch (RuntimeException exception) {
			log.error("Unexpected generation failure: jobId={}", event.jobId(), exception);
			jobs.fail(event.jobId(), "GENERATION_FAILED", "보고서 생성에 실패했습니다.");
		}
	}
}
