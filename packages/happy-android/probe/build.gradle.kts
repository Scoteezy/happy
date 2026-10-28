// Dev only: an instrumentation that injects finger gestures with exact
// timing into whatever is on screen, to compare scrolling with stock apps.
// Included with -Pprobe; see FlingProbe.java.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.slopus.happy.probe"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.slopus.happy.probe"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1"
    }
    // Two apps: am instrument restarts its target, so the stock list it is
    // compared with lives in a package of its own.
    flavorDimensions += "role"
    productFlavors {
        create("inject") { dimension = "role" }
        create("list") {
            dimension = "role"
            applicationIdSuffix = ".list"
        }
    }
}

dependencies {
    // The same RecyclerView the app uses, stock, as the reference list.
    implementation(libs.androidx.recyclerview)
}
