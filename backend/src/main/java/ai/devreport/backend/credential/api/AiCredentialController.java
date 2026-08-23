package ai.devreport.backend.credential.api;

import jakarta.validation.Valid;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.credential.api.request.AiCredentialRequest;
import ai.devreport.backend.credential.api.response.AiCredentialListResponse;
import ai.devreport.backend.credential.api.response.AiCredentialResponse;
import ai.devreport.backend.credential.application.AiCredentialService;
import ai.devreport.backend.integration.ai.AiProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/ai-credentials")
class AiCredentialController {

	private final AiCredentialService credentials;

	AiCredentialController(AiCredentialService credentials) {
		this.credentials = credentials;
	}

	@GetMapping
	ResponseEntity<AiCredentialListResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok(AiCredentialListResponse.from(credentials.list(AuthenticatedUser.id(jwt))));
	}

	@PutMapping("/{provider}")
	ResponseEntity<AiCredentialResponse> save(@AuthenticationPrincipal Jwt jwt, @PathVariable AiProvider provider,
		@Valid @RequestBody AiCredentialRequest request) {
		return ResponseEntity.ok(AiCredentialResponse.from(
			credentials.save(AuthenticatedUser.id(jwt), provider, request.apiKey())));
	}

	@DeleteMapping("/{provider}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable AiProvider provider) {
		credentials.delete(AuthenticatedUser.id(jwt), provider);
		return ResponseEntity.noContent().build();
	}
}
