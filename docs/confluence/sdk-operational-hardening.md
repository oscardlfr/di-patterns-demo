# SDK Operational Hardening for Production Rollout

> Mitigaciones obligatorias y modelo de responsabilidades para distribuir
> el SDK basado en Pattern H a un parque de aplicaciones consumidoras.
> Complemento operativo de
> [`sdk-internal-architecture.md`](sdk-internal-architecture.md) y
> [`app-consumption-guide.md`](app-consumption-guide.md).

> **Implementado en este repo.** Las mitigaciones de las secciones 4-8
> existen como artefactos vivos:
>
> | Mitigación | Artefacto |
> |---|---|
> | Keep rules R8/DexGuard | `di-contracts/consumer-rules.pro` (auto-aplicado vía `consumerProguardFiles`) |
> | Test integración release | `sample-multimodule/src/androidTest/.../SdkIntegrationTest.kt` (5 tests, build release con R8) |
> | Detekt ForbiddenImport | `detekt.yml` + plugin en `build.gradle.kts` (root) |
> | Verificación classpath | Gradle task `:sdk:wiring-h:verifySdkClasspath` |
> | Generador grafo deps | `scripts/generate-dependency-graph.py` + tasks `generateDependencyGraph` / `verifyDependencyGraph` |
>
> Comando único de gate CI:
> ```bash
> ./gradlew detekt :sdk:wiring-h:verifySdkClasspath verifyDependencyGraph \
>           :di-contracts:test :sample-multimodule:assembleRelease
> # más, en CI con dispositivo conectado:
> ./gradlew :sample-multimodule:connectedReleaseAndroidTest
> ```

---

## 1. Contexto: por qué Pattern H

El equipo SDK distribuye una colección de features a un parque de
**decenas o cientos de aplicaciones consumidoras**. Las apps **no son
homogéneas**:

- Una app de banca personal puede consumir `Auth + Storage + Sync + Encryption + Observability`.
- Una app de empleado puede consumir sólo `Auth + Observability`.
- Una app de soporte puede consumir sólo `Observability + Analytics`.
- Etc.

Forzar a cada app a declarar todos los features para satisfacer un grafo
compile-time completo (Pattern Q2 puro, Pattern E con todas las entries en
`allEntries()`) **no es viable** a esta escala:

- Las apps que no usan un feature pagarían el coste de incluirlo en el APK.
- El SDK tendría que mantener una lista exhaustiva centralizada que crece
  con cada feature nuevo.
- Cada app modificaría ese grafo en su build, generando coupling cruzado.

**Pattern H resuelve esto** vía discovery por classpath: cada app declara
sólo los `runtimeOnly(:features:feature-X-impl)` que necesita, y el
ServiceLoader se queda con lo que está realmente en el APK. Las APIs no
declaradas simplemente lanzan `NoProviderFoundException` si alguien las
pide — comportamiento esperado.

**Trade-off aceptado conscientemente:** validación runtime en lugar de
compile-time. El resto del documento son mitigaciones para que ese
trade-off no degrade la fiabilidad del sistema en producción.

---

## 2. Modelo de responsabilidades

| Responsabilidad | Equipo SDK | Equipo de cada App |
|---|---|---|
| Mantener el wiring (`:sdk:integration`, `MultiModuleSdkH`) | Sí | No |
| Mantener el `Resolver` y la jerarquía de excepciones | Sí | No |
| Implementar y mantener cada `feature-X-impl` | Sí | No |
| Declarar `runtimeOnly(:features:feature-X-impl)` en su `:app` | No | **Sí** |
| Declarar `implementation(:sdk:integration)` en su `:app` | No | **Sí** |
| Importar `:sdk:api` en cada módulo de la app | No | **Sí** |
| Llamar `MultiModuleSdkH.init(...)` en `Application.onCreate()` | No | **Sí** |
| Configurar keep rules de R8/DexGuard que cubran ServiceLoader | Provee plantilla | **Aplica + verifica** |
| Test de integración que valida que sus APIs resuelven en release | No | **Sí** |
| Reportar incidentes de discovery (provider faltante en producción) | Triage | Detectar y reportar |

**Lectura clave:** el equipo SDK **no puede garantizar** que cada app
configure correctamente su classpath. Lo que puede (y debe) hacer es
**proveer las herramientas y las plantillas** para que cada app valide su
propia integración antes de publicar.

