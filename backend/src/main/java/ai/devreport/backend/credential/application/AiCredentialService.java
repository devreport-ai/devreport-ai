package ai.devreport.backend.credential.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ai.devreport.backend.auth.infrastructure.UserRepository;
import ai.devreport.backend.credential.domain.AiCredentialException;
import ai.devreport.backend.credential.domain.UserAiCredential;
import ai.devreport.backend.credential.domain.UserAiCredential.EncryptedKey;
import ai.devreport.backend.credential.infrastructure.UserAiCredentialRepository;
import ai.devreport.backend.integration.ai.AiModelCatalog;
import ai.devreport.backend.integration.ai.AiProvider;
import ai.devreport.backend.integration.ai.AiServiceClient;
import ai.devreport.backend.integration.ai.AiServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 provider API Key 등록·조회·삭제.
 * 원문 키는 저장 전 검증과 생성 실행 시 복호화 순간에만 메모리에 존재하며, 응답·로그·예외에 싣지 않는다.
 */
@Service
public class AiCredentialService {

	private static final int MIN_KEY_LENGTH = 8;
	private static final int MAX_KEY_LENGTH = 512;
	private static final int HINT_LENGTH = 4;

	private final UserAiCredentialRepository credentials;
	private final UserRepository users;
	private final AiCredentialCipher cipher;
	private final AiServiceClient aiService;
	private final AiModelCatalog models;
	private final TransactionTemplate transactions;

	AiCredentialService(UserAiCredentialRepository credentials, UserRepository users, AiCredentialCipher cipher,
		AiServiceClient aiService, AiModelCatalog models, TransactionTemplate transactions) {
		this.credentials = credentials;
		this.users = users;
		this.cipher = cipher;
		this.aiService = aiService;
		this.models = models;
		this.transactions = transactions;
	}

	@Transactional(readOnly = true)
	public List<UserAiCredential> list(UUID userId) {
		return credentials.findAllByUserIdOrderByProviderAsc(userId);
	}

	/**
	 * 현재 마스터 키로 복호화할 수 있는 키가 등록된 provider 집합.
	 * 복호화할 수 없는 행(마스터 키 분실·rotation 누락)은 "등록되지 않은 것"으로 보아 기본 모델이 서버 키로 계속 동작하게 한다.
	 */
	@Transactional(readOnly = true)
	public Set<AiProvider> usableProviders(UUID userId) {
		Set<AiProvider> usable = EnumSet.noneOf(AiProvider.class);
		for (UserAiCredential credential : credentials.findAllByUserIdOrderByProviderAsc(userId)) {
			if (decrypt(userId, credential).isPresent()) {
				usable.add(credential.getProvider());
			}
		}
		return usable;
	}

	@Transactional(readOnly = true)
	public boolean isUsable(UUID userId, AiProvider provider) {
		return credentials.findByUserIdAndProvider(userId, provider)
			.flatMap(credential -> decrypt(userId, credential)).isPresent();
	}

	/**
	 * provider 검증(원격 호출)은 트랜잭션·행 잠금 밖에서 수행하고, 암호화 저장만 사용자 행 잠금 안에서 처리한다.
	 * 검증을 잠금 안에서 하면 AI Service가 느릴 때 같은 사용자의 업로드·생성·내보내기 한도 검사가 모두 막힌다.
	 */
	@Transactional(propagation = Propagation.NEVER)
	public UserAiCredential save(UUID userId, AiProvider provider, String apiKey) {
		String normalized = apiKey == null ? "" : apiKey.strip();
		if (normalized.length() < MIN_KEY_LENGTH || normalized.length() > MAX_KEY_LENGTH
			|| !isPrintableAscii(normalized)) {
			throw invalid();
		}
		if (models.models().stream().noneMatch(model -> model.provider() == provider)) {
			throw unsupportedProvider();
		}
		verify(provider, normalized);
		EncryptedKey encrypted = cipher.encrypt(userId, provider, normalized);
		String hint = hint(normalized);
		Instant now = Instant.now();
		return transactions.execute(status -> {
			lockUser(userId);
			Optional<UserAiCredential> existing = credentials.findForUpdate(userId, provider);
			if (existing.isPresent()) {
				existing.get().replace(encrypted, hint, now);
				return existing.get();
			}
			return credentials.save(new UserAiCredential(userId, provider, encrypted, hint, now));
		});
	}

	@Transactional
	public void delete(UUID userId, AiProvider provider) {
		// 생성 접수와 같은 사용자 행 잠금으로 직렬화해, 삭제 직후 접수된 작업이 사라진 키를 가리키지 않게 한다.
		lockUser(userId);
		UserAiCredential credential = credentials.findForUpdate(userId, provider)
			.orElseThrow(AiCredentialService::notFound);
		credentials.delete(credential);
	}

	/**
	 * 생성 실행 직전에 호출한다. 키가 없거나 복호화할 수 없으면 empty를 돌려준다.
	 * 반환된 원문은 AI Service 호출에만 쓰고 어디에도 저장하지 않는다.
	 */
	@Transactional(readOnly = true)
	public Optional<String> resolveApiKey(UUID userId, AiProvider provider) {
		return credentials.findByUserIdAndProvider(userId, provider)
			.flatMap(credential -> decrypt(userId, credential));
	}

	private Optional<String> decrypt(UUID userId, UserAiCredential credential) {
		try {
			return Optional.of(cipher.decrypt(userId, credential.getProvider(), credential.encryptedKey()));
		} catch (AiCredentialCipher.UnreadableKeyException exception) {
			return Optional.empty();
		}
	}

	private void lockUser(UUID userId) {
		users.findForUpdate(userId).orElseThrow(() -> new IllegalStateException("Authenticated user is missing"));
	}

	private void verify(AiProvider provider, String apiKey) {
		try {
			aiService.verifyCredential(provider, apiKey);
		} catch (AiServiceException exception) {
			switch (exception.code()) {
				case "AI_CREDENTIAL_INVALID" -> throw invalid();
				case "GENERATION_REQUEST_INVALID" -> throw unsupportedProvider();
				default -> throw new AiCredentialException(HttpStatus.BAD_GATEWAY,
					"AI_CREDENTIAL_VERIFICATION_FAILED", "API Key를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
			}
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

	private static AiCredentialException unsupportedProvider() {
		return new AiCredentialException(HttpStatus.BAD_REQUEST, "AI_PROVIDER_UNSUPPORTED",
			"아직 지원하지 않는 provider입니다.");
	}

	private static AiCredentialException notFound() {
		return new AiCredentialException(HttpStatus.NOT_FOUND, "AI_CREDENTIAL_NOT_FOUND",
			"등록된 API Key가 없습니다.");
	}
}
