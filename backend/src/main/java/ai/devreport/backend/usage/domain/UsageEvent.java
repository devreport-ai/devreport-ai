package ai.devreport.backend.usage.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "usage_events")
public class UsageEvent {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 32)
	private UsageEventType eventType;

	@Column(name = "deduplication_key", nullable = false, unique = true, length = 200)
	private String deduplicationKey;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "project_id")
	private UUID projectId;

	@Column(name = "file_id")
	private UUID fileId;

	@Column(name = "report_id")
	private UUID reportId;

	@Column(name = "job_id")
	private UUID jobId;

	@Column(name = "export_id")
	private UUID exportId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> metadata;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected UsageEvent() {
	}

	public UsageEvent(UsageEventType eventType, String deduplicationKey, UUID userId, UUID projectId,
		UUID fileId, UUID reportId, UUID jobId, UUID exportId, Map<String, Object> metadata,
		Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.eventType = eventType;
		this.deduplicationKey = deduplicationKey;
		this.userId = userId;
		this.projectId = projectId;
		this.fileId = fileId;
		this.reportId = reportId;
		this.jobId = jobId;
		this.exportId = exportId;
		this.metadata = new LinkedHashMap<>(metadata);
		this.occurredAt = occurredAt;
	}

	public UUID getId() {
		return id;
	}

	public UsageEventType getEventType() {
		return eventType;
	}

	public String getDeduplicationKey() {
		return deduplicationKey;
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public UUID getFileId() {
		return fileId;
	}

	public UUID getReportId() {
		return reportId;
	}

	public UUID getJobId() {
		return jobId;
	}

	public UUID getExportId() {
		return exportId;
	}

	public Map<String, Object> getMetadata() {
		return metadata == null ? null : Collections.unmodifiableMap(metadata);
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}
