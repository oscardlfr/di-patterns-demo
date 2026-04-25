# SDK Feature Dependency Graph

> Auto-generated from `features/*/src/main/kotlin/**/*Provider.kt`.
> Do not edit by hand. Regenerate with
> `python3 scripts/generate-dependency-graph.py`.

Each provider lists the API services it publishes and the
services it requests from the `Resolver` while building.

## `:features:feature-ana-impl`

### `AnaKIProvider` (KI)

`features/feature-ana-impl/src/main/kotlin/com/grinwich/sdk/feature/ana/AnaKIProvider.kt`

- **publishes:** `AnalyticsApi`
- **depends on:** `SdkLogger`

### `AnaProvider` (DAGGER)

`features/feature-ana-impl/src/main/kotlin/com/grinwich/sdk/feature/ana/AnaProvider.kt`

- **publishes:** `AnalyticsApi`
- **depends on:** `SdkLogger`

### `AnaPureProvider` (PURE)

`features/feature-ana-impl/src/main/kotlin/com/grinwich/sdk/feature/ana/AnaPureProvider.kt`

- **publishes:** `AnalyticsApi`
- **depends on:** `SdkLogger`

## `:features:feature-auth-impl`

### `AuthKIProvider` (KI)

`features/feature-auth-impl/src/main/kotlin/com/grinwich/sdk/feature/auth/AuthKIProvider.kt`

- **publishes:** `AuthApi`
- **depends on:** `EncryptionApi`, `SdkLogger`

### `AuthProvider` (DAGGER)

`features/feature-auth-impl/src/main/kotlin/com/grinwich/sdk/feature/auth/AuthProvider.kt`

- **publishes:** `AuthApi`
- **depends on:** `EncryptionApi`, `SdkLogger`

### `AuthPureProvider` (PURE)

`features/feature-auth-impl/src/main/kotlin/com/grinwich/sdk/feature/auth/AuthPureProvider.kt`

- **publishes:** `AuthApi`
- **depends on:** `EncryptionApi`, `SdkLogger`

## `:features:feature-core-impl`

### `CoreKIProvider` (KI)

`features/feature-core-impl/src/main/kotlin/com/grinwich/sdk/feature/core/CoreKIProvider.kt`

- **publishes:** `SdkConfig`
- **depends on:** `SdkConfig`

### `CoreProvider` (DAGGER)

`features/feature-core-impl/src/main/kotlin/com/grinwich/sdk/feature/core/CoreProvider.kt`

- **publishes:** `SdkConfig`
- **depends on:** `SdkConfig`

### `CorePureProvider` (PURE)

`features/feature-core-impl/src/main/kotlin/com/grinwich/sdk/feature/core/CorePureProvider.kt`

- **publishes:** `SdkConfig`
- **depends on:** `SdkConfig`

## `:features:feature-enc-impl`

### `EncKIProvider` (KI)

`features/feature-enc-impl/src/main/kotlin/com/grinwich/sdk/feature/enc/EncKIProvider.kt`

- **publishes:** `EncryptionApi`, `HashApi`
- **depends on:** `SdkLogger`

### `EncProvider` (DAGGER)

`features/feature-enc-impl/src/main/kotlin/com/grinwich/sdk/feature/enc/EncProvider.kt`

- **publishes:** `EncryptionApi`, `HashApi`
- **depends on:** `SdkLogger`

### `EncPureProvider` (PURE)

`features/feature-enc-impl/src/main/kotlin/com/grinwich/sdk/feature/enc/EncPureProvider.kt`

- **publishes:** `EncryptionApi`, `HashApi`
- **depends on:** `SdkLogger`

## `:features:feature-observability-impl`

### `ObservabilityKIProvider` (KI)

`features/feature-observability-impl/src/main/kotlin/com/grinwich/sdk/feature/observability/ObservabilityKIProvider.kt`

- **publishes:** `SdkLogger`
- **depends on:** _(no resolver.get() calls — root)_

### `ObservabilityProvider` (DAGGER)

`features/feature-observability-impl/src/main/kotlin/com/grinwich/sdk/feature/observability/ObservabilityProvider.kt`

- **publishes:** `SdkLogger`
- **depends on:** _(no resolver.get() calls — root)_

### `ObservabilityPureProvider` (PURE)

`features/feature-observability-impl/src/main/kotlin/com/grinwich/sdk/feature/observability/ObservabilityPureProvider.kt`

- **publishes:** `SdkLogger`
- **depends on:** _(no resolver.get() calls — root)_

## `:features:feature-stor-impl`

### `StorKIProvider` (KI)

`features/feature-stor-impl/src/main/kotlin/com/grinwich/sdk/feature/stor/StorKIProvider.kt`

- **publishes:** `StorageApi`
- **depends on:** `Context`, `EncryptionApi`, `HashApi`, `SdkLogger`, `SdkConfig`

### `StorProvider` (DAGGER)

`features/feature-stor-impl/src/main/kotlin/com/grinwich/sdk/feature/stor/StorProvider.kt`

- **publishes:** `StorageApi`
- **depends on:** `Context`, `SdkConfig`, `EncryptionApi`, `HashApi`, `SdkLogger`

### `StorPureProvider` (PURE)

`features/feature-stor-impl/src/main/kotlin/com/grinwich/sdk/feature/stor/StorPureProvider.kt`

- **publishes:** `StorageApi`
- **depends on:** `Context`, `EncryptionApi`, `HashApi`, `SdkLogger`, `SdkConfig`

## `:features:feature-syn-impl`

### `SynKIProvider` (KI)

`features/feature-syn-impl/src/main/kotlin/com/grinwich/sdk/feature/syn/SynKIProvider.kt`

- **publishes:** `SyncApi`
- **depends on:** `AuthApi`, `StorageApi`, `EncryptionApi`, `SdkLogger`

### `SynProvider` (DAGGER)

`features/feature-syn-impl/src/main/kotlin/com/grinwich/sdk/feature/syn/SynProvider.kt`

- **publishes:** `SyncApi`
- **depends on:** `AuthApi`, `StorageApi`, `EncryptionApi`, `SdkLogger`

### `SynPureProvider` (PURE)

`features/feature-syn-impl/src/main/kotlin/com/grinwich/sdk/feature/syn/SynPureProvider.kt`

- **publishes:** `SyncApi`
- **depends on:** `AuthApi`, `StorageApi`, `EncryptionApi`, `SdkLogger`
