# Software Requirements Specification (SRS)
## DigitalSphere — BLE-Based Smart Attendance System

| Field        | Value                              |
|--------------|------------------------------------|
| Version      | 1.0                                |
| Status       | Draft                              |
| Platform     | Android (Native Java)              |
| Last Updated | March 2026                         |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Overall Description](#2-overall-description)
3. [User Classes & Characteristics](#3-user-classes--characteristics)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [External Interface Requirements](#6-external-interface-requirements)
7. [Data Requirements](#7-data-requirements)
8. [Security Requirements](#8-security-requirements)
9. [System Constraints & Limitations](#9-system-constraints--limitations)
10. [Assumptions & Dependencies](#10-assumptions--dependencies)
11. [Out of Scope (v1.0)](#11-out-of-scope-v10)
12. [Appendix](#12-appendix)

---

## 1. Introduction

### 1.1 Purpose
This document defines the complete software requirements for **DigitalSphere**, a
proximity-based attendance system for classrooms. It is the single source of truth
for what the system must do, how it must perform, and what constraints it must
operate within.

Audience: developers, testers, product owners, and anyone reviewing the project.

### 1.2 Problem Statement
Manual attendance (paper registers, oral roll-call) is slow, error-prone, and
easily proxied. Cloud-based QR or PIN systems require internet access, which is
not always available in classrooms. There is no reliable, offline, proximity-enforced
attendance system for Android.

### 1.3 Proposed Solution
DigitalSphere uses **Bluetooth Low Energy (BLE)** to solve all three problems:
- **Offline**: no internet required at any point
- **Proximity-enforced**: a student must physically be within ~10 metres of the
  professor's device to mark attendance — no proxying from outside the room
- **Automatic**: the student's phone broadcasts their name; the professor's phone
  receives it and records it in real time — no manual input by the professor

### 1.4 Definitions & Acronyms

| Term       | Meaning                                                        |
|------------|----------------------------------------------------------------|
| BLE        | Bluetooth Low Energy — short-range, ultra-low-power radio      |
| RSSI       | Received Signal Strength Indicator (in dBm, e.g. -65)         |
| UUID       | Universally Unique Identifier used to tag BLE service types    |
| Session    | One attendance window opened by a professor for a class        |
| Session ID | Normalised class name (e.g. "CS101" → "cs101")                 |
| Beacon     | A BLE advertisement packet broadcast by a device               |
| Professor  | The user who starts the session and collects attendance        |
| Student    | The user who scans and submits their name to the professor      |
| dBm        | Decibel-milliwatts — unit for signal strength (higher = closer)|
| SRS        | Software Requirements Specification (this document)            |
| FR         | Functional Requirement                                         |
| NFR        | Non-Functional Requirement                                     |

### 1.5 Document Conventions
- Requirements are tagged **FR-XX** (functional) or **NFR-XX** (non-functional).
- **MUST** = mandatory. **SHOULD** = strongly recommended. **MAY** = optional.

---

## 2. Overall Description

### 2.1 How It Works — The Big Picture

```
PROFESSOR'S PHONE                          STUDENT'S PHONE
─────────────────                          ───────────────
1. Opens app as Professor
2. Enters session name (e.g. CS101)
3. Taps "Start Session"
4. Phone broadcasts BLE beacon  ─────────→ 5. Opens app as Student
   (UUID: 0x0000ABCD)                      6. Enters name + "CS101"
                                           7. Taps "Scan for Class"
                                           8. Detects professor beacon
                                              (RSSI >= -75 dBm = in range)
                                           9. Broadcasts own name via BLE
                                              (UUID: 0x0000DCBA, name in payload)
10. Receives student name beacon ←─────────
11. Saves name + timestamp to DB
12. Live attendance list updates
    (e.g. "1. Yash  •  24 Mar 2026, 03:30 PM")
```

No internet. No server. No QR codes. Just BLE — two phones talking to each other
in both directions simultaneously.

### 2.2 Product Scope
- **In scope**: Session creation, BLE advertising & scanning, attendance capture,
  real-time list, countdown timer, CSV export, runtime permissions, offline operation.
- **Out of scope (v1.0)**: login/accounts, cloud sync, multi-session history, admin
  dashboard, iOS support. See [Section 11](#11-out-of-scope-v10).

### 2.3 Operating Environment
| Parameter         | Value                                    |
|-------------------|------------------------------------------|
| OS                | Android 8.0 (API 26) — Android 15 (API 36) |
| Hardware required | Bluetooth 4.0+ with BLE support          |
| Internet          | Not required                             |
| Location services | Required for BLE scan (Android < 12)     |
| Min RAM           | 1 GB                                     |

---

## 3. User Classes & Characteristics

### 3.1 Professor
- Opens app on their Android phone
- Enters session name and optional duration
- Expects to see students' names appear in real time
- May export the list to CSV after the session
- **Technical proficiency**: low — must be zero-config

### 3.2 Student
- Opens app on their own Android phone
- Enters their full name and the session name given by the professor
- Taps Scan — app handles everything else
- Expects instant confirmation ("Attendance sent to professor!")
- **Technical proficiency**: very low

### 3.3 No Admin / No Backend User
Version 1.0 has no server, no admin panel, no accounts. Both roles are
self-contained on the device.

---

## 4. Functional Requirements

### 4.1 App Launch & Role Selection

| ID    | Requirement |
|-------|-------------|
| FR-01 | The app MUST present two role options on launch: **Professor** and **Student**. |
| FR-02 | Before navigating to either role, the app MUST verify BLE hardware is present. If not, display a non-dismissible error dialog. |
| FR-03 | The app MUST request all required runtime permissions before starting any BLE operation. |
| FR-04 | If any permission is denied, the app MUST explain which permission is needed and why, and offer a button to open System Settings. |
| FR-05 | If Bluetooth is off, the app MUST prompt the user to enable it via Android's native enable-BT intent before proceeding. |

---

### 4.2 Professor — Session Management

| ID    | Requirement |
|-------|-------------|
| FR-06 | The professor MUST enter a session name (e.g. "CS101") before starting. |
| FR-07 | The session ID MUST be derived from the session name by removing spaces and lowercasing (e.g. "CS 101" → "cs101"). This is the value students enter. |
| FR-08 | The professor MAY enter a custom session duration in whole minutes (1–180). |
| FR-09 | If no duration is entered, the system MUST default to **5 minutes** and display a tip: "5 minutes is the recommended duration". |
| FR-10 | Once started, the UI MUST prominently display the session ID so the professor can read it to students (e.g. "Tell students to enter: cs101"). |
| FR-11 | The professor MUST be able to manually stop a session at any time via a Stop button. |
| FR-12 | The session MUST automatically stop when the timer reaches 0:00. |
| FR-13 | After a session stops (manually or auto), students MUST NOT be able to mark attendance for that session anymore. |

---

### 4.3 Professor — BLE Advertising (Session Beacon)

| ID    | Requirement |
|-------|-------------|
| FR-14 | When a session starts, the professor's device MUST begin BLE advertising using service UUID `0000ABCD-0000-1000-8000-00805F9B34FB`. |
| FR-15 | The advertisement MUST use LOW_LATENCY mode and HIGH TX power to maximise student detection range. |
| FR-16 | The advertisement MUST NOT include the device name for privacy. |
| FR-17 | If advertising fails to start, the app MUST display the exact failure reason in a dialog and NOT mark the session as started. |
| FR-18 | When the session stops, BLE advertising MUST stop immediately. |

---

### 4.4 Professor — Student Detection (Name Scanner)

| ID    | Requirement |
|-------|-------------|
| FR-19 | While a session is active, the professor's device MUST simultaneously scan for student name beacons (UUID `0000DCBA-0000-1000-8000-00805F9B34FB`). |
| FR-20 | The scanner MUST use `CALLBACK_TYPE_FIRST_MATCH` to fire exactly once per unique student device, not on every advertisement packet. |
| FR-21 | When a student beacon is detected, the app MUST extract the student name from the manufacturer-specific data field (company ID `0xFFFF`). |
| FR-22 | The extracted name MUST be saved to the local SQLite database under the active session ID. |
| FR-23 | If the same student (same derived device ID) attempts to mark again, the duplicate MUST be silently rejected — no crash, no error to the professor. |
| FR-24 | When scanning stops (session ended), no new student detections MUST be processed. |

---

### 4.5 Professor — Real-Time Attendance List

| ID    | Requirement |
|-------|-------------|
| FR-25 | The attendance list MUST update in real time as each student is detected — no need to stop the session to see names. |
| FR-26 | Each list entry MUST show: sequential number, student name, and timestamp (format: `dd MMM yyyy, hh:mm a`). Example: `1. Yash  •  24 Mar 2026, 03:30 PM`. |
| FR-27 | A count label MUST show the number of students present (e.g. "3 present"). |
| FR-28 | The list MUST be scrollable when it exceeds the screen height. |
| FR-29 | After stopping, the final list MUST remain visible for the professor to review. |

---

### 4.6 Professor — Export

| ID    | Requirement |
|-------|-------------|
| FR-30 | The professor MUST be able to export attendance to a CSV file at any time (during or after a session). |
| FR-31 | The CSV MUST be saved to the device's Downloads folder as `attendance_[sessionId].csv`. |
| FR-32 | The CSV MUST contain a header row: `Student Name, Timestamp`. |
| FR-33 | If there are no records, the app MUST show a message: "No attendance records to export." |

---

### 4.7 Student — Check-In Flow

| ID    | Requirement |
|-------|-------------|
| FR-34 | The student MUST enter their full name before scanning. |
| FR-35 | The student MUST enter the session name exactly as told by the professor (case-insensitive). |
| FR-36 | If either field is empty when Scan is tapped, the app MUST show an inline field-level error on the empty field. |
| FR-37 | The Scan button MUST be debounced — tapping it multiple times while scanning is already in progress MUST have no effect. |

---

### 4.8 Student — BLE Proximity Detection

| ID    | Requirement |
|-------|-------------|
| FR-38 | The student's app MUST scan for the professor's session beacon (UUID `0000ABCD-0000-1000-8000-00805F9B34FB`). |
| FR-39 | The app MUST display a live signal strength bar that updates continuously while scanning. |
| FR-40 | The signal bar MUST be colour-coded and labelled per this table: |

| RSSI Range     | Colour | Label                              |
|----------------|--------|------------------------------------|
| >= -55 dBm     | Green  | Excellent — you're in range!       |
| -56 to -65 dBm | Green  | Good signal — hold still           |
| -66 to -75 dBm | Orange | Weak — try moving slightly closer  |
| < -75 dBm      | Red    | Too far — move closer to professor |

| ID    | Requirement |
|-------|-------------|
| FR-41 | The numeric RSSI value and threshold MUST be displayed below the bar for transparency (`Signal: -68 dBm  (need >= -75)`). |
| FR-42 | The signal section MUST be hidden until the student taps Scan (clean initial UI). |

---

### 4.9 Student — Name Beacon (Data Transmission)

| ID    | Requirement |
|-------|-------------|
| FR-43 | When RSSI >= -75 dBm, the student's app MUST start BLE advertising with UUID `0000DCBA-0000-1000-8000-00805F9B34FB`. |
| FR-44 | The advertisement payload MUST encode the student's name in manufacturer-specific data (company ID `0xFFFF`) as UTF-8 bytes. |
| FR-45 | Names longer than 20 bytes (UTF-8 encoded) MUST be safely truncated to 20 bytes. |
| FR-46 | The beacon MUST advertise for a minimum of **60 seconds** after being triggered, to give the professor's scanner time to pick it up. |
| FR-47 | After 60 seconds, the beacon MUST stop automatically to preserve battery. |
| FR-48 | If BLE advertising is not supported on the student's device, the app MUST display a graceful fallback message rather than crashing. |

---

### 4.10 Student — Confirmation

| ID    | Requirement |
|-------|-------------|
| FR-49 | Once the beacon is successfully started, the status MUST update to: "Attendance sent to professor!". |
| FR-50 | The Scan button MUST change to "Marked Present" and be disabled — the student cannot scan again. |
| FR-51 | A Toast MUST appear: "[Name] marked present for [session]!". |

---

### 4.11 Session Timer

| ID    | Requirement |
|-------|-------------|
| FR-52 | A live MM:SS countdown MUST be displayed while the session is active. |
| FR-53 | At 0:00, the session MUST auto-stop, BLE advertising and scanning MUST stop, and a Toast MUST say "Session ended automatically. X student(s) marked present." |

---

## 5. Non-Functional Requirements

### 5.1 Performance

| ID     | Requirement |
|--------|-------------|
| NFR-01 | Student name MUST appear on the professor's list within **5 seconds** of the student entering BLE range. |
| NFR-02 | The UI MUST remain responsive at all times — BLE operations MUST run on background threads, never the main/UI thread. |
| NFR-03 | The app MUST NOT introduce noticeable battery drain when idle (no BLE operations running). |
| NFR-04 | BLE scanning and advertising MUST use the minimum power mode necessary (LOW_LATENCY only during active sessions). |

### 5.2 Reliability

| ID     | Requirement |
|--------|-------------|
| NFR-05 | The app MUST NOT crash when Bluetooth is turned off mid-session. All BLE operations MUST be wrapped in try-catch for `IllegalStateException`. |
| NFR-06 | The app MUST NOT crash when the user navigates away mid-session. `onDestroy` MUST stop all BLE operations. |
| NFR-07 | Database writes MUST use `CONFLICT_IGNORE` to handle race conditions — duplicate inserts MUST fail silently, never throw. |
| NFR-08 | A student MUST be able to mark attendance even if they miss the first scan cycle — the scanner retries continuously until stopped. |

### 5.3 Usability

| ID     | Requirement |
|--------|-------------|
| NFR-09 | A first-time user MUST be able to complete the full check-in flow (from app open to "Marked Present") in under **60 seconds**. |
| NFR-10 | All error messages MUST be written in plain English — no stack traces, no error codes shown to users. |
| NFR-11 | All buttons MUST have white text on dark backgrounds for visibility. |
| NFR-12 | The signal strength bar MUST give clear directional guidance ("move closer" vs "hold still") — not just a number. |

### 5.4 Security

| ID     | Requirement |
|--------|-------------|
| NFR-13 | A student MUST NOT be able to mark attendance more than once per session from the same device. This MUST be enforced at the **database level** (UNIQUE INDEX) not just in code. |
| NFR-14 | Students outside BLE range (RSSI < -75 dBm) MUST NOT be able to mark attendance regardless of what they type. |
| NFR-15 | Session IDs MUST be derived from the class name only — no random tokens that could be guessed or shared externally. |
| NFR-16 | Keystore files MUST NEVER be committed to version control. `.gitignore` MUST exclude `*.keystore`, `*.jks`. |
| NFR-17 | The app MUST NOT request permissions it does not use. |

### 5.5 Maintainability

| ID     | Requirement |
|--------|-------------|
| NFR-18 | BLE logic MUST be isolated in dedicated classes (`BleAdvertiser`, `BleScanner`, `StudentBeacon`, `ProfessorScanner`) — Activities MUST NOT contain BLE code directly. |
| NFR-19 | Database access MUST be isolated in `AttendanceDB`. No raw SQL anywhere else. |
| NFR-20 | Each class MUST have a single responsibility (SRP). |

---

## 6. External Interface Requirements

### 6.1 User Interface
- Material Design components (AppCompat)
- Minimum touch target: 48dp × 48dp (Android accessibility guideline)
- All buttons: explicit white text (`android:textColor="#FFFFFF"`)
- Status messages use colour semantics: green = success, orange = warning, red = error, grey = idle

### 6.2 Hardware Interfaces

| Interface       | Requirement                                            |
|-----------------|--------------------------------------------------------|
| Bluetooth radio | BLE 4.0+ mandatory. App declares `android.hardware.bluetooth_le required=true`. |
| GPS/Location    | Location hardware may be required on Android < 12 for BLE scanning. |
| Storage         | ~5 MB for app + database. CSV export writes to external Downloads. |

### 6.3 Software Interfaces

| Interface         | Detail                                         |
|-------------------|------------------------------------------------|
| Android BLE API   | `android.bluetooth.le.*` (API 21+)            |
| SQLite            | Via `android.database.sqlite.SQLiteOpenHelper` |
| Android Keystore  | Used for APK signing (release builds only)     |
| GitHub Actions    | CI/CD — builds and publishes signed APK        |

### 6.4 Communication Interfaces
- No network calls. No HTTP. No sockets.
- All communication via Bluetooth Low Energy (2.4 GHz radio band).
- BLE advertisement packet: max 31 bytes per packet.

---

## 7. Data Requirements

### 7.1 Database Schema

**Database:** `attendance.db` (SQLite, version 2)

```sql
CREATE TABLE attendance (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    student_name TEXT    NOT NULL,
    device_id    TEXT    NOT NULL,   -- ANDROID_ID or beacon-derived ID
    timestamp    TEXT    NOT NULL,   -- format: "dd MMM yyyy, hh:mm a"
    session_id   TEXT    NOT NULL    -- normalised class name e.g. "cs101"
);

CREATE UNIQUE INDEX idx_device_session
    ON attendance(device_id, session_id);
-- Enforces: one attendance record per device per session
```

### 7.2 Data Displayed to Professor

```
1. Yash Kumar  •  24 Mar 2026, 03:30 PM
2. Priya Shah  •  24 Mar 2026, 03:31 PM
3. Rohan Mehta •  24 Mar 2026, 03:31 PM
```

### 7.3 CSV Export Format

```
Student Name, Timestamp
1. Yash Kumar  •  24 Mar 2026, 03:30 PM
2. Priya Shah  •  24 Mar 2026, 03:31 PM
```

Saved to: `Downloads/attendance_cs101.csv`

### 7.4 Data Retention
- All data stored locally on the professor's device only.
- No data leaves the device.
- No automatic deletion — data persists until the user uninstalls the app.

---

## 8. Security Requirements

### 8.1 Threat Model

| Threat                    | Attack                                    | Mitigation                                      |
|---------------------------|-------------------------------------------|-------------------------------------------------|
| Proxy attendance          | Student outside room shares PIN/QR        | BLE range enforced by RSSI threshold (-75 dBm) |
| Double marking            | Student marks twice from same device      | UNIQUE INDEX on (device_id, session_id)         |
| Fake session join         | Student guesses session ID                | Must be in BLE range regardless of session name |
| BLE replay attack         | Student replays recorded BLE packets      | FIRST_MATCH callback ignores re-broadcasts      |
| Key exposure              | Keystore committed to GitHub              | .gitignore excludes *.keystore, *.jks           |

### 8.2 Permission Justification

| Permission             | Why it is needed                                          |
|------------------------|-----------------------------------------------------------|
| BLUETOOTH_SCAN         | Student scans for professor beacon; professor scans for students |
| BLUETOOTH_ADVERTISE    | Professor advertises session; student advertises name     |
| BLUETOOTH_CONNECT      | Required by Android 12+ BLE API even for non-connected BLE |
| ACCESS_FINE_LOCATION   | Required by Android for BLE scanning (OS-level requirement) |

---

## 9. System Constraints & Limitations

| Constraint                 | Detail                                                              |
|----------------------------|---------------------------------------------------------------------|
| BLE range                  | Practical indoor range: 5–15 metres depending on walls/obstacles    |
| Name payload limit         | Student names > 20 bytes are truncated in BLE packet                |
| Simultaneous students      | Tested up to ~30 students scanning at once; untested beyond that    |
| Advertising support        | ~5% of low-end Android devices do not support BLE advertising — student fallback message shown |
| No iOS support             | Apple restricts background BLE advertising — iOS out of scope v1.0  |
| Offline only               | No cloud backup, no cross-device sync in v1.0                       |
| Single active session      | Professor can only run one session at a time per device             |

---

## 10. Assumptions & Dependencies

| # | Assumption |
|---|------------|
| 1 | Both professor and student devices have Bluetooth hardware that supports BLE. |
| 2 | Both devices are running Android 8.0 (API 26) or above. |
| 3 | The professor verbally communicates the session name (e.g. "CS101") to students. |
| 4 | Physical proximity (same room) is a sufficient proxy for "present in class". |
| 5 | Students will not lend their phone to someone outside the room to mark attendance on their behalf. |
| 6 | RSSI -75 dBm is an acceptable threshold for "in room" — may need tuning per venue. |

---

## 11. Out of Scope (v1.0)

The following are explicitly NOT part of v1.0:

| Feature                   | Reason deferred                            |
|---------------------------|--------------------------------------------|
| User accounts / login     | No backend in v1.0                         |
| Cloud sync / Firebase     | Adds complexity; offline is sufficient     |
| Session history           | Single-session view only                   |
| Admin dashboard           | No multi-professor management              |
| iOS support               | BLE advertising restricted on iOS          |
| Biometric verification    | Overkill for v1.0                          |
| Student app confirmation  | No two-way data ACK in BLE advertisement   |
| WRITE_EXTERNAL_STORAGE    | CSV uses MediaStore API on API 29+         |

---

## 12. Appendix

### A. BLE UUID Reference

| UUID                                   | Used by           | Purpose                          |
|----------------------------------------|-------------------|----------------------------------|
| `0000ABCD-0000-1000-8000-00805F9B34FB` | Professor         | Session presence beacon          |
| `0000DCBA-0000-1000-8000-00805F9B34FB` | Student           | Student name broadcast           |

Both are short-form Bluetooth Base UUIDs (16-bit: `0xABCD` and `0xDCBA`).
Android compresses these to 4-byte form in the advertisement packet, saving space.

### B. RSSI Reference Table

| RSSI      | Approx. Distance (open space) | Signal Quality | UI Guidance                        |
|-----------|-------------------------------|----------------|------------------------------------|
| >= -55    | 0–3 metres                    | Excellent       | "Excellent — you're in range!"     |
| -56 to -65| 3–7 metres                    | Good            | "Good signal — hold still"         |
| -66 to -75| 7–12 metres                   | Weak            | "Weak — try moving slightly closer"|
| < -75     | > 12 metres                   | Out of range    | "Too far — move closer to professor"|

Note: RSSI values vary significantly with walls, interference, and device hardware.
The -75 threshold is a starting point and should be calibrated per deployment venue.

### C. BLE Advertisement Packet Budget (Student Beacon)

```
Total available:           31 bytes
─────────────────────────────────────
Flags AD:                   3 bytes  (mandatory)
16-bit Service UUID list:   4 bytes  (type + length + 0xDCBA)
Manufacturer data header:   4 bytes  (type + length + company ID 0xFFFF)
─────────────────────────────────────
Remaining for student name: 20 bytes
```

20 bytes = 20 ASCII characters = sufficient for most names.
Non-ASCII (e.g. Hindi) characters use 2–3 bytes each; names are truncated at byte 20.

### D. Project File Structure

```
DigitalSphere/
├── app/src/main/java/com/example/digitalsphere/
│   ├── MainActivity.java        — role selection + permission checks
│   ├── ProfessorActivity.java   — session management UI + logic
│   ├── StudentActivity.java     — check-in UI + logic
│   ├── BleAdvertiser.java       — professor BLE beacon (UUID 0xABCD)
│   ├── BleScanner.java          — student proximity scanner
│   ├── StudentBeacon.java       — student name broadcaster (UUID 0xDCBA)
│   ├── ProfessorScanner.java    — professor name receiver
│   ├── AttendanceDB.java        — SQLite data layer
│   └── CsvExporter.java         — CSV export to Downloads
├── app/src/main/res/layout/
│   ├── activity_main.xml
│   ├── activity_professor.xml
│   └── activity_student.xml
├── app/src/main/AndroidManifest.xml
├── .github/workflows/build.yml  — CI/CD: auto-build & release APK
├── .gitignore
└── SRS.md                       — this document
```

---

*End of SRS — DigitalSphere v1.0*
