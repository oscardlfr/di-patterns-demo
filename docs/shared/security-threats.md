# Threat surface por patron

> Analisis de vulnerabilidades por mecanismo. Hasta ahora el `consumer-rules`
> y la doc de hardening miraron solo Pattern H (ServiceLoader). Cada uno de
> los 21 patrones tiene su propio threat surface — agrupados por
> **mecanismo de discovery** (la dimension que mas afecta a la superficie
> de ataque) y **modelo de DI** (la dimension que afecta a quien puede
> resolver que en runtime).

## 0. Modelo de amenazas

**En alcance:**
- Supply chain compile-time (dependencia maliciosa que se incluye en el
  build).
- Service hijacking (registrar provider para servicio que ya tenia uno
  legitimo).
- Reverse engineering / runtime tampering (Frida, Xposed, modificacion
  de APK).
- Exfiltracion via componentes "jugosos" del SDK (logger, auth, crypto).

**Fuera de alcance:**
- Compromise del entorno de build (Jenkins, Gradle wrapper, etc.) —
  trabajo del equipo de plataforma.
- 0-days del JVM/Android runtime.
- Ingenieria social.

**Asumido siempre activo en produccion:** DexGuard con keep rules
correctas, tamper detection, anti-debugging, string/class encryption.
DexGuard mitiga reverse engineering, hooking y APK modification.
**No** mitiga supply chain compile-time ni bugs de logica del SDK.

---

## 1. Clasificacion por mecanismo de discovery

El **vector principal** de las vulnerabilidades es como el SDK descubre
que features estan disponibles. Cuanto mas abierta es la lista, mas
amplio el threat surface.

### Tipo 1 — Lista cerrada en codigo (closed list)

El consumidor o el wiring **edita codigo central** para registrar cada
feature. Ningun proceso automatico anade providers en runtime ni en
compile-time.

**Patrones:** A, B, D, E, E2, G, Q, Q2.

**Threat surface:**
- ✅ Inmunes a inyeccion via `META-INF/services` o `<meta-data>`.
- ✅ Inmunes a hijacking por orden de classpath.
- ⚠️ Compile-time supply chain sigue posible si una dependencia
  maliciosa modifica el codigo del wiring directamente — pero eso
  requiere PR aprobado, lo que es exactamente el control que un banco
  quiere.

**Veredicto:** la mas robusta. Es el modelo "todo cambio pasa por
review humano".

### Tipo 2 — Annotation-driven compile-time discovery

El compilador (KSP / compiler plugin) escanea anotaciones
`@ContributesTo` (o equivalente) y genera el grafo en compile.

**Patrones:** O, O2 (Metro compiler plugin), P, P2 (kotlin-inject-anvil
KSP).

**Threat surface:**
- ✅ Sin discovery runtime — las clases participantes estan congeladas
  en el bytecode generado.
- ⚠️ Compile-time supply chain: una dependencia maliciosa con
  `@ContributesTo` sera incluida automaticamente en el grafo en compile.
  El wiring no la "rechaza" porque no existe wiring central que listar.
- ⚠️ Compromise del compiler plugin / KSP processor: el plugin tiene
  acceso al AST y puede modificar/anadir bindings. Vector teorico,
  poco frecuente en practica.

**Veredicto:** robusto frente a runtime tampering. Vulnerable al mismo
vector de supply chain que H pero materializado en compile-time, asi
que **no se ve en runtime** — el binario malicioso ya esta firmado
y distribuido. Allowlist no aplica aqui (no hay punto de registro).

**Mitigacion principal:** SBOM con verificacion de hashes + Maven
firewall. Auditoria del compiler plugin / KSP processor.

### Tipo 3 — ServiceLoader runtime discovery

`ServiceLoader.load()` lee `META-INF/services/<SPI>` en runtime y
carga clases reflexivamente.

**Patrones:** C (monolitico), H, I, J (multi-modulo Resolver), L, M
(multi-modulo Koin).

**Threat surface:**
- ❌ Cualquier JAR en `runtimeClasspath` con `META-INF/services/<SPI>`
  + clase concreta que extienda el SPI **sera cargado**. No hay
  verificacion de autoria.
- ❌ Reflection ctor execution — codigo en `init {}` o constructor sin
  args corre durante init del SDK.
- ❌ Service hijacking via last-write-wins (corregible — ver feature
  branch `feature/security-allowlist-and-override-check`).
