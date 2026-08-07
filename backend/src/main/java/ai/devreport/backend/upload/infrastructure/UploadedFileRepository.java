package ai.devreport.backend.upload.infrastructure;

import ai.devreport.backend.upload.domain.UploadedFile;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, UUID> {
	Page<UploadedFile> findAllByProjectId(UUID projectId, Pageable pageable);

	Optional<UploadedFile> findByIdAndProjectId(UUID id, UUID projectId);

	boolean existsByProjectId(UUID projectId);
}
