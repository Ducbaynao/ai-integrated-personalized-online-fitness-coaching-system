package com.fitnesscoaching.platform.modules.content.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Port for generating dense embedding vectors for knowledge chunks and query text.
 * Enforces baseline embedding space compatibility (model code, version label, and dimension 1536).
 */
public interface EmbeddingModelPort {

    int DIMENSION = 1536;
    String PROVIDER = "simulated";
    String MODEL_CODE = "simulated/deterministic-1536";
    String VERSION_LABEL = "1.0.0";
    String MODEL_SPACE = "simulated/deterministic-1536/1.0.0";
    UUID MODEL_VERSION_ID = UUID.fromString("e1000000-0000-0000-0000-000000000001");

    int dimension();

    String provider();

    String modelCode();

    String versionLabel();

    String modelSpace();

    UUID modelVersionId();

    float[] generateEmbedding(String text);

    List<float[]> generateBatch(List<String> texts);
}
