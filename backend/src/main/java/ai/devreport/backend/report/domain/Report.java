package ai.devreport.backend.report.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
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

	@Column(name = "template_id", length = 64)
	private String templateId;

	@Column(name = "template_version")
	private Integer templateVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "presentation_settings", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> presentationSettings;

	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Report() {
	}

	public Report(UUID projectId, Map<String, Object> document) {
		this.id = UUID.randomUUID();
		this.projectId = projectId;
		this.document = document;
		this.presentationSettings = new LinkedHashMap<>();
		this.createdAt = Instant.now();
		this.updatedAt = createdAt;
	}

	public void update(Map<String, Object> document, String templateId, Integer templateVersion,
		Map<String, Object> presentationSettings) {
		this.document = document;
		this.templateId = templateId;
		this.templateVersion = templateVersion;
		this.presentationSettings = presentationSettings;
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public Map<String, Object> getDocument() {
		return document;
	}

	public String getTemplateId() {
		return templateId;
	}

	public Integer getTemplateVersion() {
		return templateVersion;
	}

	public Map<String, Object> getPresentationSettings() {
		return presentationSettings;
	}

	public long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