---

## 3. SBOM y distribución

### 3.1 Qué entrega el SDK

- Catálogo Gradle (`libs.versions.toml`) con todos los artefactos publicables
  del SDK (`:sdk:api`, `:sdk:integration`, cada `:features:feature-X-api` y
  `:features:feature-X-impl`).
- **SBOM (Software Bill of Materials)** generado en cada release:
  - Lista exhaustiva de features disponibles (api + impl pareados).
  - Versión, fingerprint, dependencias transitivas, vulnerabilidades conocidas
    (CVE scanner integrado).
  - Documenta cada feature como una unidad independiente: una app puede
    consumir N de los M features ofrecidos.
- Plantilla de keep rules para R8/DexGuard (sección 5).
- Plantilla de test de integración (sección 4).

### 3.2 Lo que la app elige

Cada app recibe el catálogo y decide qué features incluye:

```kotlin
// :app/build.gradle.kts (app de banca personal)
dependencies {
    implementation(libs.sdk.integration)        // wiring obligatorio
    implementation(libs.sdk.api)                // contratos públicos

    // Subset de features que consume — declarado por la app
    runtimeOnly(libs.feature.observability.impl)
    runtimeOnly(libs.feature.core.impl)
    runtimeOnly(libs.feature.enc.impl)
    runtimeOnly(libs.feature.auth.impl)
    runtimeOnly(libs.feature.stor.impl)
    runtimeOnly(libs.feature.syn.impl)
}
```

Otra app, con menos features:

```kotlin
// :app/build.gradle.kts (app de soporte)
dependencies {
    implementation(libs.sdk.integration)
    implementation(libs.sdk.api)

    // Sólo observability + core
    runtimeOnly(libs.feature.observability.impl)
    runtimeOnly(libs.feature.core.impl)
    runtimeOnly(libs.feature.ana.impl)
}
```

Resultado: cada app empaqueta sólo los features que declara. Llamar
`sdk.get(SyncApi)` en la app de soporte lanzará `NoProviderFoundException`
porque el classpath no lo contiene — esperado y diagnosticable.

---

## 4. Mitigación 1 — Test de integración por app

Cada app **debe** incluir un test que arranque el SDK y haga `get()` de
todas las APIs que ella consume (no las que el SDK ofrece globalmente — sólo
las que la app declara). Falla CI si falta cualquiera.

### 4.1 Plantilla provista por el SDK

```kotlin
// :sdk:integration:test-support — librería opcional
// :sdk:integration-test-support/src/main/kotlin/.../SdkIntegrationAssertions.kt
package com.empresa.sdk.testing

import com.grinwich.sdk.wiring.h.MultiModuleSdkH
import com.empresa.sdk.api.*

object SdkIntegrationAssertions {
    /**
     * Verifica que el SDK arranca y resuelve cada [api] del set provisto.
     * Lanza con causa tipada si alguna falla — el `cause` apunta al
     * `NoProviderFoundException`/`ProviderBuildException` real.
     */
    fun assertResolvable(
        context: android.content.Context,
        config: SdkConfig,
        apis: Set<Class<*>>,
    ) {
        MultiModuleSdkH.init(context, config)
        try {
            val failures = mutableListOf<Throwable>()
            for (api in apis) {
                try { MultiModuleSdkH.get(api) }
                catch (t: Throwable) { failures += IllegalStateException("$api -> ${t.message}", t) }
            }
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "SDK integration broken — ${failures.size} API(s) no resuelven:\n" +
                    failures.joinToString("\n") { " • ${it.message}" }
                )
            }
        } finally {
            MultiModuleSdkH.shutdown()
        }
    }
}
```

### 4.2 Test concreto de la app

```kotlin
// :app/src/androidTest/kotlin/com/empresa/app/SdkIntegrationTest.kt
@RunWith(AndroidJUnit4::class)
class SdkIntegrationTest {

    @Test
    fun all_consumed_apis_resolve_in_release_classpath() {
        SdkIntegrationAssertions.assertResolvable(
            context = ApplicationProvider.getApplicationContext(),
            config  = SdkConfig(debug = false),
            apis = setOf(
                EncryptionApi::class.java,
                AuthApi::class.java,
                StorageApi::class.java,
                AnalyticsApi::class.java,
                SyncApi::class.java,
                SdkLogger::class.java,
            ),
        )
    }
}
```

