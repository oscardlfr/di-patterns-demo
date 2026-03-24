# Dagger 2: Inicialización Modular de SDKs

Tres approaches para construir un SDK Android donde los consumidores seleccionan
qué features activar. Cada uno usa Dagger 2 para DI en compilación pero difiere
en cómo se organizan, descubren e inicializan las features.

Para comparación entre frameworks, ver [di-sdk-selective-init-comparison.md](di-sdk-selective-init-comparison.md).
Para conceptos de DI, ver [di-sdk-consumer-isolation.md](di-sdk-consumer-isolation.md).
Para dependencias cruzadas, ver [di-cross-feature-deps.md](di-cross-feature-deps.md).
Para el approach hybrid, ver [di-hybrid-koin-sdk-dagger-app.md](di-hybrid-koin-sdk-dagger-app.md).

---

## El Problema

Un SDK tiene N features (auth, analytics, payments, etc.). Los consumidores deben:
1. Elegir qué features activar
2. No ver clases de implementación
3. No pagar tamaño binario por features que no usan

Dagger 2 resuelve (2) vía codegen en compilación. Pero (1) y (3) entran en conflicto:
Dagger necesita conocer todos los módulos en compilación, y conocerlos significa compilarlos.
Los tres approaches navegan esta tensión de forma diferente.

---

## Approach A: Un Component, Todas las Features

```
┌─────────────────────────────────────────────────────────┐
│                   SdkComponent (@Singleton)              │
│                                                          │
│  CoreModule ─── AuthModule ─── PaymentsModule            │
│  (Logger)       (AuthService)  (PaymentService)          │
│  (Config)       puede inyectar Logger,                   │
│  (Network)      Config, AuthService                      │
└─────────────────────────────────────────────────────────┘
```

UN `@Component` lista TODOS los módulos de features. Dagger genera UNA factory
que sabe cómo crear todo. Cualquier módulo puede inyectar servicios de cualquier otro.

**Código real del proyecto** — ver `sample-dagger-a/`:

```kotlin
@Singleton
@Component(modules = [
    CoreModule::class, EncryptionModule::class,
    AuthModule::class, StorageModule::class,
    AnalyticsModule::class, SyncModule::class,
])
interface SdkComponent {
    fun encryptionService(): EncryptionService
    fun authService(): AuthService
    fun syncService(): SyncService
    // ...
}
```

### Por qué elegir A

- **Dependencias cruzadas automáticas.** SyncModule puede inyectar AuthService — mismo grafo.
- **Simple.** Un Component, un builder, una llamada init.
- **Validación completa en compilación.** Si falta un `@Provides`, el build falla.

### Por qué NO elegir A

- **Binario inflado.** Todos los módulos se compilan en el APK aunque el consumidor solo use Auth.
- **Acoplamiento central.** Añadir una feature requiere editar la anotación `@Component`.
- **No se puede publicar por feature.** `sdk-auth` no puede ser un artefacto Maven independiente.
- **Lazy init falso.** `getOrInitFeature()` solo cambia un flag — el código ya está compilado.

---

## Approach B: Component Separado por Feature

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ SecurityComp │    │ PaymentsComp │    │ AnalyticsComp│
│  @Singleton  │    │  @Singleton  │    │  @Singleton  │
└──────┬───────┘    └──────┬───────┘    └──────┬───────┘
       │                   │                   │
       └──────────┬────────┘───────────────────┘
                  ↓
          ┌──────────────┐
          │   CoreApis   │   ← interfaz Kotlin plana, NO Dagger
          └──────────────┘
```

Cada feature tiene su PROPIO `DaggerComponent`. No hay grafo global. El estado compartido
pasa a través de `CoreApis` — una interfaz Kotlin plana, no un constructo de Dagger.

**Código real del proyecto** — ver `sdk/impl-dagger-b/`:

```kotlin
// CoreApis es una interfaz Kotlin plana — NO @Component
interface CoreApis {
    val logger: Logger
    val config: SdkConfig
}

