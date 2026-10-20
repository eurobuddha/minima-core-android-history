# Graph Report - apks/history  (2026-09-04)

## Corpus Check
- 18 files · ~13,230 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 212 nodes · 441 edges · 17 communities (13 shown, 4 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 43 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `cbd6eeb8`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MainActivity
- utxoWallet — Native Android clone: figma-style mapping & build blueprint
- HistoryDb
- HistoryEntry
- NodeApi
- TokenMeta
- Minima History (native Android)
- gradlew
- HistoryDesign
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.
- install.sh
- pre-commit
- Ownership
- .showDetail

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 50 edges
2. `HistoryEntry` - 24 edges
3. `Ownership` - 23 edges
4. `HistoryDb` - 17 edges
5. `NodeApi` - 14 edges
6. `HistorySync` - 12 edges
7. `utxoWallet — Native Android clone: figma-style mapping & build blueprint` - 10 edges
8. `TxAdapter` - 9 edges
9. `Kind` - 9 edges
10. `Util` - 9 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `HistoryDb`  [EXTRACTED]
  apks/history/app/src/main/java/com/eurobuddha/history/MainActivity.java → apks/history/app/src/main/java/com/eurobuddha/history/HistoryDb.java
- `TxAdapter` --references--> `HistoryEntry`  [EXTRACTED]
  apks/history/app/src/main/java/com/eurobuddha/history/MainActivity.java → apks/history/app/src/main/java/com/eurobuddha/history/HistoryEntry.java
- `HistorySync` --references--> `MainActivity`  [EXTRACTED]
  apks/history/app/src/main/java/com/eurobuddha/history/HistorySync.java → apks/history/app/src/main/java/com/eurobuddha/history/MainActivity.java
- `MainActivity` --references--> `Listener`  [EXTRACTED]
  apks/history/app/src/main/java/com/eurobuddha/history/MainActivity.java → apks/history/app/src/main/java/com/eurobuddha/history/HistorySync.java
- `MainActivity` --references--> `NodeApi`  [EXTRACTED]
  apks/history/app/src/main/java/com/eurobuddha/history/MainActivity.java → apks/history/app/src/main/java/com/eurobuddha/history/NodeApi.java

## Import Cycles
- None detected.

## Communities (17 total, 4 thin omitted)

### Community 0 - "MainActivity"
Cohesion: 0.11
Nodes (14): Adapter, android.content.BroadcastReceiver, android.net.Uri, android.os.Bundle, android.os.Handler, android.view.View, android.widget.EditText, androidx.activity.result.ActivityResultLauncher (+6 more)

### Community 1 - "utxoWallet — Native Android clone: figma-style mapping & build blueprint"
Cohesion: 0.08
Nodes (24): 0. Design languages (runtime toggle), 1.1 ORIGINAL — light (`:root`, default), 1.2 ORIGINAL — dark (`:root[data-theme="dark"]`), 1.3 CURRENT (existing native dark), 1.4 Type & metrics (ORIGINAL), 1.5 Component note colors (`.field-note`, `.toast`, pills), 1. Design tokens, 2. Component catalog (ORIGINAL; CURRENT = Material equivalents) (+16 more)

### Community 2 - "HistoryDb"
Cohesion: 0.13
Nodes (7): android.content.Context, android.database.sqlite.SQLiteDatabase, android.database.sqlite.SQLiteOpenHelper, HistoryDb, Override, HistorySync, Listener

### Community 3 - "HistoryEntry"
Cohesion: 0.13
Nodes (5): HistoryEntry, JSONObject, Util, org.json.JSONArray, org.json.JSONObject

### Community 4 - "NodeApi"
Cohesion: 0.19
Nodes (5): Cb, NodeApi, PairingListener, MinimaAPI, org.minimarex.minimaapi.MinimaAPI

### Community 6 - "Minima History (native Android)"
Cohesion: 0.29
Nodes (6): Build, How it works, Layout, Minima History (native Android), Releases, Scope

### Community 8 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 13 - "User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly."
Cohesion: 0.50
Nodes (3): RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions., User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly., Versioning guardrail — every code change ships with a version bump

### Community 17 - "Ownership"
Cohesion: 0.13
Nodes (8): JSONArray, JSONArray, Kind, CONTRACT, EXTERNAL, OWNED, UNKNOWN, Ownership

### Community 18 - ".showDetail"
Cohesion: 0.24
Nodes (8): android.view.ViewGroup, android.widget.LinearLayout, android.widget.TextView, androidx.annotation.NonNull, VH, LinearLayout, TextView, ViewHolder

## Knowledge Gaps
- **32 isolated node(s):** `install.sh script`, `OWNED`, `CONTRACT`, `EXTERNAL`, `UNKNOWN` (+27 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `HistoryDb`, `HistoryEntry`, `NodeApi`, `Ownership`, `.showDetail`?**
  _High betweenness centrality (0.289) - this node is a cross-community bridge._
- **Why does `Ownership` connect `Ownership` to `MainActivity`, `HistoryEntry`?**
  _High betweenness centrality (0.102) - this node is a cross-community bridge._
- **Why does `HistoryEntry` connect `HistoryEntry` to `MainActivity`, `Ownership`, `HistoryDb`, `.showDetail`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **What connects `install.sh script`, `OWNED`, `CONTRACT` to the rest of the system?**
  _32 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.1092436974789916 - nodes in this community are weakly interconnected._
- **Should `utxoWallet — Native Android clone: figma-style mapping & build blueprint` be split into smaller, more focused modules?**
  _Cohesion score 0.08 - nodes in this community are weakly interconnected._
- **Should `HistoryDb` be split into smaller, more focused modules?**
  _Cohesion score 0.12535612535612536 - nodes in this community are weakly interconnected._