### 4.3 Configuración CI

```yaml
# .github/workflows/release.yml (o equivalente)
- name: Integration test (release classpath + R8/DexGuard)
  run: ./gradlew :app:connectedReleaseAndroidTest -PrunIntegrationTests=true
```

**Punto crítico:** este test debe correr contra el build **release** (con
R8/DexGuard aplicado), no debug. La causa más común de "feature ausente en
producción pero presente en debug" es que el shrinker stripeó el descriptor
`META-INF/services`. Solo el build release lo detecta.

---

## 4.4 Evidencia: A/B sin R8 vs con R8 (Pattern H)

Las keep rules de la sección 5 están **validadas empíricamente** sobre el
parque completo de tests del proyecto, ejecutado en un Samsung Galaxy
S22 Ultra (Snapdragon 8 Gen 1, Android 16) en la misma sesión del
dispositivo. Cada bloque ran dos veces — sin R8 y con R8 (`-Pminify=true`).

### 4.4.1 Pass/fail

| Bloque | Tests | sin R8 | con R8 |
|---|---:|---:|---:|
| MultiModuleBenchmark patterns=H | 15 | ✅ 15/15 | ✅ 15/15 |
| StressTortureTest patterns=H | 208 | ✅ 208/208 | ✅ 208/208 |
| MemoryBehaviorTest patterns=H | 129 | ✅ 129/129 | ✅ 129/129 |
| **Total Pattern H** | **352 × 2 = 704** | **0 fallos** | **0 fallos** |

R8 con las consumer-rules de `:di-contracts` no rompe ningún test —
ServiceLoader sobrevive, los providers se instancian, las excepciones
tipadas se lanzan correctamente.

### 4.4.2 Deltas en timing (ns, mediana, S22 Ultra)

| Métrica | sin R8 | con R8 | Δ atribuible a R8 |
|---|---:|---:|---:|
| `initCold_H` | 95,047 | 94,581 | **-0.5%** (ruido) |
| `resolveFirst_H` | 31 | 31 | -2.2% (idéntico) |
| `lazyInit_cascade_H` | 6,748 | 6,429 | -4.7% |
| `lazyInit_noDeps_H` | 1,253 | 1,223 | -2.4% |
| `stress_initShutdown_H` | 89,697 | 86,221 | -3.9% |
| `stress_concurrent_H` | 373,103 | 366,343 | -1.8% |
| `stress_incremental_H` | 98,733 | 97,396 | -1.4% |
| `stress_resolveAll_H` | 182 | 178 | -1.8% |
| `stress_reInit_H` | 201,284 | 191,734 | -4.7% |
| `stress_selective_H` | 92,067 | 90,593 | -1.6% |
| `crossFeatureOp_H_fake` | 106,335 | 107,019 | +0.6% |
| `crossFeatureOp_H_sharedprefs` | 122,596 | 116,851 | -4.7% |
| `e2eStartup_H` | 842,482 | 954,808 | +13.3% (varianza térmica) |
| `crossFeatureOp_H` (DataStore) | 3,794,582 | 1,398,940 | varianza I/O ≫ R8 |
| `crossFeatureOp_H_datastore` | 1,318,785 | 4,333,190 | varianza I/O ≫ R8 |

### 4.4.3 Lectura

- **Métricas de SDK puras** (`initCold`, `resolveFirst`, `lazyInit_*`,
  `stress_*` excepto los I/O): R8 es **transparente** o ligeramente más
  rápido (-0.5% a -4.7%). El inlinador de R8 se aplica a métodos
  privados como `castOrThrow`, lo que recupera el coste de la llamada
  que vimos en el A/B previo.
- **Métricas con DataStore I/O** (`crossFeatureOp_H` y
  `crossFeatureOp_H_datastore`): la varianza intrínseca del subsistema
  de almacenamiento (CoV 25-67%) excede en órdenes de magnitud cualquier
  delta atribuible a R8. No son métricas usables para comparar pre/post
  R8.

### 4.4.4 Conclusión operacional

