package com.grinwich.sdk.contracts

// Provision interfaces moved to per-feature contracts modules:
// - CoreProvisions  → :sdk:feature-core-contracts
// - EncProvisions   → :sdk:feature-enc-contracts
// - AuthProvisions  → :sdk:feature-auth-contracts
// - StorProvisions  → :sdk:feature-stor-contracts
// - AnaProvisions   → :sdk:feature-ana-contracts
// - SynProvisions   → :sdk:feature-syn-contracts
//
// This module re-exports all of them via api() deps in build.gradle.kts.
// Existing code with `import com.grinwich.sdk.contracts.*` works unchanged.
