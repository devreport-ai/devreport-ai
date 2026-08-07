package ai.devreport.backend.project;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "report_exports")
class ReportExport {

	@Id
	private UUID id;

	@Column(name = "report_id", nullable = false)
	private UUID reportId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(name = "size_bytes")
	private Long size;

	@Column(name = "failure_code", length = 100)
	private String failureCode;

	@Column(name = "failure_message", length = 500)
	private String failureMessage;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected ReportExport() {
	}

	ReportExport(UUID reportId) {
		this.id = UUID.randomUUID();
		this.reportId = reportId;
		this.status = Status.PENDING;
		this.createdAt = Instant.now();
	}

	void start() {
		if (status == Status.PENDING) {
			status = Status.PROCESSING;
			startedAt = Instant.now();
		}
	}

	void complete(long size, Duration ttl) {
		if (status == Status.PROCESSING) {
			status = Status.COMPLETED;
			this.size = size;
			completedAt = Instant.now();
			expiresAt = completedAt.plus(ttl);
		}
	}

	void fail(String code, String message) {
		if (status == Status.PENDING || status == Status.PROCESSING) {
			status = Status.FAILED;
			failureCode = code;
			failureMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
			completedAt = Instant.now();
		}
	}

	void expire() {
		if (status == Status.COMPLETED && isExpired()) {
			status = Status.EXPIRED;
		}
	}

	boolean isExpired() {
		return expiresAt != null && !Instant.now().isBefore(expiresAt);
	}

	UUID getId() {
		return id;
	}

	UUID getReportId() {
		return reportId;
	}

	Status getStatus() {
		return status;
	}

	Long getSize() {
		return size;
	}

	String getFailureCode() {
		return failureCode;
	}

	String getFailureMessage() {
		return failureMessage;
	}

	Instant getExpiresAt() {
		return expiresAt;
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
		PENDING, PROCESSING, COMPLETED, FAILED, EXPIRED
	}
}
