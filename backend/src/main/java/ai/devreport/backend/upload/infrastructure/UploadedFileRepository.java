package ai.devreport.backend.upload.infrastructure;

import ai.devreport.backend.upload.domain.UploadedFile;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, UUID> {
	Page<UploadedFile> findAllByProjectId(UUID projectId, Pageable pageable);

	Optional<UploadedFile> findByIdAndProjectId(UUID id, UUID projectId);

	boolean existsByProjectId(UUID projectId);

	@Query("select count(file) from UploadedFile file, Project project "
		+ "where file.projectId = project.id and project.ownerId = :ownerId")
	long countByOwnerId(UUID ownerId);

	@Query("select coalesce(sum(file.size), 0) from UploadedFile file, Project project "
		+ "where file.projectId = project.id and project.ownerId = :ownerId")
	long sumSizeByOwnerId(UUID ownerId);
}
