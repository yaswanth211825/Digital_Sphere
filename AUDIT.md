# Codebase Audit Report
## DigitalSphere — vs SRS + Architecture

| Field        | Value         |
|--------------|---------------|
| Audit Date   | March 2026    |
| SRS Version  | 1.0           |
| Arch Version | 1.0           |

---

## 1. SRS Compliance — Functional Requirements

### 4.1 App Launch & Role Selection

| ID    | Requirement (summary)                        | Status  | Finding |
|-------|----------------------------------------------|---------|---------|
| FR-01 | Two role buttons on launch                   | ✅ PASS | `btn_professor` + `btn_student` in `activity_main.xml` |
| FR-02 | BLE hardware check before navigating         | ✅ PASS | `checkAndProceed()` checks `FEATURE_BLUETOOTH_LE` |
| FR-03 | Request runtime permissions                  | ✅ PASS | `requestNeededPermissions()` in `MainActivity` |
| FR-04 | Permission denied → explain + Settings link  | ✅ PASS | `AlertDialog` with "Open Settings" button |
| FR-05 | Bluetooth off → prompt to enable             | ✅ PASS | `ACTION_REQUEST_ENABLE` intent |

---

### 4.2 Professor — Session Management

| ID    | Requirement (summary)                        | Status        | Finding |
|-------|----------------------------------------------|---------------|---------|
| FR-06 | Professor must enter session name            | ✅ PASS       | `et_session_name` validated in `ProfessorActivity` |
| FR-07 | Session ID = name normalised (spaces+lower)  | ✅ PASS       | `name.replaceAll("\\s+","").toLowerCase()` |
| FR-08 | Optional custom duration input               | ✅ PASS       | `et_duration` in XML, parsed in Activity |
| FR-09 | Default 5 min + tip label                    | ✅ PASS       | `DEFAULT_DURATION_MINUTES = 5`, tip TextView in layout |
| FR-10 | Show session ID prominently after start      | ✅ PASS       | Status shows "Tell students: cs101" |
| FR-11 | Manual stop button                           | ✅ PASS       | `btn_stop_session` → `stopSession(false)` |
| FR-12 | Auto-stop when timer hits 0:00               | ✅ PASS       | `CountDownTimer.onFinish()` → `stopSession(true)` |
| FR-13 | No new marks after session stops             | ⚠️ PARTIAL   | Advertiser stops + scanner stops. **Gap:** no timestamp gate — a student whose beacon was already in the air when the session stopped could still be recorded by `ProfessorScanner` within the few-ms window before `stopScanning()` executes. Low risk but not fully enforced. |

---

### 4.3 Professor — BLE Advertising

| ID    | Requirement (summary)                        | Status  | Finding |
|-------|----------------------------------------------|---------|---------|
| FR-14 | Advertise with SESSION_UUID = 0xABCD         | ✅ PASS | `BleAdvertiser.SESSION_UUID` correct |
| FR-15 | LOW_LATENCY + HIGH TX power                  | ✅ PASS | Both set in `BleAdvertiser.startAdvertising()` |
| FR-16 | Do not include device name                   | ✅ PASS | `setIncludeDeviceName(false)` |
| FR-17 | Advertise failure → dialog, no session start | ✅ PASS | `onAdvertiseFailed()` → `showError()`, session not started |
| FR-18 | Stop advertising when session stops          | ✅ PASS | `bleAdvertiser.stopAdvertising()` in `stopSession()` |

---

### 4.4 Professor — Student Detection

