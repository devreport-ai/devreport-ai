package ai.devreport.backend.generation.application;

import ai.devreport.backend.generation.domain.GenerationException;
import ai.devreport.backend.generation.domain.GenerationCanceledEvent;
import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.generation.domain.GenerationQueuedEvent;
import ai.devreport.backend.generation.infrastructure.GenerationJobRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.devreport.backend.credential.application.AiCredentialService;
import ai.devreport.backend.integration.ai.AiModelCatalog;
import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.project.application.ProjectService;
import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.domain.ReportDocument;
import ai.devreport.backend.report.application.ReportService;
import ai.devreport.backend.upload.application.ProjectFileService;
import ai.devreport.backend.usage.application.UsageEventService;
import ai.devreport.backend.usage.application.UsageLimitService;
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
	private final ProjectFileService files;
	private final UsageEventService usageEvents;
	private final UsageLimitService usageLimits;
	private final AiModelCatalog models;
	private final AiCredentialService credentials;
	private final ObjectMapper objectMapper;

	GenerationJobService(GenerationJobRepository jobs, ProjectService projects, ApplicationEventPublisher events,
		ReportService reports, ProjectFileService files, UsageEventService usageEvents,
		UsageLimitService usageLimits, AiModelCatalog models, AiCredentialService credentials,
		ObjectMapper objectMapper) {
		this.jobs = jobs;
		this.projects = projects;
		this.events = events;
		this.reports = reports;
		this.files = files;
		this.usageEvents = usageEvents;
		this.usageLimits = usageLimits;
		this.models = models;
		this.credentials = credentials;
		this.objectMapper = objectMapper;
	}

	public GenerationJob create(UUID ownerId, UUID projectId, GenerationRequest request) {
		projects.lock(ownerId, projectId);
		if (request.fileIds().stream().distinct().count() != request.fileIds().size()) {
			throw invalidRequest();
		}
		AiModelCatalog.AiModel model = resolveModel(request);
		files.requireAvailable(projectId, request.fileIds());
		if (jobs.existsByProjectIdAndStatusIn(projectId, ACTIVE_STATUSES)) {
			throw alreadyRunning();
		}
		// 키 삭제(AiCredentialService.delete)와 같은 사용자 행 잠금을 먼저 잡아 keySource 판정과 저장을 직렬화한다.
		usageLimits.lockUser(ownerId);
		// 복호화 가능한 사용자 키가 있으면 서버 기본 모델이라도 사용자 키로 실행한다. 서버 키는 기본 모델에만 쓴다.
		// 복호화할 수 없는 키(마스터 키 분실)는 없는 것으로 보아 기본 모델이 계속 동작하게 한다.
		GenerationJob.KeySource keySource = credentials.isUsable(ownerId, model.provider())
			? GenerationJob.KeySource.USER : GenerationJob.KeySource.SERVER;
		if (keySource == GenerationJob.KeySource.SERVER && !model.serverDefault()) {
			throw credentialRequired();
		}
		usageLimits.checkGeneration(ownerId, keySource == GenerationJob.KeySource.SERVER);
		GenerationJob job = new GenerationJob(projectId, request.withModel(model.provider(), model.model()), keySource);
		try {
			jobs.saveAndFlush(job);
		} catch (DataIntegrityViolationException exception) {
			if (isActiveJobConstraint(exception)) {
				throw alreadyRunning();
			}
			throw exception;
		}
		usageEvents.generationRequested(ownerId, job);
		events.publishEvent(new GenerationQueuedEvent(job.getId()));
		return job;
	}

	@Transactional(readOnly = true)
	public GenerationJob get(UUID ownerId, UUID jobId) {
		GenerationJob job = jobs.findById(jobId).orElseThrow(GenerationJobService::notFound);
		projects.requireOwned(ownerId, job.getProjectId());
		return job;
	}

	public void cancel(UUID ownerId, UUID jobId) {
		GenerationJob job = jobs.findForUpdateById(jobId).orElseThrow(GenerationJobService::notFound);
		projects.requireOwned(ownerId, job.getProjectId());
		if (job.cancel()) {
			events.publishEvent(new GenerationCanceledEvent(jobId));
		}
	}

	Optional<StartedJob> start(UUID jobId) {
		return jobs.findForUpdateById(jobId).filter(job -> job.getStatus() == GenerationJob.Status.PENDING)
			.map(job -> {
				job.start();
				return new StartedJob(job.getRequestDocument(), projects.ownerIdOf(job.getProjectId()),
					job.getKeySource());
			});
	}

	void complete(UUID jobId, ReportDocument result) {
		jobs.findById(jobId).filter(job -> job.getStatus() == GenerationJob.Status.PROCESSING).ifPresent(job -> {
			Report report = reports.create(job.getProjectId(), objectMapper.valueToTree(result));
			job.complete(result, report.getId());
			usageEvents.generationCompleted(job);
		});
	}

	void fail(UUID jobId, String code, String message) {
		jobs.findById(jobId).filter(job -> job.getStatus() == GenerationJob.Status.PENDING
			|| job.getStatus() == GenerationJob.Status.PROCESSING).ifPresent(job -> {
			job.fail(code, message);
			usageEvents.generationFailed(job, code);
		});
	}

	List<UUID> recover() {
		jobs.findAllByStatus(GenerationJob.Status.PROCESSING).forEach(job -> {
			job.fail("GENERATION_INTERRUPTED", "서버 재시작으로 보고서 생성이 중단되었습니다.");
			usageEvents.generationFailed(job, "GENERATION_INTERRUPTED");
		});
		return jobs.findAllByStatus(GenerationJob.Status.PENDING).stream().map(GenerationJob::getId).toList();
	}

	private static GenerationException alreadyRunning() {
		return new GenerationException(HttpStatus.CONFLICT, "GENERATION_ALREADY_RUNNING",
			"이 프로젝트에서 이미 보고서를 생성하고 있습니다.");
	}

	private AiModelCatalog.AiModel resolveModel(GenerationRequest request) {
		if (request.provider() == null && request.model() == null) {
			return models.serverDefault();
		}
		if (request.provider() == null || request.model() == null) {
			throw invalidRequest();
		}
		return models.find(request.provider(), request.model()).orElseThrow(GenerationJobService::modelNotAllowed);
	}

	private static GenerationException modelNotAllowed() {
		return new GenerationException(HttpStatus.BAD_REQUEST, "AI_MODEL_NOT_ALLOWED",
			"선택할 수 없는 provider 또는 모델입니다.");
	}

	private static GenerationException credentialRequired() {
		return new GenerationException(HttpStatus.BAD_REQUEST, "AI_CREDENTIAL_REQUIRED",
			"이 모델을 사용하려면 해당 provider의 API Key를 등록해야 합니다. 등록한 키가 있다면 다시 등록해 주세요.");
	}

	private static GenerationException invalidRequest() {
		return new GenerationException(HttpStatus.BAD_REQUEST, "GENERATION_REQUEST_INVALID",
			"보고서 생성 요청이 올바르지 않습니다.");
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

	/** 워커가 실행에 필요한 최소 정보. 사용자 키 원문은 포함하지 않고 워커가 호출 직전에 복호화한다. */
	record StartedJob(GenerationRequest request, UUID ownerId, GenerationJob.KeySource keySource) {
	}
}
