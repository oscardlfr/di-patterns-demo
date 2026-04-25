# Jerarquía de excepciones del Resolver

> Aplica a los patrones que comparten la maquinaria de `di-contracts`:
> **E** (`ServiceRegistry`), **E2** (`AutoServiceRegistry`),
> **H/I/J/K** (`Resolver` + `FeatureProvider`).
> Los patrones que delegan en frameworks externos (Koin: L/M/N, Dagger: G/Q/Q2,
> Metro: O/O2, kotlin-inject-anvil: P/P2) propagan las excepciones nativas de
> esos frameworks sin envoltura.

## Motivación

Antes de esta jerarquía, todas las rutas de error del Resolver lanzaban
`IllegalStateException` (vía `error("...")` o `checkNotNull(...)`). Las
ramificaciones más problemáticas eran:

| Síntoma | Antes | Riesgo |
|---|---|---|
| Dos `FeatureProvider` se referencian mutuamente durante `build()` | `StackOverflowError` (irrecuperable) | Procesos zombi, sin información de qué provider cerró el ciclo |
| Un `build()` lanza por config inválida y otro hilo reintenta | Re-ejecución del `build()` defectuoso, posibles efectos colaterales | Estado inconsistente, fugas de recursos |
| Cast fallido por desalineación entre `services` y el map de `build()` | `IllegalStateException` con mensaje plano | Diagnóstico engorroso |
| Servicio declarado en `services` pero no publicado por `build()` | `IllegalStateException` | Mismo |

El nuevo diseño (i) detecta los ciclos antes de que el stack explote, (ii) tipa
cada error con su clase de dominio y (iii) marca como fallidos los providers
cuyo `build()` ya falló, evitando reintentos no deterministas.

## Jerarquía

Paquete: `com.grinwich.sdk.contracts.error`.

```
DependencyResolutionException (abstract, RuntimeException)
├── NoProviderFoundException(serviceName)
├── CircularDependencyException(providerName)
├── ProviderBuildException(providerName, cause)
├── ProviderAlreadyFailedException(providerName)
├── ServiceCastException(serviceName, cause)
└── ServiceNotAvailableException(serviceName, providerName)
```

| Excepción | Cuándo se lanza |
|---|---|
| `NoProviderFoundException` | `get(X)` y nadie ha registrado un provider que exponga `X` |
| `CircularDependencyException` | Durante `build()`, otro `resolver.get()` cierra un ciclo (mismo hilo, reentrada en `buildingProviders`) |
| `ProviderBuildException` | El `build()` de un provider lanza una excepción no tipada (la causa original queda en `cause`) |
| `ProviderAlreadyFailedException` | Se intenta resolver un servicio cuyo provider ya falló previamente. Hay que llamar `clear()` antes de reintentar |
| `ServiceCastException` | El instance publicado en el mapa de `build()` no es asignable a la `Class<T>` solicitada |
| `ServiceNotAvailableException` | El provider terminó `build()` sin publicar uno de los servicios que declaró en `services` |

Todas heredan de `DependencyResolutionException`; un consumidor que prefiera un
`catch` único puede capturar la base.

## Detección de ciclos: eager vs lazy

El proyecto soporta los dos enfoques en patrones distintos. La diferencia es
**cuándo** se ejecuta la validación del grafo:

| Pattern | Mecanismo | Detección de ciclos | Coste de declaración |
|---|---|---|---|
| **E** (`ServiceEntry` + `ServiceRegistry`) | Topo-sort de Kahn en `registerAll()` | **Eager — antes del primer `get()`** | Cada entry declara `dependencies: Set<Class<*>>` |
| **E2** (`AutoServiceEntry` + `AutoServiceRegistry`) | DFS iterativo con `visiting` set en el primer `get()` | Lazy — al resolver | Igual: `dependencies` declaradas |
| **H/I/J/K** (`FeatureProvider` + `Resolver`) | DFS recursivo con `buildingProviders` set en el primer `get()` | Lazy — al resolver | Sólo `services` (las deps son implícitas vía llamadas a `resolver.get()` durante `build()`) |

### Implicaciones para el consumidor

* En **E**, un grafo cíclico hace que `MultiModuleSdkE.init(...)` lance
  `CircularDependencyException`. El crash sale en logs de startup.
