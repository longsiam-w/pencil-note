# Contributing to PENCIL

## Setup

1. Install Android Studio Hedgehog or later
2. JDK 17+ (configure in `File > Project Structure > SDK Location`)
3. Android SDK 26–36 (SDK Manager: Android 8.0 through Android 15)
4. Clone repository:
   ```bash
   git clone <repo-url>
   cd PENCIL
   ```
5. Sync Gradle and build:
   ```bash
   ./gradlew assembleDebug
   ```

## Code Style

- **Kotlin**: Use trailing commas, explicit return types for public APIs
- **Line length**: 120 characters soft limit
- **Naming**: 
  - Package: lowercase `com.vibetools.pencil.merge`
  - Class: PascalCase `MergePageService`
  - Function: camelCase `candidatesFor()`
  - Constant: SCREAMING_SNAKE `MIN_SECTION_WIDTH`
- **Structure**: Group by feature package, not layer (avoid giant `utils/`)

## Pull Request Checklist

- [ ] Compiles without warnings (`./gradlew assembleDebug`)
- [ ] Lint clean (`./gradlew lintDebug`)
- [ ] Manual test on physical device or emulator (API 26 + API 36)
- [ ] Update `docs/ARCHITECTURE.md` if adding new package or component
- [ ] Update `docs/DATA_FORMAT.md` if changing JSON schema
- [ ] Add brief summary to PR description

## Branch Naming

- `feature/short-description` (new capability)
- `fix/issue-description` (bug fix)
- `refactor/component-name` (code cleanup, no behavior change)
- `chatbox/*` (AI-assisted development sessions)

## Common Tasks

### Add New Tool (Pen/Highlighter Variant)

1. Add enum in `model/DataModels.kt`: `Tool.MARKER`
2. Implement rendering in `CanvasGlView.drawStroke()`
3. Add UI button in `res/layout/activity_main.xml`
4. Wire in `MainActivity.bindModes()`
5. Test: write, undo, save, reopen

### Add New Link Type

1. Extend `StoredLink.kind` values
2. Update hit detection in `NoteOverlayView`
3. Implement navigation in `MainActivity.handleLinkTap()`
4. Update `DocumentRasterizer` to render new type

### Debug OpenGL Rendering

1. Enable GPU debug in Android Studio (View > Tool Windows > Profiler > GPU)
2. Check `adb logcat | grep GLES`
3. Verify `CanvasGlView.onSurfaceCreated()` shader compilation
4. Use `GLES20.glGetError()` after critical calls

### Debug PDF Rendering

1. Check ContentResolver permissions: `adb logcat | grep PdfRenderer`
2. Verify page count matches file: `library.pdfPageCount(node)`
3. Test rotation and zoom at different scales
4. Check Bitmap.recycle() timing with heap profiler

## Testing

### Unit Tests (Future)

```bash
./gradlew test
```

Target: `MergePageService`, `PrintCoordinator.parseRange()`, JSON codecs

### Instrumented Tests (Future)

```bash
./gradlew connectedAndroidTest
```

Target: Canvas gestures, selection confirm/cancel, backup/restore

### Manual Regression

1. Write strokes with different tools
2. Undo/redo 20+ steps
3. Select, move, paste
4. Insert image, link, summary
5. Merge two pages, sync back
6. Print PDF page range
7. Export summaries
8. Backup and restore on different device

## Performance Profiling

1. Android Studio Profiler (CPU + Memory)
2. Systrace for frame drops: `python systrace.py gfx input view`
3. Check `CanvasGlView.onDrawFrame()` avg time < 16ms
4. Verify no allocation spikes in `NoteOverlayView.onDraw()`

## Documentation

- Architecture changes → `docs/ARCHITECTURE.md`
- Data format changes → `docs/DATA_FORMAT.md`
- Public API additions → KDoc comments
- Non-obvious logic → inline comments

## Release Checklist

- [ ] Version bump in `app/build.gradle`
- [ ] Update `README/quality_audit.md`
- [ ] Tag commit: `git tag v1.x.y`
- [ ] Generate signed APK
- [ ] Test on Android 8, 12, 15
- [ ] Backup compatibility test with previous version data
