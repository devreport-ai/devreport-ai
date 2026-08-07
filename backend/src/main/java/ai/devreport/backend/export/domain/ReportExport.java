package ai.devreport.backend.export.domain;

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
public class ReportExport {

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

	public ReportExport(UUID reportId) {
		this.id = UUID.randomUUID();
		this.reportId = reportId;
		this.status = Status.PENDING;
		this.createdAt = Instant.now();
	}

	public void start() {
		if (status == Status.PENDING) {
			status = Status.PROCESSING;
			startedAt = Instant.now();
		}
	}

	public void complete(long size, Duration ttl) {
		if (status == Status.PROCESSING) {
			status = Status.COMPLETED;
			this.size = size;
			completedAt = Instant.now();
			expiresAt = completedAt.plus(ttl);
		}
	}

	public void fail(String code, String message) {
		if (status == Status.PENDING || status == Status.PROCESSING) {
			status = Status.FAILED;
			failureCode = code;
			failureMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
			completedAt = Instant.now();
		}
	}

	public void expire() {
		if (status == Status.COMPLETED && isExpired()) {
			status = Status.EXPIRED;
		}
	}

	public boolean isExpired() {
		return expiresAt != null && !Instant.now().isBefore(expiresAt);
	}

	public UUID getId() {
		return id;
	}

	public UUID getReportId() {
		return reportId;
	}

	public Status getStatus() {
		return status;
	}

	public Long getSize() {
		return size;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public String getFailureMessage() {
		return failureMessage;
	}

	public Instant getExpiresAt() {
		return expiresAt;
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
		PENDING, PROCESSING, COMPLETED, FAILED, EXPIRED
	}
}
