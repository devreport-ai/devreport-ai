package ai.devreport.backend.project;

import java.util.Optional;

import ai.devreport.backend.ai.AiServiceClient;
import ai.devreport.backend.ai.AiServiceException;
import ai.devreport.backend.ai.GenerationRequest;
import ai.devreport.backend.ai.ReportDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class GenerationJobWorker {

	private static final Logger log = LoggerFactory.getLogger(GenerationJobWorker.class);

	private final GenerationJobService jobs;
	private final AiServiceClient aiService;

	GenerationJobWorker(GenerationJobService jobs, AiServiceClient aiService) {
		this.jobs = jobs;
		this.aiService = aiService;
	}

	@Async("generationExecutor")
	void process(GenerationQueuedEvent event) {
		Optional<GenerationRequest> request = jobs.start(event.jobId());
		if (request.isEmpty()) {
			return;
		}
		try {
			ReportDocument result = aiService.generate(request.get());
			jobs.complete(event.jobId(), result);
		} catch (AiServiceException exception) {
			jobs.fail(event.jobId(), exception.code(), exception.getMessage());
		} catch (RuntimeException exception) {
			log.error("Unexpected generation failure: jobId={}", event.jobId(), exception);
			jobs.fail(event.jobId(), "GENERATION_FAILED", "보고서 생성에 실패했습니다.");
		}
	}
}
