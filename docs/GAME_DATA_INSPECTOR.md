# Game Data Inspector (experimental)

This fork adds opt-in instrumentation for preserving resources and graphics operations
from old J2ME games, including games with obfuscated class and field names.

**Scope:** capture evidence, not automatic reconstruction of undocumented game
animation formats. The inspector does NOT yet enumerate all animations or levels
in Zombie Infection. A game can keep some tables on disk until a level is loaded;
the inspector cannot see data that has never been loaded or decoded.

## Build and run (Windows)

1. Clone/download branch
   [feature/game-data-inspector](https://github.com/neural-russia/KEmulator/tree/feature/game-data-inspector).
   Do not extract the sources into an existing KEmulator installation.
2. Open the repository root in IntelliJ IDEA (project has committed .idea
   modules and artifacts). Configure JDK 8 or 17 and install/enable Ant plugin.
3. Build → Build Project; then Build → Build Artifacts → KEmulator_x64 → Build.
   The artifact is configured to write to home/KEmulator_x64.jar.
   Supporting artifacts (m3g_lwjgl, micro3d_gl) may need rebuilding.
4. Run the newly built emulator; do not launch the previously installed
   nnmod executable. Open Tools → Memory View.
5. Use Game trace to start recording images and commands.
   Use Dump game heap to export arrays and reachable game objects.
   Turn Game trace off to flush/close files.

By default output goes to game-inspector-output/ relative to the emulator
working directory, which can differ from the repository root in an IDE.
The output directory is printed on startup in the Java console.

To capture first-screen resources, enable before starting the game, since
opening MemoryView after loading a level misses earlier load events.
Set environment variables in the parent emulator process:

~~~powershell
$env:KEM_INSPECTOR = "1"
$env:KEM_INSPECT_DIR = "C:\KEmuCaptures\ZombieInfection"
Set-Location "C:\path\to\KEmulator\home"
java -jar .\KEmulator_x64.jar
~~~

These environment variables are inherited by a child JVM.
You can also use -Dkem.inspector=true in the actual game JVM,
but the environment variable is easier with KEmulator's child process.

## Recorded files

| File | Meaning |
|---|---|
| resources.jsonl, resources/*.bin | Raw JAR resource bytes, SHA-256, caller |
| images.jsonl, images/*.png | Image IDs, dimensions and bitmap snapshots |
| draws.jsonl | drawImage, drawRegion and drawRGB metadata |
| frames.jsonl | Frame boundaries from Canvas and GameCanvas |
| state.jsonl | Changed static scalar fields of selected classes |
| methods.jsonl | Optional game method entry trace |
| heap/heap_*.jsonl | Reachable arrays and game object fields |
| snapshots.jsonl | Heap dump metadata |

Each JSONL line is an independent JSON object. imageId is a session-local
integer; file points to a PNG in images/.

### Options

Environment variables are read before the emulator/game starts:

- KEM_INSPECTOR=1: capture resources from startup.
- KEM_INSPECT_DIR=C:\captures\test1: output folder.
- KEM_INSPECT_MUTABLE=1: checksum and save new bitmap versions of mutable
  images on draw. Can significantly reduce performance.
- KEM_INSPECT_CALLSITES=1: record Java stack caller for every draw event;
  slow with many draw calls.
- KEM_INSPECT_METHODS=1: insert ASM method entry probes as classes load.
  Needs a FULL game restart and generates many events in methods.jsonl.
- KEM_INSPECT_STATE_CLASSES=g,f: static scalar classes to sample.
- KEM_INSPECT_STATE_EVERY=30: sampling interval in presented frames.
- KEM_INSPECT_MAX_ARRAY=65536: maximum per-array elements in heap export.
- KEM_INSPECT_MAX_RGB_PIXELS=500000: maximum drawRGB bitmap pixels.

Begin with only KEM_INSPECTOR=1; enable expensive probes for targeted debugging.

### Inspect arrays and private formats

1. Capture from launch, enter the level of interest.
2. Open MemoryView and press Dump game heap.
3. Search heap/*.jsonl for paths such as g.a[4], short[], byte[], arrays of refs.
4. Correlate resources.jsonl caller data with graphics or method traces.
5. Use CFR/Vineflower/ASM to understand the methods producing these arrays.

A debugger cannot identify an arbitrary short[] as an animation rather
than a tilemap, audio table, or collision structure without more analysis.

### Replay draw calls to PNGs

Install Python 3 and Pillow:

~~~powershell
py -m pip install pillow
py tools\inspector\replay_draws.py C:\KEmuCaptures\ZombieInfection --list-targets
py tools\inspector\replay_draws.py C:\KEmuCaptures\ZombieInfection --target native@123456 --size 240x320 --out output-frames
~~~

Replace native@123456 with one of the printed targets.
The previewer writes numbered PNG frames, approximating MIDP crop/transform,
anchor, translation, clipping and image compositing.

Caveats: It does NOT replay fillRect/lines or offscreen dependencies,
and some platform-specific transformations may differ. drawRGB source pixels
are recorded and replayed where the bitmap is within the configured size cap. It is a
diagnostic preview, not an exact game renderer. It also does not enumerate
animations never requested by the running game.

## Files

- src/main/emulator/debug/GameDataInspector.java: main capture engine.
- src/main/emulator/custom/ResourceManager.java: binary resource hook.
- src/midp/javax/microedition/lcdui/Image.java: allocation registration.
- src/midp/javax/microedition/lcdui/Graphics.java: draw hooks.
- src/midp/javax/microedition/lcdui/Canvas.java: paint boundaries.
- src/midp/javax/microedition/lcdui/game/GameCanvas.java: flush boundaries.
- src/main/emulator/custom/CustomMethodAdapter.java: ASM method probes.
- src/main/emulator/ui/swt/MemoryViewControls.java: UI controls.
- tools/inspector/replay_draws.py: offline visualization.

## Validation and limitations

This is an experimental source patch, not a pre-built release.
.github/workflows/game-inspector-build.yml is intended for compiler checks;
a Windows IntelliJ artifact build and actual Zombie Infection run must still
be verified. Heavy tracing and heap snapshots can pause or slow the game.

The capture writes loaded asset bytes to your local disk; treat them as
copies of copyrighted game resources, and only use material you have rights
to inspect and reproduce.

Next phase for automatic animation enumeration: identify the game's resource
decoder and infer a module → frame → animation schema. Only after that can
an enumerator produce all defined animations without playing each sequence.
