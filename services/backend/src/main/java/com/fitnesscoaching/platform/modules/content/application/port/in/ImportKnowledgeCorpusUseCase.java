package com.fitnesscoaching.platform.modules.content.application.port.in;

import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusBundle;
import com.fitnesscoaching.platform.modules.content.domain.ImportCorpusResult;

import java.util.UUID;

public interface ImportKnowledgeCorpusUseCase {

    ImportCorpusResult importBundle(UUID actorId, ImportCorpusBundle bundle);
}
