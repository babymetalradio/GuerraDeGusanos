# Guerra de Gusanos — App Android nativa (Kotlin)

Réplica nativa en Kotlin del juego web "Guerra de Gusanos" (Worms-like por
turnos). Sin WebView, sin motor externo, sin anuncios, sin permisos raros
(solo `VIBRATE`) y todo offline. El juego corre en un `Canvas` propio a
60 ticks/s en un espacio lógico de 1280×640 que la vista escala a pantalla.

- **Package:** `com.guerradegusanos.app` (se corrigió la ortografía del nombre
  propuesto originalmente, `com.gueerradegusanos.app`).
- **App:** Guerra de Gusanos, versionName `1.0`, versionCode `1`.
- **SDK:** minSdk 24, targetSdk 35, compileSdk 35. Con targetSdk 35 el juego
  sí aparece en Play Store en Android nuevos (las apps viejas con target bajo
  se ocultan en dispositivos nuevos, como le pasó a los Worms de Play Store).
- **Build:** AGP 8.5.2, Kotlin 2.0.21, Gradle 8.9, Java 17.

## Cómo se compila

No hay Android SDK en la máquina de desarrollo: el APK se compila en
GitHub Actions con `.github/workflows/build-apk.yml` (modelado en el
workflow "Compilar APK" de DatGarScanApp): push a `main` o
`workflow_dispatch` → `./gradlew assembleDebug` → sube el APK como artifact
`guerradegusanos-debug-apk`. No necesita secretos: el build debug usa la
llave de depuración por defecto.

## Estructura

```
app/src/main/
├── AndroidManifest.xml            # MainActivity launcher, sensorLandscape, VIBRATE
├── java/com/guerradegusanos/app/
│   ├── MainActivity.kt            # Menú + pantalla de juego, HUD, controles, diálogos
│   ├── ui/GameView.kt             # Custom View: bucle 60 Hz, escalado, arrastre p/ apuntar
│   └── game/
│       ├── GameConfig.kt          # GameMode, Difficulty, Team, Weapon, SoundEvent, GameState
│       ├── Worm.kt                # Gusano (equipo, nombre, posición, HP, física)
│       ├── Terrain.kt             # Heightmap destructible 1280 px
│       ├── GameEngine.kt          # Estados, turnos, física, armas, victoria, dibujo
│       ├── AiPlayer.kt            # IA por simulación de tiro
│       └── SoundManager.kt        # Sonidos sintetizados (AudioTrack, PCM generado)
└── res/
    ├── layout/activity_main.xml   # menuScreen + gameScreen
    ├── values/{strings,colors,themes}.xml
    ├── drawable/ic_launcher_{foreground,background}.xml
    └── mipmap-*/ic_launcher.png   # icono legacy (API 24/25) + adaptive icon en anydpi-v26
```

## Reglas del juego (réplica fiel de la versión web)

- Dos equipos, Rojo (Ramón, Lombriz, Tito, Chispa) vs Azul (Sargento, Bicho,
  Nube, Rex), 2/3/4 gusanos por equipo, 100 HP cada uno. Primer equipo
  aleatorio 50/50.
- Modos: contra la compu (Azul = CPU, dificultad Fácil/Normal/Difícil) o
  2 jugadores en el mismo teléfono.
- Turno: viento entero aleatorio −5..5, temporizador de 45 s que **solo**
  corre en estado de apuntado. Al terminar, el arma vuelve a bazooka.
- Armas sin munición: bazooka (radio 46 / daño 48), granada (52 / 62, rebota
  y mecha de 190 ticks ≈ 3.17 s), dinamita (62 / 74, mecha de 300 ticks y
  retirada de 6 s). Velocidad de salida `4.5 + potencia × 0.135`.
- Física: gravedad de proyectil 0.22/tick, de gusano 0.38/tick; viento afecta
  la bazooka ×0.0075 y la granada ×0.003; daño por explosión con caída lineal
  en radio+34 y empuje ×10.5; daño por caída si vy > 11.5; ahogamiento si
  y−4 > 624. Terreno destructible con cráteres; el `settle` espera 35 ticks
  estables antes de pasar el turno.
- Apuntado: arrastre del dedo con línea de trayectoria (46 pasos, puntos
  cada 3; la de granada se corta al primer contacto con el suelo, sin
  rebote), botones ▲▼, potencia cargando ¡FUEGO! con ping-pong 0→100 a
  1.7/tick. Si se apuntó arrastrando, FUEGO dispara de inmediato.
- IA: busca ángulo 12–168° (paso 3°) y potencia 12–100 (paso 4) simulando
  hasta 210 pasos; objetivo al enemigo con menos HP (35% aleatorio); 78%
  bazooka / 22% granada; error por dificultad (Fácil ±7/±13, Normal
  ±3.6/±7, Difícil ±1.6/±3.5). La CPU nunca se mueve, salta ni usa dinamita.
- Sonidos sintetizados (WebAudio en la web → AudioTrack PCM aquí): disparo,
  explosión, daño, splash, clic y salto, con las mismas frecuencias y
  duraciones. Vibración: disparo 25 ms, explosión [60,40,80], clic 10 ms.
- Victoria: gana el equipo que deja al otro en 0; empate si ambos llegan a
  0. Sin persistencia, puntuación, niveles ni red.

Rarezas preservadas deliberadamente (no "corregidas"): temporizador solo en
`aim`, preview de granada sin rebote, CPU inmóvil.

## Pendiente (fuera de esta fase, requiere aprobación de Manuel)

1. Crear el repo en GitHub (propuesto: **GuerraDeGusanos**, cuenta
   `babymetalradio`) e instalar la app de Muse en ese repo.
2. Hacer el push inicial (ruta del conector `github push-files`; el git CLI
   local no puede hacer push). Regla de Manuel: avisarle ANTES de subir
   cualquier archivo o hacer push.
3. Ver el run de "Compilar APK" en Actions y descargar el APK debug.
4. Instalar y probar en el teléfono (partida completa, modo 2 jugadores,
   las 3 dificultades de CPU, dinamita, ahogamiento y fin de partida).

## Verificación hecha sin compilar

- Los 9 XML parsean y todos los IDs/`@string`/`@color` referenciados existen.
- Balance de llaves/paréntesis/corchetes en 0 en los 8 archivos Kotlin.
- Se corrigió un doble sonido de salto (MainActivity + motor) y un posible
  riesgo de compilación (parámetro del constructor con el mismo nombre que
  la propiedad `config` en `GameEngine`).
- **No verificado:** la compilación real. El primer build de Actions es la
  prueba de fuego; si algo falla ahí, se corrige con el error del log.
