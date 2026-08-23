package ai.devreport.backend.usage.infrastructure;

import ai.devreport.backend.usage.domain.UsageEvent;

public interface UsageEventRepositoryCustom {

	int insertIgnoringDuplicate(UsageEvent event);
}
