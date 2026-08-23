package ai.devreport.backend.export.api;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.export.api.response.ExportIdResponse;
import ai.devreport.backend.export.api.response.ExportResponse;
import ai.devreport.backend.export.api.response.RenderDataResponse;
import ai.devreport.backend.export.application.ReportExportService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class ReportExportController {

	private final ReportExportService exports;

	ReportExportController(ReportExportService exports) {
		this.exports = exports;
	}

	@PostMapping("/reports/{reportId}/exports")
	ResponseEntity<ExportIdResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId) {
		UUID exportId = exports.create(AuthenticatedUser.id(jwt), reportId).getId();
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ExportIdResponse(exportId));
	}

	@GetMapping("/report-exports/{exportId}")
	ResponseEntity<ExportResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exportId) {
		return ResponseEntity.ok(ExportResponse.from(exports.get(AuthenticatedUser.id(jwt), exportId)));
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
			} catch (IOException closeException) {
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
}
