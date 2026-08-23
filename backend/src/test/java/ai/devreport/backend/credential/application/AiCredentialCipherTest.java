package ai.devreport.backend.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import ai.devreport.backend.credential.domain.UserAiCredential.EncryptedKey;
import ai.devreport.backend.integration.ai.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AiCredentialCipherTest {

	private static final String KEY_V1 = Base64.getEncoder().encodeToString(new byte[32]);
	private static final String KEY_V2 = Base64.getEncoder().encodeToString(
		"0123456789abcdef0123456789abcdef".getBytes());

	@Test
	void encryptsWithActiveKeyAndDecryptsOnlyForTheSameUserAndProvider() {
		AiCredentialCipher cipher = cipher(Map.of(1, KEY_V1), 1, "local");
		UUID userId = UUID.randomUUID();

		EncryptedKey encrypted = cipher.encrypt(userId, AiProvider.GEMINI, "AIzaSecretKeyValue1234");

		assertThat(encrypted.keyVersion()).isEqualTo(1);
		assertThat(encrypted.ciphertext()).doesNotContain("AIzaSecretKeyValue1234");
		assertThat(cipher.decrypt(userId, AiProvider.GEMINI, encrypted)).isEqualTo("AIzaSecretKeyValue1234");
		assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), AiProvider.GEMINI, encrypted))
			.isInstanceOf(AiCredentialCipher.UnreadableKeyException.class);
		assertThatThrownBy(() -> cipher.decrypt(userId, AiProvider.ANTHROPIC, encrypted))
			.isInstanceOf(AiCredentialCipher.UnreadableKeyException.class);
	}

	@Test
	void usesDistinctNoncesAndRejectsTamperedCiphertext() {
		AiCredentialCipher cipher = cipher(Map.of(1, KEY_V1), 1, "local");
		UUID userId = UUID.randomUUID();

		EncryptedKey first = cipher.encrypt(userId, AiProvider.GEMINI, "same-key");
		EncryptedKey second = cipher.encrypt(userId, AiProvider.GEMINI, "same-key");
		assertThat(first.nonce()).isNotEqualTo(second.nonce());
		assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());

		byte[] bytes = Base64.getDecoder().decode(first.ciphertext());
		bytes[0] ^= 0x01;
		EncryptedKey tampered = new EncryptedKey(Base64.getEncoder().encodeToString(bytes), first.nonce(), 1);
		assertThatThrownBy(() -> cipher.decrypt(userId, AiProvider.GEMINI, tampered))
			.isInstanceOf(AiCredentialCipher.UnreadableKeyException.class);
	}

	@Test
	void decryptsOlderKeyVersionsAfterRotationAndFailsForUnknownVersion() {
		UUID userId = UUID.randomUUID();
		EncryptedKey legacy = cipher(Map.of(1, KEY_V1), 1, "local").encrypt(userId, AiProvider.GEMINI, "legacy");

		AiCredentialCipher rotated = cipher(Map.of(1, KEY_V1, 2, KEY_V2), 2, "local");
		assertThat(rotated.decrypt(userId, AiProvider.GEMINI, legacy)).isEqualTo("legacy");
		assertThat(rotated.encrypt(userId, AiProvider.GEMINI, "fresh").keyVersion()).isEqualTo(2);

		AiCredentialCipher withoutLegacy = cipher(Map.of(2, KEY_V2), 2, "local");
		assertThatThrownBy(() -> withoutLegacy.decrypt(userId, AiProvider.GEMINI, legacy))
			.isInstanceOf(AiCredentialCipher.UnreadableKeyException.class);
	}

	@Test
	void requiresConfiguredKeyInProductionAndValidKeyLength() {
		assertThatThrownBy(() -> cipher(Map.of(), 1, "prod"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("AI_CREDENTIAL_MASTER_KEY");
		assertThatThrownBy(() -> cipher(Map.of(1, Base64.getEncoder().encodeToString(new byte[16])), 1, "local"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32바이트");
		assertThatThrownBy(() -> cipher(Map.of(1, "not-base64!!"), 1, "local"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Base64");
		// 로컬에서는 임시 키로 동작한다.
		AiCredentialCipher ephemeral = cipher(Map.of(), 1, "local");
		UUID userId = UUID.randomUUID();
		assertThat(ephemeral.decrypt(userId, AiProvider.GEMINI,
			ephemeral.encrypt(userId, AiProvider.GEMINI, "temp"))).isEqualTo("temp");
	}

	private static AiCredentialCipher cipher(Map<Integer, String> keys, int activeVersion, String profile) {
		AiCredentialProperties properties = new AiCredentialProperties();
		properties.setKeys(keys);
		properties.setActiveKeyVersion(activeVersion);
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profile);
		return new AiCredentialCipher(properties, environment);
	}
}
