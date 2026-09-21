package com.fitnesscoaching.platform.modules.user;

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

class UserModuleArchitectureTest {

    private static final String FORBIDDEN_IMPORT_PREFIX = "import com.fitnesscoaching.platform.modules.trainer";

    @Test
    @DisplayName("Architecture invariant: Module 'user' must NEVER import from module 'trainer'")
    void userModuleMustNotImportTrainerModule() throws IOException {
        Path userModuleSourceDir = Paths.get("src/main/java/com/fitnesscoaching/platform/modules/user");
        assertThat(Files.exists(userModuleSourceDir))
                .withFailMessage("User module source directory does not exist: %s", userModuleSourceDir)
                .isTrue();

        List<String> violations = new ArrayList<>();

        try (Stream<Path> pathStream = Files.walk(userModuleSourceDir)) {
            pathStream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(filePath -> {
                        try {
                            List<String> lines = Files.readAllLines(filePath);
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i).trim();
                                if (line.startsWith(FORBIDDEN_IMPORT_PREFIX)) {
                                    violations.add(String.format("File %s at line %d: %s",
                                            filePath.getFileName(), i + 1, line));
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read file: " + filePath, e);
                        }
                    });
        }

        assertThat(violations)
                .withFailMessage("Found circular dependency violations! Module 'user' imports 'trainer':\n%s",
                        String.join("\n", violations))
                .isEmpty();
    }
}