// Cada feature recibe CoreApis como @BindsInstance
@Singleton
@Component(modules = [EncryptionFeatureModule::class])
interface EncryptionComponent {
    fun encryptionService(): EncryptionService
    @Component.Builder interface Builder {
        @BindsInstance fun core(core: CoreApis): Builder
        fun build(): EncryptionComponent
    }
}

// Si Auth necesita EncryptionService → CoreApis extendido
interface AuthCoreApis : CoreApis {
    val encryptionService: EncryptionService  // ← añadido para cross-dep
}
```

**El problema de CoreApis:** `core.logger` es acceso a propiedad de Kotlin, NO resolución
de Dagger. Si PaymentsService necesita SecurityService, hay que añadirlo a CoreApis.
Con 15+ servicios compartidos, CoreApis se convierte en un God Object.

### Por qué elegir B

- **Binario eficiente.** Solo las features con dependencia Gradle acaban en el APK.
- **Publicación independiente.** `sdk-security` y `sdk-payments` son artefactos Maven separados.
- **Lazy init real.** `getOrInitModule()` crea un DaggerComponent nuevo on-demand.

### Por qué NO elegir B

- **Sin DI cross-feature.** Feature A no puede `@Inject` un servicio de Feature B — están en Components separados.
- **CoreApis crece.** Cada servicio compartido entre features = un campo más en CoreApis.
- **Edición central.** Nueva feature = editar el `when` block del facade SDK.

---

## Approach C: Per-Feature + ServiceLoader Discovery

Misma arquitectura que B (Components separados + CoreApis), pero las features
se auto-registran vía `ServiceLoader` de JVM. Añadir una feature = añadir dependencia
Gradle + fichero META-INF. Zero ediciones centrales.

**Código real del proyecto** — ver `sdk/impl-dagger-c/`:

```kotlin
// Contrato que cada feature implementa
interface FeatureInitializer {
    val featureName: String
    val requiredDependencies: Set<String>
    fun init(core: CoreApis, resolved: ServiceResolver)
    fun <T> getService(serviceClass: Class<T>): T?
}

// Registro en META-INF/services/com.grinwich.sdk.daggerc.FeatureInitializer:
// com.grinwich.sdk.daggerc.EncryptionInit
// com.grinwich.sdk.daggerc.AuthInit
// com.grinwich.sdk.daggerc.StorageInit
```

### Por qué elegir C sobre B

- **Zero edición central.** `DaggerCSdk.kt` no se toca al añadir features.
- **Escalable.** Con 20+ features, el `when` block de B es inmantenible. C escala sin ediciones centrales.

### Por qué NO elegir C

- **JVM exclusivo.** `ServiceLoader` requiere `META-INF/services/` — no disponible en Kotlin/Native.
- **Errores runtime.** Dependencia Gradle ausente = crash en init, no error de compilación.
- **Mismo problema CoreApis que B.** Las dependencias cruzadas siguen siendo manuales.

### Variante: recibir Component interfaces en vez de servicios sueltos

Validado en este proyecto (compila). En vez de pasar servicios individuales al Builder,
se pasa la interfaz del Component padre y el `@Module` extrae los servicios:

```kotlin
// ESTÁNDAR — servicios sueltos como @BindsInstance
@Component.Builder interface Builder {
    @BindsInstance fun enc(enc: EncryptionService): Builder
    @BindsInstance fun hash(h: HashService): Builder
    fun build(): CStorComp
}
@Module class CStorMod {
    @Provides fun storage(enc: EncryptionService, h: HashService, l: SdkLogger) =
        DefaultSecureStorageService(enc, h, l)
}

