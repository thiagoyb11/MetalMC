# Estado del proyecto — 2026-10-04

**Completitud estimada: aproximadamente 44 %.** La suma ponderada da 44,1 %;
es una estimación de trabajo, no una medición exacta.

Último gate completo: **ABI 15 PASS**, Apple M5 a las 23:08:32–45. El build
actual es **ABI 16**, con queries fuera de pases y calibración compilados.
Después de cuatro fallos, los probes aislaron transferencias sin sampling como
trigger. El probe de estrategias a las **01:03:53** comprobó que markers compute
y un evento GPU recuperan los samples; descriptors explícitos siguen fallando.
Producción ahora usa un evento antes de cada marker blit y de la resolución,
con valores crecientes por batch, sin espera CPU. Se conservan copias por slot,
counters privados y snapshots presupuestados. Se ampliaron checks para múltiples
transferencias/markers, slots cacheados y markers alrededor de un pase render.
Build estricto, firma, packaging y compilación de tests pasan. **Gate completo
pendiente: `python3 tools/smoke_test.py` en la Terminal del usuario.** El agente
no ejecutó tests. Evidencia del probe:
`build/reports/timestamp-interleaved-strategies-event-compute-valid.log`.
Avance validado **44,1 % ≈44 %**, sin crédito adicional hasta el PASS completo.
El historial debajo conserva resultados y estimaciones de cada etapa.

El tercer gate ABI16, a las **00:26:45 del 2026-10-04**, volvió a resolver slot3=0
con counters privados y resolución GPU; todas las suites anteriores pasaron.
Se preservó como `build/reports/timestamp-query-abi16-gpu-resolve-failure.log`.
No se afirma una nueva corrección de producción. Se preparó/compiló/firmó un
diagnóstico aislado de 12 configuraciones para sampling y resolución; el usuario
debe ejecutar `python3 tools/timestamp_probe.py` en su Terminal. El agente no
ejecutó el diagnóstico. El usuario completó el diagnóstico a las **00:39:46**:
las 12 configuraciones GPU fueron VALID, con bytes correctos y lecturas CPU de
rango completo/slot coincidentes. Evidencia preservada como
`build/reports/timestamp-probe-12-configurations-valid.log`. Producción ahora
usa el patrón de copia real por slot del probe, con ocho bytes de buffers por
slot en el presupuesto existente y checks de retención/cleanup actualizados.
Build estricto/firma/compilación de tests pasan; el gate completo corregido espera
`python3 tools/smoke_test.py` en la Terminal del usuario. Avance validado sigue
**44,1 % ≈44 %**.

El cuarto gate completo a las **00:44:16** volvió a fallar slot3=0 con el cambio
a copias; todas las suites anteriores pasaron. Log preservado como
`build/reports/timestamp-query-abi16-copy-failure.log`. Se preparó/compiló/firmó
un probe de cuatro casos que compara8/32 slots, transferencias intercaladas de
64KiB, lifetimes de autorelease por encoder y resultados dentro del callback
versus después del wait. Comando histórico ya completado: `python3 tools/timestamp_probe.py --production-sequence`.
No se afirma otra corrección de producción ni se repite el gate completo sin
nueva evidencia. Avance validado permanece **44,1 % ≈44 %**.

El probe a las **00:56:38** reprodujo el fallo fuera de JNI: pools8/32 funcionan
con sampling consecutivo y devuelven slot3=0 al intercalar los dos encoders de
transferencias. Los bytes GPU son correctos y callback/post-wait coinciden en
todos los casos. Se preservó timestamp-production-sequence-interleave-failure.log.
Se compiló/firmó un diagnóstico que compara el control fallido con descriptors
blit explícitos, markers compute y un evento GPU antes del segundo marker.
Comando histórico ya completado: `python3 tools/timestamp_probe.py --interleaved-strategies`.
Los resultados posteriores sustentan la corrección por evento descrita arriba. **44,1 % ≈44 %**.

El objetivo es renderizar Minecraft Java 26.3 completo directamente mediante
Metal, con paridad visual, integración en Fabric y estabilidad. El porcentaje
no representa FPS, líneas de código ni proporción de gráficos del juego ya
renderizados: actualmente Minecraft sigue utilizando su backend habitual.

