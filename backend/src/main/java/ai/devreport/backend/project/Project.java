package ai.devreport.backend.project;

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

	Project(UUID ownerId, String name) {
		this.id = UUID.randomUUID();
		this.ownerId = ownerId;
		this.name = name.trim();
		this.createdAt = Instant.now();
		this.updatedAt = createdAt;
	}

	void rename(String name) {
		this.name = name.trim();
		this.updatedAt = Instant.now();
	}

	void delete() {
		this.deletedAt = Instant.now();
	}

	void restore() {
		this.deletedAt = null;
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	UUID getOwnerId() {
		return ownerId;
	}

	String getName() {
		return name;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}

	Instant getDeletedAt() {
		return deletedAt;
	}
}
