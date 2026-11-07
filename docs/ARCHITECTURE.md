# PENCIL Architecture

## Package Structure

```
com.vibetools.pencil/
├── ai/           # AI client (OpenAI-compatible HTTP)
├── canvas/       # OpenGL rendering & touch handling
├── export/       # Summary Markdown export
├── merge/        # Multi-page merge & sync
├── model/        # Data models
├── print/        # Android print service coordination
├── storage/      # JSON persistence (Library, Note, Clipboard)
├── ui/           # Activities and controllers
└── util/         # Document rasterization
```

## Core Components

### Canvas Layer (`canvas/`)

- **CanvasGlView**: OpenGL ES 2.0 surface with viewport-culled stroke rendering
  - Manages undo/redo history including external objects (images, links, summaries)
  - Delegates selection, dragging, and paste operations
  - Caches stroke FloatBuffers and grid geometry
- **NoteOverlayView**: Draws text, links, images, and summary blocks on top of canvas
  - Uses Bitmap cache for inserted images
  - Releases resources on detach

### Storage Layer (`storage/`)

- **LibraryStore**: Hierarchical notebook tree (folders/notes) + JSON serialization
  - `library.json`: Node hierarchy with metadata
  - `notes/{noteId}.json`: Per-note strokes, texts, images, links, summaries
  - `notes/{noteId}__page_{N}.json`: PDF pages
  - `attachments/`: User-uploaded images and PDF files
  - Garbage collection for orphaned attachments
- **ClipboardStore**: Recent clipboard items with images/links/summaries
  - Capped at 50 items
  - Participates in attachment lifecycle

### Business Services

- **MergePageService** (`merge/`): Multi-page horizontal layout with bidirectional sync
- **PrintCoordinator** (`print/`): Page range parsing, background loading, async rasterization
- **SummaryExporter** (`export/`): Markdown generation from all notes

### UI Layer (`ui/`)

- **MainActivity**: Canvas hosting + mode switching + drawer management
  - Delegates merge/print/export to services
  - Manages document lifecycle (open, save, switch)
- **DirectoryController**: Tree view + bookmark/summary actions
- **SettingsActivity**: API URL/key configuration

## Data Flow

### Writing

1. User touch → `CanvasGlView` captures point stream
2. Distance sampling + midpoint smoothing
3. Pressure scaling for pen/pencil width
4. Commit stroke to GPU-backed cache
5. Record history snapshot (strokes + texts + extras)
6. On pause/switch: serialize to `notes/{id}.json`

### Background Rendering

1. PDF/Image URI → ContentResolver stream
2. Decode to Bitmap (capped size)
3. Upload to OpenGL texture (freed on page switch)
4. Transform with world coordinates (pan/zoom)

### Selection & Clipboard

1. Rectangle or freeform polygon captured
2. Canvas filters strokes/texts; Activity filters images/links/summaries
3. `CanvasClip` with all objects → ClipboardStore
4. Paste: compute bounding box, center in viewport, enter drag mode

### Undo/Redo

`HistoryState` captures:
- Stroke list (immutable copy)
- TextBox list
- Background visibility
- `DocumentExtras` (images, links, summaries, bookmarks)

Restore = overwrite all collections + refresh overlay.

## Extension Points

### Add New Canvas Object Type

1. Define model in `model/LibraryModels.kt` (e.g., `CanvasShape`)
2. Add to `NoteDocument.shapes` and `CanvasClip.shapes`
3. Update JSON encode/decode in `LibraryStore` and `ClipboardStore`
4. Extend `DocumentExtras` in `model/`
5. Render in `NoteOverlayView.onDraw()`
6. Hit-test and manipulate in `MainActivity` (select/move/delete)
7. Include in `DocumentRasterizer.render()` for print/export

### Add AI Provider

1. Implement protocol adapter in `ai/` (e.g., `GeminiClient`)
2. Parse response format (JSON vs. SSE)
3. Switch in `MainActivity.sendAi()` based on settings

### Add Export Format

1. Create `export/PdfExporter` or `export/NotionExporter`
2. Reuse `DocumentRasterizer.render()` for visual output
3. Call from new menu action in `MainActivity`

### Add Storage Backend

1. Create `storage/RoomStore` implementing same API as `LibraryStore`
2. Migrate JSON on first launch
3. Replace `LibraryStore` instance in `MainActivity`

## Performance Notes

- Stroke rendering: viewport culling + cached FloatBuffer (rebuild only on add/delete)
- Grid: rebuild only on pan across cell boundary or zoom change
- OpenGL texture: single background bitmap per page, freed on switch
- History: capped at 50 states, shallow-copy stroke/text lists
- PDF rendering: async on IO dispatcher, recycle Bitmap after upload

## Testing Strategy

- **Unit**: `MergePageService.sync()` coordinate math
- **Integration**: `LibraryStore` JSON round-trip
- **UI**: Espresso for mode switching, selection confirm/cancel
- **Manual**: Stylus pressure, PDF multi-page, backup/restore ZIP