| Etapa | Peso del proyecto | Avance estimado | Aporte al total |
| --- | ---: | ---: | ---: |
| Auditoría de versión y contratos | 3 % | 100 % | 3,0 % |
| Biblioteca nativa, JNI y render de diagnóstico | 5 % | 100 % | 5,0 % |
| Presentación independiente con SDL/Metal | 5 % | 100 % | 5,0 % |
| Integración del backend en Minecraft | 12 % | 40 % | 4,8 % |
| Recursos, transferencias y sincronización | 18 % | 85 % | 15,3 % |
| Shaders, pipelines y pases de renderizado | 22 % | 50 % | 11,0 % |
| Paridad visual y funcionamiento del juego completo | 25 % | 0 % | 0,0 % |
| Estabilidad, benchmarks y distribución | 10 % | 0 % | 0,0 % |
| **Total** | **100 %** | | **44,1 % ≈ 44 %** |

La fórmula es `suma(peso × avance de la etapa / 100)`. Los pesos expresan una
estimación del esfuerzo restante y pueden cambiar al descubrir problemas de
compatibilidad; no son compromisos de tiempo. Shaders, paridad del juego y
estabilidad concentran el trabajo más difícil que falta. La estimación de avance
se apoya en las etapas del [plan](IMPLEMENTATION_PLAN.md), no en contar casillas
igualmente: un shader y la paridad de todo el juego requieren más trabajo que
añadir una fábrica de recursos.

El 85 % de recursos reconoce buffers, mapas, texturas 2D/arrays/mips, views,
samplers, transferencias y regiones, borrados completos y con scissor de color/profundidad,
fences, callbacks, memoria transitoria reutilizable y conservación de recursos
comprobados en GPU. Falta validar los timestamps iniciales y completar los timestamps dentro de
pases, formatos/cubemaps adicionales, sincronización más precisa y pruebas largas. La integración recibe
40 % porque el adaptador implementa fábricas, comandos, memoria transitoria y
DeviceInfo con propiedades/límites comprobados en GPU de la API exacta del
frontend. Faltan queries/profiler, debug, compilación en workers, superficie del
juego, selección del backend y su activación segura.

La prueba completa de ABI 6 pasó en la terminal del usuario a las **16:12:43**
en **Apple M5**, con validación Metal y JNI. El nuevo paso verifica arenas
alineadas, asignaciones parciales, snapshots, bytes GPU, referencias caducadas,
64 envíos con reutilización y 64 asincrónicos, presupuestos, cancelación y cierre.
También volvieron a pasar todas las suites anteriores de texturas, buffers y
diagnósticos. Detalles: [memoria transitoria](research/transient-memory-validation.md).

Los dibujos indexados/múltiples pasaron ABI 11; los indirectos pasaron la suite completa ABI 12. La activación del backend en Minecraft seguirá pendiente
hasta poder cumplir su contrato sin operaciones esenciales sin implementar.

El soporte inicial de attachments de pases de renderizado con ABI **7** pasó
la suite completa en la terminal del usuario a las **16:46:15–16**, en Apple M5
con validación Metal/JNI. Se comprobaron ocho formatos, mips, preservación y
borrado, múltiples attachments, cancelación, límites y conservación de recursos.
Todas las suites anteriores volvieron a pasar. Este paso aporta 5 % a la etapa
de shaders/pipelines/pases (1,1 puntos al proyecto), elevando la estimación de
31 % a **32 %**. Todavía faltan pipelines del backend y comandos de dibujo; el traductor inicial pasó la validación de ABI 8 indicada abajo.
Detalles: [pases de renderizado](research/render-pass-validation.md).

Por instrucción del usuario, las próximas pruebas se entregan como comandos
para ejecutar en su terminal aparte; el agente puede leer los logs y preparar
correcciones, pero no lanzar nuevas pruebas automáticamente.
El usuario solicita esta estimación al finalizar **cada paso** futuro.

ABI **8** pasó la suite completa en la terminal del usuario a las
**17:28:25–27** en **Apple M5**, con validación Metal y JNI. Se verificaron
traducción SPIR-V → MSL, reflexión/remapeo de uniforms, imágenes, samplers y
push constants, ownership, concurrencia, el módulo real del frontend y la
compilación de funciones Metal. Todas las suites anteriores volvieron a pasar.
El log y los MSL generados fueron inspeccionados y el resultado se preservó en
`build/reports/shader-translation-smoke-pass.log`.

Esto eleva la etapa shaders/pipelines/pases del 5 % al **15 %** (3,3 puntos
al total), sumando 2,2 puntos al proyecto: **34,3 % ≈ 34 %**. El crédito reconoce
el traductor inicial y la compilación nativa, con un subconjunto acotado; faltan
pipelines, layouts de vértices, bindings, dibujos y compatibilidad con todos los
shaders vanilla. Compilar funciones no demuestra dibujos ni paridad del juego.
Detalles: [traducción de shaders](research/shader-translation-validation.md).


