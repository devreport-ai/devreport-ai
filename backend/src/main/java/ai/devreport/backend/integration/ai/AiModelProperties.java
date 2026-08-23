package ai.devreport.backend.integration.ai;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자가 선택할 수 있는 provider·model allowlist.
 * 서버 기본 모델은 서버 키로 실행되고, 나머지는 사용자 키가 있어야 선택할 수 있다.
 */
@Validated
@ConfigurationProperties(prefix = "ai.models")
public class AiModelProperties {

	@Valid
	@NotEmpty
	private List<Entry> allowlist = new ArrayList<>();

	@AssertTrue(message = "ai.models.allowlist must contain exactly one server-default model")
	public boolean isSingleServerDefault() {
		return allowlist.stream().filter(Entry::isServerDefault).count() == 1;
	}

	@AssertTrue(message = "ai.models.allowlist must not contain duplicate provider/model pairs")
	public boolean isUnique() {
		return allowlist.stream().map(entry -> entry.getProvider() + "/" + entry.getModel()).distinct().count()
			== allowlist.size();
	}

	public List<Entry> getAllowlist() {
		return allowlist;
	}

	public void setAllowlist(List<Entry> allowlist) {
		this.allowlist = allowlist;
	}

	public static class Entry {

		@NotNull
		private AiProvider provider;

		@NotBlank
		private String model;

		@NotBlank
		private String label;

		private boolean serverDefault;

		public AiProvider getProvider() {
			return provider;
		}

		public void setProvider(AiProvider provider) {
			this.provider = provider;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public String getLabel() {
			return label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public boolean isServerDefault() {
			return serverDefault;
		}

		public void setServerDefault(boolean serverDefault) {
			this.serverDefault = serverDefault;
		}
	}
}
