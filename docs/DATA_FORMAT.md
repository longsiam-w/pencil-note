# PENCIL Data Format

## Overview

PENCIL stores data in JSON files under the app's private directory:

```
/data/data/com.vibetools.pencil/files/pencil/
├── library.json              # Notebook tree hierarchy
├── clipboard.json            # Recent clipboard items
├── settings.json             # Ephemeral (merged from SharedPreferences on export)
├── notes/
│   ├── {uuid}.json          # Note content (blank or image-backed)
│   ├── {uuid}__page_0.json  # PDF page 0 annotations
│   ├── {uuid}__page_1.json  # PDF page 1 annotations
│   └── ...
└── attachments/
    ├── image_123.jpg         # User-inserted images
    ├── document_456.pdf      # Imported PDF backgrounds
    └── ...
```

## library.json

Defines the folder/note tree and metadata.

```json
{
  "nodes": [
    {
      "id": "root-folder-uuid",
      "type": "FOLDER",
      "name": "我的笔记",
      "parentId": null,
      "sourceUri": null,
      "noteType": "BLANK",
      "createdAt": 1704067200000
    },
    {
      "id": "note-uuid",
      "type": "NOTE",
      "name": "Chapter 1",
      "parentId": "root-folder-uuid",
      "sourceUri": "content://...",
      "noteType": "PDF",
      "createdAt": 1704067800000
    }
  ]
}
```

### Fields

- **id**: UUID string, unique across all nodes
- **type**: `"FOLDER"` or `"NOTE"`
- **name**: Display name (editable, may contain emoji)
- **parentId**: Parent folder UUID, `null` for root
- **sourceUri**: Android content:// URI (PDF/image background), `null` for blank notes
- **noteType**: `"BLANK"` | `"PDF"` | `"IMAGE"`
- **createdAt**: Unix timestamp (milliseconds)

### Special Nodes

- Root folder: `parentId == null`, always exists
- Drafts: name `"草稿纸"`, protected from rename/delete
- Merged: name `"合并页"`, protected folder for merged documents

## notes/{id}.json

Stores canvas content for a single note or PDF page.

```json
{
  "noteId": "note-uuid",
  "backgroundHidden": false,
  "strokes": [
    {
      "id": "stroke-uuid",
      "tool": "PEN",
      "color": -16777216,
      "width": 2.5,
      "alpha": 255,
      "points": [
        {"x": 120.5, "y": 340.2},
        {"x": 125.3, "y": 342.1}
      ]
    }
  ],
  "texts": [
    {
      "id": "text-uuid",
      "text": "Hello\nWorld",
      "x": 200.0,
      "y": 150.0,
      "fontSize": 18.0,
      "color": -16777216
    }
  ],
  "images": [
    {
      "id": "image-uuid",
      "path": "/data/.../attachments/img123.jpg",
      "x": 400.0,
      "y": 300.0,
      "width": 420.0,
      "height": 280.0
    }
  ],
  "links": [
    {
      "id": "link-uuid",
      "noteId": "note-uuid",
      "pageId": "page-1",
      "x": 500.0,
      "y": 200.0,
      "kind": "internal",
      "value": "Target Note Name",
      "label": "→ Jump"
    }
  ],
  "bookmarks": [
    {
      "id": "bookmark-uuid",
      "name": "Important Section",
      "noteId": "note-uuid",
      "pageId": "page-1",
      "x": 600.0,
      "y": 100.0
    }
  ],
  "summaries": [
    {
      "id": "summary-uuid",
      "noteId": "note-uuid",
      "chapterTitle": "Chapter 1",
      "concepts": "Key concepts...",
      "memory": "Items to memorize...",
      "methods": "Solution methods...",
      "mistakes": "Common errors...",
      "createdAt": 1704070000000,
      "x": 50.0,
      "y": 50.0
    }
  ]
}
```

### Coordinate System

- **Origin**: Top-left of infinite canvas
- **Units**: Display-independent pixels (not screen pixels)
- **Range**: Unbounded (typical working area: 0–5000 × 0–3000)
- **Zoom**: Affects rendering only, coordinates remain absolute