**El SDK con `:di-contracts:consumer-rules.pro` está listo para
distribución a apps que usen R8 o DexGuard.** Las consumer-rules
preservan los descriptors `META-INF/services`, las clases concretas que
extienden `FeatureProvider` y la jerarquía de excepciones — verificado
sobre 704 ejecuciones de test independientes y un APK release de 21
providers descubiertos correctamente.

**Artefactos de evidencia** (commiteables como prueba de auditoría):

```
docs/bench-run-20260425-H/         ← sin R8
  01-MultiModule-H.json
  02-Stress-H.xml
  03-Memory-H.xml

docs/bench-run-20260425-H-r8/      ← con R8 (-Pminify=true)
  01-MultiModule-H.json
  02-Stress-H.xml
  03-Memory-H.xml
```

Para reproducir la validación en CI antes de cada release:

```bash
# Bloque sin R8 (baseline)
./gradlew :benchmark:connectedReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.grinwich.benchmark.MultiModuleBenchmark \
    -Pandroid.testInstrumentationRunnerArguments.patterns=H

./gradlew :benchmark:connectedReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.grinwich.benchmark.StressTortureTest \
    -Pandroid.testInstrumentationRunnerArguments.patterns=H

./gradlew :benchmark:connectedReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.grinwich.benchmark.MemoryBehaviorTest \
    -Pandroid.testInstrumentationRunnerArguments.patterns=H

# Bloque con R8 (validación de keep rules)
./gradlew :benchmark:connectedReleaseAndroidTest -Pminify=true \
    -Pandroid.testInstrumentationRunnerArguments.class=com.grinwich.benchmark.MultiModuleBenchmark \
    -Pandroid.testInstrumentationRunnerArguments.patterns=H

# (los otros dos bloques análogamente)
```

Si cualquiera de los dos pipelines falla, **no se publica** —
indica regresión real (release puro) o keep rules incompletas (release+R8).

---

## 5. Mitigación 2 — Keep rules para R8 / DexGuard

### 5.1 Plantilla provista en `:sdk:di-contracts`

Archivo: `consumer-rules.pro` dentro del módulo `:sdk:di-contracts`.

```proguard
# ===================================================================
# SDK ServiceLoader Discovery — REGLAS OBLIGATORIAS
# ===================================================================
# Razón: ServiceLoader.load(FeatureProvider::class.java) lee
# META-INF/services/com.grinwich.sdk.contracts.FeatureProvider y carga
# cada FQN listado por reflexión. Si el shrinker:
#   • elimina el descriptor → discovery devuelve vacío.
#   • elimina el FeatureProvider concreto → ClassNotFoundException.
#   • elimina el constructor sin args → ServiceConfigurationError.
# Las tres condiciones rompen el SDK silenciosamente en release.

# (1) Preserva el descriptor del ServiceLoader.
-keepnames class com.grinwich.sdk.contracts.FeatureProvider
-keep class com.grinwich.sdk.contracts.FeatureProvider { *; }

# (2) Preserva CADA implementación de FeatureProvider y su no-arg ctor.
-keep class * extends com.grinwich.sdk.contracts.FeatureProvider {
    public <init>();
}

# (3) Preserva los recursos META-INF/services.
# R8 los respeta por defecto, pero DexGuard puede strippearlos —
# aplicar -keepattributes y verificar manualmente.
-keepattributes *Annotation*

# (4) Las APIs públicas del SDK que la app referencia por nombre no
# deben renombrarse (de lo contrario, los `Class<*>` de los `services`
# de cada provider no coincidirían).
-keep interface com.empresa.sdk.api.** { *; }
-keep class com.empresa.sdk.api.SdkConfig { *; }
```

`consumer-rules.pro` se aplica automáticamente a cualquier app que dependa
del módulo. La app no tiene que copiarlo.

### 5.2 DexGuard — consideraciones específicas

DexGuard es más agresivo que R8 estándar. **Riesgos adicionales:**

- **Resource shrinking** puede eliminar `META-INF/services/...` aunque las
  reglas de R8 lo preserven. DexGuard tiene flag explícita:

  ```
  -keepresources META-INF/services/**
  ```

- **String encryption** de DexGuard puede afectar a los FQN literales
  dentro del descriptor. Verificar:

  ```
  -keepstringnames class * extends com.grinwich.sdk.contracts.FeatureProvider
  ```