| ID    | Requirement (summary)                         | Status  | Finding |
|-------|-----------------------------------------------|---------|---------|
| FR-19 | Scan for STUDENT_UUID = 0xDCBA while live      | ✅ PASS | `ProfessorScanner.startScanning()` in `onSessionStarted()` |
| FR-20 | CALLBACK_TYPE_FIRST_MATCH                      | ✅ PASS | Set in `ProfessorScanner` scan settings |
| FR-21 | Extract name from manufacturer data (0xFFFF)   | ✅ PASS | `extractStudentName()` reads `mfData.get(0xFFFF)` |
| FR-22 | Save name to DB under active session ID        | ✅ PASS | `attendanceDB.markPresent(name, pseudoId, sessionId)` |
| FR-23 | Duplicate → silent rejection                   | ✅ PASS | `isAlreadyMarked()` check + `CONFLICT_IGNORE` |
| FR-24 | Stop scanning when session ends                | ✅ PASS | `professorScanner.stopScanning()` in `stopSession()` |

---

### 4.5 Professor — Real-Time Attendance List

| ID    | Requirement (summary)                         | Status  | Finding |
|-------|-----------------------------------------------|---------|---------|
| FR-25 | List updates in real time as students arrive   | ✅ PASS | `refreshAttendanceList()` called in `onStudentDetected()` |
| FR-26 | Format: `N. Name  •  timestamp`               | ✅ PASS | Built in `AttendanceDB.getAttendance()` |
| FR-27 | Count label (e.g. "3 present")                | ✅ PASS | `tvCount` updated in `refreshAttendanceList()` |
| FR-28 | List scrollable                               | ✅ PASS | `ListView` with `layout_weight="1"` |
| FR-29 | Final list visible after session stops        | ✅ PASS | `refreshAttendanceList()` called in `stopSession()` |

---

### 4.6 Professor — Export

| ID    | Requirement (summary)                         | Status  | Finding |
|-------|-----------------------------------------------|---------|---------|
| FR-30 | Export CSV during or after session            | ✅ PASS | `btn_export` always active |
| FR-31 | Save to Downloads/attendance_[sessionId].csv  | ✅ PASS | `CsvExporter.export()` |
| FR-32 | CSV header row                                | ✅ PASS | "Student Name, Timestamp" written first |
| FR-33 | Empty → "No attendance records to export."    | ✅ PASS | Toast shown when list is empty |

---

### 4.7 Student — Check-In Flow

| ID    | Requirement (summary)                         | Status  | Finding |
|-------|-----------------------------------------------|---------|---------|
| FR-34 | Student enters full name                      | ✅ PASS | `et_student_name` |
| FR-35 | Student enters session name (case-insensitive)| ✅ PASS | `et_session_id`, normalised before use |
| FR-36 | Empty fields → inline field-level error       | ✅ PASS | `etName.setError()` / `etSession.setError()` |
| FR-37 | Scan button debounced                         | ✅ PASS | `if (isScanning) return;` guard |

---

### 4.8 Student — BLE Proximity Detection

| ID    | Requirement (summary)                         | Status  | Finding |
|-------|-----------------------------------------------|---------|---------|
| FR-38 | Scan for SESSION_UUID (0xABCD)                | ✅ PASS | `BleScanner` filters by `BleAdvertiser.SESSION_UUID` |
| FR-39 | Live signal strength bar                      | ✅ PASS | `ProgressBar pb_signal` updates continuously |
| FR-40 | Colour-coded + labelled per RSSI table        | ✅ PASS | All 4 tiers implemented in `updateSignalMeter()` |
| FR-41 | Numeric RSSI + threshold shown below bar      | ✅ PASS | `"Signal: X dBm  (need >= -75)"` |
| FR-42 | Signal section hidden until scan starts       | ✅ PASS | `layout_signal` visibility GONE → VISIBLE on scan |

---

### 4.9 Student — Name Beacon

