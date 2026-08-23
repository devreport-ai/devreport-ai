package ai.devreport.backend.export.infrastructure;

import ai.devreport.backend.export.domain.ReportExport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

	@Query("select count(export) from ReportExport export, Report report, Project project "
		+ "where export.reportId = report.id and report.projectId = project.id "
		+ "and project.ownerId = :ownerId and export.status in :statuses")
	long countByOwnerIdAndStatusIn(UUID ownerId, Set<ReportExport.Status> statuses);

	@Query("select count(export) from ReportExport export, Report report, Project project "
		+ "where export.reportId = report.id and report.projectId = project.id "
		+ "and project.ownerId = :ownerId and export.createdAt >= :from")
	long countByOwnerIdAndCreatedAtOnOrAfter(UUID ownerId, Instant from);

	@Query("select export from ReportExport export, Report report "
		+ "where export.reportId = report.id and report.projectId = :projectId "
		+ "and export.status in :statuses")
	List<ReportExport> findAllByProjectIdAndStatusIn(UUID projectId, Set<ReportExport.Status> statuses);

	List<ReportExport> findAllByStatusAndExpiresAtBefore(ReportExport.Status status, Instant expiresAt,
		Pageable pageable);
}