### Stroke Fields

- **tool**: `"PEN"` | `"PENCIL"` | `"HIGHLIGHTER"` | `"TEXT"` | `"ERASER"` | `"SAND_ERASER"` | `"CORRECTION_FLUID"`
- **color**: Android ARGB integer (e.g., -16777216 = opaque black)
- **width**: Base width in canvas units (scaled by pressure for PEN/PENCIL)
- **alpha**: 0–255 (typically 255 for solid, 110 for highlighter)
- **points**: Sampled touch points with distance threshold (~1.5 units)

### Link Kinds

- `"internal"`: Jump to note by name
- `"external"`: Open URL in browser
- `"number"`: Jump to bookmark by numeric ID
- `"merge_source"`: Metadata for merged page sync (not user-visible)

### Merge Source Format

Special link with `kind == "merge_source"` stores layout metadata:

```
label: "sourceLeft|sourceTop|mergedLeft|width|height"
value: "source-note-id" or "source-note-id__page_N"
```

Used by `MergePageService.sync()` to map coordinates bidirectionally.

## clipboard.json

```json
[
  {
    "id": "clip-uuid",
    "createdAt": 1704071000000,
    "strokes": [...],
    "texts": [...],
    "images": [...],
    "links": [...],
    "summaries": [...]
  }
]
```

- Capped at 50 items (FIFO eviction)
- Same structure as note content
- Images reference absolute paths (cleaned up on app pause)

## settings.json (Export Only)

Generated during ZIP export from `SharedPreferences`:

```json
{
  "api_url": "https://api.openai.com/v1/chat/completions",
  "api_key": "sk-..."
}
```

Not persisted to file normally; merged on import.

## Backup ZIP Structure

```
backup.zip
├── library.json
├── clipboard.json
├── settings.json (optional)
├── notes/
│   ├── {uuid}.json
│   └── ...
└── attachments/
    ├── img123.jpg
    └── ...
```

### Import Safety

- Path validation: reject `../` or absolute paths outside `notes/` and `attachments/`
- URI migration: rewrite old `sourceUri` to point to restored attachments
- Orphan cleanup: delete attachments not referenced by any note or clipboard item

## Versioning

Current format: **v1.0** (implicit, no version field)

Future breaking changes:
- Add `"version": 2` to `library.json`
- Implement migration in `LibraryStore.decodeLibrary()`
- Maintain backward read compatibility for 1 major version

## Schema Evolution Examples

### Add New Object Type

1. Add field to `NoteDocument`: `val shapes: MutableList<CanvasShape>`
2. Update encoder: `root.put("shapes", JSONArray().also { ... })`
3. Update decoder: `root.optJSONArray("shapes")?.let { ... } ?: mutableListOf()`
4. Default to empty list for old files (no migration needed)

### Change Coordinate Precision

Bad: Store floats as ints (loses sub-pixel data)

Good: Keep floats, clamp on render if hardware limits exist

### Rename Field (Breaking)

1. Bump format version to 2
2. Write both old and new fields during transition
3. Read new field, fall back to old if missing
4. Drop old field after 1 year

## Performance Notes

- Typical note size: 10–500 KB (1000 strokes + 50 texts)
- Large notes: 2–5 MB (10,000+ strokes, complex drawings)
- PDF multi-page: 50 pages × 200 KB = 10 MB total
- Parse time: < 50ms for typical note on mid-range device
- Serialize time: < 30ms (no pretty-print)

## Tools

### Validate JSON

```bash
cat notes/some-uuid.json | jq .
```

### Extract Strokes

```bash
cat notes/some-uuid.json | jq '.strokes[] | select(.tool == "PEN")'
```

### Count Objects

```bash
cat notes/*.json | jq '[.strokes, .texts, .images] | map(length) | add' | paste -sd+ | bc
```

### Find Orphaned Attachments

```bash
# List all referenced paths
cat notes/*.json clipboard.json | jq -r '.images[]?.path' | sort -u > /tmp/used.txt
# Compare with actual files
ls attachments/ | grep -Fxv -f /tmp/used.txt
```
