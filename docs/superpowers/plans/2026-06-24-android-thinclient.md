# IronLog V2 — Android Thin-Client (v0.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Compose smoke-test Android client at `~/projects/IronLog-V2-Client` that exercises every endpoint the IronLog-V2 FastAPI server exposes, installs alongside the existing v1 app (`com.jauschua.ironlog`), and verifies end-to-end against `uvicorn` on `myflix.media:8000`.

**Architecture:** Single-module Compose Material3 app; one Activity with a bottom-nav `Scaffold` over a `NavHost`; ViewModels exposing `StateFlow<UiState<T>>`; manual DI through an `AppContainer` on the `Application`; Ktor 3.x HTTP client with the OkHttp engine and kotlinx-serialization. No persistence in v0.1 (no Room, no DataStore).

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, Compose BOM 2024.12.01, Navigation Compose, Ktor 3.0.x (OkHttp engine), kotlinx-serialization 1.7.3, Java 17 toolchain, JUnit 4 with Ktor `MockEngine` for tests.

## Global Constraints

Carried verbatim from the spec (`docs/superpowers/specs/2026-06-24-android-thinclient-design.md`) and `CLAUDE.md`. Every task's requirements implicitly include this section.

- **`applicationId` = `com.jauschua.ironlogv2`**, **`namespace` = `com.jauschua.ironlogv2`**, app label `"IronLog V2"`.
- **`compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`, `versionCode = 1`, `versionName = "0.1.0"`**, Java 17, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, Gradle 8.11.1.
- **Kotlin 2.x requires the Compose compiler Gradle plugin** (`org.jetbrains.kotlin.plugin.compose`) — the legacy `composeOptions { kotlinCompilerExtensionVersion }` block must not be used.
- **HTTP base URL** = `http://myflix.media:8000`, baked into `BuildConfig.SERVER_BASE_URL`. Cleartext is whitelisted **only** for `myflix.media` and `192.168.1.7` via `res/xml/network_security_config.xml`.
- **All DTO field names are snake_case** to match the FastAPI/SQLModel JSON — no `@SerialName` annotations. JSON config uses `ignoreUnknownKeys = true`, `coerceInputValues = true`, `explicitNulls = false`.
- **Autoregulate picker filters to `progression_mode == LADDER`** — composite (Hip Thrust) and assisted (Pull-up) movements are excluded from the picker.
- **Tier stepper max** = `selected.increment_ladder.size - 1` (Lateral Raise has a one-rung ladder, so its tier is fixed at 0).
- **Repos return `Result<T>`**; ViewModels map `Result.failure` to `UiState.Error` with a retry snackbar. No silent failures.
- **No persistence in v0.1.** No Room, no DataStore, no on-device caching beyond per-screen ViewModel state.
- **Out of scope** (per spec §10): login/auth, local DB, workout-logging UI, session generation UI, validator UI, build flavors, crash reporting, ProGuard rules beyond the default release template.
- **Cleartext is intentional**: the server is plain `uvicorn` on the home LAN. Do not add HTTPS handling, certificate pinning, or proxy auth.

---

## File structure

Created over the course of this plan. Paths relative to `~/projects/IronLog-V2-Client`.

```
.gitignore                                              # Task 1
README.md                                               # Task 1
build.gradle.kts                                        # Task 1 (root)
settings.gradle.kts                                     # Task 1
gradle.properties                                       # Task 1
gradle/libs.versions.toml                               # Task 1
gradle/wrapper/gradle-wrapper.properties                # Task 1 (copied)
gradle/wrapper/gradle-wrapper.jar                       # Task 1 (copied)
gradlew                                                 # Task 1 (copied)
gradlew.bat                                             # Task 1 (copied)
app/build.gradle.kts                                    # Task 1
app/proguard-rules.pro                                  # Task 1 (empty stub)
app/src/main/AndroidManifest.xml                        # Task 1
app/src/main/res/values/strings.xml                     # Task 1
app/src/main/res/values/themes.xml                      # Task 1
app/src/main/res/values/colors.xml                      # Task 1
app/src/main/res/xml/network_security_config.xml        # Task 1
app/src/main/res/xml/backup_rules.xml                   # Task 1
app/src/main/res/xml/data_extraction_rules.xml          # Task 1
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml      # Task 1
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml# Task 1
app/src/main/res/drawable/ic_launcher_foreground.xml    # Task 1
app/src/main/res/drawable/ic_launcher_background.xml    # Task 1
app/src/main/java/com/jauschua/ironlogv2/
    IronLogV2Application.kt                             # Task 1 (empty), Task 3 (wires AppContainer)
    ui/MainActivity.kt                                  # Task 1
    ui/theme/Theme.kt                                   # Task 1
    data/api/dto/Models.kt                              # Task 2
    data/api/ApiError.kt                                # Task 3
    data/api/ApiClient.kt                               # Task 3
    di/AppContainer.kt                                  # Task 3
    data/repo/LibraryRepo.kt                            # Task 4
    data/repo/AutoregRepo.kt                            # Task 4
    ui/UiState.kt                                       # Task 5
    ui/Nav.kt                                           # Task 5 (route constants + bottom-nav items)
    ui/screens/movements/MovementsListScreen.kt         # Task 5
    ui/screens/movements/MovementsListViewModel.kt      # Task 5
    ui/screens/movement_detail/MovementDetailScreen.kt  # Task 6
    ui/screens/movement_detail/MovementDetailViewModel.kt # Task 6
    ui/screens/bands/BandsScreen.kt                     # Task 7
    ui/screens/bands/BandsViewModel.kt                  # Task 7
    ui/screens/autoregulate/AutoregulateScreen.kt       # Task 8
    ui/screens/autoregulate/AutoregulateViewModel.kt    # Task 8
app/src/test/java/com/jauschua/ironlogv2/
    data/api/dto/ModelsTest.kt                          # Task 2
    data/repo/LibraryRepoTest.kt                        # Task 4
    data/repo/AutoregRepoTest.kt                        # Task 4
app/src/test/resources/
    fixtures/movements.json                             # Task 4
    fixtures/movement_back_squat.json                   # Task 4
    fixtures/bands_usable.json                          # Task 4
docs/v0.1-smoke-test-results.md                         # Task 9
```

---

### Task 1: Project skeleton — buildable, launchable, three-tab APK

**Files:**
- Create: all root Gradle files, wrapper (copied from `~/projects/IronLog`), `app/build.gradle.kts`, manifest, XML resources, launcher icons, `IronLogV2Application.kt`, `MainActivity.kt`, `ui/theme/Theme.kt`, `.gitignore`, `README.md`.

**Interfaces:**
- Consumes: nothing.
- Produces: `IronLogV2Application` (Application subclass — empty for now, AppContainer slot reserved as `val container: AppContainer by lazy { ... }` filled in Task 3). `MainActivity` (renders three placeholder tabs). `BuildConfig.SERVER_BASE_URL: String` (compile-time string constant `"http://myflix.media:8000"`).

- [ ] **Step 1: Copy Gradle wrapper from `~/projects/IronLog`**

```bash
cd ~/projects/IronLog-V2-Client
cp ~/projects/IronLog/gradlew ~/projects/IronLog/gradlew.bat .
chmod +x gradlew
mkdir -p gradle/wrapper
cp ~/projects/IronLog/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
cp ~/projects/IronLog/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
```

Verify: `cat gradle/wrapper/gradle-wrapper.properties` shows `distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip`.

- [ ] **Step 2: Create `.gitignore`**

Create `.gitignore`:
```
*.iml
.gradle/
local.properties
.idea/
.DS_Store
build/
captures/
.externalNativeBuild/
.cxx/
*.apk
*.aab
output.json
release/
app/release/
*.jks
*.keystore
```

- [ ] **Step 3: Create `settings.gradle.kts`**

Create `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "IronLog-V2-Client"
include(":app")
```

- [ ] **Step 4: Create root `build.gradle.kts`**

Create `build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
```

- [ ] **Step 5: Create `gradle.properties`**

Create `gradle.properties`:
```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 6: Create `gradle/libs.versions.toml`**

Create `gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
composeBom = "2024.12.01"
lifecycle = "2.8.7"
navigation = "2.8.5"
coroutines = "1.9.0"
ktor = "3.0.3"
kotlinxSerialization = "1.7.3"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.15.0" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.3" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-logging = { group = "io.ktor", name = "ktor-client-logging", version.ref = "ktor" }
ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 7: Create `app/build.gradle.kts`**

Create `app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.jauschua.ironlogv2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jauschua.ironlogv2"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SERVER_BASE_URL", "\"http://myflix.media:8000\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 8: Create `app/proguard-rules.pro` (empty stub)**

Create `app/proguard-rules.pro`:
```
# v0.1: no app-specific ProGuard rules. Release minify uses default templates.
```

- [ ] **Step 9: Create `app/src/main/AndroidManifest.xml`**

Create `app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".IronLogV2Application"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/Theme.IronLogV2">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.IronLogV2">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 10: Create resource XML files**

Create `app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">IronLog V2</string>
</resources>
```

Create `app/src/main/res/values/colors.xml`:
```xml
<resources>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
</resources>
```

