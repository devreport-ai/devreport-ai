package ai.devreport.backend.project;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface ProjectRepository extends JpaRepository<Project, UUID> {
	Page<Project> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

	Page<Project> findAllByOwnerIdAndDeletedAtIsNotNull(UUID ownerId, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<Project> findAllByDeletedAtBefore(Instant deletedBefore, Pageable pageable);

	Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNotNull(UUID id, UUID ownerId);
}
