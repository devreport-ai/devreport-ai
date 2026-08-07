package ai.devreport.backend.report;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reports")
public class Report {

	@Id
	private UUID id;

	@Column(name = "project_id", nullable = false)
	private UUID projectId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> document;

	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Report() {
	}

	Report(UUID projectId, Map<String, Object> document) {
		this.id = UUID.randomUUID();
		this.projectId = projectId;
		this.document = document;
		this.createdAt = Instant.now();
		this.updatedAt = createdAt;
	}

	void update(Map<String, Object> document) {
		this.document = document;
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	UUID getProjectId() {
		return projectId;
	}

	public Map<String, Object> getDocument() {
		return document;
	}

	long getVersion() {
		return version;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}
}
