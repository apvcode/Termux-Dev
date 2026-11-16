<div align="center">

# devx para Android

**Un asistente de IA de bolsillo y un mando a distancia para devx en tu ordenador.**  
Sin cuenta y sin servidor nuestro. Tus claves y chats se quedan en el teléfono.

<br/>

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Download APK](https://img.shields.io/badge/download-APK-00c2d1.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Languages](https://img.shields.io/badge/languages-9-4f8cff.svg?style=flat-square&labelColor=0a0c11)](#)
[![devx: MIT](https://img.shields.io/badge/devx-MIT-4f8cff.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/blob/main/LICENSE)

<br/>

[English](README.md) &nbsp;•&nbsp; [Русский](README_ru.md) &nbsp;•&nbsp; [Українська](README_uk.md) &nbsp;•&nbsp; [Deutsch](README_de.md) &nbsp;•&nbsp; **Español** &nbsp;•&nbsp; [Français](README_fr.md) &nbsp;•&nbsp; [Português](README_pt.md) &nbsp;•&nbsp; [中文](README_zh.md) &nbsp;•&nbsp; [日本語](README_ja.md)

</div>

---

## De un vistazo

<table>
<tr>
<td valign="top" width="25%"><b>Asistente de bolsillo</b><br/>Cualquier modelo que elijas, voz, fotos y archivos, memoria, habilidades, MCP, búsqueda web.</td>
<td valign="top" width="25%"><b>Cuadernos</b><br/>Aprende o reúne ideas: fuentes, plan de estudio, tarjetas, resumen en audio, respuestas con citas.</td>
<td valign="top" width="25%"><b>Voz y teléfono</b><br/>Alarmas, llamadas, mensajes, lugares y tiempo, tareas programadas, widgets.</td>
<td valign="top" width="25%"><b>Mando para devx</b><br/>Las mismas sesiones que en la terminal: respuestas, aprobaciones, planes, archivos.</td>
</tr>
</table>

## Descargar

La app tiene su propia página en Termux-Dev, aparte de las versiones de devx (la app y devx tienen versiones separadas). Cada versión es allí un archivo con su suma de verificación; las nuevas se añaden junto a las antiguas y un archivo nunca se sustituye:

**[Releases → devx for Android](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)** → el archivo más reciente, `devx-android-X.Y.Z.apk`

Ábrelo en el teléfono y permite instalar desde esta fuente. Android 8.0 (API 26) o superior. Las actualizaciones se instalan sobre la versión anterior: cada versión está firmada con la misma clave y una versión publicada nunca se sustituye.

## Empezar

1. Abre la app y dile tu nombre.
2. **Ajustes → Proveedores → Añadir un proveedor.** Elige un preajuste o introduce una dirección y una clave de API (compatible con OpenAI o formato Anthropic; el formato se detecta solo). Carga los modelos y elige uno.
3. Escribe o toca el micrófono.

La clave de API se guarda cifrada en el teléfono (Android Keystore) y va solo al proveedor que añadiste.

## Qué hace

### Chat

- Varios proveedores y modelos, esfuerzo de razonamiento por modelo, ramas al editar un mensaje, chats temporales, chats bloqueados con el bloqueo de pantalla o un código, proyectos (trabajo continuo con sus propias instrucciones y conocimiento) y cuadernos (abajo).
- Fotos, vídeo, archivos y cámara en un mensaje; visor de PDF y de texto; exportación a Markdown o PDF.
- Memoria: el asistente guarda notas útiles y un resumen sobre ti, solo en el teléfono. Puedes leerlas, editarlas y borrarlas. El resumen se renueva solo en segundo plano y muestra lo nuevo; una nota que pronto dejará de ser cierta (un examen el viernes) puede caducar; cuando hay muchas notas, se envían primero las que tocan tu pregunta.
- Habilidades (guías escritas que el asistente lee cuando una tarea encaja): integradas, importadas (`.skill` o `SKILL.md`) o escritas por ti.
- Servidores MCP por https (GitHub, documentación y otros) con aprobaciones y un interruptor de solo lectura.
- Búsqueda web (lee las páginas de búsqueda por sí misma; una clave gratuita de Tavily o Brave en Ajustes la hace notablemente más precisa), archivos y paquetes, una terminal y Python dentro de la app, en una carpeta propia. Cada cosa se puede desactivar.
- Formularios que el asistente dibuja para ti: preguntas con etiquetas y pistas, opciones (una o varias), deslizadores e interruptores; lo que rellenas se guarda y un formulario enviado queda enviado.
- Los nombres de los chats se escriben mientras se escribe la respuesta, con una pequeña animación; el chat en el que estás está marcado en la lista.
- Respuestas ricas: tarjetas, tablas, gráficos, mapas, tiempo, temporizadores, listas, diagramas Mermaid, tarjetas de apps de Google Play y F-Droid. Saludos rotativos en la pantalla de inicio.
- Un editor a pantalla completa con vista previa de Markdown para mensajes largos. Selecciona varios chats para borrarlos o archivarlos.
- Escribe `@` en el campo: un menú de lo que puedes señalar (un cuaderno, un proyecto, un archivo de la biblioteca, una tarea programada). Un cuaderno o proyecto pasa a ser parte de la frase, con su color, y el asistente recibe lo que contiene. Por voz basta decir su nombre.
- Una sola búsqueda en chats, cuadernos, proyectos, archivos y fuentes, también por lo que está escrito dentro de documentos, PDF y notas.
- Pega imágenes y archivos directamente en el campo del mensaje (desde el portapapeles o el teclado). «Preguntar a devx» sobre un texto seleccionado no toca tu portapapeles.
- Copia de seguridad y restauración de chats, cuadernos, memoria, habilidades y ajustes en un archivo; una copia semanal automática en una carpeta que elijas (se conservan las cuatro últimas).
- Fijar y archivar chats, bloquear un chat concreto, añadir un chat a la pantalla de inicio. «Preguntar a devx» sobre un texto seleccionado cita el fragmento como tarjeta en tu siguiente mensaje.
- El asistente trabaja con archivos en una carpeta propia: crea y lee archivos, hace y descomprime paquetes, lee un PDF página a página y mira sus imágenes, edita imágenes y descomprime, modifica, vuelve a empaquetar y firma APK. Las aprobaciones se pueden dar desde la propia notificación (Permitir / Rechazar).
- Cuatro widgets (una barra para preguntar, botones rápidos, los últimos chats, las tarjetas por repasar) y dos mosaicos de ajustes rápidos (preguntar, voz).

### Cuadernos y la biblioteca

- Un cuaderno es un lugar para aprender o reunir ideas sobre un tema. Los cuadernos de *ideas* guardan fuentes y chats sobre un tema; los de *estudio* añaden una meta, un plan de lecciones y tu progreso. Los proyectos son aparte: son para el trabajo continuo.
- Fuentes: archivos, fotos (también de la cámara) y páginas web. Cada una recibe una descripción breve del asistente; pídele que busque fuentes en la web y añadirá las mejores páginas.
- Las respuestas sobre un cuaderno citan sus fuentes con pequeños chips (`notes.pdf · 12`); un toque abre la fuente, un PDF en esa página.
- Un cuaderno de estudio enseña paso a paso, comprueba con formularios, escribe el plan solo y mantiene tarjetas: las preguntas vuelven para repasar cuando toca (cuanto mejor sabes una, más tarde vuelve). Las tarjetas tienen una pantalla propia: la pregunta, un toque da la vuelta a la tarjeta, cuatro botones (otra vez, difícil, bien, fácil) que muestran cuándo volverá y un resumen al final; funciona sin el modelo y no cuesta nada. Un recordatorio diario es una notificación con el número de tarjetas por repasar.
- El asistente puede cambiar un cuaderno o un proyecto (nombre, icono, color, descripción, instrucciones, meta) y, tras hablarlo, proponer uno nuevo: confirmas en una tarjeta.
- Comparte un enlace, un texto, un PDF o una imagen desde cualquier app: devx ofrece un chat nuevo o uno de tus cuadernos, donde pasa a ser una fuente (un enlace se lee y se guarda).
- Estadísticas de estudio en la página: días seguidos, los últimos siete días en barras; un widget de la pantalla de inicio muestra cuántas tarjetas tocan y empieza el repaso. Las tarjetas y el resumen del cuaderno se pueden escuchar (un *resumen en audio* se escribe para escucharlo y luego se lee en voz alta), y un cuaderno se exporta como un archivo Markdown.
- La biblioteca guarda cada archivo que adjuntaste o que creó el asistente: búsqueda, filtros, dónde se usó, adjuntar a un chat nuevo, borrar en todas partes. PDF, Word, diapositivas, hojas de cálculo, texto, imágenes y sitios se abren en el visor integrado; los originales de Office se conservan.

### Aspecto, carácter y seguridad

- Aspecto: el del sistema, negro, gris oscuro o claro.
- Carácter: elige un estilo (tranquilo, sin rodeos, detallado, amable, profesional, directo, mentor), ajusta el tono, ponle nombre al asistente y escribe tus propias instrucciones. Un toque lo restablece todo.
- Bloqueo de la app con el bloqueo de pantalla, un código o biometría; también se pueden bloquear chats sueltos, y la lista de apps recientes oculta la imagen mientras hay un bloqueo activo.
- Fotos por mensaje, notificaciones de respuestas y aprobaciones, y si un ordenador puede funcionar en modo «todo», están en los ajustes.

### Asistente de voz

- Elige Devx como asistente del teléfono: el botón de encendido o un deslizamiento desde una esquina abre una ventana de voz transparente sobre cualquier app.
- Alarmas, temporizadores, recordatorios; abrir apps y ajustes del sistema; contactos, llamadas, mensajes y calendario (el último botón lo pulsas tú); linterna, música, volumen; brillo y No molestar.
- Lugares, rutas y tiempo de OpenStreetMap y Open-Meteo, sin claves. 2GIS y Google Maps se pueden añadir con tu propia clave.
- Mira tu galería, lee las notificaciones en pantalla, trabaja en una carpeta que elijas, pero solo cuando se lo pides y solo si lo permitiste.
- Tareas programadas: «cada mañana envíame las noticias sobre IA». Se ejecutan solas y llegan como chat y notificación.
- La velocidad de habla, la voz y un modelo rápido aparte para hablar están en **Ajustes → Asistente de voz**.
- Toca la píldora para escribir en vez de hablar; mantenla y desliza hacia arriba para abrir la app con el mismo chat (una pista sobre la píldora muestra siempre cómo). El micrófono tiene sus propios sonidos suaves en vez del pitido del servicio de voz.
- «Seguir escuchando tras una respuesta» (manos libres) y el idioma en que hablas, elegido aparte del de la app.
- Las tareas programadas tienen una pantalla propia: una hora, los días, el modelo de cada una, «ejecutar ahora».

### Devx Remote

- Controla devx en tu ordenador: cada chat, respuestas y razonamiento en vivo, tarjetas de aprobación, preguntas, el plan (con *Go and compact context*), modos plan/agent/YOLO, modelo y proveedor, contexto y coste, `/compact` y otros comandos. Los archivos y fotos van en ambos sentidos.
- Funciona con la terminal cerrada. El teléfono llega al ordenador directamente (misma Wi-Fi, una VPN como Tailscale, un puerto reenviado) o por un relé público gratuito. Todo está cifrado de extremo a extremo.
- Inicia una sesión nueva en el ordenador en una carpeta que elijas; añade un ordenador por dirección (VPN, puerto reenviado) y no solo por código QR.

En el ordenador:

```
devx remote start     # el host, sigue funcionando sin terminal
devx remote pair      # un código QR: escanéalo en la app, confirma los seis dígitos en el ordenador
```

Un teléfono se quita con `/phones` en devx o `devx remote revoke <name>`. Más en el [README de devx](https://github.com/apvcode/Termux-Dev#readme).

## Idiomas

English, Русский, Deutsch, Español, Français, 日本語, Português, Українська, 中文: los mismos nueve que devx. **Ajustes → Idioma**. El idioma también define lo que escucha el micrófono.

## Privacidad

- Chats, memoria, habilidades y claves están en el teléfono. Nada va a nosotros; no hay servidor nuestro.
- Tus mensajes van al proveedor que elegiste, y a un servidor MCP o a un servicio de mapas solo cuando una herramienta lo necesita.
- Galería, notificaciones, ubicación, contactos y la carpeta se leen solo cuando lo pides, y cada uno se puede desactivar.
- Las llamadas, mensajes y eventos del calendario nunca se envían sin tu último toque.

## Compilar

```
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

JDK 17 y el SDK de Android. Firma: pon `signing.properties` (storeFile, storePassword, keyAlias, keyPassword) en `~/.config/devx-android/`, o apunta a él con `DEVX_SIGNING_PROPERTIES`, o usa `keystore.properties` en la carpeta del proyecto. Sin ninguno de ellos la compilación se firma con la clave de depuración y no puede actualizar una versión instalada. Guarda bien la clave: es lo que permite que las actualizaciones se instalen sobre la versión anterior.

La app tiene sus propias versiones. Un solo comando la compila, comprueba la clave de firma y añade `devx-android-X.Y.Z.apk` a la página `android-app` de Termux-Dev (antes pregunta; un archivo que ya está allí nunca se sustituye; `--dry-run` muestra lo que haría):

```
node scripts/release-android.mjs      # ejecutar en la carpeta devx
```

## Tecnología

Kotlin, Jetpack Compose, OkHttp, Android Keystore. Los iconos son [Tabler Icons](https://tabler.io/icons) (MIT); Mermaid está incluido para diagramas (MIT). Los avisos están en [NOTICE](NOTICE).

## Licencia

La app todavía no tiene un archivo de licencia propio; devx en sí es MIT. Los avisos de terceros (Tabler Icons, Mermaid) están en [NOTICE](NOTICE), las licencias de las fuentes tipográficas en [licenses/](licenses).
