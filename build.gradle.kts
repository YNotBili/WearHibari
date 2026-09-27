plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.huanli233.hibari.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Wear OS 2.0+. Also the floor of androidx.wear:wear.
        minSdk = 25

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    }
    kotlinOptions {
        jvmTarget = libs.versions.javaVersion.get()
    }
}

dependencies {
    api(project(":hibari-animation"))
    api(project(":hibari-foundation"))
    api(project(":hibari-material"))
    api(project(":hibari-recyclerview"))
    api(project(":hibari-ui"))
    kotlinCompilerPluginClasspath(project(":hibari-compiler"))
    implementation(libs.androidx.wear)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
