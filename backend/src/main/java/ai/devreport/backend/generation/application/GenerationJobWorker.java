package ai.devreport.backend.generation.application;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
		GenerationJob.KeySource keySource = GenerationJob.KeySource.SERVER;
		try {
			Optional<GenerationJobService.StartedJob> started = jobs.start(event.jobId());
			if (started.isEmpty()) {
				return;
			}
			GenerationJobService.StartedJob job = started.get();
			keySource = job.keySource();
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
		} catch (AiServiceException exception) {
			fail(event.jobId(), keySource, exception);
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

	/**
	 * 서버 키로 실행한 작업에서 provider가 키를 거부하거나 한도를 넘긴 것은 사용자 잘못이 아니다.
	 * 사용자에게 "등록한 키를 확인하라"고 안내하지 않도록 서비스 장애 코드로 바꾼다.
	 */
	private void fail(UUID jobId, GenerationJob.KeySource keySource, AiServiceException exception) {
		if (keySource == GenerationJob.KeySource.SERVER) {
			switch (exception.code()) {
				case "AI_CREDENTIAL_INVALID" -> {
					log.error("Server provider key was rejected: jobId={}", jobId);
					jobs.fail(jobId, "AI_SERVICE_UNAVAILABLE", "AI 서비스에 연결할 수 없습니다.");
					return;
				}
				case "PROVIDER_QUOTA_EXCEEDED" -> {
					log.warn("Server provider quota exhausted: jobId={}", jobId);
					jobs.fail(jobId, "GENERATION_CAPACITY_EXCEEDED",
						"AI 생성 요청이 많아 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
					return;
				}
				default -> {
					// 그대로 전달
				}
			}
		}
		jobs.fail(jobId, exception.code(), exception.getMessage());
	}

	void cancel(UUID jobId) {
		Thread worker = running.get(jobId);
		if (worker != null) {
			worker.interrupt();
		}
	}
}
