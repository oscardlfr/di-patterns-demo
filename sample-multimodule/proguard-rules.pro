# sample-multimodule — release ProGuard / R8 rules.
#
# `:di-contracts:consumer-rules.pro` already covers ServiceLoader, the
# FeatureProvider hierarchy, and the typed exception types. This file is
# only for app-level rules. Add app-specific keeps below if needed.

# AndroidX test runner classes referenced by name from
# AndroidJUnitRunner — keep them so the instrumented integration test can
# load against the release APK.
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**