- ❌ Logger persistent malicioso captura todos los logs.
- ⚠️ DexGuard puede strippear el descriptor `META-INF/services/...` si
  resource shrinking esta agresivo — falla silenciosa en release.

**Mitigaciones:**
- **Allowlist de FQN aprobados** en register (ya en feature branch).
  Cierra supply chain compile-time, hijacking, logger malicioso.
- Override detection en register (ya en feature branch).
- Keep rules para `META-INF/services/**` y FeatureProvider hierarchy
  (ya en `consumer-rules.pro`).
- Test integracion en CI release que valide que cada API documentada
  resuelve.

**Veredicto:** la mas amplia, requiere defensa en profundidad. Con
las mitigaciones aplicadas queda al nivel de tipo 2.

### Tipo 4 — Sweet-spi (KSP-generated ServiceLoader replacement)

Igual que ServiceLoader pero los descriptores se generan en compile via
KSP, sin descriptores runtime.

**Patrones:** N.

**Threat surface:**
- ✅ No hay `META-INF/services` que strippear — el descriptor es codigo
  compilado.
- ❌ Mismo vector de supply chain que tipo 3: dependencia maliciosa con
  anotacion sweet-spi se incluye automaticamente.
- ❌ KSP processor maliciosa misma preocupacion que en tipo 2.

**Veredicto:** intermedio entre 2 y 3. Mejor que ServiceLoader (no hay
descriptor a strippear), pero mismo riesgo de supply chain compile-time.

### Tipo 5 — AndroidManifest meta-data discovery

`PackageManager.getServiceInfo` lee `<meta-data>` del manifest mergeado.

**Patrones:** K.

**Threat surface:**
- ✅ El manifest **no se puede strippear** — es estructural al APK.
  Robusto frente a shrinkers agresivos sin keep rules de
  `META-INF/services`.
- ❌ Cualquier dependencia que aporte un fragmento de manifest con
  `<meta-data>` apuntando a su clase **se incluye en el manifest
  mergeado**. Mismo vector de supply chain que ServiceLoader.
- ⚠️ `Class.forName(metaDataValue)` en runtime: si DexGuard ofusca la
  clase, el `<meta-data>` apunta al FQN original → `ClassNotFoundException`.
  Necesita keep rules igual que ServiceLoader (no es ventaja).
- ⚠️ Coste: 2-3x mas lento en init que ServiceLoader (PackageManager
  IPC al system_server).

**Veredicto:** ventaja sobre ServiceLoader **solo** en escenarios donde
el shrinker strippea descriptors. Mismo riesgo de supply chain. No
mitiga hijacking ni logger malicioso.

---

## 2. Clasificacion por modelo de DI

Independiente del discovery, el modelo DI afecta **quien puede pedir
que** una vez el SDK esta inicializado.

### Modelo A — DI por inyeccion de constructor (compile-time enforcement)

Cada feature declara explicitamente sus dependencias en su
constructor. Dagger/kotlin-inject/Metro generan el codigo que
construye el grafo.

**Patrones:** A, B, D, E, E2, G, H, J, O, O2, P, P2, Q, Q2.

**Propiedad:** un feature **no puede pedir un servicio que no declaro**.
Si una feature comprometida intenta acceder a `EncryptionApi` sin
declararla, no compila o falla en compile.

**Vulnerabilidad residual:** el grafo del feature puede ser modificado
en compile-time si su `@Module` o `@Provides` esta comprometido.

### Modelo B — Service Locator (runtime resolution)

El feature pide servicios por tipo en runtime: `koin.get<Foo>()`,
`resolver.get(Foo::class.java)`. Sin enforcement compile-time.

**Patrones:** Koin, Hybrid, I (Pure Resolver), L, M, N (Koin con
discovery distinto).

**Propiedad:** cualquier feature con acceso al locator puede pedir
**cualquier** servicio. Un feature comprometido puede `koin.get<EncryptionApi>()`
y usarlo aunque "logicamente" no debiera tener acceso.

**Vulnerabilidad:** privilege escalation entre features. La mitigacion
clasica es no exponer el locator — pero en estos patrones es
necesariamente accesible al feature en su `build()` o equivalente.

### Modelo hibrido

- **C, H, J** combinan: cada feature usa Dagger/kotlin-inject **internamente**
  (compile-time) pero el cross-feature wiring pasa por el Resolver
  (runtime). Las APIs publicas son contratos limpios; el Resolver solo
  se usa en `build()`.

