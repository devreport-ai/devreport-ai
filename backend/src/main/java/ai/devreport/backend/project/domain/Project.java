package ai.devreport.backend.project.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class Project {

	@Id
	private UUID id;

	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected Project() {
	}

	public Project(UUID ownerId, String name) {
		this.id = UUID.randomUUID();
		this.ownerId = ownerId;
		this.name = name.trim();
		this.createdAt = Instant.now();
		this.updatedAt = createdAt;
	}

	public void rename(String name) {
		this.name = name.trim();
		this.updatedAt = Instant.now();
	}

	public void delete() {
		this.deletedAt = Instant.now();
	}

	public void restore() {
		this.deletedAt = null;
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerId() {
		return ownerId;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
