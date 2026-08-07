package ai.devreport.backend.generation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import ai.devreport.backend.integration.ai.GenerationRequest;
import ai.devreport.backend.report.ReportDocument;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "generation_jobs")
public class GenerationJob {

	@Id
	private UUID id;

	@Column(name = "project_id", nullable = false)
	private UUID projectId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private int progress;

	@Enumerated(EnumType.STRING)
	@Column(name = "current_stage", nullable = false, length = 20)
	private Stage currentStage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "request_document", nullable = false, columnDefinition = "jsonb")
	private GenerationRequest requestDocument;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result_document", columnDefinition = "jsonb")
	private ReportDocument resultDocument;

	@Column(name = "failure_code", length = 100)
	private String failureCode;

	@Column(name = "failure_message", length = 500)
	private String failureMessage;

	@Column(name = "report_id")
	private UUID reportId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected GenerationJob() {
	}

	public GenerationJob(UUID projectId, GenerationRequest requestDocument) {
		this.id = UUID.randomUUID();
		this.projectId = projectId;
		this.status = Status.PENDING;
		this.progress = 0;
		this.currentStage = Stage.QUEUED;
		this.requestDocument = requestDocument;
		this.createdAt = Instant.now();
		this.updatedAt = createdAt;
	}

	public void start() {
		if (status != Status.PENDING) {
			return;
		}
		status = Status.PROCESSING;
		progress = 10;
		currentStage = Stage.CALLING_AI;
		startedAt = Instant.now();
		updatedAt = startedAt;
	}

	public void complete(ReportDocument result, UUID reportId) {
		if (status != Status.PROCESSING) {
			return;
		}
		status = Status.COMPLETED;
		progress = 100;
		currentStage = Stage.COMPLETED;
		resultDocument = result;
		this.reportId = reportId;
		completedAt = Instant.now();
		updatedAt = completedAt;
	}

	public void fail(String code, String message) {
		if (status != Status.PENDING && status != Status.PROCESSING) {
			return;
		}
		status = Status.FAILED;
		currentStage = Stage.FAILED;
		failureCode = code;
		failureMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
		completedAt = Instant.now();
		updatedAt = completedAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public Status getStatus() {
		return status;
	}

	public int getProgress() {
		return progress;
	}

	public Stage getCurrentStage() {
		return currentStage;
	}

	public GenerationRequest getRequestDocument() {
		return requestDocument;
	}

	public ReportDocument getResultDocument() {
		return resultDocument;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public String getFailureMessage() {
		return failureMessage;
	}

	public UUID getReportId() {
		return reportId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public enum Status {
		PENDING, PROCESSING, COMPLETED, FAILED
	}

	public enum Stage {
		QUEUED, CALLING_AI, COMPLETED, FAILED
	}
}