ABI **9** pasó la suite completa en la terminal del usuario a las
**18:36:06–09**, en **Apple M5**, con validación Metal y JNI. El log completo
fue inspeccionado y preservado en `build/reports/pipeline-draw-smoke-pass.log`.
Se verificaron pipelines nativos conservados, variantes con/sin profundidad,
layouts y bindings de vértices, píxeles de dibujos directos con instancias,
mezcla/máscaras, scissor, profundidad, offsets, memoria transitoria y ciclos de
vida. Los 16 envíos asincrónicos completaron su conservación y liberación de
recursos; todas las suites anteriores también pasaron. La compilación estricta,
firma y JAR están completas. No se ejecutaron pruebas desde el agente.

Esto eleva shaders/pipelines/pases del 15 % al **25 %** y el aporte de 3,3 a
5,5 puntos: **36,5 % ≈37 %** del proyecto. Faltan uniforms/texturas de shaders,
dibujos indexados/indirectos, compatibilidad vanilla y activación del juego.
La prueba cubre el subconjunto inicial, no todos los estados ni paridad completa.
No se requiere repetir esta suite para el incremento exacto validado.
Detalles: [pipelines y dibujos](research/pipeline-draw-validation.md).


ABI **10** pasó la suite completa en la terminal del usuario a las
**19:18:37–43**, en **Apple M5**, con validación Metal/JNI y exit code 0.
El agente inspeccionó el log completo y lo preservó en
`build/reports/shader-resource-smoke-pass.log`. Pasaron los píxeles de uniforms
por etapa, texturas/views y samplers combinados, snapshots de push constants,
mips/capas/siete formatos de color, memoria transitoria, rebinding, cancelación
y conservación de recursos, incluidos 32 envíos asincrónicos de la nueva suite.
Todas las suites anteriores también pasaron. La compilación estricta, firma
nativa y empaquetado están completos; no se lanzaron pruebas desde el agente.

Esto eleva shaders/pipelines/pases del 25 % al **35 %** (7,7 puntos), con
**38,7 % ≈39 %** del proyecto. No otorga crédito por juego/paridad ni por FPS.
No se requiere repetir este incremento exacto ni la ventana independiente.
Faltan dibujos indexados/múltiples/indirectos, compatibilidad vanilla y activación
del backend en Minecraft. Detalles: [recursos de shaders](research/shader-resource-validation.md).


ABI **11** pasó la suite completa en la terminal del usuario a las
**19:41:23–31 del 2026-10-03**, en Apple M5 con validación Metal y JNI.
Se inspeccionó el log completo y se preservó en
`build/reports/indexed-draw-smoke-pass.log`. Pasaron los píxeles UInt16/UInt32,
firstIndex, bases firmadas, instancias, las cuatro variantes de multi-draw,
snapshots, preflight atómico, cancelación y retención de recursos, además de
cada suite anterior. Solo permanece el aviso conocido de Unsafe en LWJGL.
No se ejecutaron tests desde el agente. Esto eleva shaders/pipelines/pases
al **40 %** (8,8 puntos): **39,8 % ≈40 %** del proyecto. Los indirectos siguen
pendientes y los índices dependientes del contenido GPU deben ser válidos por
contrato. Minecraft continúa usando su backend actual.
Detalles: [dibujos indexados/múltiples](research/indexed-draw-validation.md).

ABI **12** implementa dibujos indirectos con registros GPU de 16/20 bytes,
transferencias ordenadas, offsets alineados a cuatro bytes, argumentos normales
y transitorios, retención y preflight de límites conocidos. La compilación
estricta completa pasó; `IndirectDrawTest` está preparado y compilado.
**La suite completa pasó en la terminal del usuario a las 20:18:57–20:19:07 en Apple M5**, con validación Metal/JNI. Se inspeccionó el log completo y se preservó en `build/reports/indirect-draw-smoke-pass.log`. Los parámetros y
rangos derivados del contenido GPU siguen siendo responsabilidad del frontend;
no se inspeccionan desde CPU. Esto eleva shaders/pipelines/pases del 40 % al 45 % y el total a **41 % (40,9 %)**. No otorga crédito de juego/paridad.
Detalles/comando: [dibujos indirectos](research/indirect-draw-validation.md).