---

## 3. Tabla resumen — threat surface por patron

Score de **runtime risk** asumiendo DexGuard activo + supply chain
compile-time como el vector principal a mitigar:

| Pattern | Discovery | DI model | Supply chain | Hijacking | Logger expo | Otros | Score |
|---|---|---|---|---|---|---|---:|
| **A** (Dagger mono) | closed | compile-time | compile-time only | n/a | n/a | educativo | Bajo |
| **B** (Per-feature Components) | closed | compile-time | compile-time only | n/a | n/a | enum cerrado | Bajo |
| **C** (DaggerC ServiceLoader) | ServiceLoader | compile-time | **alto** | **alto** (string-based) | sin Logger persistent | requiere allowlist | **Alto** |
| **D** (when-block + ensure) | closed | compile-time | compile-time only | n/a | n/a | central edit | Bajo |
| **E** (Registry + topo-sort) | closed | compile-time | compile-time only | n/a | n/a | central edit | Bajo |
| **E2** (AutoRegistry DFS) | closed | compile-time | compile-time only | n/a | n/a | central edit | Bajo |
| **G** (Factory functions) | closed | compile-time | compile-time only | n/a | n/a | central edit | Bajo |
| **H** (Resolver + Dagger) | ServiceLoader | compile-time per feature | **alto** sin allowlist | **alto** sin override guard | **alto** | la mas estudiada | **Alto sin mitigaciones / Bajo con allowlist + override** |
| **I** (Pure Resolver) | ServiceLoader | runtime (Service Locator) | **alto** | **alto** | **alto** | + privilege escalation entre features | **Alto** |
| **J** (kotlin-inject + SL) | ServiceLoader | compile-time per feature | **alto** | **alto** | **alto** | igual a H | **Alto sin mitigaciones / Bajo con allowlist + override** |
| **K** (AndroidManifest) | Manifest meta-data | compile-time per feature | **alto** | **alto** | **alto** | descriptor robusto a shrinker, + lento | **Alto sin mitigaciones** |
| **L** (Koin + SL eager) | ServiceLoader | runtime (Service Locator) | **alto** | **alto** | **alto** | + privilege escalation | **Alto** |
| **M** (Koin + SL lazy) | ServiceLoader | runtime (Service Locator) | **alto** | **alto** | **alto** | igual a L | **Alto** |
| **N** (sweet-spi + Koin) | sweet-spi (KSP) | runtime (Service Locator) | compile-time only | n/a | medio | + privilege escalation | Medio |
| **O** (Metro eager) | `@ContributesTo` | compile-time | compile-time only | n/a | n/a | KSP plugin scan | Bajo |
| **O2** (Metro Lazy) | `@ContributesTo` | compile-time | compile-time only | n/a | n/a | igual O | Bajo |
| **P** (KI-anvil eager) | KSP `@ContributesTo` | compile-time | compile-time only | n/a | n/a | KSP estandar | Bajo |
| **P2** (KI-anvil Lazy) | KSP `@ContributesTo` | compile-time | compile-time only | n/a | n/a | igual P | Bajo |
| **Q** (Hilt-style eager) | closed (`@Component(modules)`) | compile-time | compile-time only | n/a | n/a | central edit | Bajo |
| **Q2** (Hilt-style Lazy) | closed (`@Component(modules)`) | compile-time | compile-time only | n/a | n/a | central edit | Bajo |
| **Koin** (mono) | closed modules | runtime (Service Locator) | compile-time only | n/a | n/a | + privilege escalation intra-app | Medio |
| **Hybrid** | closed modules | runtime + Dagger bridge | compile-time only | n/a | n/a | bridge filtra acceso | Medio |

### Lectura

**Sin mitigaciones, los patrones con discovery runtime (C, H, I, J, K, L, M)
son los unicos con score Alto.** Los closed-list y los compile-time
discovery son inherentemente robustos al supply chain runtime.

**Con allowlist + override guard aplicados** (feature branch existente),
H, J, K bajan a **Bajo**. C, L, M tambien bajarian si se aplicasen las
mismas mitigaciones (mismo vector).

I y los Service Locator (Koin/Hybrid/L/M/N) tienen un riesgo adicional
no mitigable arquitecturalmente: **privilege escalation entre features**.
Cualquier feature comprometida puede pedir cualquier servicio. Las
defensas viables son auditoria del codigo de cada feature + restringir
que features se incluyen.