Create `app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.IronLogV2" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

Create `app/src/main/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">myflix.media</domain>
        <domain includeSubdomains="false">192.168.1.7</domain>
    </domain-config>
</network-security-config>
```

Create `app/src/main/res/xml/backup_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content />
```

Create `app/src/main/res/xml/data_extraction_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup />
    <device-transfer />
</data-extraction-rules>
```

- [ ] **Step 11: Create adaptive launcher icon**

Create `app/src/main/res/drawable/ic_launcher_background.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#1F3A93" />
</shape>
```

Create `app/src/main/res/drawable/ic_launcher_foreground.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M30,40 L78,40 L78,68 L30,68 Z M44,30 L64,30 L64,78 L44,78 Z" />
</vector>
```

Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 12: Create `IronLogV2Application.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/IronLogV2Application.kt`:
```kotlin
package com.jauschua.ironlogv2

import android.app.Application

class IronLogV2Application : Application() {
    // Task 3 replaces this stub with: val container by lazy { AppContainer() }
}
```

- [ ] **Step 13: Create `ui/theme/Theme.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/theme/Theme.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun IronLogV2Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

- [ ] **Step 14: Create `MainActivity.kt` with three-tab bottom-nav scaffold and placeholder screens**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt`:
```kotlin
package com.jauschua.ironlogv2.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jauschua.ironlogv2.ui.theme.IronLogV2Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IronLogV2Theme {
                RootScaffold()
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("movements", "Movements", Icons.Filled.FitnessCenter),
    Tab("bands", "Bands", Icons.Filled.Sync),
    Tab("autoregulate", "Autoregulate", Icons.Filled.Calculate),
)

@Composable
private fun RootScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = "movements",
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            composable("movements") { Text("Movements (placeholder)") }
            composable("bands") { Text("Bands (placeholder)") }
            composable("autoregulate") { Text("Autoregulate (placeholder)") }
        }
    }
}
```

- [ ] **Step 15: Create `README.md`**

Create `README.md`:
```markdown
# IronLog V2 — Android Client

Smoke-test thin client for the IronLog V2 FastAPI server.

See `docs/superpowers/specs/2026-06-24-android-thinclient-design.md` for design,
`CLAUDE.md` for the working agreement, `docs/superpowers/plans/2026-06-24-android-thinclient.md`
for the build plan.

## Build

    ./gradlew :app:assembleDebug

## Install

    adb install -r app/build/outputs/apk/debug/app-debug.apk

Installs alongside the v1 app (`com.jauschua.ironlog`); this app's id is
`com.jauschua.ironlogv2`, label "IronLog V2".

## Server

The app talks to `http://myflix.media:8000`. Start the server with:

    uvicorn ironlog.api.app:app --host 0.0.0.0 --port 8000
```

- [ ] **Step 16: Verify build succeeds**

Run: `cd ~/projects/IronLog-V2-Client && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (first run will download Gradle 8.11.1 and dependencies — may take several minutes).

If the build fails on Java version, run `./gradlew -version` to confirm JDK 17 is in use; install or set `JAVA_HOME` to a JDK 17 if needed.

- [ ] **Step 17: Verify APK package metadata**

Run:
```
~/Android/Sdk/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | head -3
```
(Adjust the build-tools path if your SDK lives elsewhere; `ls ~/Android/Sdk/build-tools/` to discover the version.)

Expected output starts with:
```
package: name='com.jauschua.ironlogv2' versionCode='1' versionName='0.1.0'
application-label:'IronLog V2'
```

- [ ] **Step 18: Manual install + smoke test alongside v1**

Connect a phone with USB debugging enabled, then:
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm list packages | grep ironlog
```
Expected: both `package:com.jauschua.ironlog` and `package:com.jauschua.ironlogv2` listed.

Launch "IronLog V2" from the app drawer. Expected: three-tab bottom nav with "Movements", "Bands", "Autoregulate" labels; each tab shows its placeholder text. No crash on tab switches.

- [ ] **Step 19: Commit**

```bash
cd ~/projects/IronLog-V2-Client
git add -A
git commit -m "feat: project skeleton — buildable APK with three-tab bottom nav

Gradle 8.11.1 wrapper, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01,
kotlin-serialization plugin, Ktor 3.0.3 deps wired (unused until Task 3).
Material3 theme with dynamic color on API 31+. Manifest declares INTERNET
and a network_security_config that whitelists cleartext only for
myflix.media and 192.168.1.7. applicationId com.jauschua.ironlogv2 installs
alongside the v1 app.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: DTOs and enums with serialization round-trip tests

**Files:**
- Create: `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/Models.kt`
- Test: `app/src/test/java/com/jauschua/ironlogv2/data/api/dto/ModelsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `MovementDto`, `BandPairDto`, `NextSetRequest`, `NextSetResponse`, plus the enums `Region`, `LiftCategory`, `FeedbackTap`, `BandCalStatus`, `ProgressionMode`, `Scheme`, `Objective`, `Phase`, `Status`, `AssistSubtype`, `AssistUnit`. All `@Serializable`. Field names are snake_case verbatim — repos in Task 4 deserialize server JSON into these types.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jauschua/ironlogv2/data/api/dto/ModelsTest.kt`:
```kotlin
package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun parsesBackSquatMovement() {
        val raw = """
        {
          "id": 1,
          "name": "Back Squat [PB]",
          "base_name": "Back Squat",
          "region": "LOWER",
          "lift_category": "BACK_SQUAT",
          "is_primary": true,
          "is_tracked": true,
          "status": "ACTIVE",
          "load_equipment_id": 1,
          "progression_mode": "LADDER",
          "scheme": "TOPSET_BACKOFF",
          "objective_override": null,
          "increment_ladder": [10.0, 5.0, 2.5],
          "min_step": 2.5,
          "load_floor": 45.0,
          "cap": null,
          "rpe_capped": true,
          "rpe_cap_exempt": false,
          "equipment_tags": ["PB"],
          "family": "back_squat",
          "is_family_anchor": true,
          "derived_from_id": null,
          "start_ratio": null,
          "band_eligible": false,
          "notes": null,
          "unknown_future_field": "ignored"
        }
        """.trimIndent()

        val m = json.decodeFromString(MovementDto.serializer(), raw)

        assertEquals(1, m.id)
        assertEquals("Back Squat [PB]", m.name)
        assertEquals(Region.LOWER, m.region)
        assertEquals(LiftCategory.BACK_SQUAT, m.lift_category)
        assertEquals(true, m.is_primary)
        assertEquals(Status.ACTIVE, m.status)
        assertEquals(ProgressionMode.LADDER, m.progression_mode)
        assertEquals(Scheme.TOPSET_BACKOFF, m.scheme)
        assertEquals(listOf(10.0, 5.0, 2.5), m.increment_ladder)
        assertEquals(45.0, m.load_floor!!, 0.0)
        assertEquals(listOf("PB"), m.equipment_tags)
        assertEquals("back_squat", m.family)
    }

    @Test
    fun parsesHipThrustWithBandEligible() {
        val raw = """
        {
          "id": 2,
          "name": "Hip Thrust [HIP_THRUST]",
          "base_name": "Hip Thrust",
          "region": "LOWER",
          "lift_category": "HIP_THRUST",
          "progression_mode": "COMPOSITE",
          "scheme": "STRAIGHT",
          "rpe_cap_exempt": true,
          "band_eligible": true,
          "equipment_tags": ["HIP_THRUST"]
        }
        """.trimIndent()
        val m = json.decodeFromString(MovementDto.serializer(), raw)
        assertEquals(LiftCategory.HIP_THRUST, m.lift_category)
        assertEquals(ProgressionMode.COMPOSITE, m.progression_mode)
        assertTrue(m.rpe_cap_exempt)
        assertTrue(m.band_eligible)
    }

    @Test
    fun parsesPullupAssisted() {
        val raw = """
        {
          "id": 5,
          "name": "Pull-up [TOWER + TUBES]",
          "base_name": "Pull-up",
          "region": "UPPER",
          "lift_category": "NONE",
          "progression_mode": "ASSISTED",
          "assist_subtype": "REP_RATIO",
          "scheme": "REP_RATIO",
          "objective_override": "PROGRESS"
        }
        """.trimIndent()
        val m = json.decodeFromString(MovementDto.serializer(), raw)
        assertEquals(ProgressionMode.ASSISTED, m.progression_mode)
        assertEquals(AssistSubtype.REP_RATIO, m.assist_subtype)
        assertEquals(Objective.PROGRESS, m.objective_override)
    }

    @Test
    fun parsesBandPair() {
        val raw = """
        { "id": 1, "label": "#0 Orange", "bottom_lb": 14.0, "peak_lb": 30.0,
          "calibration_status": "MODELED", "usable": true }
        """.trimIndent()
        val b = json.decodeFromString(BandPairDto.serializer(), raw)
        assertEquals("#0 Orange", b.label)
        assertEquals(14.0, b.bottom_lb, 0.0)
        assertEquals(BandCalStatus.MODELED, b.calibration_status)
    }

    @Test
    fun serializesNextSetRequest() {
        val req = NextSetRequest(movement_id = 1, current_load = 100.0, tap = FeedbackTap.ON_TARGET, tier = 0)
        val out = json.encodeToString(NextSetRequest.serializer(), req)
        // exact JSON: key order is the declaration order under kotlinx-serialization
        assertEquals("""{"movement_id":1,"current_load":100.0,"tap":"ON_TARGET","tier":0}""", out)
    }

    @Test
    fun parsesNextSetResponse() {
        val raw = """{"suggested_load": 105.0}"""
        val r = json.decodeFromString(NextSetResponse.serializer(), raw)
        assertEquals(105.0, r.suggested_load, 0.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jauschua.ironlogv2.data.api.dto.ModelsTest'`
