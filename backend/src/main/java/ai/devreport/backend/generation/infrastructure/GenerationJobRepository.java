package ai.devreport.backend.generation.infrastructure;

import ai.devreport.backend.generation.domain.GenerationJob;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

	boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<GenerationJob.Status> statuses);

	@Query("select count(job) from GenerationJob job, Project project "
		+ "where job.projectId = project.id and project.ownerId = :ownerId "
		+ "and job.status in :statuses")
	long countByOwnerIdAndStatusIn(UUID ownerId, Collection<GenerationJob.Status> statuses);

	@Query("select count(job) from GenerationJob job, Project project "
		+ "where job.projectId = project.id and project.ownerId = :ownerId "
		+ "and job.createdAt >= :from")
	long countByOwnerIdAndCreatedAtOnOrAfter(UUID ownerId, Instant from);

	List<GenerationJob> findAllByStatus(GenerationJob.Status status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select job from GenerationJob job where job.id = :id")
	Optional<GenerationJob> findForUpdateById(UUID id);
}
