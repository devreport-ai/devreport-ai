package ai.devreport.backend.project;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/reports")
class ReportController {

	private final ReportService reports;

	ReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping("/{reportId}")
	Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId) {
		return reports.get(ProjectController.ownerId(jwt), reportId).getDocument();
	}

	@PutMapping("/{reportId}")
	Map<String, Object> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId,
		@RequestBody JsonNode document) {
		return reports.update(ProjectController.ownerId(jwt), reportId, document).getDocument();
	}
}
