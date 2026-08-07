package ai.devreport.backend.export.infrastructure;

import ai.devreport.backend.export.domain.ReportExport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ReportExportRepository extends JpaRepository<ReportExport, UUID> {

	@Query("select export from ReportExport export, Report report, Project project "
		+ "where export.id = :id and export.reportId = report.id and report.projectId = project.id "
		+ "and project.ownerId = :ownerId and project.deletedAt is null")
	Optional<ReportExport> findOwned(UUID id, UUID ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select export from ReportExport export where export.id = :id")
	Optional<ReportExport> findForUpdateById(UUID id);

	List<ReportExport> findAllByStatus(ReportExport.Status status);

	List<ReportExport> findAllByStatusAndExpiresAtBefore(ReportExport.Status status, Instant expiresAt,
		Pageable pageable);
}
