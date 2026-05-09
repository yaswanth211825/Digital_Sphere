# DigitalSphere — Diagnostic Data Capture

## How logs are stored

Every diagnostic event is written to a CSV file **on the device itself**, no adb required.

- **Location:** `<app internal storage>/diag_logs/`
- **Filename:** `diag_<YYYYMMDD_HHmmss>_<ModelName>.csv` — one file per app launch
- **Format:** `timestamp, model, android_version, sdk_int, event, detail, value, result`

Files persist until the app is uninstalled. They survive restarts, crashes, and disconnections.

---

## Getting logs off the phone (no adb needed)

### From the Professor phone
**Long-press the "Export" button** → Android share sheet opens → send via Gmail / WhatsApp / Google Drive / etc.

### From the Student phone
**Long-press the "Scan for Class" button** → Android share sheet opens → same options.

### With adb (optional, for faster bulk collection)
```bash
adb -s <SERIAL> pull /data/data/com.example.digitalsphere/files/diag_logs/ ./logs/
```

---

## Steps for each test run

1. Open DigitalSphere on both phones — device capability events fire immediately at launch
2. Run a full session: professor starts → student scans → wait for result
3. After the session, long-press the export/scan button to share the CSV
4. Name the file with phone model + Android version before saving
   - e.g. `Samsung_A54_Android14_20250405.csv`, `Redmi_Note12_MIUI14_20250405.csv`

---

## Log format

```
HH:mm:ss.SSS | MANUFACTURER_MODEL | EVENT | detail | value | result
```

Example:
```
10:23:45.123 | Xiaomi_Redmi Note 12 | AUDIO_HASH_STORED | hash=null | | NULL_STORED
10:23:45.891 | Samsung_Galaxy A54   | AUDIORECORD_STATE | | | INITIALIZED
```

---

## What each event means

### Device capability (fires once at app launch)

| Event | What it tells you |
|-------|------------------|
| `DEVICE_INFO` | Phone model, Android version, SDK level |
| `BLE_ADVERTISE_SUPPORT` | Can this phone act as BLE peripheral? NOT_SUPPORTED = student beacon will fail |
| `BLE_SCAN_SUPPORT` | Is Bluetooth adapter available? |
| `MIC_PERMISSION` | Is RECORD_AUDIO granted? DENIED = ultrasound + audio always skipped |
| `UNPROCESSED_AUDIO_SUPPORT` | SDK >= 24? NOT_SUPPORTED = forced to use processed MIC source |
| `BAROMETER_PRESENT` | Does the phone have a pressure sensor? |
| `AUDIO_BUFFER_SIZE` | AudioRecord min buffer. BROKEN = mic pipeline broken at OS level |
| `AUDIORECORD_STATE` | Can AudioRecord actually initialize? EXCEPTION = OEM blocks mic |

### Audio hash flow (BUG 1 — "professor audio reference not received")

Trace these events in order across **both** logs:

```
[PROFESSOR] AUDIO_HASH_RECORDED   → professor hash computed
[PROFESSOR] AUDIO_HASH_PACKED     → hash written into BLE payload
[PROFESSOR] AUDIO_HASH_IN_PAYLOAD → BLE advertising starts with hash

[STUDENT]   AUDIO_HASH_SCAN_RECEIVED → student BLE scanner got manufacturer data
[STUDENT]   AUDIO_HASH_UNPACKED      → hash extracted from BLE bytes
[STUDENT]   AUDIO_HASH_DELIVERED     → hash passed to StudentPresenter
[STUDENT]   AUDIO_HASH_STORED        → hash saved as professorAmbientHash
[STUDENT]   AUDIO_HASH_USED          → continueWithAudio() checks hash
[STUDENT]   AUDIO_RECORD_START       → student starts recording ambient audio
[STUDENT]   AUDIO_RECORD_DONE        → student hash computed
```

**Diagnosis:**
- `AUDIO_HASH_PACKED` → `ALL_ZERO` = professor mic didn't capture anything
- `AUDIO_HASH_IN_PAYLOAD` → `NO_HASH` = hash was null when advertising started (race condition — professor tapped Start too fast)
- `AUDIO_HASH_SCAN_RECEIVED` → `NULL` = BLE manufacturer data not present in packet
- `AUDIO_HASH_UNPACKED` → `NULL` = unpackAmbientHash() returned null (all-zero bytes or short payload)
- `AUDIO_HASH_STORED` → `NULL_STORED` = hash never arrived; student will always skip audio check
- `AUDIO_HASH_USED` → `NULL_SKIPPING` = professor audio skipped; audio layer contributes 0 to DSVF score

### Timing events (BUG 2 — slow or hanging)

```
SCAN_STARTED          t=0
FIRST_BEACON_SEEN     ms=xxx   ← time to find professor
IN_RANGE_TRIGGERED    ms=xxx   ← time to cross -75dBm threshold
VERIFY_FLOW_START     ms_since_inrange=xxx
BARO_STEP_START
BARO_STEP_DONE        durationMs=xxx
ULTRA_STEP_START
ULTRA_STEP_DONE       durationMs=xxx  ← should be < 4000ms; if ~4000 = timeout
AUDIO_STEP_START
AUDIO_STEP_DONE       durationMs=xxx  ← should be ~2000ms
DSVF_START
DSVF_DONE             durationMs=xxx  score=0.xx  PRESENT/REJECTED_ROOM/...
TOTAL_VERIFY_TIME     ms=xxx
ATTENDANCE_MARKED     ms=xxx (total from scan start)
```

**Diagnosis:**
- `ULTRA_STEP_DONE durationMs ~ 4000` = ultrasound timed out, not actually detected
- `TOTAL_VERIFY_TIME ms > 8000` = pipeline is hanging, check which step has the largest gap
- `ATTENDANCE_MARKED` never appears = DSVF rejected (check `DSVF_DONE result`)

### Error events (BUG 3 — silent failures)

| Event | Meaning |
|-------|---------|
| `BLE_ADVERTISE_ERROR` | Student beacon couldn't start — professor won't see this student |
| `BLE_SCAN_ERROR` | Student can't scan — will never find professor |
| `ULTRASOUND_EMIT_FAILED` | Professor ultrasound not emitting — students will skip room check |
| `AMBIENT_RECORD_FAILED` | Audio recording failed (mic busy, permission revoked mid-session) |

---

## For the research paper

Collect logs from **at least** these device categories:
- Budget Android (MediaTek MT6xxx) — tests BLE advertising support
- Mid-range Xiaomi (MIUI 12+) — tests AudioSource.UNPROCESSED fallback
- Samsung Galaxy A/S series (OneUI 6+) — tests OneUI audio processing
- iPhone (via TestFlight if iOS version exists) — baseline high-frequency comparison
- High-end Pixel / OnePlus — expected full-feature baseline

For each device record: `DEVICE_INFO`, `BLE_ADVERTISE_SUPPORT`, `AUDIORECORD_STATE`, `BAROMETER_PRESENT`, `TOTAL_VERIFY_TIME`, `DSVF_DONE` result.

---

## Stop capturing
`Ctrl+C` in each terminal. The `.txt` files are ready to share or import into a spreadsheet.