| ID    | Requirement (summary)                         | Status        | Finding |
|-------|-----------------------------------------------|---------------|---------|
| FR-43 | Start advertising when RSSI >= -75            | ✅ PASS       | `inRange` flag triggers `studentBeacon.start()` |
| FR-44 | STUDENT_UUID + 0xFFFF manufacturer data       | ✅ PASS       | Both set in `StudentBeacon.start()` |
| FR-45 | Names > 20 bytes truncated safely             | ✅ PASS       | `Arrays.copyOf(nameBytes, MAX_NAME_BYTES)` |
| FR-46 | Beacon runs minimum 60 seconds               | ✅ PASS       | `postDelayed(60_000ms)` before stop |
| FR-47 | Auto-stop after 60 seconds                   | ✅ PASS       | `Handler.postDelayed(() -> studentBeacon.stop(), 60_000)` |
| FR-48 | Graceful fallback if advertising unsupported  | ✅ PASS       | `onBeaconFailed()` shows Toast, no crash |

---

### 4.10 Student — Confirmation

| ID    | Requirement (summary)                         | Status     | Finding |
|-------|-----------------------------------------------|------------|---------|
| FR-49 | Status → "Attendance sent to professor!"      | ✅ PASS    | Set in `onBeaconStarted()` callback |
| FR-50 | Button → "Marked Present", disabled           | ✅ PASS    | `btnScan.setText("Marked Present"); setEnabled(false)` |
| FR-51 | Toast: "[Name] marked present for [session]!" | ❌ MISSING | `onBeaconStarted()` updates status text but **does not show a Toast**. The old StudentActivity had a Toast here; it was lost when the beacon architecture was added. |

---

### 4.11 Session Timer

| ID    | Requirement (summary)                         | Status  | Finding |
|-------|-----------------------------------------------|---------|---------|
| FR-52 | Live MM:SS countdown while session active     | ✅ PASS | `CountDownTimer` with `String.format("%d:%02d", ...)` |
| FR-53 | Auto-stop at 0:00 + Toast with count          | ✅ PASS | `stopSession(true)` with correct message |

---

### SRS Functional Summary

```
Total FRs   : 53
PASS        : 50  (94%)
PARTIAL     : 1   (FR-13)
MISSING     : 1   (FR-51)
FAIL        : 0
```

---

## 2. SRS Compliance — Non-Functional Requirements

| ID      | Requirement (summary)                            | Status        | Finding |
|---------|--------------------------------------------------|---------------|---------|
| NFR-01  | Student name on list within 5 seconds            | ✅ PASS       | FIRST_MATCH + LOW_LATENCY achieves this |
| NFR-02  | UI always responsive, BLE on background threads  | ✅ PASS       | All BLE callbacks → `runOnUiThread()` |
| NFR-03  | No battery drain at idle                         | ✅ PASS       | BLE stopped in `onDestroy()` |
| NFR-04  | Minimum power during sessions                    | ✅ PASS       | LOW_LATENCY only during active sessions |
| NFR-05  | No crash when BT turned off mid-session          | ✅ PASS       | `try-catch IllegalStateException` in all BLE classes |
| NFR-06  | No crash on navigate away mid-session            | ✅ PASS       | `onDestroy()` stops everything |
| NFR-07  | DB race condition → CONFLICT_IGNORE              | ✅ PASS       | `insertWithOnConflict(CONFLICT_IGNORE)` |
| NFR-08  | Student can retry until in range                 | ✅ PASS       | Scan continues until beacon triggered |
| NFR-09  | First-time check-in under 60 seconds             | ✅ PASS       | Flow is: open → enter 2 fields → tap scan → done |
| NFR-10  | Plain English errors, no stack traces            | ✅ PASS       | All errors are human-readable strings |
| NFR-11  | White text on dark buttons                       | ✅ PASS       | `android:textColor="#FFFFFF"` on all buttons |
| NFR-12  | Signal bar gives directional guidance            | ✅ PASS       | "move closer" / "hold still" labels |
| NFR-13  | One mark per device per session — DB enforced    | ✅ PASS       | UNIQUE INDEX on `(device_id, session_id)` |
| NFR-14  | Outside BLE range cannot mark                    | ✅ PASS       | RSSI threshold gate before beacon starts |
| NFR-15  | Session ID from class name only                  | ✅ PASS       | No random tokens |
| NFR-16  | Keystore excluded from git                       | ✅ PASS       | `.gitignore` has `*.keystore`, `*.jks` |
| NFR-17  | No unnecessary permissions                       | ✅ PASS       | All declared permissions are used |
| NFR-18  | BLE logic in dedicated classes, not Activities   | ⚠️ PARTIAL   | BLE classes exist but **Activities directly instantiate and coordinate them** — no `BleManager` facade |
| NFR-19  | DB access only in `AttendanceDB`                 | ✅ PASS       | No raw SQL outside `AttendanceDB` |
| NFR-20  | Single responsibility per class (SRP)            | ⚠️ PARTIAL   | Activities currently hold: UI + BLE coordination + timer + validation + DB calls — multiple responsibilities |

