# =====================================================================
# di-contracts consumer-rules.pro — applied to every consumer of the SDK
# =====================================================================
#
# These rules are mandatory because the runtime relies on JVM ServiceLoader
# discovery + reflective no-arg instantiation of FeatureProvider subclasses.
# Without them, R8/DexGuard release builds will silently break the SDK:
#
#   • Stripped descriptor          → ServiceLoader.load returns empty
#   • Stripped concrete provider   → ClassNotFoundException
#   • Removed no-arg constructor   → ServiceConfigurationError
#   • Renamed services class names → still resolves (Class<*> identity is
#     stable through obfuscation) but stack traces become unreadable
#
# Rules are split per concern; do NOT remove individual blocks.
# =====================================================================

# ---------------------------------------------------------------------
# (1) FeatureProvider hierarchy
# ---------------------------------------------------------------------
# Preserve the SPI root. The class itself is referenced by FQN literal
# from ServiceLoader.load(FeatureProvider::class.java).
-keep class com.grinwich.sdk.contracts.FeatureProvider { *; }
-keep class com.grinwich.sdk.contracts.FeatureContribution { *; }
-keep class com.grinwich.sdk.contracts.SyntheticFeatureProvider { *; }
-keep class com.grinwich.sdk.contracts.Flavor { *; }

# Every concrete implementation across the classpath. ServiceLoader needs
# both the class to exist AND a public no-arg constructor it can invoke
# reflectively.
-keep class * extends com.grinwich.sdk.contracts.FeatureProvider {
    public <init>();
}

# ---------------------------------------------------------------------
# (2) Resolver and lifecycle types
# ---------------------------------------------------------------------
# Resolver itself is referenced by feature-impl modules through
# `resolver.get(...)`. The instance is internal to the wiring object,
# but R8 may still rename methods if the class is shrunk.
-keep class com.grinwich.sdk.contracts.Resolver {
    public <init>();
    public <methods>;
}

# Registry alternates (E / E2) — same reasoning if the consumer wiring
# uses any of these.
-keep class com.grinwich.sdk.contracts.ServiceRegistry { *; }
-keep class com.grinwich.sdk.contracts.AutoServiceRegistry { *; }
-keep class com.grinwich.sdk.contracts.ServiceEntry { *; }
-keep class com.grinwich.sdk.contracts.AutoServiceEntry { *; }

# ---------------------------------------------------------------------
# (3) Typed exception hierarchy
# ---------------------------------------------------------------------
# Stack traces in Crashlytics / production logs name the concrete
# exception type. Keeping their simple names readable is a hard
# requirement for incident triage.
-keep class com.grinwich.sdk.contracts.error.** { *; }
-keepnames class com.grinwich.sdk.contracts.error.**

# ---------------------------------------------------------------------
# (4) META-INF/services descriptors
# ---------------------------------------------------------------------
# R8 preserves these by default. DexGuard may strip them with aggressive
# resource shrinking. The two directives below are no-ops for R8 but
# provide explicit documentation of the requirement and survive when
# this file is fed to DexGuard.
#
# DexGuard-specific (no-op in R8):
#   -keepresources META-INF/services/**
#
# Standard:
-keepattributes *Annotation*

# ---------------------------------------------------------------------
# (5) Class names referenced via Class<*> identity
# ---------------------------------------------------------------------
# Each FeatureProvider declares `services: Set<Class<*>>`. The `Class<*>`
# tokens are stable across obfuscation (the JVM compares Class identity,
# not name). However, any code that builds keys via
# `Class.forName("com.empresa.sdk.api.FooApi")` would break — keep the
# public API surface readable to be safe.
-keep interface com.grinwich.sdk.api.** { *; }
-keep class com.grinwich.sdk.api.** { *; }

# Feature API modules. R8 already keeps types reachable by the app code,
# but feature-X-api types are reached only through `Class<*>` keys; this
# is conservative.
-keep interface com.grinwich.sdk.feature.**.api.** { *; }
-keep class com.grinwich.sdk.feature.**.api.** { *; }

# ---------------------------------------------------------------------
# DexGuard-specific (uncomment if DexGuard is the obfuscator):
# ---------------------------------------------------------------------
# -keepresources META-INF/services/**
# -keepstringnames class * extends com.grinwich.sdk.contracts.FeatureProvider
# -dontencryptclasses class * extends com.grinwich.sdk.contracts.FeatureProvider
