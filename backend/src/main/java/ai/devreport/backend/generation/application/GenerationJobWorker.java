package ai.devreport.backend.generation.application;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import ai.devreport.backend.credential.application.AiCredentialCipher;
import ai.devreport.backend.credential.application.AiCredentialService;
import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.generation.domain.GenerationQueuedEvent;
import ai.devreport.backend.integration.ai.AiServiceClient;
import ai.devreport.backend.integration.ai.AiServiceException;
import ai.devreport.backend.integration.ai.GenerationBundle;
import ai.devreport.backend.integration.ai.GenerationBundleFactory;
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
	private final AiCredentialService credentials;
	private final ConcurrentMap<UUID, Thread> running = new ConcurrentHashMap<>();

	GenerationJobWorker(GenerationJobService jobs, AiServiceClient aiService, GenerationBundleFactory bundles,
		AiCredentialService credentials) {
		this.jobs = jobs;
		this.aiService = aiService;
		this.bundles = bundles;
		this.credentials = credentials;
	}

	@Async("generationExecutor")
	void process(GenerationQueuedEvent event) {
		Thread worker = Thread.currentThread();
		if (running.putIfAbsent(event.jobId(), worker) != null) {
			return;
		}
		try {
			Optional<GenerationJobService.StartedJob> started = jobs.start(event.jobId());
			if (started.isEmpty()) {
				return;
			}
			GenerationJobService.StartedJob job = started.get();
			// 사용자 키는 AI 호출 직전에만 복호화하고 로그·잡·예외 어디에도 남기지 않는다.
			String providerApiKey = job.keySource() == GenerationJob.KeySource.USER
				? credentials.resolveApiKey(job.ownerId(), job.request().provider()).orElse(null)
				: null;
			if (job.keySource() == GenerationJob.KeySource.USER && providerApiKey == null) {
				jobs.fail(event.jobId(), "AI_CREDENTIAL_REQUIRED", "등록된 API Key가 없어 보고서를 생성할 수 없습니다.");
				return;
			}
			ReportDocument result;
			try (GenerationBundle bundle = bundles.create(job.request())) {
				result = aiService.generate(job.request(), bundle, providerApiKey);
			}
			jobs.complete(event.jobId(), result);
		} catch (AiCredentialCipher.UnreadableKeyException exception) {
			jobs.fail(event.jobId(), "AI_CREDENTIAL_REQUIRED", "등록된 API Key를 사용할 수 없습니다. 다시 등록해 주세요.");
		} catch (AiServiceException exception) {
			jobs.fail(event.jobId(), exception.code(), exception.getMessage());
		} catch (ReportException exception) {
			jobs.fail(event.jobId(), exception.code(), exception.getMessage());
		} catch (RuntimeException exception) {
			log.error("Unexpected generation failure: jobId={}", event.jobId(), exception);
			jobs.fail(event.jobId(), "GENERATION_FAILED", "보고서 생성에 실패했습니다.");
		} finally {
			running.remove(event.jobId(), worker);
			Thread.interrupted();
		}
	}

	void cancel(UUID jobId) {
		Thread worker = running.get(jobId);
		if (worker != null) {
			worker.interrupt();
		}
	}
}