* En **E2/H/I/J/K**, `init()` siempre tiene éxito. El ciclo se manifiesta la
  primera vez que alguien resuelve un servicio del componente cíclico
  (`sdk.get(...)`).
* La detección lazy en H/I/J/K se hace **antes de profundizar el stack** vía
  el set `buildingProviders` en `Resolver.ensureBuilt`: el `StackOverflowError`
  queda eliminado como modo de fallo en favor de
  `CircularDependencyException`.

### Por qué H/I/J/K se mantienen lazy

El valor de `FeatureProvider` es la falta de boilerplate de deps: el provider
sólo declara qué publica, y las dependencias se descubren al ejecutar `build()`.
Añadir `dependencies` a `FeatureProvider` para validar al registrar
convertiría a H en una variante de E2 — y E2 ya existe para ese caso de uso.
Quien necesite *fail-fast* en init debe usar **Pattern E**, que es exactamente
esa propiedad.

### Posible extensión opt-in (no implementada)

Una alternativa que no rompe la API actual:

```kotlin
abstract class FeatureProvider {
    open val dependencies: Set<Class<*>> = emptySet() // opcional
    // ...
}

// Tras registrar todos los providers:
resolver.validateGraph() // opcional: corre topo-sort si hay deps declaradas
```

Quienes declaren `dependencies` se benefician de la detección eager; el resto
mantiene el comportamiento actual. Pendiente de demanda real.

## Política de reintentos

* Un `CircularDependencyException` **no** marca al provider como fallido: el
  ciclo es estructural, así que reintentar arroja la misma excepción de forma
  determinista. La estructura del grafo no cambia entre `get()` y `get()`.
* Un `ProviderBuildException` **sí** marca al provider en `failedProviders`:
  un segundo `get(...)` lanza `ProviderAlreadyFailedException` en lugar de
  re-ejecutar el `build()` defectuoso.
* `clear()` (invocado por `shutdown()` en cada facade) drena
  `failedProviders`, así que un nuevo `init()` empieza con estado limpio.

## Coste medible

Validado contra Pattern H mediante un A/B en la misma sesión del dispositivo
(Samsung Galaxy S22 Ultra, Android 16):

| Métrica | Pre | Post | Δ |
|---|---:|---:|---|
| `initCold_H` | 92,471 ns | ~104,000 ns | +0 ns atribuible al cambio (la diferencia entra en la varianza térmica entre runs) |
| `resolveFirst_H` (cache hit) | 21 ns | 41 ns | +10–20 ns atribuible al `try/catch` alrededor de `Class.cast` |
| `stress_initShutdown_H` | 83,639 ns | 136,410 ns | +0 ns atribuible al `synchronized(lifecycleLock)` |

El sobrecoste de cache hit es **inherente** a la API tipada: sin
`try/catch` no se puede mapear el `ClassCastException` a `ServiceCastException`
preservando la causa. Inlinearlo a mano (probado en una variante) no aporta
nada — la JVM ya inlinea el helper privado en hot path.

## Cómo lo capturan los `MultiModuleSdk*` facades

Los facades `MultiModuleSdkH/I/J/K` y `MultiModuleSdkE2` tienen un guard
adicional para preservar el contrato externo "si llamas mientras
`shutdown()` está corriendo, recibes `IllegalStateException`":

```kotlin
override fun <T : Any> get(clazz: Class<T>): T {
    check(_initialized) { "MultiModuleSdkH not initialized." }
    return try {
        resolver.get(clazz)
    } catch (e: DependencyResolutionException) {
        if (!_initialized) throw IllegalStateException("MultiModuleSdkH not initialized.", e)
        throw e
    }
}
```

Si la race con `shutdown()` deja al resolver vacío justo después del
`check(_initialized)`, las excepciones de dominio se remapean a la lifecycle
exception que el consumidor espera. Si el SDK sigue inicializado, la excepción
de dominio se propaga sin tocar.

`init()` y `shutdown()` están protegidos por `synchronized(lifecycleLock)` para
que dos hilos no puedan entrelazar registros y limpiezas. `get()` no toma ese
lock — el `Resolver` (y `AutoServiceRegistry`) ya tienen el suyo propio para
`ensureBuilt`, y la coherencia post-shutdown la cubre el catch+remap anterior.
