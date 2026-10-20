# Privacy Delete

Android app that makes already-deleted files unrecoverable **without** a factory reset.
Current files are never touched.

1. **Empty trash**: permanently deletes photos/videos sitting in the system trash (Android 11+).
2. **Wipe free space**: fills free storage with junk files, then deletes them, overwriting
   leftovers of deleted files.

3. **Recovery check**: scans like a recovery app (system trash, recycle-bin files,
   thumbnail caches, hidden photos). Proof test: create test photo → scan (FOUND) →
   Step 1 + Step 2 → scan (GONE).

## Build
Open this folder in Android Studio → let Gradle sync → Run on a phone (USB debugging on).

## Logs
Logcat filter: `tag:WipeEngine | tag:RecoveryScan`
