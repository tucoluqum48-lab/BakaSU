import com.diffplug.spotless.LineEnding

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.spotless)
}

spotless {
    lineEndings = LineEnding.UNIX

    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        ktlint(libs.versions.ktlint.get())
            .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:${libs.versions.compose.rules.get()}"))
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_compose_modifier-missing-check" to "disabled",
                    "ktlint_compose_compositionlocal-allowlist" to "disabled",
                    "ktlint_compose_mutable-state-param-check" to "disabled",
                    "ktlint_compose_parameter-naming" to "disabled",
                    "ktlint_compose_modifier-naming" to "disabled",
                ),
            )
    }

    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
}

extra["androidMinSdkVersion"] = 26
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidBuildToolsVersion"] = "36.1.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
// 与现有内核驱动保持一致，避免管理器判定"未安装"
// 内核侧升级到 35259 后，这里必须同步（当前驱动版本见管理器首页"内核驱动版本"）
extra["managerVersionCode"] = 35259
extra["managerVersionName"] = getGitDescribe()
extra["isPrBuild"] = project.findProperty("IS_PR_BUILD")?.toString()?.toBoolean() ?: false
extra["defaultManagerPackageName"] = "com.resukisu.resukisu"
extra["managerPackageName"] = project.findProperty("KSU_PACKAGE_NAME")?.toString() ?: extra["defaultManagerPackageName"]
extra["defaultManagerAppName"] = if (extra["isPrBuild"] == true) "BakaSU PR" else "BakaSU"
extra["managerName"] = project.findProperty("KSU_NAME")?.toString() ?: extra["defaultManagerAppName"]

val isSpoofedBuild = project.findProperty("IS_SPOOFED_BUILD")?.toString()?.toBoolean() ?: false

fun getGitCommitCount(): Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()

fun getGitDescribe(): String {
    val raw = providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--abbrev=0")
    }.standardOutput.asText.get().trim()
    // 无 tag 时 git describe 会回退成裸 commit sha，界面上显示成乱码版本号
    val desc = if (raw.matches(Regex("^[0-9a-f]{7,40}$"))) "v4.2.0-rc3" else raw
    if (isSpoofedBuild) {
        return "$desc-spoofed"
    }
    return desc
}
