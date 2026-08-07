package ai.devreport.backend.upload.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.upload.application.ProjectFileService;
import ai.devreport.backend.upload.domain.UploadedFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{projectId}/files")
class ProjectFileController {

	private final ProjectFileService fileService;

	ProjectFileController(ProjectFileService fileService) {
		this.fileService = fileService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	FileIdResponse upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@RequestPart MultipartFile file) {
		return new FileIdResponse(fileService.upload(AuthenticatedUser.id(jwt), projectId, file).getId());
	}

	@GetMapping
	FilePageResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return FilePageResponse.from(fileService.list(AuthenticatedUser.id(jwt), projectId, page, size));
	}

	@GetMapping("/{fileId}")
	ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
		@PathVariable UUID fileId) {
		ProjectFileService.FileContent content = fileService.content(AuthenticatedUser.id(jwt), projectId, fileId);
		UploadedFile file = content.file();
		ContentDisposition disposition = isPreviewable(file)
			? ContentDisposition.inline().filename(file.getOriginalName(), StandardCharsets.UTF_8).build()
			: ContentDisposition.attachment().filename(file.getOriginalName(), StandardCharsets.UTF_8).build();
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.getContentType()))
			.contentLength(file.getSize())
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.body(new InputStreamResource(content.inputStream()));
	}

	@DeleteMapping("/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId, @PathVariable UUID fileId) {
		fileService.delete(AuthenticatedUser.id(jwt), projectId, fileId);
	}

	private static boolean isPreviewable(UploadedFile file) {
		return file.getContentType().equals(MediaType.IMAGE_PNG_VALUE)
			|| file.getContentType().equals(MediaType.IMAGE_JPEG_VALUE)
			|| file.getContentType().equals(MediaType.APPLICATION_PDF_VALUE);
	}

	record FileIdResponse(UUID fileId) {
	}

	record FileResponse(UUID id, String originalName, String contentType, long size, Instant createdAt) {
		static FileResponse from(UploadedFile file) {
			return new FileResponse(file.getId(), file.getOriginalName(), file.getContentType(), file.getSize(),
				file.getCreatedAt());
		}
	}

	record FilePageResponse(List<FileResponse> items, int page, int size, long totalElements, int totalPages) {
		static FilePageResponse from(Page<UploadedFile> files) {
			return new FilePageResponse(files.getContent().stream().map(FileResponse::from).toList(),
				files.getNumber(), files.getSize(), files.getTotalElements(), files.getTotalPages());
		}
	}
}
