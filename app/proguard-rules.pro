# Forge app R8 rules.
#
# Debug builds are minified too (see app/build.gradle.kts): CI's runner has a
# hard memory ceiling, and shrinking before dexing keeps the D8 worker inside
# it. It also keeps the artifact small, which matters on 3 GB phones.

# --- JNI ------------------------------------------------------------------
# The native PTY layer is reached from C by name: Java_com_forge_ide_core_jni_
# PtyNativeKt_*. R8 cannot see those calls, so keep the bridge surface.
-keep class com.forge.ide.core.jni.** { *; }

# --- kotlinx.serialization ------------------------------------------------
# The plugin ships consumer rules, but our own @Serializable types are in the
# app and the backend library; keep their generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.forge.ide.core.backendapi.** {
    *** Companion;
    <fields>;
    <init>(...);
}
-keepclasseswithmembers class com.forge.ide.core.backendapi.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Coroutines -----------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- Ktor / slf4j (server runs in-process; slf4j is optional at runtime) --
-dontwarn org.slf4j.**
-dontwarn io.ktor.**

# --- Compose / AndroidX ship their own consumer rules ---------------------
