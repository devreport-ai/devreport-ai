package ai.devreport.backend.export.api;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.export.application.ReportExportService;
import ai.devreport.backend.export.domain.ReportExport;
import ai.devreport.backend.upload.application.ProjectFileService.FileContent;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class ReportExportController {

	private final ReportExportService exports;

	ReportExportController(ReportExportService exports) {
		this.exports = exports;
	}

	@PostMapping("/reports/{reportId}/exports")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ExportIdResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId) {
		return new ExportIdResponse(exports.create(AuthenticatedUser.id(jwt), reportId).getId());
	}

	@GetMapping("/report-exports/{exportId}")
	ExportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exportId) {
		return ExportResponse.from(exports.get(AuthenticatedUser.id(jwt), exportId));
	}

	@GetMapping("/report-exports/{exportId}/render-data")
	ResponseEntity<RenderDataResponse> renderData(@PathVariable UUID exportId,
		@RequestHeader(value = "X-Render-Token", required = false) String renderToken) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(RenderDataResponse.from(exports.renderData(exportId, renderToken)));
	}

	@GetMapping("/report-exports/{exportId}/files/{fileId}")
	ResponseEntity<InputStreamResource> renderFile(@PathVariable UUID exportId, @PathVariable UUID fileId,
		@RequestHeader(value = "X-Render-Token", required = false) String renderToken) {
		FileContent content = exports.renderFile(exportId, fileId, renderToken);
		try {
			return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.file().getContentType()))
				.contentLength(content.file().getSize())
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename(content.file().getOriginalName(), StandardCharsets.UTF_8)
						.build().toString())
				.body(new InputStreamResource(content.inputStream()));
		} catch (RuntimeException exception) {
			try {
				content.inputStream().close();
			} catch (java.io.IOException closeException) {
				exception.addSuppressed(closeException);
			}
			throw exception;
		}
	}

	@GetMapping("/report-exports/{exportId}/download")
	ResponseEntity<FileSystemResource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exportId) {
		Path path = exports.download(AuthenticatedUser.id(jwt), exportId);
		FileSystemResource resource = new FileSystemResource(path);
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.contentLength(path.toFile().length())
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment().filename("report-" + exportId + ".pdf").build().toString())
			.body(resource);
	}

	record ExportIdResponse(UUID exportId) {
	}

	record ExportResponse(UUID exportId, UUID reportId, String status, Long size, String failureCode,
		String failureMessage, Instant expiresAt, Instant createdAt, Instant startedAt, Instant completedAt) {

		static ExportResponse from(ReportExport export) {
			String status = export.isExpired() ? "EXPIRED" : export.getStatus().name();
			return new ExportResponse(export.getId(), export.getReportId(), status, export.getSize(),
				export.getFailureCode(), export.getFailureMessage(), export.getExpiresAt(), export.getCreatedAt(),
				export.getStartedAt(), export.getCompletedAt());
		}
	}

	record RenderDataResponse(UUID exportId, UUID reportId, UUID projectId, long reportVersion,
		Map<String, Object> document, String templateId, Integer templateVersion,
		Map<String, Object> presentationSettings, Set<UUID> imageFileIds) {

		static RenderDataResponse from(ReportExportService.RenderData data) {
			return new RenderDataResponse(data.exportId(), data.reportId(), data.projectId(), data.reportVersion(),
				data.document(), data.templateId(), data.templateVersion(), data.presentationSettings(),
				data.imageFileIds());
		}
	}
}