Expected: compilation failure — `MovementDto`, `BandPairDto`, etc. unresolved.

- [ ] **Step 3: Create `Models.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/Models.kt`:
```kotlin
package com.jauschua.ironlogv2.data.api.dto

import kotlinx.serialization.Serializable

@Serializable enum class Region { UPPER, LOWER, CORE, NONE }

@Serializable enum class LiftCategory {
    BENCH, BACK_SQUAT, FRONT_SQUAT, OHP, RDL, DEADLIFT, ROW,
    HIP_THRUST, REV_HYPER, CG_PRESS, NONE
}

@Serializable enum class Status { ACTIVE, INACTIVE, PREP }

@Serializable enum class ProgressionMode {
    LADDER, COMPOSITE, ASSISTED, PROTOCOL, CONDITIONING, NONE
}

@Serializable enum class Scheme {
    STRAIGHT, DOUBLE_PROGRESSION, TOPSET_BACKOFF, UNDULATION, WAVE, REP_RATIO
}

@Serializable enum class Objective { MAINTAIN, PROGRESS, MEASURE }

@Serializable enum class Phase { CALIBRATION, CUT, STAB, REBUILD }

@Serializable enum class FeedbackTap { TOO_EASY, ON_TARGET, TOO_HARD }

@Serializable enum class BandCalStatus { MODELED, MEASURED }

@Serializable enum class AssistSubtype { CONTINUOUS, REP_RATIO }

@Serializable enum class AssistUnit { DEGREES, CABLE_LB, TUBE_COUNT, REP_COUNT }

@Serializable
data class MovementDto(
    val id: Int,
    val name: String,
    val base_name: String,
    val region: Region = Region.NONE,
    val lift_category: LiftCategory = LiftCategory.NONE,
    val is_primary: Boolean = false,
    val is_tracked: Boolean = true,
    val status: Status = Status.ACTIVE,
    val load_equipment_id: Int? = null,
    val progression_mode: ProgressionMode = ProgressionMode.NONE,
    val assist_subtype: AssistSubtype? = null,
    val assist_unit: AssistUnit? = null,
    val scheme: Scheme = Scheme.STRAIGHT,
    val objective_override: Objective? = null,
    val increment_ladder: List<Double> = emptyList(),
    val min_step: Double? = null,
    val load_floor: Double? = null,
    val cap: Double? = null,
    val rpe_capped: Boolean = false,
    val rpe_cap_exempt: Boolean = false,
    val equipment_tags: List<String> = emptyList(),
    val family: String? = null,
    val is_family_anchor: Boolean = false,
    val derived_from_id: Int? = null,
    val start_ratio: Double? = null,
    val band_eligible: Boolean = false,
    val notes: String? = null,
)

@Serializable
data class BandPairDto(
    val id: Int,
    val label: String,
    val bottom_lb: Double,
    val peak_lb: Double,
    val calibration_status: BandCalStatus = BandCalStatus.MODELED,
    val usable: Boolean = true,
)

@Serializable
data class NextSetRequest(
    val movement_id: Int,
    val current_load: Double,
    val tap: FeedbackTap,
    val tier: Int = 0,
)

@Serializable
data class NextSetResponse(val suggested_load: Double)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jauschua.ironlogv2.data.api.dto.ModelsTest'`
Expected: `Tests passed: 6`.

If `serializesNextSetRequest` fails on key ordering, kotlinx-serialization preserves declaration order — re-check that `NextSetRequest`'s field declaration order is exactly `movement_id, current_load, tap, tier`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jauschua/ironlogv2/data/api/dto/Models.kt \
        app/src/test/java/com/jauschua/ironlogv2/data/api/dto/ModelsTest.kt
git commit -m "feat(dto): add API DTOs and enums with serialization round-trip tests

