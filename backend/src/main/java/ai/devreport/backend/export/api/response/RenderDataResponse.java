package ai.devreport.backend.export.api.response;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.devreport.backend.export.application.ReportExportService;

public record RenderDataResponse(UUID exportId, UUID reportId, UUID projectId, long reportVersion,
	Map<String, Object> document, String templateId, Integer templateVersion,
	Map<String, Object> presentationSettings, Set<UUID> imageFileIds) {

	public static RenderDataResponse from(ReportExportService.RenderData data) {
		return new RenderDataResponse(data.exportId(), data.reportId(), data.projectId(), data.reportVersion(),
			data.document(), data.templateId(), data.templateVersion(), data.presentationSettings(),
			data.imageFileIds());
	}
}
