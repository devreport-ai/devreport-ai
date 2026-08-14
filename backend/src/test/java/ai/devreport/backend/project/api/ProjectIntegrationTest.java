package ai.devreport.backend.project.api;

import ai.devreport.backend.auth.domain.PolicyVersions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:project-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ProjectIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void projectCrudAndOwnershipFlow() throws Exception {
		String ownerToken = signupAndLogin("owner@example.com");
		String otherToken = signupAndLogin("other@example.com");

		String createBody = mvc.perform(post("/api/projects")
				.header("Authorization", bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"첫 프로젝트\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String projectId = JsonPath.read(createBody, "$.projectId");

		mvc.perform(get("/api/projects").header("Authorization", bearer(ownerToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(projectId))
			.andExpect(jsonPath("$.items[0].name").value("첫 프로젝트"))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.totalPages").value(1));
		mvc.perform(get("/api/projects").header("Authorization", bearer(otherToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/projects").param("size", "101")
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isBadRequest());

		mvc.perform(get("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("첫 프로젝트"));
		mvc.perform(get("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

		mvc.perform(put("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"수정 프로젝트\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("수정 프로젝트"));
		mvc.perform(delete("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/projects/trash").header("Authorization", bearer(ownerToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(projectId))
			.andExpect(jsonPath("$.items[0].deletedAt").exists());
		mvc.perform(post("/api/projects/{projectId}/restore", projectId)
				.header("Authorization", bearer(otherToken)))
			.andExpect(status().isNotFound());
		mvc.perform(post("/api/projects/{projectId}/restore", projectId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(projectId));
		mvc.perform(get("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isOk());
		mvc.perform(delete("/api/projects/{projectId}", projectId)
				.header("Authorization", bearer(ownerToken)))
			.andExpect(status().isNoContent());

		mvc.perform(post("/api/projects")
				.header("Authorization", bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\" \"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/projects"))
			.andExpect(status().isUnauthorized());
	}

	private String signupAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123","name":"사용자","privacyPolicyVersion":"%s","termsOfServiceVersion":"%s"}
					""".formatted(email, PolicyVersions.PRIVACY_POLICY, PolicyVersions.TERMS_OF_SERVICE)))
			.andExpect(status().isCreated());
		String body = mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"%s","password":"password123"}
					""".formatted(email)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
