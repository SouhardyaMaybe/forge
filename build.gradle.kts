// Root build file — plugins are declared here with `apply false`
// and applied in the modules that need them.
//
// Note: there is deliberately NO `org.jetbrains.kotlin.android` plugin here.
// AGP 9 ships built-in Kotlin support, which replaces it.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// AGP 9's built-in Kotlin uses KGP 2.2.10 by default. Pin KGP to our Kotlin
// version so the Compose and serialization compiler plugins match it.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