---

## 3. Architecture Compliance

This section checks the code against `ARCHITECTURE.md`.

### 3.1 Layers — What Exists vs What Was Designed

| Component                    | Designed In ARCH.md | Exists In Code | Status      |
|------------------------------|---------------------|----------------|-------------|
| `MainActivity`               | ✅                  | ✅             | ✅ EXISTS   |
| `ProfessorActivity`          | ✅                  | ✅             | ✅ EXISTS   |
| `StudentActivity`            | ✅                  | ✅             | ✅ EXISTS   |
| `IProfessorView` (interface) | ✅                  | ❌             | ❌ MISSING  |
| `IStudentView` (interface)   | ✅                  | ❌             | ❌ MISSING  |
| `ProfessorPresenter`         | ✅                  | ❌             | ❌ MISSING  |
| `StudentPresenter`           | ✅                  | ❌             | ❌ MISSING  |
| `SessionManager`             | ✅                  | ❌             | ❌ MISSING  |
| `IAttendanceRepository`      | ✅                  | ❌             | ❌ MISSING  |
| `ValidationResult`           | ✅                  | ❌             | ❌ MISSING  |
| `AttendanceRepository`       | ✅                  | ❌             | ❌ MISSING  |
| `AttendanceDB`               | ✅                  | ✅             | ✅ EXISTS   |
| `BleManager` (facade)        | ✅                  | ❌             | ❌ MISSING  |
| `BleAdvertiser`              | ✅                  | ✅             | ✅ EXISTS   |
| `BleScanner`                 | ✅                  | ✅             | ✅ EXISTS   |
| `StudentBeacon`              | ✅                  | ✅             | ✅ EXISTS   |
| `ProfessorScanner`           | ✅                  | ✅             | ✅ EXISTS   |
| `CsvExporter`                | ✅                  | ✅             | ✅ EXISTS   |

```
Designed  : 18 components
Exist     : 10  (56%)
Missing   : 8   (44%)
```

### 3.2 Package Structure — Designed vs Actual

| Designed Package                | Actual Location              | Status     |
|---------------------------------|------------------------------|------------|
| `presentation/`                 | root package (flat)          | ❌ MISSING |
| `presenter/`                    | does not exist               | ❌ MISSING |
| `contract/`                     | does not exist               | ❌ MISSING |
| `domain/`                       | does not exist               | ❌ MISSING |
| `data/db/`                      | root package (flat)          | ❌ MISSING |
| `data/ble/`                     | root package (flat)          | ❌ MISSING |
| `data/export/`                  | root package (flat)          | ❌ MISSING |

All 9 classes sit in the root package — no layer separation at all.

### 3.3 Dependency Rules — Violations

Architecture mandates: **Activities must NOT talk directly to BLE classes or AttendanceDB.**