MovementDto carries every server field a screen renders (status,
load_equipment_id, objective_override, band_eligible, assist_subtype/unit
included per the design's signature-flags rule). BandPairDto,
NextSetRequest, NextSetResponse complete the v0.1 contract. All
snake_case to match FastAPI/SQLModel; @SerialName not needed.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: ApiClient + ApiError + AppContainer wiring

**Files:**
- Create: `app/src/main/java/com/jauschua/ironlogv2/data/api/ApiError.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/data/api/ApiClient.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/IronLogV2Application.kt`

**Interfaces:**
- Consumes: `BuildConfig.SERVER_BASE_URL` from Task 1.
- Produces:
  - `sealed interface ApiError { Network, Server(status, body), Client(status, body), Timeout, Parse }` plus `suspend fun <T> runCatchingApi(block: suspend () -> T): Result<T>` that catches Ktor exceptions and wraps as `Result.failure(IronLogException(error))`.
  - `class ApiClient(baseUrl: String, json: Json)` exposing `val http: HttpClient`.
  - `class AppContainer` exposing `val apiClient: ApiClient`; Task 4 adds `val libraryRepo` and `val autoregRepo`.
  - `IronLogV2Application.container: AppContainer` (lazily initialized).

- [ ] **Step 1: Create `ApiError.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/data/api/ApiError.kt`:
```kotlin
package com.jauschua.ironlogv2.data.api

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.serialization.SerializationException
import java.io.IOException

sealed interface ApiError {
    data class Network(val cause: Throwable) : ApiError
    data class Server(val status: Int, val body: String?) : ApiError
    data class Client(val status: Int, val body: String?) : ApiError
    data class Timeout(val cause: Throwable) : ApiError
    data class Parse(val cause: Throwable) : ApiError
}

class IronLogException(val error: ApiError) : Exception(error.toString())

/** Wrap a Ktor call so [Result.failure] carries an [IronLogException] with a typed [ApiError]. */
suspend fun <T> runCatchingApi(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: HttpRequestTimeoutException) {
    Result.failure(IronLogException(ApiError.Timeout(e)))
} catch (e: ConnectTimeoutException) {
    Result.failure(IronLogException(ApiError.Timeout(e)))
} catch (e: SocketTimeoutException) {
    Result.failure(IronLogException(ApiError.Timeout(e)))
} catch (e: ClientRequestException) {
    Result.failure(IronLogException(ApiError.Client(e.response.status.value, runCatching { e.response.toString() }.getOrNull())))
} catch (e: ServerResponseException) {
    Result.failure(IronLogException(ApiError.Server(e.response.status.value, runCatching { e.response.toString() }.getOrNull())))
} catch (e: SerializationException) {
    Result.failure(IronLogException(ApiError.Parse(e)))
} catch (e: IOException) {
    Result.failure(IronLogException(ApiError.Network(e)))
}

/** Human-readable message for snackbars. */
fun ApiError.humanMessage(): String = when (this) {
    is ApiError.Network -> "Network error — is the server reachable?"
    is ApiError.Timeout -> "Request timed out"
    is ApiError.Server  -> "Server error ($status)"
    is ApiError.Client  -> "Bad request ($status)"
    is ApiError.Parse   -> "Couldn't parse server response"
}
```

- [ ] **Step 2: Create `ApiClient.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/data/api/ApiClient.kt`:
```kotlin
package com.jauschua.ironlogv2.data.api

import com.jauschua.ironlogv2.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiClient(
    baseUrl: String = BuildConfig.SERVER_BASE_URL,
    engine: HttpClientEngine? = null,
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val http: HttpClient = if (engine != null) {
        HttpClient(engine) { configure(baseUrl) }
    } else {
        HttpClient(OkHttp) { configure(baseUrl) }
    }

    private fun io.ktor.client.HttpClientConfig<*>.configure(baseUrl: String) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis  = 10_000
            requestTimeoutMillis = 10_000
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
        }
        defaultRequest { url(baseUrl) }
    }
}
```

- [ ] **Step 3: Create `AppContainer.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt`:
```kotlin
package com.jauschua.ironlogv2.di

import com.jauschua.ironlogv2.data.api.ApiClient

class AppContainer {
    val apiClient: ApiClient by lazy { ApiClient() }
    // Task 4 adds:
    //   val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    //   val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }
}
```

- [ ] **Step 4: Wire `AppContainer` into the Application**

Replace `app/src/main/java/com/jauschua/ironlogv2/IronLogV2Application.kt` with:
```kotlin
package com.jauschua.ironlogv2

import android.app.Application
import com.jauschua.ironlogv2.di.AppContainer

class IronLogV2Application : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
```

- [ ] **Step 5: Verify build succeeds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. No runtime exercise yet — Task 4 introduces the repos that actually use `ApiClient`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jauschua/ironlogv2/data/api/ApiError.kt \
        app/src/main/java/com/jauschua/ironlogv2/data/api/ApiClient.kt \
        app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt \
        app/src/main/java/com/jauschua/ironlogv2/IronLogV2Application.kt
git commit -m "feat(api): Ktor + OkHttp client, typed ApiError, AppContainer DI

ApiClient owns the singleton HttpClient (OkHttp engine, 5s connect /
10s socket+request timeouts, kotlinx JSON, Logging plugin at HEADERS
in debug). ApiError sealed interface + runCatchingApi() collapse every
Ktor exception path into Result<T> with a typed cause. AppContainer is
created lazily on Application; ApiClient construction accepts an
injectable engine so Task 4 can swap MockEngine in tests.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: LibraryRepo + AutoregRepo with MockEngine unit tests

**Files:**
- Create: `app/src/main/java/com/jauschua/ironlogv2/data/repo/LibraryRepo.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/data/repo/AutoregRepo.kt`
- Create: `app/src/test/java/com/jauschua/ironlogv2/data/repo/LibraryRepoTest.kt`
- Create: `app/src/test/java/com/jauschua/ironlogv2/data/repo/AutoregRepoTest.kt`
- Create: `app/src/test/resources/fixtures/movements.json`
- Create: `app/src/test/resources/fixtures/movement_back_squat.json`
- Create: `app/src/test/resources/fixtures/bands_usable.json`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt`

**Interfaces:**
- Consumes: `ApiClient`, `MovementDto`, `BandPairDto`, `NextSetRequest`, `NextSetResponse`, `runCatchingApi`.
- Produces:
  - `class LibraryRepo(apiClient: ApiClient)` with `suspend fun movements(): Result<List<MovementDto>>`, `suspend fun getMovement(id: Int): Result<MovementDto>`, `suspend fun usableBands(): Result<List<BandPairDto>>`.
  - `class AutoregRepo(apiClient: ApiClient)` with `suspend fun nextSet(req: NextSetRequest): Result<NextSetResponse>`.
  - `AppContainer.libraryRepo`, `AppContainer.autoregRepo` (lazy).

- [ ] **Step 1: Create test fixtures**

Create `app/src/test/resources/fixtures/movements.json`:
```json
[
  {
    "id": 1, "name": "Back Squat [PB]", "base_name": "Back Squat",
    "region": "LOWER", "lift_category": "BACK_SQUAT",
    "is_primary": true, "progression_mode": "LADDER",
    "scheme": "TOPSET_BACKOFF",
    "increment_ladder": [10.0, 5.0, 2.5], "min_step": 2.5, "load_floor": 45.0,
    "equipment_tags": ["PB"]
  },
  {
    "id": 2, "name": "Hip Thrust [HIP_THRUST]", "base_name": "Hip Thrust",
    "region": "LOWER", "lift_category": "HIP_THRUST",
    "progression_mode": "COMPOSITE", "scheme": "STRAIGHT",
    "rpe_cap_exempt": true, "band_eligible": true,
    "equipment_tags": ["HIP_THRUST"]
  },
  {
    "id": 3, "name": "Lateral Raise [FT]", "base_name": "Lateral Raise",
    "region": "UPPER", "progression_mode": "LADDER",
    "scheme": "DOUBLE_PROGRESSION",
    "increment_ladder": [2.5], "min_step": 2.5, "load_floor": 10.0,
    "equipment_tags": ["FT"]
  }
]
```

Create `app/src/test/resources/fixtures/movement_back_squat.json`:
```json
{
  "id": 1, "name": "Back Squat [PB]", "base_name": "Back Squat",
  "region": "LOWER", "lift_category": "BACK_SQUAT",
  "is_primary": true, "progression_mode": "LADDER",
  "scheme": "TOPSET_BACKOFF",
  "increment_ladder": [10.0, 5.0, 2.5], "min_step": 2.5, "load_floor": 45.0,
  "equipment_tags": ["PB"], "family": "back_squat", "is_family_anchor": true
}
```

Create `app/src/test/resources/fixtures/bands_usable.json`:
```json
[
  {"id":1,"label":"#0 Orange","bottom_lb":14.0,"peak_lb":30.0,"calibration_status":"MODELED","usable":true},
  {"id":2,"label":"#1 Red","bottom_lb":29.0,"peak_lb":60.0,"calibration_status":"MODELED","usable":true},
  {"id":3,"label":"#2 Blue","bottom_lb":47.0,"peak_lb":100.0,"calibration_status":"MODELED","usable":true},
  {"id":4,"label":"#3 Green","bottom_lb":63.0,"peak_lb":133.0,"calibration_status":"MODELED","usable":true},
  {"id":5,"label":"#4 Black","bottom_lb":102.0,"peak_lb":217.0,"calibration_status":"MODELED","usable":true}
]
```

- [ ] **Step 2: Write the failing LibraryRepo test**

Create `app/src/test/java/com/jauschua/ironlogv2/data/repo/LibraryRepoTest.kt`:
```kotlin
package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.ApiError
import com.jauschua.ironlogv2.data.api.IronLogException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepoTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().readText()

    private fun jsonEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockEngine = MockEngine {
        respond(content = ByteReadChannel(body), status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun movements_parsesListSuccessfully() = runTest {
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = jsonEngine(fixture("movements.json"))))
        val result = repo.movements()
        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(3, list.size)
        assertEquals("Back Squat [PB]", list[0].name)
    }

    @Test
    fun getMovement_parsesSingleSuccessfully() = runTest {
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = jsonEngine(fixture("movement_back_squat.json"))))
        val result = repo.getMovement(1)
        assertTrue(result.isSuccess)
        assertEquals("Back Squat [PB]", result.getOrThrow().name)
    }

    @Test
    fun usableBands_parsesSuccessfully() = runTest {
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = jsonEngine(fixture("bands_usable.json"))))
        val result = repo.usableBands()
        assertTrue(result.isSuccess)
        assertEquals(5, result.getOrThrow().size)
    }

    @Test
    fun movements_serverErrorMapsToServerApiError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = engine))
        val result = repo.movements()
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as IronLogException).error
        assertTrue("expected Server error, got $err", err is ApiError.Server)
        assertEquals(500, (err as ApiError.Server).status)
    }

    @Test
    fun movements_malformedJsonMapsToParseError() = runTest {
        val engine = jsonEngine("not valid json{")
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = engine))
        val result = repo.movements()
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as IronLogException).error
        assertTrue("expected Parse error, got $err", err is ApiError.Parse)
    }

    @Test
    fun getMovement_clientErrorMapsToClientApiError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        val repo = LibraryRepo(ApiClient(baseUrl = "http://test", engine = engine))
        val result = repo.getMovement(999)
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as IronLogException).error
        assertNotNull(err)
        assertTrue("expected Client error, got $err", err is ApiError.Client)
        assertEquals(404, (err as ApiError.Client).status)
    }
}
```

- [ ] **Step 3: Write the failing AutoregRepo test**

Create `app/src/test/java/com/jauschua/ironlogv2/data/repo/AutoregRepoTest.kt`:
```kotlin
package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.NextSetRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoregRepoTest {

    @Test
    fun nextSet_postsExpectedBodyAndParsesResponse() = runTest {
        var capturedBody: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null

        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as TextContent).text
            respond(
                content = ByteReadChannel("""{"suggested_load": 105.0}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = AutoregRepo(ApiClient(baseUrl = "http://test", engine = engine))

        val req = NextSetRequest(movement_id = 1, current_load = 100.0, tap = FeedbackTap.ON_TARGET, tier = 0)
        val result = repo.nextSet(req)

        assertTrue(result.isSuccess)
        assertEquals(105.0, result.getOrThrow().suggested_load, 0.0)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("/autoregulate/next-set", capturedPath)
        assertNotNull(capturedBody)
        // body is canonical kotlinx JSON in declaration order
        assertEquals("""{"movement_id":1,"current_load":100.0,"tap":"ON_TARGET","tier":0}""", capturedBody)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jauschua.ironlogv2.data.repo.*'`
Expected: compilation failure — `LibraryRepo`, `AutoregRepo` unresolved.

- [ ] **Step 5: Create `LibraryRepo.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/data/repo/LibraryRepo.kt`:
```kotlin
package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.BandPairDto
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.get

class LibraryRepo(private val apiClient: ApiClient) {

    suspend fun movements(): Result<List<MovementDto>> = runCatchingApi {
        apiClient.http.get("/movements").body()
    }

    suspend fun getMovement(id: Int): Result<MovementDto> = runCatchingApi {
        apiClient.http.get("/movements/$id").body()
    }

    suspend fun usableBands(): Result<List<BandPairDto>> = runCatchingApi {
        apiClient.http.get("/bands/usable").body()
    }
}
```

- [ ] **Step 6: Create `AutoregRepo.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/data/repo/AutoregRepo.kt`:
```kotlin
package com.jauschua.ironlogv2.data.repo

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.NextSetRequest
import com.jauschua.ironlogv2.data.api.dto.NextSetResponse
import com.jauschua.ironlogv2.data.api.runCatchingApi
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class AutoregRepo(private val apiClient: ApiClient) {

    suspend fun nextSet(req: NextSetRequest): Result<NextSetResponse> = runCatchingApi {
        apiClient.http.post("/autoregulate/next-set") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }
}
```

- [ ] **Step 7: Wire repos into AppContainer**

Replace `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt`:
```kotlin
package com.jauschua.ironlogv2.di

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo

class AppContainer {
    val apiClient: ApiClient by lazy { ApiClient() }
    val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all 7 tests pass (6 from Task 2 + 1 AutoregRepo test... wait, also 6 LibraryRepoTest tests — so 13 total). Sample output line: `Tests passed: 13`.

If a Ktor `expectSuccess = true` ServerResponseException isn't thrown for 5xx, double-check that `ApiClient` actually has `expectSuccess = true` set in its `configure(...)` block.

- [ ] **Step 9: Verify the full app still builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/jauschua/ironlogv2/data/repo/ \
        app/src/test/java/com/jauschua/ironlogv2/data/repo/ \
        app/src/test/resources/fixtures/ \
        app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt
git commit -m "feat(repo): LibraryRepo + AutoregRepo with MockEngine tests

LibraryRepo.movements() / getMovement(id) / usableBands(), AutoregRepo.nextSet().
All return Result<T> through runCatchingApi so the typed ApiError flows
to the UI without uncaught exceptions. 7 unit tests cover happy-path
parse for each endpoint, 4xx -> Client error, 5xx -> Server error,
malformed JSON -> Parse error, and the exact POST body shape for nextSet.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: UiState + Nav constants + MovementsListScreen wired end-to-end

**Files:**
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/UiState.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movements/MovementsListViewModel.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movements/MovementsListScreen.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `IronLogV2Application.container.libraryRepo`, `MovementDto`, `ApiError.humanMessage()`.
- Produces:
  - `sealed interface UiState<out T> { Loading, Success(data), Error(msg) }`.
  - `object Routes { const val MOVEMENTS = "movements"; const val MOVEMENT_DETAIL = "movement/{id}"; const val BANDS = "bands"; const val AUTOREGULATE = "autoregulate"; fun movementDetail(id: Int) = "movement/$id" }`.
  - `class MovementsListViewModel(libraryRepo: LibraryRepo) : ViewModel()` exposing `val state: StateFlow<UiState<List<MovementDto>>>` and `fun reload()`.
  - `@Composable fun MovementsListScreen(vm: MovementsListViewModel, onMovementClick: (Int) -> Unit)`.

- [ ] **Step 1: Create `UiState.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/UiState.kt`:
```kotlin
package com.jauschua.ironlogv2.ui

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val msg: String) : UiState<Nothing>
}
```

- [ ] **Step 2: Create `Nav.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt`:
```kotlin
package com.jauschua.ironlogv2.ui

object Routes {
    const val MOVEMENTS = "movements"
    const val MOVEMENT_DETAIL = "movement/{id}"
    const val BANDS = "bands"
    const val AUTOREGULATE = "autoregulate"

    fun movementDetail(id: Int): String = "movement/$id"
}
```

- [ ] **Step 3: Create `MovementsListViewModel.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movements/MovementsListViewModel.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.movements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MovementsListViewModel(private val libraryRepo: LibraryRepo) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<MovementDto>>>(UiState.Loading)
    val state: StateFlow<UiState<List<MovementDto>>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            libraryRepo.movements()
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IronLogV2Application
                MovementsListViewModel(app.container.libraryRepo)
            }
        }
    }
}
```

- [ ] **Step 4: Create `MovementsListScreen.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movements/MovementsListScreen.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.movements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementsListScreen(
    onMovementClick: (Int) -> Unit,
    vm: MovementsListViewModel = viewModel(factory = MovementsListViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    HealthDot(state)
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        }
    ) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> CenteredSpinner()
                is UiState.Error   -> ErrorView(s.msg) { vm.reload() }
                is UiState.Success -> MovementsList(s.data, onMovementClick)
            }
        }
    }
}

@Composable
private fun HealthDot(state: UiState<*>) {
    val color = when (state) {
        is UiState.Success -> Color(0xFF2E7D32)
        is UiState.Error   -> Color(0xFFC62828)
        UiState.Loading    -> Color.Gray
    }
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(12.dp)
            .clip(CircleShape)
            .clickable(enabled = false) {},
    ) {
        Surface(color = color, modifier = Modifier.fillMaxSize()) {}
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(msg: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(msg, color = MaterialTheme.colorScheme.error)
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun MovementsList(items: List<MovementDto>, onClick: (Int) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { m ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onClick(m.id) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.size(4.dp))
                    val sub = buildString {
                        append(m.region.name)
                        append(" · ")
                        append(m.lift_category.name)
                        if (m.is_primary) append(" · primary")
                    }
                    Text(sub, style = MaterialTheme.typography.bodyMedium)
                    val floor = m.load_floor
                    val cap = m.cap
                    if (floor != null || cap != null) {
                        Spacer(Modifier.size(4.dp))
                        Text("floor=${floor ?: "—"}  cap=${cap ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
```

- [ ] **Step 5: Replace the placeholder in `MainActivity.kt`**

Edit `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt`. Find:
```kotlin
            composable("movements") { Text("Movements (placeholder)") }
```
Replace with:
```kotlin
            composable(Routes.MOVEMENTS) {
                MovementsListScreen(onMovementClick = { id ->
                    nav.navigate(Routes.movementDetail(id))
                })
            }
```

Also change the other two `composable(...)` lines to use the `Routes` constants:
```kotlin
            composable(Routes.BANDS) { Text("Bands (placeholder)") }
            composable(Routes.AUTOREGULATE) { Text("Autoregulate (placeholder)") }
```

And import `com.jauschua.ironlogv2.ui.Routes` and `com.jauschua.ironlogv2.ui.screens.movements.MovementsListScreen`.

Finally, update the `TABS` list's first entry's `route` to `Routes.MOVEMENTS`, second to `Routes.BANDS`, third to `Routes.AUTOREGULATE` — and `NavHost(startDestination = Routes.MOVEMENTS, ...)`.

- [ ] **Step 6: Verify build succeeds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Manual install + visual smoke test**

Pre-req: server reachable. SSH into myflix and run, in a tmux session:
```
cd ~/projects/IronLog-V2 && .venv/bin/uvicorn ironlog.api.app:app --host 0.0.0.0 --port 8000
```
(If the venv isn't set up on myflix yet, this is Task 9's deploy step — for now, run uvicorn on whatever workstation reaches the phone and temporarily set `SERVER_BASE_URL` to that host's IP. For development, you can rebuild with a different URL by editing `app/build.gradle.kts` `buildConfigField`. Reset after testing.)

Then:
```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.jauschua.ironlogv2/.ui.MainActivity
```

Expected: Movements tab renders a list with all 5 seeded movements; green health dot in the TopAppBar; tapping a row navigates (to a not-yet-implemented detail destination — error or blank screen is OK at this stage). Tapping Bands/Autoregulate tabs still shows placeholder text.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/jauschua/ironlogv2/ui/UiState.kt \
        app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt \
        app/src/main/java/com/jauschua/ironlogv2/ui/screens/movements/ \
        app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt
git commit -m "feat(ui): UiState pattern, Routes, MovementsListScreen wired

UiState<T> sealed interface (Loading/Success/Error) is shared by every
screen. Routes constants centralize navigation paths. MovementsList
fetches via LibraryRepo on init, shows a green/red health dot in the
TopAppBar, supports Retry on error. Tapping a movement navigates to a
'movement/{id}' route the next task implements.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: MovementDetailScreen + cross-tab pre-fill hand-off

**Files:**
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movement_detail/MovementDetailViewModel.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movement_detail/MovementDetailScreen.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt` (add a `MutableStateFlow<Int?>` bridge for cross-tab pre-fill — simpler than threading through `savedStateHandle`, which is unreliable across the bottom-nav `popUpTo(start)` reshuffle)
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt` (add detail destination)

**Interfaces:**
- Consumes: `LibraryRepo`, `MovementDto`, `Routes`, `AppContainer`.
- Produces:
  - `class MovementDetailViewModel(libraryRepo, movementId)` with `state: StateFlow<UiState<MovementDto>>`, `fun reload()`.
  - `@Composable fun MovementDetailScreen(onBack: () -> Unit, onTryAutoregulate: (Int) -> Unit)`.
  - `AppContainer.autoregPrefill: MutableStateFlow<Int?>` — set by MovementDetail's "Try autoregulate" callback, consumed once by `AutoregulateViewModel` in Task 8 (which resets it to `null` after reading).

- [ ] **Step 1: Add the prefill bridge to AppContainer**

Replace `app/src/main/java/com/jauschua/ironlogv2/di/AppContainer.kt`:
```kotlin
package com.jauschua.ironlogv2.di

import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer {
    val apiClient: ApiClient by lazy { ApiClient() }
    val libraryRepo: LibraryRepo by lazy { LibraryRepo(apiClient) }
    val autoregRepo: AutoregRepo by lazy { AutoregRepo(apiClient) }

    /** Cross-tab pre-fill bridge. MovementDetail writes a movement id here; AutoregulateViewModel
     *  reads it once on init and resets to null. Simpler and more reliable than threading through
     *  savedStateHandle when the bottom-nav popUpTo(start)/saveState pattern reshuffles the back stack. */
    val autoregPrefill: MutableStateFlow<Int?> = MutableStateFlow(null)
}
```

- [ ] **Step 2: Create `MovementDetailViewModel.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movement_detail/MovementDetailViewModel.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.movement_detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MovementDetailViewModel(
    private val libraryRepo: LibraryRepo,
    private val movementId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<MovementDto>>(UiState.Loading)
    val state: StateFlow<UiState<MovementDto>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            libraryRepo.getMovement(movementId)
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IronLogV2Application
                val handle: SavedStateHandle = createSavedStateHandle()
                val id = handle.get<String>("id")?.toIntOrNull()
                    ?: error("MovementDetailViewModel requires nav arg 'id'")
                MovementDetailViewModel(app.container.libraryRepo, id)
            }
        }
    }
}
```

- [ ] **Step 3: Create `MovementDetailScreen.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/movement_detail/MovementDetailScreen.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.movement_detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementDetailScreen(
    onBack: () -> Unit,
    onTryAutoregulate: (Int) -> Unit,
    vm: MovementDetailViewModel = viewModel(factory = MovementDetailViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Movement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is UiState.Error   -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.msg, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> DetailBody(s.data, onTryAutoregulate)
            }
        }
    }
}

@Composable
private fun DetailBody(m: MovementDto, onTryAutoregulate: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(m.name, style = MaterialTheme.typography.headlineSmall)
        Text(m.base_name, style = MaterialTheme.typography.bodyMedium)
        Field("Region",          m.region.name)
        Field("Lift category",   m.lift_category.name)
        Field("Primary",         m.is_primary.toString())
        Field("Status",          m.status.name)
        Field("Progression",     m.progression_mode.name)
        Field("Scheme",          m.scheme.name)
        m.objective_override?.let { Field("Objective override", it.name) }
        Field("Increment ladder", m.increment_ladder.joinToString(", ").ifEmpty { "—" })
        Field("Min step",         m.min_step?.toString() ?: "—")
        Field("Load floor",       m.load_floor?.toString() ?: "—")
        Field("Cap",              m.cap?.toString() ?: "—")
        Field("RPE-capped",       m.rpe_capped.toString())
        Field("RPE-cap exempt",   m.rpe_cap_exempt.toString())
        Field("Equipment tags",   m.equipment_tags.joinToString(", ").ifEmpty { "—" })
        Field("Load equipment id", m.load_equipment_id?.toString() ?: "—")
        Field("Band eligible",    m.band_eligible.toString())
        m.assist_subtype?.let { Field("Assist subtype", it.name) }
        m.assist_unit?.let    { Field("Assist unit",    it.name) }
        m.family?.let         { Field("Family",         it) }
        Field("Family anchor",    m.is_family_anchor.toString())
        m.derived_from_id?.let { Field("Derived from id", it.toString()) }
        m.start_ratio?.let     { Field("Start ratio",     it.toString()) }
        m.notes?.let           { Field("Notes",           it) }

        Button(
            onClick = { onTryAutoregulate(m.id) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            enabled = m.progression_mode.name == "LADDER",
        ) {
            Text(
                if (m.progression_mode.name == "LADDER") "Try autoregulate"
                else "Autoregulate not available (LADDER lifts only)"
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 4: Wire detail destination + cross-tab pre-fill in `MainActivity.kt`**

Edit `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt`. Modify the `NavHost` block to add the detail destination and the `onTryAutoregulate` hand-off via the `AppContainer.autoregPrefill` bridge:
```kotlin
        val container = (LocalContext.current.applicationContext as IronLogV2Application).container

        NavHost(
            navController = nav,
            startDestination = Routes.MOVEMENTS,
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            composable(Routes.MOVEMENTS) {
                MovementsListScreen(onMovementClick = { id ->
                    nav.navigate(Routes.movementDetail(id))
                })
            }
            composable(
                route = Routes.MOVEMENT_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                MovementDetailScreen(
                    onBack = { nav.popBackStack() },
                    onTryAutoregulate = { id ->
                        container.autoregPrefill.value = id
                        nav.navigate(Routes.AUTOREGULATE) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.BANDS) { Text("Bands (placeholder)") }
            composable(Routes.AUTOREGULATE) { Text("Autoregulate (placeholder)") }
        }
```

Add the imports:
```kotlin
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.ui.screens.movement_detail.MovementDetailScreen
```

Note: the detail destination uses `NavType.StringType` because consuming the nav arg as a String + `.toIntOrNull()` inside the ViewModel (Task 6 Step 2) avoids the surprise crash if `IntType` is missing from the back stack after process death.

- [ ] **Step 5: Verify build succeeds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Manual visual smoke test**

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.jauschua.ironlogv2/.ui.MainActivity
```
Expected: Movements list → tap "Back Squat" → detail screen renders all fields including `objective_override`, `band_eligible`, `assist_subtype`, `assist_unit` where present → "Try autoregulate" button is enabled for LADDER, disabled for HIP_THRUST → tap → switches to Autoregulate tab (which still shows placeholder text; Task 8 consumes the pre-filled id).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jauschua/ironlogv2/ui/Nav.kt \
        app/src/main/java/com/jauschua/ironlogv2/ui/screens/movement_detail/ \
        app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt
git commit -m "feat(ui): MovementDetailScreen + cross-tab autoregulate prefill

Detail screen renders the full MovementDto including the signature flags
(status, objective_override, band_eligible, assist_subtype/unit). 'Try
autoregulate' is disabled for non-LADDER movements (composite/assisted
return meaningless suggestions); for LADDER it stashes the movement id in
the Movements destination's savedStateHandle under PREFILL_MOVEMENT_ID
and pops to the Autoregulate tab where Task 8 will consume it.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: BandsScreen

**Files:**
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/bands/BandsViewModel.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/bands/BandsScreen.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt` (replace `Routes.BANDS` placeholder)

**Interfaces:**
- Consumes: `LibraryRepo.usableBands()`, `BandPairDto`, `BandCalStatus`.
- Produces:
  - `class BandsViewModel` with `state: StateFlow<UiState<List<BandPairDto>>>`, `fun reload()`.
  - `@Composable fun BandsScreen(vm: BandsViewModel = viewModel(factory = ...))`.

- [ ] **Step 1: Create `BandsViewModel.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/bands/BandsViewModel.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.bands

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.BandPairDto
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BandsViewModel(private val libraryRepo: LibraryRepo) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<BandPairDto>>>(UiState.Loading)
    val state: StateFlow<UiState<List<BandPairDto>>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            libraryRepo.usableBands()
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IronLogV2Application
                BandsViewModel(app.container.libraryRepo)
            }
        }
    }
}
```

- [ ] **Step 2: Create `BandsScreen.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/bands/BandsScreen.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.bands

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.BandCalStatus
import com.jauschua.ironlogv2.data.api.dto.BandPairDto
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandsScreen(
    vm: BandsViewModel = viewModel(factory = BandsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Bands") }) }) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.msg, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> BandsList(s.data)
            }
        }
    }
}

@Composable
private fun BandsList(bands: List<BandPairDto>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(bands, key = { it.id }) { b ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(b.calibration_status.name) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = when (b.calibration_status) {
                                    BandCalStatus.MEASURED -> Color(0xFFC8E6C9)
                                    BandCalStatus.MODELED  -> Color(0xFFFFE0B2)
                                }
                            ),
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Text("bottom ${b.bottom_lb} lb  ·  peak ${b.peak_lb} lb", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Replace `Routes.BANDS` placeholder in `MainActivity.kt`**

Replace the line:
```kotlin
            composable(Routes.BANDS) { Text("Bands (placeholder)") }
```
With:
```kotlin
            composable(Routes.BANDS) { BandsScreen() }
```

Add import:
```kotlin
import com.jauschua.ironlogv2.ui.screens.bands.BandsScreen
```

- [ ] **Step 4: Verify build succeeds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual visual smoke test**

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: Bands tab shows 5 cards (#0 Orange, #1 Red, #2 Blue, #3 Green, #4 Black) — #5 Purple correctly absent (server filter `/bands/usable`). Each card shows bottom/peak in lb and a colored chip for calibration status.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jauschua/ironlogv2/ui/screens/bands/ \
        app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt
git commit -m "feat(ui): BandsScreen lists usable bands with calibration chip"
```

---

### Task 8: AutoregulateScreen with LADDER filter and ladder-derived tier max

**Files:**
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/autoregulate/AutoregulateViewModel.kt`
- Create: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/autoregulate/AutoregulateScreen.kt`
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt` (replace `Routes.AUTOREGULATE` placeholder)

**Interfaces:**
- Consumes: `LibraryRepo.movements()`, `AutoregRepo.nextSet()`, `MovementDto`, `NextSetRequest`, `NextSetResponse`, `FeedbackTap`, `AppContainer.autoregPrefill`.
- Produces:
  - `data class AutoregUi(movements: List<MovementDto>, selectedId: Int?, currentLoad: String, tap: FeedbackTap, tier: Int, lastResult: NextSetResponse?, submitError: String?, submitting: Boolean)` — the screen's combined state.
  - `class AutoregulateViewModel(libraryRepo, autoregRepo, prefillSource)` with `val state: StateFlow<UiState<AutoregUi>>`, `fun select(id: Int)`, `fun setCurrentLoad(s: String)`, `fun setTap(t: FeedbackTap)`, `fun setTier(t: Int)`, `fun submit()`. Pre-fill is consumed in `init` from the injected `MutableStateFlow<Int?>` and reset to `null`.
  - `@Composable fun AutoregulateScreen(vm: AutoregulateViewModel = viewModel(factory = ...))`.

- [ ] **Step 1: Create `AutoregulateViewModel.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/autoregulate/AutoregulateViewModel.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.autoregulate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NextSetRequest
import com.jauschua.ironlogv2.data.api.dto.NextSetResponse
import com.jauschua.ironlogv2.data.api.dto.ProgressionMode
import com.jauschua.ironlogv2.data.repo.AutoregRepo
import com.jauschua.ironlogv2.data.repo.LibraryRepo
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutoregUi(
    val movements: List<MovementDto> = emptyList(),
    val selectedId: Int? = null,
    val currentLoad: String = "",
    val tap: FeedbackTap = FeedbackTap.ON_TARGET,
    val tier: Int = 0,
    val lastResult: NextSetResponse? = null,
    val submitError: String? = null,
    val submitting: Boolean = false,
)

class AutoregulateViewModel(
    private val libraryRepo: LibraryRepo,
    private val autoregRepo: AutoregRepo,
    private val prefillSource: kotlinx.coroutines.flow.MutableStateFlow<Int?>,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<AutoregUi>>(UiState.Loading)
    val state: StateFlow<UiState<AutoregUi>> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            libraryRepo.movements()
                .onSuccess { all ->
                    val ladder = all.filter { it.progression_mode == ProgressionMode.LADDER }
                    _state.value = UiState.Success(AutoregUi(movements = ladder))
                    // consume the pre-fill once, if present and valid
                    val prefill = prefillSource.value
                    if (prefill != null && ladder.any { it.id == prefill }) {
                        select(prefill)
                    }
                    prefillSource.value = null
                }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    private fun mutateSuccess(block: (AutoregUi) -> AutoregUi) {
        _state.update { s -> if (s is UiState.Success) UiState.Success(block(s.data)) else s }
    }

    fun select(id: Int) = mutateSuccess { ui ->
        val movement = ui.movements.firstOrNull { it.id == id } ?: return@mutateSuccess ui
        val initialLoad = movement.load_floor?.toString() ?: ui.currentLoad.ifEmpty { "100" }
        ui.copy(
            selectedId = id,
            currentLoad = initialLoad,
            tier = 0,
            lastResult = null,
            submitError = null,
        )
    }

    fun setCurrentLoad(s: String) = mutateSuccess { it.copy(currentLoad = s, submitError = null, lastResult = null) }
    fun setTap(t: FeedbackTap)    = mutateSuccess { it.copy(tap = t,        submitError = null, lastResult = null) }
    fun setTier(t: Int)           = mutateSuccess { it.copy(tier = t,       submitError = null, lastResult = null) }

    fun submit() {
        val cur = _state.value
        if (cur !is UiState.Success) return
        val ui = cur.data
        val id = ui.selectedId ?: return
        val load = ui.currentLoad.toDoubleOrNull()
        if (load == null) {
            mutateSuccess { it.copy(submitError = "Current load must be a number") }
            return
        }
        mutateSuccess { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            autoregRepo.nextSet(NextSetRequest(movement_id = id, current_load = load, tap = ui.tap, tier = ui.tier))
                .onSuccess { resp -> mutateSuccess { it.copy(submitting = false, lastResult = resp) } }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage() ?: "Unknown error"
                    mutateSuccess { it.copy(submitting = false, submitError = msg) }
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IronLogV2Application
                AutoregulateViewModel(
                    libraryRepo = app.container.libraryRepo,
                    autoregRepo = app.container.autoregRepo,
                    prefillSource = app.container.autoregPrefill,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Create `AutoregulateScreen.kt`**

Create `app/src/main/java/com/jauschua/ironlogv2/ui/screens/autoregulate/AutoregulateScreen.kt`:
```kotlin
package com.jauschua.ironlogv2.ui.screens.autoregulate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jauschua.ironlogv2.data.api.dto.FeedbackTap
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoregulateScreen(
    vm: AutoregulateViewModel = viewModel(factory = AutoregulateViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Autoregulate") }) }) { inner ->
        Surface(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error   -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.msg, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> Form(s.data, vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Form(ui: AutoregUi, vm: AutoregulateViewModel) {
    val selected: MovementDto? = ui.selectedId?.let { id -> ui.movements.firstOrNull { it.id == id } }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Movement picker (LADDER-only)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selected?.name ?: "Pick a movement…",
                onValueChange = {},
                readOnly = true,
                label = { Text("Movement (LADDER only)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ui.movements.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.name) },
                        onClick = { vm.select(m.id); expanded = false },
                    )
                }
                if (ui.movements.isEmpty()) {
                    DropdownMenuItem(text = { Text("No LADDER movements available") }, onClick = {}, enabled = false)
                }
            }
        }

        // Current load
        OutlinedTextField(
            value = ui.currentLoad,
            onValueChange = { vm.setCurrentLoad(it) },
            label = { Text("Current load (lb)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = selected != null,
        )

        // Tap (segmented)
        val taps = listOf(FeedbackTap.TOO_EASY, FeedbackTap.ON_TARGET, FeedbackTap.TOO_HARD)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            taps.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = ui.tap == t,
                    onClick = { vm.setTap(t) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = taps.size),
                    enabled = selected != null,
                ) { Text(t.name.replace('_', ' ')) }
            }
        }

        // Tier stepper bounded by selected ladder
        val tierMax = (selected?.increment_ladder?.size ?: 1) - 1
        Card {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Tier: ${ui.tier}  (0..$tierMax)", style = MaterialTheme.typography.bodyLarge)
                Row {
                    IconButton(
                        onClick = { vm.setTier((ui.tier - 1).coerceAtLeast(0)) },
                        enabled = selected != null && ui.tier > 0,
                    ) { androidx.compose.material3.Icon(Icons.Filled.Remove, contentDescription = "tier-down") }
                    IconButton(
                        onClick = { vm.setTier((ui.tier + 1).coerceAtMost(tierMax)) },
                        enabled = selected != null && ui.tier < tierMax,
                    ) { androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = "tier-up") }
                }
            }
        }

        Button(
            onClick = { vm.submit() },
            enabled = selected != null && !ui.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ui.submitting) "Submitting…" else "Suggest next load") }

        ui.submitError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ui.lastResult?.let { res ->
            val current = ui.currentLoad.toDoubleOrNull()
            val delta = if (current != null) res.suggested_load - current else null
            val deltaText = when {
                delta == null      -> "—"
                delta > 0          -> "+${delta} lb"
                delta < 0          -> "${delta} lb"
                else               -> "unchanged"
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Suggested next load", style = MaterialTheme.typography.labelMedium)
                    Text("${res.suggested_load} lb", style = MaterialTheme.typography.headlineMedium)
                    Text("Δ $deltaText", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire the destination in `MainActivity.kt`**

Replace the line:
```kotlin
            composable(Routes.AUTOREGULATE) { Text("Autoregulate (placeholder)") }
```
With:
```kotlin
            composable(Routes.AUTOREGULATE) { AutoregulateScreen() }
```

Add import:
```kotlin
import com.jauschua.ironlogv2.ui.screens.autoregulate.AutoregulateScreen
```

(The pre-fill flows from `MovementDetail` → `AppContainer.autoregPrefill` → `AutoregulateViewModel.init` automatically — no plumbing needed at the destination.)

- [ ] **Step 4: Verify build succeeds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual visual smoke test**

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected behavior:
1. Open Autoregulate tab: dropdown shows only LADDER movements (Back Squat, Front Squat, Lateral Raise from the seed — Hip Thrust and Pull-up are NOT in the list).
2. Pick Lateral Raise: tier stepper shows `Tier: 0  (0..0)` and both `+`/`-` are disabled (ladder length is 1).
3. Pick Back Squat: stepper shows `Tier: 0  (0..2)`; `+` enables.
4. Enter `100`, tap `ON_TARGET`, hit Submit → result card shows `100.0 lb` (deterministic engine returns the same load on `ON_TARGET`) with `Δ unchanged`.
5. Tap `TOO_EASY`, Submit → suggested load goes up by one ladder step (10 lb at tier 0).
6. Navigate from Movements → Back Squat detail → tap "Try autoregulate" → land on Autoregulate tab with Back Squat pre-selected and `current_load` pre-filled to the load floor (45.0).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jauschua/ironlogv2/ui/screens/autoregulate/ \
        app/src/main/java/com/jauschua/ironlogv2/ui/MainActivity.kt
git commit -m "feat(ui): AutoregulateScreen with LADDER filter + dynamic tier max

Picker filters to progression_mode == LADDER (Hip Thrust composite and
Pull-up assisted are excluded — they don't use the ladder loop). Tier
stepper max derives from selected.increment_ladder.size - 1 (Lateral
Raise has a one-rung ladder, so tier is fixed at 0). MovementDetail can
hand off via savedStateHandle PREFILL_MOVEMENT_ID; pre-fill is consumed
once via remove() to prevent re-application on tab switch.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: End-to-end smoke-test verification + completion report

**Files:**
- Create: `docs/v0.1-smoke-test-results.md`

**Interfaces:**
- Consumes: a running server reachable at `http://myflix.media:8000`, a phone on the home LAN with USB debugging enabled.
- Produces: a markdown report capturing the 8-step protocol's actual outcome.

- [ ] **Step 1: Deploy the server on myflix (quick path — tmux + uvicorn)**

```
ssh myflix
cd ~/project-ops/IronLog-V2 2>/dev/null || cd ~/projects/IronLog-V2
# create venv if missing
python3 -m venv .venv 2>/dev/null
.venv/bin/pip install -q -e ".[dev]"
# seed if not already
[ -f ironlog.db ] || .venv/bin/python -m ironlog.seed
# verify pytest baseline
.venv/bin/pytest -q
# start uvicorn in tmux so it survives the SSH session ending
tmux new-session -d -s ironlogv2 ".venv/bin/uvicorn ironlog.api.app:app --host 0.0.0.0 --port 8000"
sleep 2
ss -tlnp 2>/dev/null | grep :8000 && echo "uvicorn listening on :8000"
```

Verify from the workstation:
```
curl -sf http://myflix.media:8000/movements | python3 -m json.tool | head -20
```
Expected: a JSON array of 5 seeded movements.

- [ ] **Step 2: Install the app on the phone**

```
cd ~/projects/IronLog-V2-Client
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm list packages | grep ironlog
```
Expected: both `package:com.jauschua.ironlog` and `package:com.jauschua.ironlogv2`.

- [ ] **Step 3: Run the 8-step smoke protocol from spec §9.3, capturing outcomes**

For each step, observe and note pass/fail:
1. Server `pytest -q` green (verified in Step 1).
2. `uvicorn` on `myflix.media:8000` reachable (curl in Step 1).
3. Phone on home WiFi: launch "IronLog V2" → bottom-nav visible.
4. Movements tab: 5 seeded movements render, no error snackbar.
5. Tap a movement → detail screen renders all its fields without crash.
6. Bands tab: 5 usable bands visible (#5 Purple correctly absent).
7. Autoregulate tab: dropdown shows only LADDER lifts; pick Back Squat, `current_load=100`, `ON_TARGET`, submit → suggested_load returned.
8. SSH into myflix and stop uvicorn (`tmux kill-session -t ironlogv2`); in the app, refresh → error snackbar with Retry; restart uvicorn (`tmux new-session -d -s ironlogv2 ".venv/bin/uvicorn ironlog.api.app:app --host 0.0.0.0 --port 8000"`); tap Retry → recovers without app restart.

- [ ] **Step 4: Capture screenshots (optional but recommended)**

```
adb shell screencap -p /sdcard/v01-movements.png && adb pull /sdcard/v01-movements.png /tmp/
adb shell screencap -p /sdcard/v01-detail.png    && adb pull /sdcard/v01-detail.png    /tmp/
adb shell screencap -p /sdcard/v01-bands.png     && adb pull /sdcard/v01-bands.png     /tmp/
adb shell screencap -p /sdcard/v01-autoreg.png   && adb pull /sdcard/v01-autoreg.png   /tmp/
```

- [ ] **Step 5: Write the results doc**

Create `docs/v0.1-smoke-test-results.md`. Fill in the actual observations from Step 3 — pass/fail per step, any deviations, notes on what surprised you:

```markdown
# v0.1 Smoke Test Results

**Date:** YYYY-MM-DD
**Server:** myflix.media:8000 (uvicorn, dev mode, tmux session `ironlogv2`)
**Client:** com.jauschua.ironlogv2 v0.1.0 (debug build)
**Phone:** <phone model>, Android <version>, on home WiFi

## Protocol outcomes (spec §9.3)

| # | Step | Outcome | Notes |
|---|---|---|---|
| 1 | server `pytest -q` green | | |
| 2 | `uvicorn` reachable on 8000 | | |
| 3 | app launches, bottom-nav visible | | |
| 4 | Movements lists 5 seeded items | | |
| 5 | Movement detail renders all fields | | |
| 6 | Bands lists 5 usable (#5 absent) | | |
| 7 | Autoregulate ON_TARGET returns suggested_load | | |
| 8 | Server stop/Retry/recover loop | | |

## Issues found

(none / or list)

## Follow-ups

(none / or list)
```

- [ ] **Step 6: Commit and (if everything passed) tag**

```bash
cd ~/projects/IronLog-V2-Client
git add docs/v0.1-smoke-test-results.md
git commit -m "docs: v0.1 smoke-test results"
# Optional, only if all 8 steps passed:
git tag -a v0.1.0 -m "v0.1.0 — smoke-test client passes §9.3 protocol"
```

- [ ] **Step 7: Register in the orchestration CLAUDE.md**

Edit `~/project-ops/CLAUDE.md`. In the **Projects Managed** table (after the IronLog-V2 row), add:
```
| IronLog V2 Client | `~/projects/IronLog-V2-Client` | Kotlin/Compose | `~/projects/IronLog-V2-Client/gradlew :app:assembleDebug` |
```
In the **Build Verification** table:
```
| IronLog V2 Client | `~/projects/IronLog-V2-Client/gradlew :app:assembleDebug` | `adb install -r ~/projects/IronLog-V2-Client/app/build/outputs/apk/debug/app-debug.apk` |
```

Commit in `~/project-ops`:
```bash
cd ~/project-ops
git add CLAUDE.md
git commit -m "docs: register IronLog V2 Client as a managed project"
```

- [ ] **Step 8: Decide on the server's proper deployment**

Quick `tmux` deployment from Step 1 is the v0.1 floor. Decide whether to graduate to:
- (a) A systemd user service on myflix (`~/.config/systemd/user/ironlogv2.service`), or
- (b) A podman container mirroring the MCP-fleet pattern documented in `~/project-ops` memory.

Either is a separate piece of work; capture as a follow-up in the results doc rather than implementing here.

---

## Self-review (against the spec)

**Spec coverage** — every spec section maps to a task:
- §1 Purpose: implicit (whole plan).
- §2 Constraints: enforced via Global Constraints + Task 1's BuildConfig URL, Task 3's cleartext config, Task 8's LADDER filter.
- §3.1 Stack: Task 1 (build files), Task 3 (Ktor/OkHttp), Task 5 (UiState/VM pattern).
- §3.2 Build targets: Task 1 (`app/build.gradle.kts`, manifest, plugins).
- §3.3 Module layout: created across Tasks 1–8 (see File Structure section).
- §4.1 MovementsListScreen: Task 5.
- §4.2 MovementDetailScreen: Task 6.
- §4.3 BandsScreen: Task 7.
- §4.4 AutoregulateScreen: Task 8 (LADDER filter, ladder-derived tier max, pre-fill).
- §4.5 UiState pattern: Task 5.
- §5 Networking (Ktor+OkHttp config, BuildConfig URL, cleartext): Task 1 + Task 3.
- §6 DTOs: Task 2.
- §7 ApiError + Result mapping: Task 3.
- §8 Testing (~6 unit tests, MockEngine): Task 4 has 7 (6 LibraryRepo + 1 AutoregRepo) + 6 from Task 2 = 13 total.
- §9.1/9.2 Build + install: Task 1 step 18, Task 9.
- §9.3 Smoke-test protocol: Task 9.
- §10 Out of scope: deliberately not implemented; enforced via Global Constraints.
- §11 Server deployment: Task 9 step 1 (quick path) + step 8 (proper path follow-up).
- §12 Registration: Task 9 step 7.

**Placeholder scan** — no TBDs, no "implement appropriate error handling" hand-waves, no "similar to Task N" without code, no step that says what without showing how.

**Type consistency** — `MovementsListViewModel.Factory` / `MovementDetailViewModel.Factory` / `BandsViewModel.Factory` / `AutoregulateViewModel.Factory` all use the same `viewModelFactory { initializer { … } }` pattern with `AndroidViewModelFactory.APPLICATION_KEY`. `runCatchingApi` signature appears in Task 3 and is consumed verbatim by both repos in Task 4. `Routes.MOVEMENTS` / `BANDS` / `AUTOREGULATE` / `MOVEMENT_DETAIL` are defined in Task 5 and used by `MainActivity` from Task 5 onward. `UiState<T>` is declared in Task 5 and used by every screen. `AppContainer.autoregPrefill: MutableStateFlow<Int?>` is added in Task 6 Step 1, written by `MovementDetail`'s callback in Task 6 Step 4, and consumed/cleared in `AutoregulateViewModel.reload()` in Task 8 — single producer, single consumer, idempotent (re-reads return null after clear). DTO field names are snake_case throughout (Tasks 2, 4, 5, 6, 8) — no rename drift.

**Scope** — one implementation plan, 9 tasks, single Android app, single deliverable (smoke-test APK). No subsystem decomposition needed.