// VARIANTE — Component interface como @BindsInstance
@Component.Builder interface Builder {
    @BindsInstance fun encComp(encComp: CEncComp): Builder  // ← Component completo
    fun build(): CStorComp
}
@Module class CStorMod {
    @Provides fun encryption(encComp: CEncComp): EncryptionService = encComp.encryption()
    @Provides fun hash(encComp: CEncComp): HashService = encComp.hash()
    @Provides fun storage(enc: EncryptionService, h: HashService, l: SdkLogger) =
        DefaultSecureStorageService(enc, h, l)
}
```

**Ventaja:** El Builder recibe un solo objeto en vez de N servicios sueltos. Más limpio cuando
un Component padre expone muchos servicios.

**Coste:** Un `@Provides` extractor por cada servicio que se necesite del Component padre.
Con 5 servicios del padre, son 5 líneas de boilerplate.

**Relación con approach D:** Esta variante es funcionalmente equivalente a
`@Component(dependencies = [CEncComp::class])` del approach D, pero hecho manualmente.
D lo declara en la anotación y Dagger genera los extractores automáticamente — zero boilerplate.

---

## Approach D: Component Dependencies

```
CoreComponent → EncComponent → AuthComponent
                             → StorComponent
                                            → SyncComponent
```

Cada feature tiene su `DaggerComponent`, pero los Components hijo declaran
`dependencies = [ParentComponent::class]`. El hijo ve las provision methods del padre
**automáticamente** — sin CoreApis, sin wiring manual.

**Código real del proyecto** — ver `sdk/impl-dagger-d/`:

```kotlin
// Auth depende de Core + Encryption
@AuthScope
@Component(
    dependencies = [CoreComponent::class, EncComponent::class],
    modules = [InternalAuthModule::class],
)
internal interface AuthComponent {
    fun auth(): AuthService
}

@Module
internal class InternalAuthModule {
    @Provides @AuthScope
    fun auth(enc: EncryptionService, logger: SdkLogger): AuthService =
        DefaultAuthService(enc, logger)
    //   Dagger resuelve enc desde EncComponent.encryption() — AUTOMÁTICO
}
```

### Por qué elegir D

- **Cross-feature automático.** `dependencies=[EncComponent]` — Dagger resuelve sin CoreApis.
- **Compile-time safe.** Parent faltante = error de compilación.
- **Lazy init real.** `getOrInitModule()` crea Components on-demand con cascada.
- **Sin God Object.** No hay interfaz CoreApis que crezca.

### Por qué NO elegir D

- **Binario no lean.** Todas las features están en `impl-dagger-d`. Para binario lean, cada feature necesitaría su propio módulo Gradle.
- **Edición central.** Nueva feature = editar `Feature` enum + `when` block en `DaggerSdk.kt`.
- **JVM exclusivo.** Dagger no soporta KMP.

---

## Comparación

|  | A: Monolítico | B: Per-Feature | C: Discovery | D: Component Deps |
|---|---|---|---|---|
| **Arquitectura** | 1 Component global | N Components + CoreApis | N Components + ServiceLoader | N Components con `dependencies=[]` |
| **Cross-feature** | ✅ Automático | ❌ Solo CoreApis | ❌ Solo CoreApis | ✅ Automático |
| **Singletons** | ✅ `@Singleton` | ⚠️ CoreApis manual | ⚠️ CoreApis manual | ✅ Provision methods |
| **Binario** | ❌ Todo compilado | ✅ Solo Gradle deps | ✅ Solo Gradle deps | ❌ Todo compilado |
| **Lazy init real** | ❌ | ✅ | ✅ | ✅ |
| **Añadir feature** | Editar @Component | +CoreApis + `when` | +META-INF (zero central) | +`dependencies` + `when` |
| **Compile-time** | ✅ Grafo completo | ⚠️ Per-feature | ⚠️ Per-feature + runtime | ✅ Grafo con deps |
| **KMP** | ❌ | ❌ | ❌ | ❌ |
| **Complejidad** | Baja | Media | Alta | Media |

### Cuándo usar

| Escenario | Approach |
|----------|----------|
| SDK pequeño (≤5 features), features interdependientes | **A** |
| SDK modular, publicación per-feature | **B** |
| 20+ features, adiciones frecuentes, JVM | **C** |
| Cross-deps complejas + compile-time safety | **D** |
| KMP necesario | Ninguno — ver Koin en [comparación](di-sdk-selective-init-comparison.md) |
