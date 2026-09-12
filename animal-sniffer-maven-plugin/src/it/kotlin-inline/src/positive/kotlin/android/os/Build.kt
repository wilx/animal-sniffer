package android.os

// Supply the Android version constants used by the issue's reproducer without requiring an Android SDK.
// SDK_INT stays mutable so Kotlin retains the runtime availability branch in the compiled fixture.
object Build {
    object VERSION {
        @JvmField var SDK_INT: Int = 36
    }
    object VERSION_CODES {
        const val BAKLAVA: Int = 36
    }
}
