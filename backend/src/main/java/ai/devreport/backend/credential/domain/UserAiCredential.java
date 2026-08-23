package ai.devreport.backend.credential.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import ai.devreport.backend.integration.ai.AiProvider;

/**
 * 사용자가 등록한 provider API Key. 원문은 저장하지 않고 AES-GCM 암호문과 끝 4자리 힌트만 보관한다.
 */
@Entity
@Table(name = "user_ai_credentials")
public class UserAiCredential {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AiProvider provider;

	@Column(nullable = false, length = 1024)
	private String ciphertext;

	@Column(nullable = false, length = 32)
	private String nonce;

	@Column(name = "key_version", nullable = false)
	private int keyVersion;

	@Column(name = "key_hint", nullable = false, length = 8)
	private String keyHint;

	@Column(name = "verified_at")
	private Instant verifiedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserAiCredential() {
	}

	public UserAiCredential(UUID userId, AiProvider provider, EncryptedKey encrypted, String keyHint,
		Instant verifiedAt) {
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.provider = provider;
		this.createdAt = Instant.now();
		replace(encrypted, keyHint, verifiedAt);
	}

	public void replace(EncryptedKey encrypted, String keyHint, Instant verifiedAt) {
		this.ciphertext = encrypted.ciphertext();
		this.nonce = encrypted.nonce();
		this.keyVersion = encrypted.keyVersion();
		this.keyHint = keyHint;
		this.verifiedAt = verifiedAt;
		this.updatedAt = Instant.now();
	}

	public EncryptedKey encryptedKey() {
		return new EncryptedKey(ciphertext, nonce, keyVersion);
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public AiProvider getProvider() {
		return provider;
	}

	public String getKeyHint() {
		return keyHint;
	}

	public Instant getVerifiedAt() {
		return verifiedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** Base64 암호문·nonce와 암호화에 사용한 마스터 키 버전. */
	public record EncryptedKey(String ciphertext, String nonce, int keyVersion) {
	}
}
