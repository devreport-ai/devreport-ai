package ai.devreport.backend.credential.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import ai.devreport.backend.credential.domain.UserAiCredential.EncryptedKey;
import ai.devreport.backend.integration.ai.AiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 사용자 API Key를 AES-256-GCM으로 암호화한다.
 * AAD에 userId·provider를 묶어 다른 사용자의 암호문을 옮겨 붙이는 것을 막는다.
 */
@Component
public class AiCredentialCipher {

	private static final Logger log = LoggerFactory.getLogger(AiCredentialCipher.class);
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int KEY_BYTES = 32;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final Map<Integer, SecretKey> keys = new HashMap<>();
	private final int activeVersion;
	private final SecureRandom random = new SecureRandom();

	AiCredentialCipher(AiCredentialProperties properties, Environment environment) {
		properties.getKeys().forEach((version, encoded) -> {
			if (encoded != null && !encoded.isBlank()) {
				keys.put(version, decodeKey(version, encoded));
			}
		});
		this.activeVersion = properties.getActiveKeyVersion();
		if (!keys.containsKey(activeVersion)) {
			if (environment.acceptsProfiles(Profiles.of("prod"))) {
				throw new IllegalStateException("AI_CREDENTIAL_MASTER_KEY가 운영 환경에 설정되지 않았습니다.");
			}
			byte[] ephemeral = new byte[KEY_BYTES];
			random.nextBytes(ephemeral);
			keys.put(activeVersion, new SecretKeySpec(ephemeral, "AES"));
			log.warn("AI_CREDENTIAL_MASTER_KEY가 없어 임시 키를 생성했습니다. "
				+ "재시작하면 저장된 사용자 API Key를 복호화할 수 없습니다.");
		}
	}

	public EncryptedKey encrypt(UUID userId, AiProvider provider, String apiKey) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad(userId, provider));
			byte[] ciphertext = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
			return new EncryptedKey(Base64.getEncoder().encodeToString(ciphertext),
				Base64.getEncoder().encodeToString(nonce), activeVersion);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("API Key 암호화에 실패했습니다.", exception);
		}
	}

	/** 복호화 실패(키 버전 없음·변조·AAD 불일치)는 {@link UnreadableKeyException}으로 알린다. */
	public String decrypt(UUID userId, AiProvider provider, EncryptedKey encrypted) {
		SecretKey key = keys.get(encrypted.keyVersion());
		if (key == null) {
			throw new UnreadableKeyException("암호화 키 버전 " + encrypted.keyVersion() + "을 찾을 수 없습니다.");
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
				new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(encrypted.nonce())));
			cipher.updateAAD(aad(userId, provider));
			byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypted.ciphertext()));
			return new String(plain, StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException exception) {
			throw new UnreadableKeyException("저장된 API Key를 복호화할 수 없습니다.");
		}
	}

	private static byte[] aad(UUID userId, AiProvider provider) {
		return (userId + ":" + provider.name()).getBytes(StandardCharsets.UTF_8);
	}

	private static SecretKey decodeKey(int version, String encoded) {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(encoded.trim());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("ai-credentials.keys[" + version + "]는 Base64여야 합니다.");
		}
		if (raw.length != KEY_BYTES) {
			throw new IllegalStateException("ai-credentials.keys[" + version + "]는 32바이트(AES-256) 키여야 합니다.");
		}
		return new SecretKeySpec(raw, "AES");
	}

	public static class UnreadableKeyException extends RuntimeException {
		UnreadableKeyException(String message) {
			super(message);
		}
	}
}
