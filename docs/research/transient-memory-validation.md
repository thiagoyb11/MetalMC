# Memoria transitoria — Minecraft 26.3

Estado: **PASS** de la suite completa en la terminal del usuario, **2026-10-03
16:12:43**, **Apple M5**, con validación Metal/JNI y compilación estricta.
El usuario proporcionó la salida y se leyó el mismo log en disco. JNI permanece
en **ABI 6**: se reutiliza el encoder nativo existente, sin firmas JNI nuevas.

## Contrato inspeccionado

Las firmas y el bytecode de `TransientMemory`, `VulkanTransientMemory`, su
buffer temporal y `TransientBlockAllocator` se inspeccionaron en el JAR instalado
26.3. El tercer y cuarto `long` de las asignaciones son el tamaño mínimo y el
múltiplo de una asignación parcial al final de un bloque; no son índices de frame.
Se puede devolver el tamaño completo solicitado. Un tail parcial debe alcanzar
el mínimo y ser múltiplo del quantum. El wrapper Vulkan caduca en el envío y
rechaza `map()` y `slice()` del buffer; se usa el slice recibido de la asignación.
Los uploads de listas incluyen padding por alineación entre fuentes y al final;
multi-upload devuelve un slice con el tamaño original por cada fuente, en orden.

## Implementación

- `MetalTransientMemory` implementa todas las entradas abstractas y hereda las
  sobrecargas por defecto del contrato exacto. Cada encoder conserva su allocator.
- Tres arenas separadas: staging, GPU sin mapear y GPU mapeada. Bloques nativos
  compartidos de 1 MiB, o bloques mayores cuando la petición lo exige. Subasignación
  con alineación de offset compatible con el blit de cuatro bytes. Las arenas GPU
  conservan el padding reservado, fuera del slice visible.
- Los mapas contienen bytes Java directos separados; `close()` registra un upload
  asincrónico que toma inmediatamente una copia nativa. Cambiar el ByteBuffer
  retenido después no altera un upload ni una asignación reciclada. Los mapas
  deben cerrarse antes de consumir su slice o hacer submit. Cerrar un mapa dos
  veces no duplica la operación.
- El submit invalida los tokens anteriores antes de despachar callbacks. Un
  ticket retenido en el allocator conserva los bloques como ocupados hasta la
  finalización GPU. Se consulta sin espera ni callbacks al asignar/enviar, para
  evitar reentrada. El límite existente de tres envíos en vuelo mantiene la
  contrapresión. No se introduce una espera por cada frame.
- Los slices sólo se consumen en su encoder y dentro de su reserva. Un wrapper
  cerrado no destruye el bloque compartido; los comandos ya grabados siguen
  siendo válidos. Cancelar/cerrar el encoder libera el pool, y el encoder nativo
  conserva los bloques enviados hasta que termine la GPU.
- `allocateCpu` devuelve memoria Java directa alineada, de vida independiente
  del pool nativo. No se reciclan ByteBuffers CPU escapados. El GC controla su
  liberación; el límite por envío no representa un límite de memoria Java que un
  consumidor pueda retener entre envíos.

## Límites iniciales

64 MiB de bloques residentes por allocator, 64 MiB de snapshots Java reservados
por envío, 1024 asignaciones por envío, tamaños positivos y alineación 1..4096.
La alineación CPU debe ser potencia de dos; los offsets GPU aceptan otras
alineaciones positivas. Los bloques libres se guardan para reutilización del
mismo tipo; de momento no se compactan ni se expulsan automáticamente. Si el pool
no puede atender otra petición bajo su límite, falla explícitamente. También
aplican los límites existentes de operaciones/staging nativo y el presupuesto
global nativo de 1 GiB. Uploads/mapas vacíos no están soportados.

Las copias entre slices del mismo bloque siguen sujetas al límite existente de
buffers distintos del encoder. Los tres tipos de arenas evitan ese conflicto
en una copia staging → GPU. El contenido GPU sin upload es indefinido. Los
bytes de padding de un upload mapeado empiezan en cero. No se expone memoria
Metal en Java ni se afirma una mejora de FPS.

## Prueba completada

`TransientMemoryTest`, integrada en `NativeSmokeTest`, comprueba bytes GPU de
uploads simples/de listas/múltiples, mapas, padding y posiciones de entrada;
alineación CPU/GPU y subasignaciones; tails parciales; tokens caducados, rangos
forjados, usos, encoder/hilo propietario; mapas abiertos; cancelación y cierre
antes/después de submit. Comprueba un bloque reutilizado durante 64 envíos con
fence y otros 64 asincrónicos, conservación de cada patrón y presupuesto nativo.
Incluye los límites del pool y de cantidad de asignaciones.

El usuario ejecutó, desde otra terminal:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/smoke_test.py
```

Además de ejecutar Metal y `-Xcheck:jni`, el comando compila Java/Objective-C++
con warnings como errores, firma y empaqueta el JAR y vuelve a comprobar las
suites previas. Todas pasaron. Evidencia: `build/reports/native-smoke.log` (última
ejecución; se sobrescribe en futuras pruebas). No fue necesario repetir la
presentación SDL ni se activó el backend dentro del juego.