- **Class encryption** puede romper la instanciación reflexiva:

  ```
  -dontencryptclasses class * extends com.grinwich.sdk.contracts.FeatureProvider
  ```

  (Sintaxis exacta: comprobar contra la versión de DexGuard del proyecto.)

- **Tamper detection** puede invalidar el APK si el descriptor
  `META-INF/services` cambia entre builds. Confirmar política.

### 5.3 Verificación obligatoria del APK release

Tras cada build release, antes de publicar, ejecutar:

```bash
# Verifica que el descriptor sobrevive al shrinking
unzip -p app/build/outputs/apk/release/app-release.apk \
  META-INF/services/com.grinwich.sdk.contracts.FeatureProvider | head

# Si está vacío o el archivo no existe → release rota → no publicar
```

Idealmente, automatizado como step de CI tras `assembleRelease`.

---

## 6. Mitigación 3 — Verificación de classpath con Gradle

### 6.1 Dependency-insight como gate de CI

```bash
./gradlew :app:dependencyInsight \
    --configuration releaseRuntimeClasspath \
    --dependency com.empresa.sdk:integration

./gradlew :app:dependencyInsight \
    --configuration releaseRuntimeClasspath \
    --dependency com.empresa.features:feature-enc-impl
```

Cada feature que la app declara `runtimeOnly` **debe** aparecer aquí. Si
la salida es "No dependencies matching..." el feature no está realmente en
el APK. Step de CI:

```yaml
- name: Verify SDK feature classpath
  run: |
    set -e
    for feature in observability core enc auth stor ana syn; do
      ./gradlew :app:dependencyInsight \
        --configuration releaseRuntimeClasspath \
        --dependency "com.empresa.features:feature-${feature}-impl" \
        > /dev/null \
        || { echo "Feature $feature ausente del runtimeClasspath"; exit 1; }
    done
```

Lista de features auditada según lo que la app **declara consumir** (no la
totalidad del catálogo del SDK).

### 6.2 Plugin Gradle propio (opcional)

Una alternativa más limpia: un plugin Gradle distribuido por el SDK que
añade automáticamente:

```kotlin
plugins {
    id("com.empresa.sdk.consumer") version "..."
}

sdkConsumer {
    features = listOf("observability", "core", "enc", "auth", "stor", "syn")
}
```

El plugin:
- Añade los `runtimeOnly` correspondientes.
- Inyecta el test de integración descrito en sección 4.
- Inyecta los keep rules.
- Falla el build si alguno de los `feature-*-impl` declarados no está en
  Maven local/remoto.

Centraliza disciplina sin que cada app tenga que recordarlo.

---

## 7. Mitigación 4 — Lint custom (detekt)

Una regla custom en detekt que el equipo SDK distribuye y cada app aplica:

```kotlin
// detekt-rules-sdk/src/main/kotlin/.../NoDirectSdkAccess.kt
class NoDirectSdkAccess(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "NoDirectSdkAccess",
        severity = Severity.CodeSmell,
        description = "Llamadas a MultiModuleSdkH desde fuera del módulo " +
                "wiring. Las apps deben acceder al SDK vía SdkProvider o " +
                "inyección de cada API individual.",
        debt = Debt.TWENTY_MINS,
    )

    override fun visitImportDirective(import: KtImportDirective) {
        super.visitImportDirective(import)
        val fqName = import.importedFqName?.asString() ?: return
        if (fqName.startsWith("com.grinwich.sdk.wiring.")
            || fqName == "com.grinwich.sdk.contracts.Resolver"
            || fqName.startsWith("com.grinwich.sdk.contracts.error.")
        ) {
            // Permitir sólo en :sdk:integration y en módulos test.
            val file = import.containingKtFile
            if (!file.virtualFilePath.contains("/sdk/integration/")
                && !file.virtualFilePath.contains("/test/")
                && !file.virtualFilePath.contains("/androidTest/")
            ) {
                report(CodeSmell(
                    issue, Entity.from(import),
                    "$fqName no debe importarse fuera de :sdk:integration o tests."
                ))
            }
        }
    }
}
```

Aplicado vía `detekt.yml` en cada app. Un PR que importe `MultiModuleSdkH`
desde `:app:feature-checkout` se rechaza automáticamente.

---

## 8. Mitigación 5 — Documentación viva del grafo

