# Architecture Design Document (ADD)
## DigitalSphere — BLE-Based Smart Attendance System

| Field        | Value                      |
|--------------|----------------------------|
| Version      | 1.0                        |
| Pattern      | MVP + Repository           |
| Language     | Java (Android)             |
| Last Updated | March 2026                 |

---

## Table of Contents

1. [Current Architecture — What's Wrong](#1-current-architecture--whats-wrong)
2. [Architecture Decision — Why MVP](#2-architecture-decision--why-mvp)
3. [Target Architecture — Big Picture](#3-target-architecture--big-picture)
4. [Layer Breakdown](#4-layer-breakdown)
5. [Component Diagram](#5-component-diagram)
6. [Contract Definitions (Interfaces)](#6-contract-definitions-interfaces)
7. [Data Flow Diagrams](#7-data-flow-diagrams)
8. [Class Responsibility Matrix](#8-class-responsibility-matrix)
9. [Dependency Rules](#9-dependency-rules)
10. [Package Structure](#10-package-structure)
11. [Migration Plan](#11-migration-plan)
12. [Future Path — MVVM](#12-future-path--mvvm)

---

## 1. Current Architecture — What's Wrong

This is what the app looks like today. Everything lives inside Activities.

```
┌─────────────────────────────────────────────────────────┐
│                   ProfessorActivity                      │
│                                                          │
│  UI logic  +  BLE logic  +  Timer logic  +  DB logic    │
│            +  validation  +  error handling              │
│                                                          │
│  ┌────────────┐  ┌───────────────┐  ┌────────────────┐  │
│  │BleAdvertiser│  │ProfessorScanner│  │  AttendanceDB  │  │
│  └────────────┘  └───────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    StudentActivity                       │
│                                                          │
│  UI logic  +  BLE logic  +  Beacon logic  +  DB logic   │
│            +  RSSI mapping  +  error handling            │
│                                                          │
│  ┌──────────┐  ┌─────────────┐  ┌────────────────────┐  │
│  │BleScanner│  │StudentBeacon│  │    AttendanceDB     │  │
│  └──────────┘  └─────────────┘  └────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Problems with this design

| Problem                  | Impact                                                   |
|--------------------------|----------------------------------------------------------|
| God Activity             | One class doing 5+ jobs — hard to read, hard to change   |
| Zero testability         | Can't unit test business logic without running the app   |
| Tight coupling           | Activity directly instantiates BLE and DB classes        |
| Logic scattered          | Session ID normalisation lives in two different places   |
| No contracts             | No interface between UI and logic — can't swap or mock   |
| Lifecycle mismanagement  | BLE callbacks holding Activity references = memory leaks |

---

## 2. Architecture Decision — Why MVP

### Options Considered

| Pattern          | Pros                              | Cons                              | Verdict   |
|------------------|-----------------------------------|-----------------------------------|-----------|
| MVC (current)    | Simple to start                   | Activity = Controller + View mess | Rejected  |
| **MVP**          | Clear separation, easy to learn   | Boilerplate contracts             | ✅ Chosen |
| MVVM + ViewModel | Industry standard, lifecycle-safe | Requires Jetpack, steeper curve   | Phase 2   |
| Clean Arch       | Maximum separation                | Overkill for this app size        | Phase 3   |

### Why MVP now
- We already have Activities acting as Views — MVP formalises this with interfaces
- Presenters are plain Java — fully unit-testable with JUnit, no Android emulator needed
- The BLE callback model maps naturally to Presenter methods
- Direct migration path to MVVM later (replace Presenter with ViewModel, keep Repository)

### MVP in one sentence
> The **View** (Activity) only draws UI and forwards user events.
> The **Presenter** contains all logic and calls back into the View interface.
> The **Model** (Repository + DB) owns all data.

---

## 3. Target Architecture — Big Picture

```
╔══════════════════════════════════════════════════════════════════╗
║                       PRESENTATION LAYER                         ║
║                                                                  ║
║   ┌──────────────┐   ┌────────────────────┐   ┌──────────────┐  ║
║   │ MainActivity │   │ ProfessorActivity  │   │StudentActivity│  ║
║   │              │   │ implements         │   │ implements    │  ║
║   │ (permission  │   │ IProfessorView     │   │ IStudentView  │  ║
║   │  + routing)  │   └────────┬───────────┘   └──────┬───────┘  ║
╚═══════════════════════════════╪══════════════════════╪══════════╝
                                │ calls                 │ calls
╔═══════════════════════════════╪══════════════════════╪══════════╗
║                       PRESENTER LAYER                 │          ║
║                               │                       │          ║
║              ┌────────────────▼───────┐  ┌────────────▼──────┐  ║
║              │  ProfessorPresenter    │  │  StudentPresenter  │  ║
║              │                        │  │                    │  ║
║              │  - startSession()      │  │  - startScan()     │  ║
║              │  - stopSession()       │  │  - onScanResult()  │  ║
║              │  - onStudentDetected() │  │  - onBeaconResult()│  ║
║              └──────────┬─────────────┘  └────────┬──────────┘  ║
╚═════════════════════════╪══════════════════════════╪════════════╝
                          │ uses                      │ uses
╔═════════════════════════╪══════════════════════════╪════════════╗
║                    DOMAIN / BUSINESS LAYER          │            ║
║                          │                          │            ║
║         ┌────────────────▼──────────────────────────▼────────┐  ║
║         │              SessionManager                         │  ║
║         │  - createSessionId(name): String                    │  ║
║         │  - validateName(name): ValidationResult             │  ║
║         │  - validateDuration(input): int                     │  ║
║         └──────────────────────────────────────────────────┘  ║
║                                                                  ║
║         ┌──────────────────────────────────────────────────┐    ║
║         │           IAttendanceRepository (interface)       │    ║
║         │  + AttendanceRepository (implementation)          │    ║
║         └──────────────────────────────────────────────────┘    ║
╚══════════════════════════════════════════════════════════════════╝
                          │ uses
╔═════════════════════════╪══════════════════════════════════════╗
║                       DATA LAYER                                 ║
║                          │                                       ║
║    ┌─────────────────────▼──────┐   ┌───────────────────────┐   ║
║    │       AttendanceDB         │   │       BleManager       │   ║
║    │   (SQLiteOpenHelper)       │   │  (facade over all BLE) │   ║
║    │                            │   │                        │   ║
║    │  attendance.db (SQLite)    │   │  ┌──────────────────┐  │   ║
║    └────────────────────────────┘   │  │  BleAdvertiser   │  │   ║
║                                     │  │  BleScanner      │  │   ║
║                                     │  │  StudentBeacon   │  │   ║
║                                     │  │  ProfessorScanner│  │   ║
║                                     │  └──────────────────┘  │   ║
║                                     └───────────────────────┘   ║
╚══════════════════════════════════════════════════════════════════╝
```

---

## 4. Layer Breakdown

### Layer 1 — Presentation (View)
**What it does:** Draws the UI. Forwards user actions to Presenter.
**What it does NOT do:** Business logic, DB access, BLE calls, validation.

```
Activity/View responsibilities:
  ✅ setContentView, findViewById
  ✅ Show/hide views, update text, update colours
  ✅ Forward button clicks to Presenter
  ✅ Forward lifecycle events (onDestroy) to Presenter
  ❌ Session ID normalisation
  ❌ RSSI threshold decisions
  ❌ Database queries
  ❌ BLE start/stop
```

### Layer 2 — Presenter
**What it does:** All business logic. Receives events, makes decisions, tells View what to show.
**Key property:** Plain Java class — no `import android.*` except Context for DB.
**Testable:** Yes — instantiate in a JUnit test, mock the View interface.

```
ProfessorPresenter responsibilities:
  ✅ Validate session name and duration
  ✅ Generate session ID (normalise name)
  ✅ Coordinate BleAdvertiser + ProfessorScanner lifecycle
  ✅ Run countdown timer
  ✅ Decide when to stop session
  ✅ Handle student detection → save to DB → tell View to refresh
  ❌ Touch any Android View directly
  ❌ Call findViewById
```

### Layer 3 — Domain
**What it does:** Shared business rules that neither Presenter should own alone.
- `SessionManager` — session ID creation, input validation (used by both Presenters)
- `IAttendanceRepository` — data contract interface; Presenters depend on the interface, not SQLite

### Layer 4 — Data
**What it does:** Talks to hardware (BLE) and storage (SQLite). Nothing else.
- `AttendanceDB` — SQLite implementation of `IAttendanceRepository`
- `BleManager` — single facade over the 4 BLE classes; hides BLE API complexity from Presenters

---

## 5. Component Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                        DigitalSphere App                      │
│                                                               │
│  ┌──────────────┐                                             │
│  │ MainActivity │──── routes to ────┐                        │
│  └──────────────┘                   │                        │
│                          ┌──────────▼──────────┐             │
│                          │                     │             │
│             ┌────────────▼──────┐   ┌──────────▼──────────┐  │
│             │ ProfessorActivity │   │   StudentActivity   │  │
│             │ (IProfessorView)  │   │   (IStudentView)    │  │
│             └────────┬──────────┘   └──────────┬──────────┘  │
│                      │ owns                     │ owns        │
│             ┌────────▼──────────┐   ┌──────────▼──────────┐  │
│             │ProfessorPresenter │   │  StudentPresenter   │  │
│             └──┬────────────┬───┘   └────┬──────────┬─────┘  │
│                │            │            │          │         │
│          ┌─────▼──┐   ┌─────▼────────────▼──┐      │         │
│          │Session │   │  IAttendanceRepo     │      │         │
│          │Manager │   │  (AttendanceRepository│     │         │
│          └────────┘   │  → AttendanceDB)     │      │         │
│                       └──────────────────────┘      │         │
│                                                      │         │
│             ┌────────────────────────────────────────▼──────┐  │
│             │                  BleManager                    │  │
│             │   ┌─────────────┐     ┌─────────────────────┐ │  │
│             │   │ BleAdvertiser│    │   ProfessorScanner   │ │  │
│             │   │ (Professor) │     │   (listens for      │ │  │
│             │   │ UUID: 0xABCD│     │    students)        │ │  │
│             │   └─────────────┘     └─────────────────────┘ │  │
│             │   ┌─────────────┐     ┌─────────────────────┐ │  │
│             │   │  BleScanner │     │    StudentBeacon     │ │  │
│             │   │ (Student)   │     │   (broadcasts name)  │ │  │
│             │   │UUID: 0xABCD │     │   UUID: 0xDCBA      │ │  │
│             │   └─────────────┘     └─────────────────────┘ │  │
│             └───────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## 6. Contract Definitions (Interfaces)

These are the interfaces the architecture is built around. They are the
single most important part — everything else plugs into them.

### 6.1 IProfessorView

```java
// What the Presenter can ask the Professor screen to do
public interface IProfessorView {
    void showSessionStarted(String sessionId, String displayName);
    void showSessionStopped();
    void showCountdown(String timeFormatted);     // "4:32"
    void showStudentDetected(String studentRecord); // "1. Yash  •  03:30 PM"
    void showStudentCount(int count);              // "3 present"
    void showAttendanceList(List<String> records);
    void showError(String title, String message);
    void showMessage(String message);
    void setStartButtonEnabled(boolean enabled);
    void setStopButtonEnabled(boolean enabled);
}
```

### 6.2 IStudentView

```java
// What the Presenter can ask the Student screen to do
public interface IStudentView {
    void showScanning();
    void showSignalUpdate(int rssi, int progressPercent, String label, int colour);
    void showInRange();
    void showAttendanceSent(String studentName, String sessionName);
    void showAlreadyMarked();
    void showBeaconFallback(String reason);    // device doesn't support advertising
    void showScanError(String reason);
    void showError(String title, String message);
    void showFieldError(Field field, String message);
    void setScanButtonEnabled(boolean enabled);
    void setScanButtonText(String text);
    void showSignalSection(boolean visible);

    enum Field { NAME, SESSION }
}
```

### 6.3 ProfessorPresenter

```java
public class ProfessorPresenter {
    // Lifecycle
    public ProfessorPresenter(IAttendanceRepository repo, BleManager ble, SessionManager session) {}
    public void attachView(IProfessorView view) {}
    public void detachView() {}   // called in onDestroy — prevents memory leak

    // User actions (called from Activity)
    public void onStartSessionClicked(String rawName, String rawDuration) {}
    public void onStopSessionClicked() {}
    public void onExportClicked() {}

    // Internal callbacks (from BleManager, CountDownTimer)
    void onAdvertiseStarted() {}
    void onAdvertiseFailed(String reason) {}
    void onStudentDetected(String studentName) {}
    void onTimerTick(long millisRemaining) {}
    void onTimerFinished() {}
}
```

### 6.4 StudentPresenter

```java
public class StudentPresenter {
    public StudentPresenter(IAttendanceRepository repo, BleManager ble, SessionManager session) {}
    public void attachView(IStudentView view) {}
    public void detachView() {}

    // User actions
    public void onScanClicked(String rawName, String rawSession) {}

    // BLE callbacks (from BleManager)
    void onScanResult(int rssi, boolean inRange) {}
    void onScanError(String reason) {}
    void onBeaconStarted() {}
    void onBeaconFailed(String reason) {}
}
```

### 6.5 IAttendanceRepository

```java
// The data contract — Presenters depend on this interface, NOT on SQLite
public interface IAttendanceRepository {
    boolean markPresent(String studentName, String deviceId, String sessionId);
    boolean isAlreadyMarked(String deviceId, String sessionId);
    List<String> getAttendance(String sessionId);
    int getAttendanceCount(String sessionId);
}

// The real implementation (wraps AttendanceDB)
public class AttendanceRepository implements IAttendanceRepository { ... }

// A fake for unit tests (no SQLite needed)
public class FakeAttendanceRepository implements IAttendanceRepository { ... }
```

### 6.6 SessionManager

```java
// Shared domain logic — no Android imports
public class SessionManager {

    // "CS 101" → "cs101"
    public String createSessionId(String rawName) {
        return rawName.replaceAll("\\s+", "").toLowerCase();
    }

    public ValidationResult validateSessionName(String name) {
        if (name == null || name.trim().isEmpty())
            return ValidationResult.error("Session name cannot be empty.");
        if (name.trim().length() < 2)
            return ValidationResult.error("Session name is too short.");
        return ValidationResult.ok();
    }

    public int validateAndParseDuration(String input, int defaultMinutes) {
        if (input == null || input.trim().isEmpty()) return defaultMinutes;
        try {
            int d = Integer.parseInt(input.trim());
            if (d < 1 || d > 180)
                throw new IllegalArgumentException("Duration must be between 1 and 180 minutes.");
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Duration must be a whole number.");
        }
    }
}
```

### 6.7 BleManager

```java
// Facade — hides all 4 BLE classes behind one simple API
public class BleManager {

    public interface ProfessorBleListener {
        void onAdvertiseStarted();
        void onAdvertiseFailed(String reason);
        void onStudentDetected(String studentName);
        void onScanError(String reason);
    }

    public interface StudentBleListener {
        void onScanResult(int rssi, boolean inRange);
        void onScanError(String reason);
        void onBeaconStarted();
        void onBeaconFailed(String reason);
    }

    // Professor phone: advertise session + scan for student names
    public void startProfessorMode(ProfessorBleListener listener) {}
    public void stopProfessorMode() {}

    // Student phone: scan for professor + broadcast own name when in range
    public void startStudentScan(StudentBleListener listener) {}
    public void startStudentBeacon(String studentName, StudentBleListener listener) {}
    public void stopStudentMode() {}
}
```

---

## 7. Data Flow Diagrams

### 7.1 Professor Starts a Session

```
Professor taps "Start"
        │
        ▼
ProfessorActivity.onStartSessionClicked("CS101", "5")
        │
        ▼
ProfessorPresenter.onStartSessionClicked("CS101", "5")
        │
        ├─ SessionManager.validateSessionName("CS101")  → OK
        ├─ SessionManager.validateAndParseDuration("5") → 5
        ├─ SessionManager.createSessionId("CS101")      → "cs101"
        │
        ▼
BleManager.startProfessorMode(listener)
        │
        ├─ BleAdvertiser.startAdvertising()  ──→ onAdvertiseStarted()
        └─ ProfessorScanner.startScanning()
                │
                ▼
        ProfessorPresenter.onAdvertiseStarted()
                │
                ▼
        AttendanceRepository.getAttendance("cs101")  → []
                │
                ▼
        IProfessorView.showSessionStarted("cs101", "CS101")
        IProfessorView.showStudentCount(0)
        IProfessorView.showAttendanceList([])
        IProfessorView.setStartButtonEnabled(false)
        IProfessorView.setStopButtonEnabled(true)
                │
                ▼
        CountDownTimer starts (5 min)
        → onTimerTick() → IProfessorView.showCountdown("4:59")
                             IProfessorView.showCountdown("4:58")
                             ...
```

### 7.2 Student Marks Attendance

```
Student taps "Scan" (name="Yash", session="cs101")
        │
        ▼
StudentActivity.onScanClicked("Yash", "cs101")
        │
        ▼
StudentPresenter.onScanClicked("Yash", "cs101")
        │
        ├─ SessionManager.validateSessionName("cs101") → OK
        ├─ name.isEmpty() check → OK
        │
        ▼
IStudentView.showScanning()
IStudentView.setScanButtonText("Scanning...")
IStudentView.setScanButtonEnabled(false)
IStudentView.showSignalSection(true)
        │
        ▼
BleManager.startStudentScan(listener)
        │
        ▼  (BLE scan packets arrive)
StudentPresenter.onScanResult(rssi=-68, inRange=false)
        │
        ▼
IStudentView.showSignalUpdate(-68, 46%, "Weak — move closer", ORANGE)
        │
        ▼  (moves closer, stronger signal)
StudentPresenter.onScanResult(rssi=-55, inRange=true)
        │
        ├─ inRange = true && !alreadyMarked → proceed
        │
        ▼
BleManager.stopStudentScan()
BleManager.startStudentBeacon("Yash", listener)
        │
        ▼
IStudentView.showInRange()
IStudentView.showSignalUpdate(-55, 64%, "Excellent!", GREEN)
        │
        ▼  (beacon starts successfully)
StudentPresenter.onBeaconStarted()
        │
        ▼
IStudentView.showAttendanceSent("Yash", "cs101")
IStudentView.setScanButtonText("Marked Present")
IStudentView.setScanButtonEnabled(false)
        │
        ▼  (60 seconds later)
BleManager.stopStudentMode()  ← auto-stop
```

### 7.3 Professor Receives Student Name

```
       PROFESSOR'S PHONE

ProfessorScanner (running in background)
        │
        │  (picks up "Yash" beacon via BLE)
        ▼
BleManager.ProfessorBleListener.onStudentDetected("Yash")
        │
        ▼
ProfessorPresenter.onStudentDetected("Yash")
        │
        ├─ pseudoDeviceId = "beacon_yash_cs101"
        ├─ repo.isAlreadyMarked("beacon_yash_cs101", "cs101") → false
        │
        ▼
repo.markPresent("Yash", "beacon_yash_cs101", "cs101") → true
        │
        ▼
repo.getAttendance("cs101") → ["1. Yash  •  03:30 PM"]
repo.getAttendanceCount("cs101") → 1
        │
        ▼
IProfessorView.showAttendanceList(["1. Yash  •  03:30 PM"])
IProfessorView.showStudentCount(1)
IProfessorView.showMessage("Yash marked present!")  ← Toast
```

### 7.4 Session Auto-Stops (Timer Expires)

```
CountDownTimer.onFinish()
        │
        ▼
ProfessorPresenter.onTimerFinished()
        │
        ├─ BleManager.stopProfessorMode()
        ├─ repo.getAttendanceCount("cs101") → 3
        │
        ▼
IProfessorView.showSessionStopped()
IProfessorView.showCountdown("0:00")
IProfessorView.setStartButtonEnabled(true)
IProfessorView.setStopButtonEnabled(false)
IProfessorView.showMessage("Session ended. 3 student(s) marked present.")
```

---

## 8. Class Responsibility Matrix

| Class                  | Layer        | Responsibility                                       | Knows About            |
|------------------------|--------------|------------------------------------------------------|------------------------|
| `MainActivity`         | Presentation | Role selection, permission checks, BT enable prompt  | Nothing else           |
| `ProfessorActivity`    | Presentation | Professor UI only — no logic                         | `IProfessorView`       |
| `StudentActivity`      | Presentation | Student UI only — no logic                           | `IStudentView`         |
| `ProfessorPresenter`   | Presenter    | All professor business logic                         | `IProfessorView`, `IAttendanceRepository`, `BleManager`, `SessionManager` |
| `StudentPresenter`     | Presenter    | All student business logic                           | `IStudentView`, `IAttendanceRepository`, `BleManager`, `SessionManager` |
| `SessionManager`       | Domain       | Session ID creation, input validation                | Nothing (pure Java)    |
| `IAttendanceRepository`| Domain       | Data contract interface                              | Nothing                |
| `AttendanceRepository` | Data         | Repository implementation wrapping AttendanceDB      | `AttendanceDB`         |
| `AttendanceDB`         | Data         | SQLite CRUD operations                               | Android SQLite API     |
| `BleManager`           | Data         | BLE facade — coordinates 4 BLE classes               | All 4 BLE classes      |
| `BleAdvertiser`        | Data         | Professor session beacon advertising                 | Android BLE API        |
| `BleScanner`           | Data         | Student proximity detection                          | Android BLE API        |
| `StudentBeacon`        | Data         | Student name broadcasting                            | Android BLE API        |
| `ProfessorScanner`     | Data         | Professor name detection from student beacons        | Android BLE API        |
| `CsvExporter`          | Data         | CSV file write to Downloads                          | Android File API       |

---

## 9. Dependency Rules

The golden rule of layered architecture:

```
✅  Upper layers depend on lower layers
✅  Inner layers (Domain) are completely independent
❌  Lower layers NEVER import upper layers
❌  Data layer never knows about Activities
❌  Domain layer never imports android.*

ALLOWED:
  Presentation  →  Presenter
  Presenter     →  Domain (SessionManager, IAttendanceRepository)
  Presenter     →  Data (BleManager)
  Data          →  Android APIs (BLE, SQLite)

FORBIDDEN:
  AttendanceDB  →  Activity        ❌
  BleManager    →  Presenter       ❌
  SessionManager →  AttendanceDB   ❌
  Presenter      →  android.widget ❌  (except Context for DB)
```

### Dependency diagram

```
                ┌─────────────────────┐
                │   android.* APIs    │  ← Only Data layer touches this
                └──────────┬──────────┘
                           │
        ┌──────────────────▼──────────────────────────┐
        │              DATA LAYER                       │
        │  AttendanceDB  BleAdvertiser  BleScanner      │
        │  StudentBeacon  ProfessorScanner  BleManager  │
        └──────────────────┬──────────────────────────┘
                           │  implements / uses
        ┌──────────────────▼──────────────────────────┐
        │             DOMAIN LAYER                      │
        │    IAttendanceRepository   SessionManager     │
        └──────────────────┬──────────────────────────┘
                           │  uses
        ┌──────────────────▼──────────────────────────┐
        │            PRESENTER LAYER                    │
        │    ProfessorPresenter   StudentPresenter      │
        └──────────────────┬──────────────────────────┘
                           │  implements interfaces
        ┌──────────────────▼──────────────────────────┐
        │           PRESENTATION LAYER                  │
        │  MainActivity  ProfessorActivity  Student...  │
        └─────────────────────────────────────────────┘
```

---

## 10. Package Structure

```
com.example.digitalsphere/
│
├── presentation/               ← Layer 1: UI only
│   ├── MainActivity.java
│   ├── ProfessorActivity.java
│   └── StudentActivity.java
│
├── presenter/                  ← Layer 2: Business logic
│   ├── ProfessorPresenter.java
│   └── StudentPresenter.java
│
├── contract/                   ← Interfaces (View contracts)
│   ├── IProfessorView.java
│   └── IStudentView.java
│
├── domain/                     ← Layer 3: Shared rules, pure Java
│   ├── SessionManager.java
│   ├── IAttendanceRepository.java
│   └── model/
│       └── ValidationResult.java
│
└── data/                       ← Layer 4: Hardware + storage
    ├── db/
    │   ├── AttendanceDB.java
    │   └── AttendanceRepository.java
    ├── ble/
    │   ├── BleManager.java
    │   ├── BleAdvertiser.java
    │   ├── BleScanner.java
    │   ├── StudentBeacon.java
    │   └── ProfessorScanner.java
    └── export/
        └── CsvExporter.java
```

---

## 11. Migration Plan

The current codebase has no architecture. Here is the step-by-step plan to
reach the target state without breaking the working app at any point.

### Phase 1 — Extract Domain Logic (no behaviour change)
Create `SessionManager` by pulling the session ID normalisation and validation
out of Activities into a testable class.

```
ProfessorActivity (before):
  sessionId = name.replaceAll("\\s+", "").toLowerCase();

ProfessorActivity (after):
  sessionId = sessionManager.createSessionId(name);
```

### Phase 2 — Introduce Repository
Create `IAttendanceRepository` interface and `AttendanceRepository` implementation.
Activities start calling `repo.markPresent()` instead of `db.markPresent()`.
This one change makes the data layer swappable.

### Phase 3 — Introduce BleManager Facade
Create `BleManager` that wraps all 4 BLE classes. Activities now call
`bleManager.startProfessorMode()` instead of managing 2 BLE objects directly.
This eliminates the BLE lifecycle bug risk in Activities.

### Phase 4 — Extract Presenters
For each Activity:
1. Create View interface (`IProfessorView`)
2. Have Activity implement the interface
3. Create Presenter, move all non-UI logic into it
4. Activity only calls `presenter.onXxxClicked()` and implements `showXxx()` methods

### Phase 5 — Write Unit Tests
With Presenters holding all logic, write JUnit tests using `FakeAttendanceRepository`
and mock View interfaces. Test every FR from the SRS.

---

## 12. Future Path — MVVM

After MVP is stable, migrate to MVVM using Android Jetpack:

```
MVP Today                           MVVM Tomorrow
─────────────────────────────────────────────────────
ProfessorPresenter              →   ProfessorViewModel (extends ViewModel)
IProfessorView (interface)      →   LiveData / StateFlow (observed by Activity)
attachView / detachView         →   Gone (ViewModel survives rotation natively)
AttendanceRepository            →   Same (no change needed)
BleManager                      →   Same (no change needed)
SessionManager                  →   Same (no change needed)
```

The data layer (Repository + BleManager) is **completely reusable** between MVP and MVVM.
Only the Presenter → ViewModel swap is needed. This is why building the
Repository pattern correctly now pays off later.

---

*End of Architecture Design Document — DigitalSphere v1.0*
