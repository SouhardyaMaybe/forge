plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.forge.ide.core.jni"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        ndkVersion = "27.0.12077973"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        prefab = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
