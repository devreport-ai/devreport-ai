package ai.devreport.backend.project.infrastructure;

import ai.devreport.backend.project.domain.Project;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
	Page<Project> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

	Page<Project> findAllByOwnerIdAndDeletedAtIsNotNull(UUID ownerId, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<Project> findAllByDeletedAtBefore(Instant deletedBefore, Pageable pageable);

	Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select project from Project project where project.id = :id and project.ownerId = :ownerId "
		+ "and project.deletedAt is null")
	Optional<Project> findOwnedForUpdate(UUID id, UUID ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select project from Project project where project.id = :id and project.deletedAt is null")
	Optional<Project> findForUpdate(UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNotNull(UUID id, UUID ownerId);
}
