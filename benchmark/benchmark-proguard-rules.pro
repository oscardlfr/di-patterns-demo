# benchmark-proguard-rules.pro
#
# Keep rules applied to the :benchmark module when invoked with
# `-Pminify=true`. These are the minimal keeps that allow androidx.benchmark
# + JUnit + AndroidX test runner to work after R8 has run.
#
# `:di-contracts:consumer-rules.pro` is auto-applied via consumerProguardFiles
# and covers the SDK side (FeatureProvider hierarchy, exception types,
# ServiceLoader). This file only adds the test-infrastructure side.

# androidx.benchmark — instrumentation runner + measurement helpers
-keep class androidx.benchmark.** { *; }
-dontwarn androidx.benchmark.**

# AndroidX Test runner (looked up reflectively by AndroidJUnitRunner)
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**

# JUnit 4 — @Test/@Before/@After methods discovered reflectively
-keepclasseswithmembers class * {
    @org.junit.Test <methods>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    @org.junit.BeforeClass <methods>;
    @org.junit.AfterClass <methods>;
}
-keepclassmembers class * {
    @org.junit.Test <methods>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    @org.junit.BeforeClass <methods>;
    @org.junit.AfterClass <methods>;
}
-keepclasseswithmembers @org.junit.runner.RunWith class * { *; }

# Benchmark + memory + stress test classes — instantiated by name
-keep class com.grinwich.benchmark.** { *; }
-keepnames class com.grinwich.benchmark.**

# Coroutines — runBlocking + suspend functions used by sync benchmarks
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX runner internals
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Signature,Exceptions,*Annotation*,InnerClasses,EnclosingMethod
