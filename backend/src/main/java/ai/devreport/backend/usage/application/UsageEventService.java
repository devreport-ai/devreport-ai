package ai.devreport.backend.usage.application;

import ai.devreport.backend.export.domain.ReportExport;
import ai.devreport.backend.project.domain.Project;
import ai.devreport.backend.project.infrastructure.ProjectRepository;
import ai.devreport.backend.report.domain.Report;
import ai.devreport.backend.report.infrastructure.ReportRepository;
import ai.devreport.backend.generation.domain.GenerationJob;
import ai.devreport.backend.upload.domain.UploadedFile;
import ai.devreport.backend.usage.domain.UsageEvent;
import ai.devreport.backend.usage.domain.UsageEventType;
import ai.devreport.backend.usage.infrastructure.UsageEventRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UsageEventService {

	private static final Set<String> SAFE_FAILURE_CODES = Set.of(
		"AI_SERVICE_ERROR",
		"AI_SERVICE_INVALID_RESPONSE",
		"AI_SERVICE_TIMEOUT",
		"AI_SERVICE_UNAVAILABLE",
		"GENERATION_CAPACITY_EXCEEDED",
		"GENERATION_FAILED",
		"GENERATION_INTERRUPTED",
		"REPORT_DOCUMENT_INVALID"
	);
	private static final Set<String> SAFE_CONTENT_TYPES = Set.of(
		"application/pdf",
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
		"text/plain",
		"text/markdown",
		"application/zip",
		"application/x-zip-compressed",
		"image/jpeg",
		"image/png"
	);

	private final UsageEventRepository events;
	private final ProjectRepository projects;
	private final ReportRepository reports;
	private final Duration retention;

	UsageEventService(UsageEventRepository events, ProjectRepository projects, ReportRepository reports,
		UsageEventProperties properties) {
		this.events = events;
		this.projects = projects;
		this.reports = reports;
		this.retention = properties.getRetention();
	}

	public void projectCreated(UUID userId, Project project) {
		record(UsageEventType.PROJECT_CREATED, key("project-created", project.getId()), userId,
			project.getId(), null, null, null, null, project.getCreatedAt(), Map.of());
	}

	public void fileUploaded(UUID userId, UploadedFile file) {
		Map<String, Object> metadata = SAFE_CONTENT_TYPES.contains(file.getContentType())
			? Map.of("contentType", file.getContentType(), "sizeBytes", file.getSize())
			: Map.of("sizeBytes", file.getSize());
		record(UsageEventType.FILE_UPLOADED, key("file-uploaded", file.getId()), userId,
			file.getProjectId(), file.getId(), null, null, null, file.getCreatedAt(), metadata);
	}

	public void generationRequested(UUID userId, GenerationJob job) {
		record(UsageEventType.GENERATION_REQUESTED, key("generation-requested", job.getId()), userId,
			job.getProjectId(), null, null, job.getId(), null, job.getCreatedAt(),
			Map.of("fileCount", job.getRequestDocument().fileIds().size()));
	}

	public void generationCompleted(GenerationJob job) {
		projects.findById(job.getProjectId()).ifPresent(project -> record(
			UsageEventType.GENERATION_COMPLETED,
			key("generation-completed", job.getId()),
			project.getOwnerId(), job.getProjectId(), null, job.getReportId(), job.getId(), null,
			orNow(job.getCompletedAt()), Map.of()));
	}

	public void generationFailed(GenerationJob job, String failureCode) {
		projects.findById(job.getProjectId()).ifPresent(project -> record(
			UsageEventType.GENERATION_FAILED,
			key("generation-failed", job.getId()),
			project.getOwnerId(), job.getProjectId(), null, null, job.getId(), null,
			orNow(job.getCompletedAt()), failureMetadata(failureCode)));
	}

	public void reportEdited(UUID userId, Report report, long previousVersion) {
		record(UsageEventType.REPORT_EDITED,
			"report-edited:" + report.getId() + ":" + previousVersion,
			userId, report.getProjectId(), null, report.getId(), null, null,
			orNow(report.getUpdatedAt()), Map.of("previousVersion", previousVersion));
	}

	public void pdfExported(ReportExport export) {
		reports.findById(export.getReportId()).ifPresent(report ->
			projects.findById(report.getProjectId()).ifPresent(project -> {
				Map<String, Object> metadata = export.getSize() == null
					? Map.of()
					: Map.of("sizeBytes", export.getSize());
				record(UsageEventType.PDF_EXPORTED, key("pdf-exported", export.getId()), project.getOwnerId(),
					report.getProjectId(), null, report.getId(), null, export.getId(),
					orNow(export.getCompletedAt()), metadata);
			}));
	}

	@Scheduled(cron = "${usage-events.purge-cron:0 30 3 * * *}",
		zone = "${usage-events.purge-zone:Asia/Seoul}")
	public void purgeExpired() {
		// ponytail: 한 번에 전체 보존 기간을 정리하며, 이벤트량이 커지면 배치 삭제로 전환한다.
		events.deleteByOccurredAtBefore(Instant.now().minus(retention));
	}

	private void record(UsageEventType eventType, String deduplicationKey, UUID userId, UUID projectId,
		UUID fileId, UUID reportId, UUID jobId, UUID exportId, Instant occurredAt,
		Map<String, Object> metadata) {
		UsageEvent event = new UsageEvent(eventType, deduplicationKey, userId, projectId, fileId, reportId, jobId,
			exportId, metadata, orNow(occurredAt));
		events.flush();
		if (events.insertIgnoringDuplicate(event) == 0) {
			return;
		}
	}

	private static Map<String, Object> failureMetadata(String failureCode) {
		return SAFE_FAILURE_CODES.contains(failureCode)
			? Map.of("failureCode", failureCode)
			: Map.of();
	}

	private static String key(String prefix, UUID id) {
		return prefix + ":" + id;
	}

	private static Instant orNow(Instant value) {
		return value == null ? Instant.now() : value;
	}
}