| Violation                                             | Location                      |
|-------------------------------------------------------|-------------------------------|
| `ProfessorActivity` directly instantiates `BleAdvertiser` | `ProfessorActivity.java:38`  |
| `ProfessorActivity` directly instantiates `ProfessorScanner` | `ProfessorActivity.java:40` |
| `ProfessorActivity` directly calls `attendanceDB.markPresent()` | `ProfessorActivity.java:47` |
| `ProfessorActivity` directly calls `attendanceDB.getAttendance()` | `ProfessorActivity.java:156` |
| `StudentActivity` directly instantiates `BleScanner`  | `StudentActivity.java:46`    |
| `StudentActivity` directly instantiates `StudentBeacon`| `StudentActivity.java:47`    |
| `StudentActivity` directly calls `attendanceDB.isAlreadyMarked()` | `StudentActivity.java:64` |
| `StudentActivity` directly calls `attendanceDB.markPresent()` | `StudentActivity.java:75`   |
| Session ID logic (`replaceAll + toLowerCase`) in Activity | `ProfessorActivity.java:78` |
| Duration validation in Activity                       | `ProfessorActivity.java:66`  |

---

## 4. Master Gap List — Prioritised

### Priority 1 — SRS Bugs (must fix before features)

| #  | ID     | What's broken                  | Where              | Fix                                |
|----|--------|--------------------------------|--------------------|------------------------------------|
| 1  | FR-51  | No Toast after attendance sent | `StudentActivity`  | Add Toast in `onBeaconStarted()`   |
| 2  | FR-13  | Race condition on session stop | `ProfessorActivity`| Add session-active guard in `onStudentDetected()` |

### Priority 2 — Architecture (required before tests can be written)

| #  | Component               | Why it's needed                                         |
|----|-------------------------|---------------------------------------------------------|
| 3  | `SessionManager`        | Centralises session ID + validation (used by both sides)|
| 4  | `IAttendanceRepository` | Decouples Presenters from SQLite — makes unit tests possible |
| 5  | `AttendanceRepository`  | Concrete implementation wrapping `AttendanceDB`         |
| 6  | `BleManager`            | Facade over 4 BLE classes — NFR-18 compliance           |
| 7  | `IProfessorView`        | Contract that lets Presenter be tested without Activity |
| 8  | `IStudentView`          | Same for student side                                   |
| 9  | `ProfessorPresenter`    | Moves all logic out of `ProfessorActivity`              |
| 10 | `StudentPresenter`      | Moves all logic out of `StudentActivity`                |
| 11 | `ValidationResult`      | Return type for `SessionManager.validate*()` methods   |
| 12 | Package reorganisation  | Move all classes into correct sub-packages              |

### Priority 3 — Tests (only possible after Priority 2)

| #  | Test class                         | What it verifies       |
|----|------------------------------------|------------------------|
| 13 | `SessionManagerTest`               | FR-07, FR-08, FR-09    |
| 14 | `ProfessorPresenterTest`           | FR-06..FR-33           |
| 15 | `StudentPresenterTest`             | FR-34..FR-51           |
| 16 | `AttendanceRepositoryTest`         | NFR-07, NFR-13, FR-23  |
| 17 | `BleManagerTest`                   | NFR-05, NFR-18         |

---

## 5. Build Order (Feature-by-Feature)

Based on this audit, here is the correct order of work:

```
STEP 1 — Fix 2 SRS bugs (FR-51, FR-13)          ~30 min
  └─ These are small isolated fixes

STEP 2 — Build Domain layer                       ~1 hour
  └─ SessionManager  +  IAttendanceRepository
  └─ AttendanceRepository  +  ValidationResult

STEP 3 — Build BleManager facade                  ~1 hour
  └─ Wraps BleAdvertiser, BleScanner,
     StudentBeacon, ProfessorScanner

STEP 4 — Build Presenter layer                    ~2 hours
  └─ IProfessorView  →  ProfessorPresenter
  └─ IStudentView    →  StudentPresenter

STEP 5 — Migrate Activities to use Presenters     ~1 hour
  └─ Activities become thin Views only
  └─ Reorganise into sub-packages

STEP 6 — Write tests against SRS FRs              ~2 hours
  └─ SessionManagerTest
  └─ ProfessorPresenterTest
  └─ StudentPresenterTest
  └─ AttendanceRepositoryTest
```

---

*End of Audit Report*
