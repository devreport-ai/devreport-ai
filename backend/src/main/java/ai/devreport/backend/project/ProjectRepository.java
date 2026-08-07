package ai.devreport.backend.project;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectRepository extends JpaRepository<Project, UUID> {
	Page<Project> findAllByOwnerId(UUID ownerId, Pageable pageable);

	Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);
}
