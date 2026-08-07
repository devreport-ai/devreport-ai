package ai.devreport.backend.generation.application;

import ai.devreport.backend.generation.domain.GenerationException;
import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.generation.domain.GenerationQueuedEvent;
import ai.devreport.backend.generation.infrastructure.GenerationJobRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.project.application.ProjectService;
import ai.devreport.backend.report.Report;
import ai.devreport.backend.report.ReportDocument;
import ai.devreport.backend.report.ReportService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class GenerationJobService {

	private static final EnumSet<GenerationJob.Status> ACTIVE_STATUSES =
		EnumSet.of(GenerationJob.Status.PENDING, GenerationJob.Status.PROCESSING);

	private final GenerationJobRepository jobs;
	private final ProjectService projects;
	private final ApplicationEventPublisher events;
	private final ReportService reports;
	private final ObjectMapper objectMapper;

	GenerationJobService(GenerationJobRepository jobs, ProjectService projects, ApplicationEventPublisher events,
		ReportService reports, ObjectMapper objectMapper) {
		this.jobs = jobs;
		this.projects = projects;
		this.events = events;
		this.reports = reports;
		this.objectMapper = objectMapper;
	}

	public GenerationJob create(UUID ownerId, UUID projectId, GenerationRequest request) {
		projects.lock(ownerId, projectId);
		if (jobs.existsByProjectIdAndStatusIn(projectId, ACTIVE_STATUSES)) {
			throw alreadyRunning();
		}
		GenerationJob job = new GenerationJob(projectId, request);
		try {
			jobs.saveAndFlush(job);
		} catch (DataIntegrityViolationException exception) {
			if (isActiveJobConstraint(exception)) {
				throw alreadyRunning();
			}
			throw exception;
		}
		events.publishEvent(new GenerationQueuedEvent(job.getId()));
		return job;
	}

	@Transactional(readOnly = true)
	public GenerationJob get(UUID ownerId, UUID jobId) {
		GenerationJob job = jobs.findById(jobId).orElseThrow(GenerationJobService::notFound);
		projects.requireOwned(ownerId, job.getProjectId());
		return job;
	}

	Optional<GenerationRequest> start(UUID jobId) {
		return jobs.findForUpdateById(jobId).filter(job -> job.getStatus() == GenerationJob.Status.PENDING)
			.map(job -> {
				job.start();
				return job.getRequestDocument();
			});
	}

	void complete(UUID jobId, ReportDocument result) {
		jobs.findById(jobId).filter(job -> job.getStatus() == GenerationJob.Status.PROCESSING).ifPresent(job -> {
			Report report = reports.create(job.getProjectId(), objectMapper.valueToTree(result));
			job.complete(result, report.getId());
		});
	}

	void fail(UUID jobId, String code, String message) {
		jobs.findById(jobId).ifPresent(job -> job.fail(code, message));
	}

	List<UUID> recover() {
		jobs.findAllByStatus(GenerationJob.Status.PROCESSING).forEach(job ->
			job.fail("GENERATION_INTERRUPTED", "서버 재시작으로 보고서 생성이 중단되었습니다."));
		return jobs.findAllByStatus(GenerationJob.Status.PENDING).stream().map(GenerationJob::getId).toList();
	}

	private static GenerationException alreadyRunning() {
		return new GenerationException(HttpStatus.CONFLICT, "GENERATION_ALREADY_RUNNING",
			"이 프로젝트에서 이미 보고서를 생성하고 있습니다.");
	}

	private static boolean isActiveJobConstraint(Throwable throwable) {
		for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException constraint
				&& "uq_generation_jobs_active_project".equals(constraint.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

	private static GenerationException notFound() {
		return new GenerationException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND",
			"생성 작업을 찾을 수 없습니다.");
	}
}
