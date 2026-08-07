package ai.devreport.backend.project;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ReportExportService {

	private final ReportExportRepository exports;
	private final ReportService reports;
	private final ReportRepository reportRepository;
	private final ApplicationEventPublisher events;
	private final PdfReportRenderer renderer;
	private final Duration ttl;

	ReportExportService(ReportExportRepository exports, ReportService reports, ReportRepository reportRepository,
		ApplicationEventPublisher events, PdfReportRenderer renderer,
		@Value("${storage.export-ttl}") Duration ttl) {
		this.exports = exports;
		this.reports = reports;
		this.reportRepository = reportRepository;
		this.events = events;
		this.renderer = renderer;
		this.ttl = ttl;
	}

	ReportExport create(UUID ownerId, UUID reportId) {
		reports.get(ownerId, reportId);
		ReportExport export = exports.save(new ReportExport(reportId));
		events.publishEvent(new ReportExportQueuedEvent(export.getId()));
		return export;
	}

	ReportExport get(UUID ownerId, UUID exportId) {
		ReportExport export = exports.findOwned(exportId, ownerId).orElseThrow(ReportExportService::notFound);
		if (export.isExpired()) {
			renderer.delete(exportId);
		}
		return export;
	}

	Path download(UUID ownerId, UUID exportId) {
		ReportExport export = get(ownerId, exportId);
		if (export.isExpired()) {
			throw new ReportExportException(HttpStatus.GONE, "EXPORT_EXPIRED", "PDF 다운로드 기간이 만료되었습니다.");
		}
		if (export.getStatus() == ReportExport.Status.FAILED) {
			throw new ReportExportException(HttpStatus.CONFLICT, "EXPORT_FAILED", "PDF 생성에 실패했습니다.");
		}
		if (export.getStatus() != ReportExport.Status.COMPLETED) {
			throw new ReportExportException(HttpStatus.CONFLICT, "EXPORT_NOT_READY", "PDF가 아직 준비되지 않았습니다.");
		}
		Path path = renderer.path(exportId);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new ReportExportException(HttpStatus.NOT_FOUND, "EXPORT_FILE_NOT_FOUND",
				"PDF 파일을 찾을 수 없습니다.");
		}
		return path;
	}

	Optional<ExportInput> start(UUID exportId) {
		return exports.findForUpdateById(exportId)
			.filter(export -> export.getStatus() == ReportExport.Status.PENDING)
			.flatMap(export -> reportRepository.findById(export.getReportId()).map(report -> {
				export.start();
				return new ExportInput(export.getId(), report.getDocument());
			}));
	}

	boolean complete(UUID exportId, long size) {
		return exports.findById(exportId)
			.filter(export -> export.getStatus() == ReportExport.Status.PROCESSING)
			.map(export -> {
				export.complete(size, ttl);
				return true;
			})
			.orElse(false);
	}

	void fail(UUID exportId, String code, String message) {
		exports.findById(exportId).ifPresent(export -> export.fail(code, message));
	}

	List<UUID> recover() {
		exports.findAllByStatus(ReportExport.Status.PROCESSING).forEach(export ->
			export.fail("EXPORT_INTERRUPTED", "서버 재시작으로 PDF 생성이 중단되었습니다."));
		return exports.findAllByStatus(ReportExport.Status.PENDING).stream().map(ReportExport::getId).toList();
	}

	private static ReportExportException notFound() {
		return new ReportExportException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND",
			"PDF 내보내기 작업을 찾을 수 없습니다.");
	}

	record ExportInput(UUID exportId, Map<String, Object> document) {
	}
}
