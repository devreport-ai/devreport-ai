package ai.devreport.backend.report.infrastructure;

import ai.devreport.backend.report.domain.Report;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReportRepository extends JpaRepository<Report, UUID> {

	Page<Report> findAllByProjectId(UUID projectId, Pageable pageable);

	@Query("select report from Report report, Project project where report.id = :id "
		+ "and report.projectId = project.id and project.ownerId = :ownerId and project.deletedAt is null")
	Optional<Report> findOwned(UUID id, UUID ownerId);
}
