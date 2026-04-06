package com.grinwich.sdk.contracts

import javax.inject.Scope

// ============================================================
// Scope annotations shared across feature impl modules.
//
// Each feature's @Component uses its own scope to ensure
// Dagger generates singletons per-component (not per-app).
// ============================================================

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class EncScope
@Scope @Retention(AnnotationRetention.RUNTIME) annotation class AuthScope
@Scope @Retention(AnnotationRetention.RUNTIME) annotation class StorScope
@Scope @Retention(AnnotationRetention.RUNTIME) annotation class AnaScope
@Scope @Retention(AnnotationRetention.RUNTIME) annotation class SynScope
