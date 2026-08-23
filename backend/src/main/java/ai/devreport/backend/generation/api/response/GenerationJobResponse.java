package ai.devreport.backend.generation.api.response;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.integration.ai.AiProvider;

public record GenerationJobResponse(UUID jobId, GenerationJob.Status status, int progress,
	GenerationJob.Stage currentStage, AiProvider provider, String model, UUID reportId, String failureCode,
	String failureMessage, Instant createdAt, Instant startedAt, Instant completedAt) {
	public static GenerationJobResponse from(GenerationJob job) {
		return new GenerationJobResponse(job.getId(), job.getStatus(), job.getProgress(),
			job.getCurrentStage(), job.getProvider(), job.getModel(), job.getReportId(), job.getFailureCode(),
			job.getFailureMessage(), job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt());
	}
}
