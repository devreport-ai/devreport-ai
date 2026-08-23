package ai.devreport.backend.credential.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.devreport.backend.auth.infrastructure.UserRepository;
import ai.devreport.backend.credential.domain.AiCredentialException;
import ai.devreport.backend.credential.domain.UserAiCredential;
import ai.devreport.backend.credential.domain.UserAiCredential.EncryptedKey;
import ai.devreport.backend.credential.infrastructure.UserAiCredentialRepository;
import ai.devreport.backend.integration.ai.AiProvider;
import ai.devreport.backend.integration.ai.AiServiceClient;
import ai.devreport.backend.integration.ai.AiServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 provider API Key 등록·조회·삭제.
 * 원문 키는 저장 전 검증과 생성 실행 시 복호화 순간에만 메모리에 존재하며, 응답·로그·예외에 싣지 않는다.
 */
@Service
@Transactional
public class AiCredentialService {

	private static final int HINT_LENGTH = 4;

	private final UserAiCredentialRepository credentials;
	private final UserRepository users;
	private final AiCredentialCipher cipher;
	private final AiServiceClient aiService;

	AiCredentialService(UserAiCredentialRepository credentials, UserRepository users, AiCredentialCipher cipher,
		AiServiceClient aiService) {
		this.credentials = credentials;
		this.users = users;
		this.cipher = cipher;
		this.aiService = aiService;
	}

	@Transactional(readOnly = true)
	public List<UserAiCredential> list(UUID userId) {
		return credentials.findAllByUserIdOrderByProviderAsc(userId);
	}

	@Transactional(readOnly = true)
	public boolean exists(UUID userId, AiProvider provider) {
		return credentials.existsByUserIdAndProvider(userId, provider);
	}

	public UserAiCredential save(UUID userId, AiProvider provider, String apiKey) {
		String normalized = apiKey == null ? "" : apiKey.strip();
		if (normalized.isEmpty() || normalized.length() > 512 || !isPrintableAscii(normalized)) {
			throw invalid();
		}
		// 사용자 행을 잠가 같은 provider에 대한 동시 등록이 unique 제약 오류로 새지 않게 한다.
		users.findForUpdate(userId).orElseThrow(() -> new IllegalStateException("Authenticated user is missing"));
		verify(provider, normalized);
		EncryptedKey encrypted = cipher.encrypt(userId, provider, normalized);
		String hint = hint(normalized);
		Instant now = Instant.now();
		Optional<UserAiCredential> existing = credentials.findForUpdate(userId, provider);
		if (existing.isPresent()) {
			existing.get().replace(encrypted, hint, now);
			return existing.get();
		}
		return credentials.save(new UserAiCredential(userId, provider, encrypted, hint, now));
	}

	public void delete(UUID userId, AiProvider provider) {
		UserAiCredential credential = credentials.findForUpdate(userId, provider)
			.orElseThrow(AiCredentialService::notFound);
		credentials.delete(credential);
	}

	/**
	 * 생성 실행 직전에 호출한다. 키가 없으면 empty, 복호화할 수 없으면 예외를 던진다.
	 * 반환된 원문은 AI Service 호출에만 쓰고 어디에도 저장하지 않는다.
	 */
	@Transactional(readOnly = true)
	public Optional<String> resolveApiKey(UUID userId, AiProvider provider) {
		return credentials.findByUserIdAndProvider(userId, provider)
			.map(credential -> cipher.decrypt(userId, provider, credential.encryptedKey()));
	}

	private void verify(AiProvider provider, String apiKey) {
		try {
			aiService.verifyCredential(provider, apiKey);
		} catch (AiServiceException exception) {
			if ("AI_CREDENTIAL_INVALID".equals(exception.code())) {
				throw invalid();
			}
			throw new AiCredentialException(HttpStatus.BAD_GATEWAY, "AI_CREDENTIAL_VERIFICATION_FAILED",
				"API Key를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	private static String hint(String apiKey) {
		return apiKey.substring(apiKey.length() - HINT_LENGTH);
	}

	private static boolean isPrintableAscii(String value) {
		return value.chars().allMatch(character -> character >= 0x21 && character <= 0x7E);
	}

	private static AiCredentialException invalid() {
		return new AiCredentialException(HttpStatus.BAD_REQUEST, "AI_CREDENTIAL_INVALID",
			"API Key가 올바르지 않습니다.");
	}

	private static AiCredentialException notFound() {
		return new AiCredentialException(HttpStatus.NOT_FOUND, "AI_CREDENTIAL_NOT_FOUND",
			"등록된 API Key가 없습니다.");
	}
}
