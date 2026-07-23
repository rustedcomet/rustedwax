plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "com.rustedwax.app"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.rustedwax.app"
		minSdk = 26
		targetSdk = 35
		versionCode = 5
		versionName = "0.3.1"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
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
	}
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.bouncycastle)
	implementation(libs.androidx.security.crypto)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.activity.compose)

	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.compose.material3)

	debugImplementation(libs.androidx.compose.ui.tooling)

	// The hive/ package is pure JVM by design so it can be verified against
	// dhive-generated golden vectors without a device. org.json ships with
	// Android at runtime but must be added explicitly for JVM unit tests.
	testImplementation(libs.junit)
	testImplementation(libs.json)
	testImplementation(libs.bouncycastle)
}
