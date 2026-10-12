# Riego Solar (app Android)

App para Android que maneja tu Galcon GSI según el sol que mide tu estación Omixom.
Funciona sola en segundo plano desde el celular: no hace falta PC ni servidor.

## Qué hace

Revisa cada 15 minutos:

| Momento | Qué hace |
|---|---|
| Antes de las **08:30** | Pausado. |
| Desde las **08:30** | Apenas hay sol suficiente (450 W/m² dos lecturas seguidas), despausa y riega. Te avisa con una notificación. |
| Si se nubla | Sigue como estaba. |
| Desde las **12:30**, si pasaron **90 min sin sol** | Te manda una notificación con dos botones: **Pausar** / **Seguir con red**. Si no contestás en 30 min, pausa (se puede cambiar). |
| Vuelve el sol | Se olvida tu elección y vuelve a regar normal. |
| **18:30** (invierno) / **20:30** (verano: octubre a marzo) | Pausa hasta el otro día a las 08:30. |
| Llovió 5 mm o más en 24 h | Pausa y avisa. |

En la app también tenés botones para **Regar con red**, **Pausar** a mano y volver a **Automático**.
Todos los horarios y números se cambian en la pantalla de la app.

Para pausar, la app usa el botón **"Pausa el cabezal de riego"** de GSI (pausa todo el
cabezal, todos los programas). Para reanudar, **"Libera el cabezal de riego"**.
Ojo: esa pausa no vence sola. Si el celular se queda sin batería o sin internet con el
riego pausado, queda pausado hasta que la app vuelva o lo liberes a mano (desde GSI o
desde la versión web).

## Versión web

En la carpeta `docs` está la página web (se publica con GitHub Pages). Entrás con tu
usuario de GSI y ves si el cabezal está pausado o regando, el estado de cada programa,
y podés pausar o liberar el riego desde la PC o el celular.

## Cómo obtener el APK

El proyecto se compila gratis en GitHub:

1. Creá una cuenta en github.com (si no tenés).
2. Arriba a la derecha: **+ → New repository**. Nombre: `RiegoSolar`, público (GitHub Pages gratis lo necesita; el código no tiene claves). **Create repository**.
3. Tocá **uploading an existing file** y arrastrá **todo el contenido** de esta carpeta
   (incluida la carpeta `.github`). **Commit changes**.
4. Andá a la pestaña **Actions**: se compila solo (tarda unos 5 minutos).
5. Entrá a la compilación terminada (tilde verde) y abajo, en **Artifacts**, bajá
   **RiegoSolar-apk**. Es un .zip con el `app-debug.apk` adentro.

## Instalar en el celular

1. Pasá el `app-debug.apk` al celular y abrilo.
2. Android va a pedir permiso para "instalar apps desconocidas": aceptalo para esa vez.
3. Abrí **Riego Solar**, aceptá las notificaciones y completá:
   - Token y número de estación de Omixom.
   - Usuario y clave de GSI (el proyecto, el equipo y el número de serie los detecta sola).
4. Tocá **Guardar** y después **Probar conexión**: tiene que mostrar ✔ en Omixom y en GSI,
   y qué sensor tomó como radiación y cuál como lluvia.
5. Tocá **Permitir que funcione siempre (batería)** y aceptá. Si no, Android puede frenar
   la app en segundo plano.
   En Xiaomi, Samsung y Motorola conviene además: Ajustes → Apps → Riego Solar →
   Batería → **Sin restricciones** (y en Xiaomi activar **Inicio automático**).

## Avisos

- Galcon no tiene una API oficial: la app usa las mismas llamadas que la página de GSI.
  Si Galcon cambia su sitio, puede dejar de funcionar.
- La clave de GSI queda guardada solo en tu celular.
- Android revisa cada 15 minutos como mínimo, y a veces con algunos minutos de demora.
