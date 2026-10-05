# Prueba de presentación SDL + Metal

Este ensayo abre una ventana independiente y dibuja un triángulo directamente
con Metal. Todavía no renderiza Minecraft ni instala el mod en tu perfil.

## Paso necesario desde tu Terminal

En la Terminal habitual de macOS ejecutá:

```sh
cd /Users/thiago/Desktop/MetalMC
python3 tools/presentation_test.py --exercise
```

No hace falta instalar dependencias nuevas en esta Mac. El comando compila el
proyecto y utiliza las bibliotecas SDL que ya incluye Minecraft 26.3.

La ventana debería permanecer abierta unos 18 segundos. Las acciones automáticas
empiezan cada dos segundos: tamaño pequeño, tamaño grande, ocultar/mostrar,
minimizar/restaurar, entrar/salir de pantalla completa y volver al tamaño inicial.
Dejá que termine para que ejecute todas las comprobaciones. Evitá pulsar otras
teclas durante el ensayo automático.

Observá si:

- Aparece un triángulo de colores sobre fondo oscuro; el vértice azul está arriba,
  rojo abajo a la izquierda y verde abajo a la derecha.
- El triángulo se adapta al tamaño de la ventana y se ve correctamente en Retina.
- La ventana vuelve a dibujar después de ocultarse, minimizarse y salir de
  pantalla completa, sin quedar negra o congelada.
- El proceso termina por sí solo y la Terminal muestra `PASS` sin errores de
  validación de Metal o JNI.

Al finalizar, comunicá si apareció la ventana, si estas transiciones funcionaron
y si terminó con `PASS`. Si falla, el registro queda en
`build/reports/sdl-presentation.log`; podés copiar el error que muestra la Terminal.
La prueba genera también `build/reports/sdl-presentation.json`. Ambos archivos
se reemplazan en cada ejecución.

Un `PASS` comprueba envío y finalización de comandos GPU, límite de cuadros en
vuelo, liberación y validaciones de la interfaz nativa. No certifica por sí solo
lo que se vio ni que el gestor de ventanas completó todas las transiciones.

## Prueba interactiva adicional

```sh
python3 tools/presentation_test.py
```

Arrastrá los bordes para cambiar el tamaño. `F` alterna pantalla completa, `M`
minimiza y restaura después de dos segundos, `H` oculta y muestra después de dos
segundos. `Esc` o el botón de cierre terminan la prueba. Para comprobar Retina en
otra pantalla, mové la ventana entre pantallas durante este ensayo.

Para compilar sin abrir una ventana:

```sh
python3 tools/presentation_test.py --build-only
```

## Estado registrado por el agente

La ejecución del usuario desde su Terminal **funcionó**: terminó con `PASS`, 862
cuadros enviados y completados, máximo de dos en vuelo y cero pendientes al
cierre. El usuario confirmó el resultado con una captura del triángulo. Se
registraron Retina 2×, cambios de tamaño y las transiciones de ventana. Detalles:
[registro de validación](research/presentation-validation.md).

El código Java y nativo compila y la prueba GPU fuera de pantalla pasa en Apple
M5 con validación de Metal/JNI. Desde las herramientas del agente, SDL devuelve
`The video driver did not add any displays` al inicializar vídeo. No se llega a
adquirir un drawable ni a ejecutar el ensayo de presentación. La causa exacta
no está demostrada. La ejecución desde la sesión gráfica del usuario ya confirmó
la presentación; no hace falta repetirla por los nuevos buffers.

## Contrato utilizado

SDL exige el hilo principal para
[crear la vista Metal](https://wiki.libsdl.org/SDL3/SDL_Metal_CreateView),
[obtener su capa](https://wiki.libsdl.org/SDL3/SDL_Metal_GetLayer) y
[consultar el tamaño en píxeles](https://wiki.libsdl.org/SDL3/SDL_GetWindowSizeInPixels).
El lanzador utiliza `-XstartOnFirstThread`. La vista y la ventana SDL se destruyen
después de drenar y cerrar la superficie Metal. Las esperas de finalización se
reservan para cierre/pruebas; los cuadros se envían de forma asíncrona.
