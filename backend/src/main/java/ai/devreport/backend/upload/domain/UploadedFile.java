package ai.devreport.backend.upload.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "uploaded_files")
public class UploadedFile {

	@Id
	private UUID id;

	@Column(name = "project_id", nullable = false)
	private UUID projectId;

	@Column(name = "original_name", nullable = false, length = 255)
	private String originalName;

	@Column(name = "stored_name", nullable = false, unique = true, length = 36)
	private String storedName;

	@Column(name = "content_type", nullable = false, length = 100)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long size;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UploadedFile() {
	}

	public UploadedFile(UUID projectId, String originalName, String contentType, long size) {
		this.id = UUID.randomUUID();
		this.projectId = projectId;
		this.originalName = originalName;
		this.storedName = id.toString();
		this.contentType = contentType;
		this.size = size;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public String getOriginalName() {
		return originalName;
	}

	public String getStoredName() {
		return storedName;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSize() {
		return size;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
