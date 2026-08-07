package ai.devreport.backend.project.application;

public class ProjectNotFoundException extends RuntimeException {

	ProjectNotFoundException() {
		super("프로젝트를 찾을 수 없습니다.");
	}
}