ABI **13** añade los formatos de vértice vanilla faltantes: `RG16_SINT`,
`RGBA8_SNORM` (incluido input vec3 para normales) y `RGB32_SINT`. La nueva suite
verifica los layouts reales del juego con shaders sintéticos, píxeles, rangos,
alineación y conservación de recursos. Su gate GPU manual está pendiente;
el avance validado permanece en **41 % (40,9 %)**.
Detalles: [formatos de vértice](research/vertex-format-validation.md).

La primera prueba ABI 13 del usuario (20:46:41) pasó todas las suites anteriores
y falló al inicializar DefaultVertexFormat por falta de Guava Ticker en el
classpath independiente. El runner ahora resuelve las bibliotecas Java
instaladas completas desde metadata; el build estricto volvió a pasar. El log
fallido se preservó en `build/reports/vertex-formats-smoke-missing-runtime.log`.
Se requiere rerun manual; progreso validado permanece **41 %**.

El rerun ABI 13 de las 21:39:48 falló cargando `libjemalloc.dylib`: la ampliación
del classpath había añadido su binding Java sin el native correspondiente.
El resolver ahora incluye ambos tipos de artefactos del runtime instalado
para macOS arm64 (67 JARs), y se verificó por inspección la dylib arm64.
Build estricto completo pasó; rerun manual pendiente. Error preservado en
`build/reports/vertex-formats-smoke-missing-jemalloc.log`. Avance sigue **41 %**.

ABI **13** pasó la suite completa del usuario a las **21:53:40–54** en Apple M5
con validación Metal/JNI. Se inspeccionó y preservó el log completo en
`build/reports/vertex-formats-smoke-pass.log`: los dieciséis layouts reales
vanilla, coordenadas signed, normales SNORM, posiciones enteras, límites y
conservación de recursos pasaron, junto con todas las suites anteriores.
Esto eleva shaders/pipelines/pases del 45 % al **50 %** (11,0 puntos), para
**42,0 % ≈42 %** del proyecto. Los dos errores de classpath permanecen como
historial; el runtime completo con natives arm64 quedó validado.

ABI **14** compila propiedades nativas y DeviceInfo con límites conservadores
y features de dibujo previamente validadas. Build estricto, firma, JAR y toda
la compilación de pruebas pasaron. Su gate manual de propiedades/contratos,
píxeles en ejes máximos de texturas y ownership está pendiente; avance
validado sigue **42 %**. No se activó el frontend/backend del juego.
Detalles: [capacidades](research/device-info-validation.md).

ABI **14** pasó la suite completa en la terminal del usuario a las
**22:33:26–39**, en Apple M5 con validación Metal/JNI. Se inspeccionó y preservó
el log en `build/reports/device-info-smoke-pass.log`: propiedades nativas,
DeviceInfo conservador, bytes GPU en ejes máximos, snapshots/límites/ownership
y todas las suites anteriores pasaron. La integración sube de 30 % a **40 %**,
para **43,2 % ≈43 %** del proyecto. No aporta paridad de juego ni FPS.

ABI **15** implementa borrados de color/profundidad con scissor en un mip:
conserva píxeles externos, usa snapshots propios y retención asincrónica. Build
estricto, firma, JAR y toda la compilación de tests pasan; el nuevo gate GPU
manual está pendiente. Avance validado permanece **43 %**. No se lanzaron tests
desde el agente ni se activó el renderer del juego.
Detalles: [borrados con scissor](research/scissored-clear-validation.md).

ABI **15** pasó la suite completa a las **23:08:32–45**, en Apple M5 con
validación Metal/JNI. El usuario confirmó PASS y se inspeccionó/preservó todo
el log como `build/reports/scissored-clear-smoke-pass.log`. Pasaron los píxeles
color/D32 en siete formatos, regiones/mips externos intactos, snapshots mutables,
profundidad 0/0,5/1, cancelación, límites y 16 envíos asincrónicos con retención,
además de todas las suites anteriores. Recursos sube de 80 % a **85 %**, para
**44,1 % ≈44 %** del proyecto. Sin crédito de juego/FPS.

ABI **16** implementa pools de timestamps GPU con sampling entre operaciones
del encoder, lecturas OptionalLong no bloqueantes, generaciones/retención y
calibración contra nanoTime. Build estricto, firma, JAR y compilación de pruebas
pasan. El gate GPU manual está pendiente; el agente no lanzó tests. Los queries
dentro de pases siguen sin implementar y no se activó Tracy ni el frontend del
juego. Avance validado sigue **44 %**. Detalles:
[queries de timestamps](research/timestamp-query-validation.md).
