package ai.devreport.backend.project;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import ai.devreport.backend.ai.GenerationRequest;
import ai.devreport.backend.ai.ReportDocument;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "generation_jobs")
class GenerationJob {

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

	GenerationJob(UUID projectId, GenerationRequest requestDocument) {
		this.id = UUID.randomUUID();
		this.projectId = projectId;
		this.status = Status.PENDING;
		this.progress = 0;
		this.currentStage = Stage.QUEUED;
		this.requestDocument = requestDocument;
		this.createdAt = Instant.now();
		this.updatedAt = createdAt;
	}

	void start() {
		if (status != Status.PENDING) {
			return;
		}
		status = Status.PROCESSING;
		progress = 10;
		currentStage = Stage.CALLING_AI;
		startedAt = Instant.now();
		updatedAt = startedAt;
	}

	void complete(ReportDocument result) {
		if (status != Status.PROCESSING) {
			return;
		}
		status = Status.COMPLETED;
		progress = 100;
		currentStage = Stage.COMPLETED;
		resultDocument = result;
		completedAt = Instant.now();
		updatedAt = completedAt;
	}

	void fail(String code, String message) {
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

	UUID getId() {
		return id;
	}

	UUID getProjectId() {
		return projectId;
	}

	Status getStatus() {
		return status;
	}

	int getProgress() {
		return progress;
	}

	Stage getCurrentStage() {
		return currentStage;
	}

	GenerationRequest getRequestDocument() {
		return requestDocument;
	}

	ReportDocument getResultDocument() {
		return resultDocument;
	}

	String getFailureCode() {
		return failureCode;
	}

	String getFailureMessage() {
		return failureMessage;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getStartedAt() {
		return startedAt;
	}

	Instant getCompletedAt() {
		return completedAt;
	}

	enum Status {
		PENDING, PROCESSING, COMPLETED, FAILED
	}

	enum Stage {
		QUEUED, CALLING_AI, COMPLETED, FAILED
	}
}
