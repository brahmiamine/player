plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("androidx.baselineprofile")
}

android {
    namespace = "fr.streamia.tv"
    compileSdk = 36

    // Clé de release réelle, fournie via des variables d'environnement (secrets GitHub Actions en
    // CI, ou export local pour une build manuelle) — jamais committée. Tant que ces variables sont
    // absentes (build d'un contributeur sans les secrets, PR venant d'un fork) `releaseSigning`
    // reste `null` et les variantes signées retombent sur la clé debug, comme avant : aucune build
    // locale ne casse. Voir docs/release-signing.md pour la procédure de génération de la clé.
    val releaseSigningEnv = listOfNotNull(
        System.getenv("RELEASE_STORE_FILE")?.takeIf(String::isNotBlank),
        System.getenv("RELEASE_STORE_PASSWORD")?.takeIf(String::isNotBlank),
        System.getenv("RELEASE_KEY_ALIAS")?.takeIf(String::isNotBlank),
        System.getenv("RELEASE_KEY_PASSWORD")?.takeIf(String::isNotBlank),
    ).takeIf { it.size == 4 }

    signingConfigs {
        if (releaseSigningEnv != null) {
            val (storeFilePath, storePasswordEnv, keyAliasEnv, keyPasswordEnv) = releaseSigningEnv
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    defaultConfig {
        applicationId = "fr.streamia.tv"
        minSdk = 23
        targetSdk = 36
        // Numéro de version = nombre de commits : il augmente à chaque commit et vaut la même chose
        // en local et en CI, donc chaque APK s'installe en mise à jour du précédent (Android refuse
        // un numéro plus petit). Repli sur 16 hors dépôt git.
        versionCode = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toIntOrNull()?.coerceAtLeast(16) ?: 16
        versionName = "1.5.8"

        // Jeton TMDB (lecture seule, gratuit, usage personnel) : secret GitHub `TMDB_TOKEN` en CI,
        // `tmdb.token` dans local.properties (ignoré par git) en local. Absent : fonctions TMDB désactivées.
        val tmdbToken = System.getenv("TMDB_TOKEN")?.takeIf(String::isNotBlank)
            ?: rootProject.file("local.properties").takeIf { it.exists() }
                ?.readLines()?.firstOrNull { it.startsWith("tmdb.token=") }?.substringAfter('=')?.trim()
            ?: ""
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        create("optimized") {
            initWith(getByName("release"))
            versionNameSuffix = "-optimized"
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        disable += "UnsafeOptInUsageError"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("fr")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Un APK release/optimized signé avec la clé debug ne doit jamais être publié : il serait
// incompatible avec les mises à jour signées et réinstallable par n'importe qui possédant une clé
// debug. Sans les variables RELEASE_*, ces builds échouent donc, sauf opt-in local explicite
// (`-PallowDebugSignedRelease`) pour un essai sur son propre boîtier.
gradle.taskGraph.whenReady {
    val signedVariantRequested = allTasks.any { task ->
        task.project == project && Regex("^(assemble|package|bundle)(Release|Optimized)$").matches(task.name)
    }
    // Les quatre valeurs, comme `releaseSigningEnv` : une seule manquante suffit à retomber sur la clé debug.
    val releaseKeyComplete = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
        .all { !System.getenv(it).isNullOrBlank() }
    if (signedVariantRequested && !releaseKeyComplete && !project.hasProperty("allowDebugSignedRelease")) {
        throw GradleException(
            "Clé de signature release absente (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, " +
                "RELEASE_KEY_PASSWORD). Voir docs/release-signing.md, ou -PallowDebugSignedRelease pour un essai local.",
        )
    }
}

androidComponents {
    // L'app est distribuée en APK direct (pas de Play Store pour découper par appareil) ; les
    // boîtiers/clés Android TV sont quasi exclusivement ARM, donc on n'embarque pas les .so
    // x86/x86_64 (ExoPlayer, Crashlytics NDK) dans l'APK diffusé, pour en réduire la taille.
    // Limité aux variantes distribuées : le debug garde toutes les ABI pour rester installable
    // sur les émulateurs x86/x86_64 courants sur poste Intel/AMD.
    onVariants { variant ->
        if (variant.buildType == "optimized" || variant.buildType == "release") {
            variant.packaging.jniLibs.excludes.addAll(setOf("**/x86/**", "**/x86_64/**"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    val media3Version = "1.11.0"

    implementation(composeBom)
    androidTestImplementation(composeBom)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.17.0")
    // Rangée « Continuer à regarder » de l'accueil Google TV (Watch Next).
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    // Installe le baseline profile sur les APK installés hors Play Store (distribution directe).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    // Décodeur audio logiciel (AC3, E-AC3, DTS, MP2…) : beaucoup de boîtiers et flux IPTV n'ont pas
    // de décodeur matériel pour ces formats — sans lui, la vidéo joue sans le moindre son.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-crashlytics")

    // Parseur HTML tolérant pour le scrape de liveonsat.com (balisage ancien, tables imbriquées,
    // balises non refermées) : voir fr.streamia.tv.liveonsat.LiveOnSatParser.
    implementation("org.jsoup:jsoup:1.18.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