---

## 4. Mitigaciones por categoria

### Para todos los patrones runtime-discovery (C, H, I, J, L, M, K)

1. **Allowlist FQN** rechazando providers no aprobados al registrar
   (implementado para H en feature branch — extensible al resto).
2. **Override detection** en register() (idem).
3. **Keep rules R8/DexGuard** para descriptores y clases (en
   `consumer-rules.pro`).
4. **Test integracion en CI release** que valide cada API
   (en `:sample-multimodule:src/androidTest`).
5. **SBOM con verificacion de hashes** de cada dependencia.

### Para todos los patrones compile-time discovery (O, O2, P, P2, N)

1. **SBOM con hashes** del classpath de compilacion + verificacion en
   CI antes del firmado.
2. **Maven repository firewall** (Artifactory/Nexus interno) con
   vetting de cada dependencia que aporte una anotacion `@ContributesTo`
   (o equivalente).
3. **Auditoria periodica del compiler plugin / KSP processor** (Metro,
   kotlin-inject-anvil, sweet-spi).
4. **Build reproducibility** — el mismo source debe producir el mismo
   binario byte-a-byte. Permite detectar inyecciones en el pipeline.

### Para Service Locator (Koin, Hybrid, L, M, N, I)

1. **Code review estricto** del codigo de cada feature: prohibir
   `koin.get<X>()` o `resolver.get(X)` para servicios fuera del scope
   declarado del feature.
2. **Lint custom** que bloquee `koin.get<EncryptionApi>()` (o
   equivalente) desde modulos de feature que no esten en una lista de
   "esta feature puede pedir crypto".
3. **Auditoria de cada feature publicada al SDK**: que solo pida lo
   que necesita.

### Para todos sin excepcion

1. **DexGuard activo en release** con tamper detection +
   anti-debugging + class/string encryption.
2. **Sanitizacion de mensajes de excepcion** — politica de no incluir
   valores reales de SdkConfig/tokens en mensajes (mitigacion de
   exfiltracion via Crashlytics).
3. **Logger registrable explicitamente, no descubrible** —
   especialmente en C, H, I, J, K, L, M, N donde el logger podria ser
   sobrescrito via discovery.

---

## 5. Recomendacion para banca

**Si la decision esta abierta:**
- **Closed-list patterns (D, E, E2, G, Q, Q2)** son los mas alineados
  con cultura de banca — control central, sin runtime discovery, sin
  Service Locator.
- **Compile-time discovery (P2, O2)** son la siguiente opcion —
  zero-touch para anadir features sin sacrificar runtime safety. Trade-off:
  frameworks jovenes (P2 mejor mantenido por Amazon que Metro de
  ZacSweers).

**Si Pattern H esta decidido (escenario 100+ apps con subsetting):**
- Aplicar allowlist + override guard (feature branch
  `feature/security-allowlist-and-override-check`).
- Logger fuera de ServiceLoader (registrable explicitamente por
  wiring).
- Sanitizacion de mensajes de excepcion.
- DexGuard configurado y verificado.
- SBOM con hashes.
- CI gates documentados en `sdk-operational-hardening.md`.

**Lo que NO recomendaria en banca:**
- **C** (DaggerC ServiceLoader): mismas vulnerabilidades que H pero
  monolitico, sin la flexibilidad multimodulo. Sin valor anadido vs H.
- **I** (Pure Resolver): pierde compile-time safety entre features
  (Service Locator) ademas del riesgo de discovery. Combo peor.
- **L, M** (Koin + ServiceLoader): mismas vulnerabilidades de discovery
  que H + privilege escalation entre features por Service Locator.
  Sin compile-time safety. Doble debilidad.

---

## 6. Tabla de referencia rapida

| Quiero priorizar... | Pattern recomendado |
|---|---|
| Maxima seguridad, sin importar manualidad | E o E2 (closed list + topo-sort/DFS explicito) |
| Compile-time + zero-touch, banca con DexGuard | P2 (kotlin-inject-anvil lazy) |
| Compile-time + zero-touch + KMP y aceptamos framework joven | O2 (Metro lazy) |
| Discovery runtime con flexibilidad de features per app | H **con mitigaciones aplicadas** |
| Mantenibilidad maxima sobre seguridad | Q2 (Hilt-style + dagger.Lazy) |

Para detalle por pattern: `docs/multimodule/*/patterns-overview.md` y
`sdk-recommendation-android.md`.
