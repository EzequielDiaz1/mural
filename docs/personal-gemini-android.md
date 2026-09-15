# Mural personal para Android con Gemini

Esta rama agrega Gemini como proveedor opcional. OpenAI conserva su integración original.
No hace falta Android Studio para instalar: GitHub Actions genera el APK.

## Instalar

1. Abrir Actions → **Personal Android APK** y elegir la última ejecución exitosa de `feat/gemini-android`.
2. Descargar el artefacto **mural-gemini-android** (requiere iniciar sesión en GitHub).
3. Extraer el ZIP, transferir `app-debug.apk` al teléfono y abrirlo. Autorizar la instalación desde esa fuente.
4. Se requiere Android 8.0 o posterior, Internet y permiso de micrófono para voz.
5. Elegir los idiomas. En Ajustes elegir **Gemini**, abrir las opciones de API key y guardar una key propia
   de <https://aistudio.google.com/app/api-keys>.
6. Iniciar una conversación. También se puede practicar por escrito.

La API key se introduce únicamente en la app, nunca en GitHub, el código o los logs.
Cada proveedor tiene un almacén cifrado con Android Keystore independiente; ninguno se exporta en backups.
No hay cambio automático de proveedor al agotar una cuota.

## Alcance

- Voz bidireccional por WebSocket: entrada PCM16 mono a 16 kHz, salida a 24 kHz.
- Subtítulos de entrada y salida, silenciar, terminar y liberar audio al salir de la app.
- Texto, traducciones, consultas de palabras, evaluaciones estructuradas y búsqueda de temas mediante Gemini.
- Los pedidos auxiliares de una conversación conservan su proveedor original dentro de esta instalación.
- Modelo de voz: `gemini-3.8-live`. Modelo auxiliar: `gemini-2.5-flash`.
- Sin servidor adicional, cuentas administradas ni compras activadas.

## Límites de esta versión personal

- Hace falta probar micrófono, reproducción, cancelación de eco e interrupciones en el dispositivo real.
  Las pruebas automáticas no sustituyen esa prueba ni verifican el acceso de una key real a los modelos.
- La ruta de Gemini está pensada para el altavoz o auriculares con cable; Bluetooth no está validado.
- La voz no usa delegación de herramientas ni verifica noticias; la búsqueda de temas usa Google Search.
- Las cuotas gratuitas dependen del proyecto/modelo y pueden agotarse. Con facturación activa puede haber cargos.
  El consumo real se consulta en Google AI Studio; la app no calcula el precio de voz de Gemini.
- Google puede usar contenido de la cuota gratuita para mejorar sus productos. Aplican sus condiciones:
  <https://ai.google.dev/gemini-api/terms>.
- Los backups siguen el formato original. El proveedor se conserva localmente, no se añade al backup compatible con iPhone.
- El APK es de depuración para instalación personal. Cada runner limpio genera una firma de depuración distinta:
  exportar el progreso antes de reemplazar una instalación cuya firma difiera. No desinstalar sin backup.
  Para actualizaciones permanentes hace falta configurar una clave de firma privada estable.

## Prueba en el teléfono

1. Guardar la key de Gemini y verificar que una conversación escrita responde.
2. Iniciar voz, conceder micrófono, escuchar el saludo y contestar; comprobar ambos subtítulos.
3. Pedir ayuda, silenciar y reactivar; verificar que no se envía voz mientras está silenciado.
4. Terminar y volver a iniciar; salir de la app durante una conversación y comprobar que libera el micrófono.
5. Abrir una palabra, consultar su traducción y revisar el historial.

Protocolo y modelos contrastados con documentación oficial de Google:
<https://ai.google.dev/api/live>, <https://ai.google.dev/gemini-api/docs/live-api/capabilities>,
<https://ai.google.dev/gemini-api/docs/structured-output>.
