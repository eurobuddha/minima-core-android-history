# Graph Report - history  (2026-08-05)

## Corpus Check
- 15 files · ~11,330 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 149 nodes · 296 edges · 13 communities (9 shown, 4 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 11 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `88ced239`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MainActivity
- LinearLayout
- HistoryDb
- HistoryEntry
- NodeApi
- TokenMeta
- Handler
- Override
- gradlew
- HistoryDesign

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 46 edges
2. `HistoryEntry` - 18 edges
3. `HistoryDb` - 13 edges
4. `HistorySync` - 11 edges
5. `NodeApi` - 11 edges
6. `TxAdapter` - 9 edges
7. `Util` - 9 edges
8. `VH` - 6 edges
9. `Cb` - 5 edges
10. `Listener` - 4 edges

## Surprising Connections (you probably didn't know these)
- `HistorySync` --references--> `MainActivity`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/history/HistorySync.java → app/src/main/java/com/eurobuddha/history/MainActivity.java
- `HistorySync` --references--> `HistoryDb`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/history/HistorySync.java → app/src/main/java/com/eurobuddha/history/HistoryDb.java

## Import Cycles
- None detected.

## Communities (13 total, 4 thin omitted)

### Community 0 - "MainActivity"
Cohesion: 0.13
Nodes (13): ActivityResultLauncher, MainActivity, AppCompatActivity, BroadcastReceiver, Bundle, EditText, HistoryDb, HistorySync (+5 more)

### Community 1 - "LinearLayout"
Cohesion: 0.18
Nodes (10): Adapter, TxAdapter, VH, HistoryEntry, LinearLayout, NonNull, Override, TextView (+2 more)

### Community 2 - "HistoryDb"
Cohesion: 0.11
Nodes (8): HistoryDb, Context, Override, HistorySync, Listener, Handler, SQLiteDatabase, SQLiteOpenHelper

### Community 3 - "HistoryEntry"
Cohesion: 0.14
Nodes (5): HistoryEntry, JSONObject, JSONObject, Util, JSONArray

### Community 4 - "NodeApi"
Cohesion: 0.22
Nodes (7): Cb, Context, Handler, JSONObject, NodeApi, PairingListener, MinimaAPI

### Community 8 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `LinearLayout`, `HistoryDb`?**
  _High betweenness centrality (0.419) - this node is a cross-community bridge._
- **Why does `HistorySync` connect `HistoryDb` to `MainActivity`?**
  _High betweenness centrality (0.238) - this node is a cross-community bridge._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.12903225806451613 - nodes in this community are weakly interconnected._
- **Should `HistoryDb` be split into smaller, more focused modules?**
  _Cohesion score 0.10574712643678161 - nodes in this community are weakly interconnected._
- **Should `HistoryEntry` be split into smaller, more focused modules?**
  _Cohesion score 0.14285714285714285 - nodes in this community are weakly interconnected._