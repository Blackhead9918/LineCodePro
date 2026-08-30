package cn.lineai.data.repository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Automatically inspects the active workspace to build an adaptive,
 * context-aware environment profile for the agent system prompt.
 */
public final class WorkspaceContextProfiler {
    private static final WorkspaceContextProfiler INSTANCE = new WorkspaceContextProfiler();

    public static class WorkspaceProfile {
        private final String projectType;
        private final String buildSystem;
        private final List<String> detectedTechnologies;
        private final boolean isRemote;
        private final String environmentSummary;

        public WorkspaceProfile(
                String projectType,
                String buildSystem,
                List<String> detectedTechnologies,
                boolean isRemote,
                String environmentSummary
        ) {
            this.projectType = projectType;
            this.buildSystem = buildSystem;
            this.detectedTechnologies = detectedTechnologies == null ? new ArrayList<>() : detectedTechnologies;
            this.isRemote = isRemote;
            this.environmentSummary = environmentSummary;
        }

        public String getProjectType() { return projectType; }
        public String getBuildSystem() { return buildSystem; }
        public List<String> getDetectedTechnologies() { return detectedTechnologies; }
        public boolean isRemote() { return isRemote; }
        public String getEnvironmentSummary() { return environmentSummary; }

        public String formatForPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("### Active Workspace Context Profile:\n");
            sb.append("- Type: ").append(projectType).append("\n");
            sb.append("- Build System: ").append(buildSystem).append("\n");
            if (!detectedTechnologies.isEmpty()) {
                sb.append("- Tech Stack: ").append(String.join(", ", detectedTechnologies)).append("\n");
            }
            sb.append("- Environment: ").append(environmentSummary);
            return sb.toString();
        }
    }

    private WorkspaceContextProfiler() {
    }

    public static WorkspaceContextProfiler getInstance() {
        return INSTANCE;
    }

    public WorkspaceProfile profileWorkspace(String homePath) {
        if (homePath == null || homePath.trim().isEmpty()) {
            return new WorkspaceProfile("Generic", "Standard Shell", new ArrayList<>(), false, "Local Default Workspace");
        }

        File root = new File(homePath);
        if (!root.exists() || !root.isDirectory()) {
            return new WorkspaceProfile("Generic", "Standard Shell", new ArrayList<>(), false, "Path: " + homePath);
        }

        List<String> tech = new ArrayList<>();
        String projectType = "Generic Workspace";
        String buildSystem = "Shell Scripting";

        // Inspect marker files
        boolean hasGradle = new File(root, "build.gradle").exists() || new File(root, "build.gradle.kts").exists() || new File(root, "settings.gradle.kts").exists();
        boolean hasPackageJson = new File(root, "package.json").exists();
        boolean hasPom = new File(root, "pom.xml").exists();
        boolean hasCargo = new File(root, "Cargo.toml").exists();
        boolean hasPyproject = new File(root, "pyproject.toml").exists() || new File(root, "requirements.txt").exists();
        boolean hasGoMod = new File(root, "go.mod").exists();
        boolean hasCMake = new File(root, "CMakeLists.txt").exists();

        if (hasGradle) {
            boolean isAndroid = new File(root, "app/src/main/AndroidManifest.xml").exists() || new File(root, "AndroidManifest.xml").exists();
            projectType = isAndroid ? "Android (Jetpack Compose / Kotlin / Java)" : "JVM / Gradle Project";
            buildSystem = "Gradle Wrapper / CLI";
            tech.add("Kotlin/Java");
            if (isAndroid) tech.add("Android SDK");
        } else if (hasPackageJson) {
            projectType = "Node.js / Web Application";
            buildSystem = "npm / yarn / pnpm";
            tech.add("JavaScript / TypeScript");
        } else if (hasCargo) {
            projectType = "Rust Application";
            buildSystem = "Cargo";
            tech.add("Rust");
        } else if (hasGoMod) {
            projectType = "Go Application";
            buildSystem = "Go CLI (go build)";
            tech.add("Go");
        } else if (hasPyproject) {
            projectType = "Python Project";
            buildSystem = "pip / poetry / setuptools";
            tech.add("Python");
        } else if (hasPom) {
            projectType = "Maven JVM Project";
            buildSystem = "Maven (mvn)";
            tech.add("Java");
        } else if (hasCMake) {
            projectType = "C/C++ Project";
            buildSystem = "CMake";
            tech.add("C/C++");
        }

        String envSummary = "Local Linux/Android Environment (" + root.getAbsolutePath() + ")";
        return new WorkspaceProfile(projectType, buildSystem, tech, false, envSummary);
    }
}
