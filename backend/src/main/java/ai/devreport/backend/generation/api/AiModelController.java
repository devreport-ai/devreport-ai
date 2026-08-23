package ai.devreport.backend.generation.api;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.credential.application.AiCredentialService;
import ai.devreport.backend.generation.api.response.AiModelCatalogResponse;
import ai.devreport.backend.integration.ai.AiModelCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AiModelController {

	private final AiModelCatalog catalog;
	private final AiCredentialService credentials;

	AiModelController(AiModelCatalog catalog, AiCredentialService credentials) {
		this.catalog = catalog;
		this.credentials = credentials;
	}

	@GetMapping("/api/ai/models")
	ResponseEntity<AiModelCatalogResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok(AiModelCatalogResponse.from(catalog.models(),
			credentials.usableProviders(AuthenticatedUser.id(jwt))));
	}
}
