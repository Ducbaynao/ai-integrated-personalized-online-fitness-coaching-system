package com.fitnesscoaching.platform.modules.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SupportModuleArchitectureTest {

    private static final String FORBIDDEN_ADMIN_PERSISTENCE = "import com.fitnesscoaching.platform.modules.administration.privileged.adapter.out.persistence";
    private static final String GENERAL_FOREIGN_OUT_PERSISTENCE = ".adapter.out.persistence";

    @Test
    @DisplayName("Architecture invariant: Module 'support' must NEVER import administration persistence adapters directly")
    void supportModuleMustNotImportForeignPersistenceAdapters() throws IOException {
        Path supportSourceDir = Paths.get("src/main/java/com/fitnesscoaching/platform/modules/support");
        if (!Files.exists(supportSourceDir)) {
            supportSourceDir = Paths.get("services/backend/src/main/java/com/fitnesscoaching/platform/modules/support");
        }
        assertThat(Files.exists(supportSourceDir))
                .withFailMessage("Support module source directory does not exist: %s", supportSourceDir)
                .isTrue();

        List<String> violations = new ArrayList<>();

        try (Stream<Path> pathStream = Files.walk(supportSourceDir)) {
            pathStream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(filePath -> {
                        try {
                            List<String> lines = Files.readAllLines(filePath);
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i).trim();
                                if (line.startsWith("import") && line.contains(GENERAL_FOREIGN_OUT_PERSISTENCE)) {
                                    // Allowed: importing support module's own persistence adapters
                                    if (!line.contains("com.fitnesscoaching.platform.modules.support")) {
                                        violations.add(String.format("File %s at line %d: %s",
                                                filePath.getFileName(), i + 1, line));
                                    }
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read file: " + filePath, e);
                        }
                    });
        }

        assertThat(violations)
                .withFailMessage("Found module boundary violations! Module 'support' imports foreign persistence adapters:\n%s",
                        String.join("\n", violations))
                .isEmpty();
    }
}