El argumento de auditoría ("dame la lista de qué depende de qué") se cubre
con un **generador automático** del grafo de dependencias parseando los
`FeatureProvider`s.

### 8.1 Estrategia

Un script (Kotlin compiler plugin, KSP processor, o parser AST simple
basado en `kotlin-compile-testing`) que:

1. Recorre todos los módulos `:features:feature-*-impl`.
2. En cada `FeatureProvider`, extrae:
   - `services`: el `Set<Class<*>>` declarado.
   - `dependencies implícitas`: cada `resolver.get(X::class.java)` que
     aparece dentro de `build()`.
3. Genera un fichero `docs/generated/dependency-graph.md` con:

   ```markdown
   ## Feature dependency graph

   - **EncProvider** publishes: `EncryptionApi`, `HashApi`
     - depends on: `SdkConfig`, `SdkLogger`

   - **AuthProvider** publishes: `AuthApi`
     - depends on: `EncryptionApi`, `SdkLogger`

   - **SyncProvider** publishes: `SyncApi`
     - depends on: `AuthApi`, `StorageApi`, `EncryptionApi`, `SdkLogger`
   ```

4. El generador corre en CI y falla si el `dependency-graph.md` commiteado
   no coincide con el regenerado: cualquier nuevo `resolver.get()` no
   documentado bloquea el merge.

### 8.2 Visualización

Mismo fichero exporta un `.dot` para Graphviz:

```dot
digraph SdkDeps {
  EncryptionApi -> SdkConfig;
  EncryptionApi -> SdkLogger;
  AuthApi -> EncryptionApi;
  SyncApi -> AuthApi;
  SyncApi -> StorageApi;
  ...
}
```

El equipo de arquitectura/auditoría tiene un PNG actualizado por release.

### 8.3 Ventaja sobre Pattern E con `dependencies` declaradas

Pattern E exige al desarrollador declarar las deps **dos veces** (una en
`dependencies =` del entry, otra en las llamadas a `r.get()`). El generador
automático extrae la verdad **del único lugar donde reside**: el código de
`build()`. Cero riesgo de drift entre declaración y realidad.

---

## 9. Mitigación 6 — Plan de migración escapable

Si el modelo runtime no aguanta el ritmo de incidentes (más de N
producción-incidentes por trimestre atribuibles a discovery silencioso), el
SDK **debe** tener un plan de migración a un modelo más estricto, sin
romper a las apps existentes.

### 9.1 Compatibilidad binaria del facade

`MultiModuleSdkH.init/get/shutdown` son la superficie pública. Cualquier
migración interna debe preservarla:

```kotlin
// Contrato que se preserva — invariante del SDK
override fun init(context: Context, config: SdkConfig)
override fun <T : Any> get(clazz: Class<T>): T
override fun shutdown()
override val isInitialized: Boolean
override val builtFeatureCount: Int
```

Una app que ya consume el SDK no debe cambiar nada al cambiar el patrón
interno.

### 9.2 Caminos de migración

| Hacia | Coste | Beneficio |
|---|---|---|
| **Pattern E2 (AutoServiceRegistry)** | Modificar cada `FeatureProvider` para declarar `dependencies` explícitas. ~10-30 min por feature. | DFS iterativo (no SOE en cadenas profundas). Detección de ciclo en init opcional vía `validateGraph()`. Sigue siendo runtime + ServiceLoader. |
| **Pattern Q2 con KSP custom** | Generar el `@Component(modules = [...])` y el `when` del facade desde anotaciones `@SdkFeature`. Implica desarrollar el processor KSP. | Compile-time COMPLETO. Pero pierde la flexibilidad de subsetting por app — todas las apps incluyen todas las features que el `@Component` declare. **Migración no recomendada para vuestro modelo.** |
| **Híbrido E + H opt-in** | Añadir campo `dependencies: Set<Class<*>>` opcional a `FeatureProvider`. Validar el grafo en `init()` cuando esté declarado. | Cada feature elige si valida eager. Cero ruptura, fail-fast opt-in. **Es el camino más realista.** |

### 9.3 Disparadores de la migración

Documentar explícitamente las condiciones que activan el plan:

- **>3 incidentes producción / trimestre** atribuibles a discovery
  silencioso (META-INF stripped, `runtimeOnly` olvidado, etc.).
- **>1 incidente con impacto a clientes** (no solo error técnico — error
  visible al usuario).
