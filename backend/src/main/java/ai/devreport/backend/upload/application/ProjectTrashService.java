package ai.devreport.backend.upload.application;

import java.time.Duration;
import java.time.Instant;

import ai.devreport.backend.project.application.ProjectService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectTrashService {

	private static final Duration RETENTION = Duration.ofDays(30);

	private final ProjectService projects;
	private final ProjectFileService files;

	ProjectTrashService(ProjectService projects, ProjectFileService files) {
		this.projects = projects;
		this.files = files;
	}

	@Scheduled(cron = "${storage.trash-purge-cron:0 0 3 * * *}",
		zone = "${storage.trash-purge-zone:Asia/Seoul}")
	@Transactional
	public void purgeExpiredProjects() {
		// ponytail: 한 번에 100개 정리하며, 적체가 관측되면 배치 크기나 실행 주기를 조정한다.
		Sort sort = Sort.by(Sort.Order.asc("deletedAt"), Sort.Order.asc("id"));
		projects.findExpired(Instant.now().minus(RETENTION), PageRequest.of(0, 100, sort))
			.forEach(project -> {
				files.stageProjectPurge(project.getId());
				projects.purge(project);
			});
	}
}
