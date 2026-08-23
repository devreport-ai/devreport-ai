package ai.devreport.backend.upload.api.response;

import java.util.List;

import ai.devreport.backend.upload.domain.UploadedFile;
import org.springframework.data.domain.Page;

public record FilePageResponse(List<FileResponse> items, int page, int size, long totalElements, int totalPages) {
	public static FilePageResponse from(Page<UploadedFile> files) {
		return new FilePageResponse(files.getContent().stream().map(FileResponse::from).toList(),
			files.getNumber(), files.getSize(), files.getTotalElements(), files.getTotalPages());
	}
}