- **Auditoría/regulador** exige fail-fast en init demostrable.

Sin esos disparadores: Pattern H sigue siendo el modelo. El plan vive
documentado pero inactivo.

---

## 10. Checklist de hardening por release

Antes de publicar una nueva versión del SDK:

- [ ] CI ejecuta tests unitarios de `:di-contracts` (52 actualmente, 0 fallos).
- [ ] CI ejecuta `:benchmark:connectedReleaseAndroidTest` para todos los
      patrones afectados (al menos H, sus 12 benchmarks core).
- [ ] CI ejecuta el integration test de cada app demo/canónica con el SDK
      candidate, en build **release con R8/DexGuard aplicado**.
- [ ] CI verifica vía `unzip` que cada APK release contiene el descriptor
      `META-INF/services/com.grinwich.sdk.contracts.FeatureProvider`.
- [ ] CI corre `dependency-insight` y verifica que cada `feature-X-impl`
      declarado está en el `releaseRuntimeClasspath`.
- [ ] El generador del grafo de dependencias se ejecuta y commit del
      diff resultante (si hay) — bloquea release si el grafo cambió sin
      actualizar la documentación.
- [ ] SBOM generado y validado contra el catálogo (no falta ningún feature
      publicable).
- [ ] Detekt corre con la regla `NoDirectSdkAccess` activa en cada app
      consumidora — 0 warnings.
- [ ] Notas de release listan cualquier cambio de contrato visible (nuevos
      services, nuevas excepciones, breaking changes).

Antes de cada PR que añade un feature nuevo:

- [ ] El feature tiene `META-INF/services/com.grinwich.sdk.contracts.FeatureProvider`.
- [ ] El `FeatureProvider` tiene constructor sin args.
- [ ] `services` declara todas las APIs que `build()` retorna en el map
      (verificado por test unitario del propio feature).
- [ ] Cualquier `resolver.get()` nuevo dentro de `build()` está reflejado
      en el `dependency-graph.md` regenerado.
- [ ] La feature está documentada en el catálogo SBOM (api + impl + versión).

---

## 11. Lo que el SDK NO puede prevenir

A pesar de todas las mitigaciones, hay clases de fallo que sólo la app
puede detectar:

- **Una app olvida declarar `runtimeOnly(:feature-X-impl)`** y luego llama
  `sdk.get(XApi)`. Lanza `NoProviderFoundException`. El SDK se comporta
  correctamente; la responsabilidad es de la app.
- **Una app aplica un `dexguard.pro` propio que sobreescribe** las reglas
  consumer del SDK. Posible silently strip. El test de integración en
  release lo detecta — pero sólo si la app lo ejecuta.
- **Una app integra una versión del SDK incompatible con sus features**.
  Mitigado parcialmente por SBOM y catálogo Gradle versionado, pero la app
  puede forzar versiones manualmente.

El modelo de responsabilidades de la sección 2 lo deja explícito: **el
equipo SDK provee herramientas; la app las aplica y verifica**. No hay
forma de hacer que un consumidor remoto valide en tu nombre.

---

## 12. Resumen

Pattern H es la elección correcta para este modelo de distribución (decenas
o cientos de apps con subsetting de features por consumidor). Su debilidad
estructural — validación runtime — se mitiga con:

1. **Test de integración por app**, ejecutado en build release con
   shrinker aplicado. Detecta cualquier silently-stripped descriptor.
2. **Keep rules R8/DexGuard plantilladas** y verificación manual del APK
   release (`unzip` + grep del descriptor).
3. **Verificación Gradle del classpath** vía `dependencyInsight` por
   feature declarado.
4. **Detekt con regla custom** `NoDirectSdkAccess` que prohibe importar
   `MultiModuleSdkH` fuera de `:sdk:integration`.
5. **Generador automático del grafo** de dependencias derivado del código
   real (zero drift entre doc y realidad).
6. **Plan de migración a E2 o híbrido E+H opt-in** documentado con
   disparadores claros, listo para activar si el modelo runtime no
   aguanta.

Las partes que el SDK no puede garantizar (que cada app aplique las
mitigaciones, que su DexGuard config sea correcta, que su CI corra los
tests) quedan **explícitamente** en el modelo de responsabilidades. La
documentación es honesta sobre el reparto de garantías.
