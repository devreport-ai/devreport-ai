package ai.devreport.backend.generation.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.generation.application.GenerationJobService;
import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.usage.application.RateLimitService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GenerationController {

	private final GenerationJobService jobs;
	private final RateLimitService rateLimits;

	GenerationController(GenerationJobService jobs, RateLimitService rateLimits) {
		this.jobs = jobs;
		this.rateLimits = rateLimits;
	}

	@PostMapping("/api/projects/{projectId}/generations")
	@ResponseStatus(HttpStatus.ACCEPTED)
	JobIdResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@Valid @RequestBody GenerationRequest request) {
		UUID ownerId = AuthenticatedUser.id(jwt);
		rateLimits.checkGeneration(ownerId);
		return new JobIdResponse(jobs.create(ownerId, projectId, request).getId());
	}

	@GetMapping("/api/generations/{jobId}")
	GenerationJobResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
		return GenerationJobResponse.from(jobs.get(AuthenticatedUser.id(jwt), jobId));
	}

	@DeleteMapping("/api/generations/{jobId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
		jobs.cancel(AuthenticatedUser.id(jwt), jobId);
	}

	record JobIdResponse(UUID jobId) {
	}

	record GenerationJobResponse(UUID jobId, GenerationJob.Status status, int progress,
		GenerationJob.Stage currentStage, UUID reportId, String failureCode, String failureMessage,
		Instant createdAt, Instant startedAt, Instant completedAt) {
		static GenerationJobResponse from(GenerationJob job) {
			return new GenerationJobResponse(job.getId(), job.getStatus(), job.getProgress(),
				job.getCurrentStage(), job.getReportId(), job.getFailureCode(), job.getFailureMessage(), job.getCreatedAt(),
				job.getStartedAt(), job.getCompletedAt());
		}
	}
}
