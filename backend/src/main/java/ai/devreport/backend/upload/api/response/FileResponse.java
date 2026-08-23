package ai.devreport.backend.upload.api.response;

import java.time.Instant;
import java.util.UUID;

import ai.devreport.backend.upload.domain.UploadedFile;

public record FileResponse(UUID id, String originalName, String contentType, long size, Instant createdAt) {
	public static FileResponse from(UploadedFile file) {
		return new FileResponse(file.getId(), file.getOriginalName(), file.getContentType(), file.getSize(),
			file.getCreatedAt());
	}
}
