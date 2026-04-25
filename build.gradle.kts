import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.metro) apply false
    alias(libs.plugins.sweet.spi) apply false
    alias(libs.plugins.detekt)
}

// Apply detekt to every subproject. The shared `detekt.yml` enforces the
// `ForbiddenImport` rule that bans direct references to the SDK wiring
// (`MultiModuleSdkH`) from any module other than :sdk:wiring-h itself or
// sample apps demonstrating consumption.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        toolVersion = rootProject.libs.versions.detekt.get()
        config.setFrom(rootProject.files("detekt.yml"))
        buildUponDefaultConfig = true
        ignoreFailures = false
        autoCorrect = false
    }

    tasks.withType<Detekt>().configureEach {
        exclude("**/build/**")
        exclude("**/generated/**")
        reports {
            html.required.set(false)
            xml.required.set(false)
            txt.required.set(false)
            sarif.required.set(true)
        }
    }
}

// =====================================================================
// generateDependencyGraph + verifyDependencyGraph — root tasks
// =====================================================================
// Auto-generates `docs/generated/dependency-graph.md` (+ `.dot`) from the
// FeatureProvider sources. The `verify` variant fails CI when the
// committed graph drifts from what the script would emit today — useful
// to catch a new `resolver.get(...)` introduced without doc update.

val pythonExecutable = providers.environmentVariable("PYTHON")
    .orElse(if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3")

tasks.register<Exec>("generateDependencyGraph") {
    group = "documentation"
    description = "Regenerate docs/generated/dependency-graph.{md,dot} " +
        "from the FeatureProvider sources."
    workingDir = projectDir
    commandLine(pythonExecutable.get(), "scripts/generate-dependency-graph.py")
}

tasks.register("verifyDependencyGraph") {
    group = "verification"
    description = "Fails when the committed dependency-graph.md is stale " +
        "relative to the FeatureProvider sources."
    dependsOn("generateDependencyGraph")
    doLast {
        val mdFile = file("docs/generated/dependency-graph.md")
        val result = providers.exec {
            workingDir = projectDir
            commandLine("git", "diff", "--exit-code", "--", mdFile.path)
            isIgnoreExitValue = true
        }
        if (result.result.get().exitValue != 0) {
            throw GradleException(
                "verifyDependencyGraph FAILED — docs/generated/dependency-graph.md " +
                "is out of date. Run `./gradlew generateDependencyGraph` and commit " +
                "the diff."
            )
        }
        logger.lifecycle("verifyDependencyGraph OK — dependency graph is up to date.")
    }
}
