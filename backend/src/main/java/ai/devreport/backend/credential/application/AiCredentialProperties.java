package ai.devreport.backend.credential.application;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자 API Key 암호화 마스터 키 설정.
 * keys는 버전별 Base64(32바이트) 키이며, 새 키는 active-key-version으로 암호화하고
 * 이전 버전 키는 복호화 용도로만 남겨 rotation을 지원한다.
 */
@Validated
@ConfigurationProperties(prefix = "ai-credentials")
public class AiCredentialProperties {

	@Min(1)
	private int activeKeyVersion = 1;

	private Map<Integer, String> keys = new LinkedHashMap<>();

	public int getActiveKeyVersion() {
		return activeKeyVersion;
	}

	public void setActiveKeyVersion(int activeKeyVersion) {
		this.activeKeyVersion = activeKeyVersion;
	}

	public Map<Integer, String> getKeys() {
		return keys;
	}

	public void setKeys(Map<Integer, String> keys) {
		this.keys = keys;
	}
}
