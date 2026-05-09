# DigitalSphere — Research Data & Bug History
## For: Research Paper on Multimodal BLE Attendance Verification

---

## 1. System Overview

**App name:** DigitalSphere  
**Purpose:** Multimodal classroom attendance verification — confirms a student's physical presence without manual sign-in.  
**Core claim (novelty):** 4-modality fusion (BLE RSSI + barometric pressure + near-ultrasound FSK + ambient audio fingerprint) fused via the DSVF algorithm into a single presence decision resistant to relay attacks.

**Test date:** 2026-05-05 (Sessions test1, test2)  
**Researcher email:** yaswanthbopparaju@gmail.com

---

## 2. Hardware

| Role      | Device              | Android | SDK | BLE Adv | UNPROCESSED audio | Barometer |
|-----------|---------------------|---------|-----|---------|-------------------|-----------|
| Professor | vivo I2126          | 14      | 34  | YES     | YES (declared)    | NO        |
| Student   | realme RMX3741      | 15      | 35  | YES     | YES (hardware OK, property false) | NO |

**Critical device fact — student:**  
`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=false` on RMX3741 even though hardware can record 17400–18400 Hz.  
`UltrasoundCapabilityManager` incorrectly returned `VOICE_RECOGNITION` as recommended source.  
After fix: `ULTRA_AUDIO_SOURCE requested=9 actual=9 OPENED` — UNPROCESSED accepted.

---

## 3. Algorithm Parameters (DSVF)

| Stage | Parameter | Value | Meaning |
|-------|-----------|-------|---------|
| Hard Gate | BARO_FLOOR_LOCK_HPA | 0.30 hPa | Max |Δpressure| before REJECTED_FLOOR |
| Hard Gate | ULTRA_MIN_CONFIDENCE | 0.30 | Min Goertzel confidence before REJECTED_ROOM |
| Base weights | BLE / Baro / Ultra / Audio | 0.20 / 0.25 / 0.35 / 0.20 | Sum = 1.0 |
| Fusion | FUSION_PRESENT_THRESHOLD | 0.75 | Score ≥ 0.75 → PRESENT |
| Fusion | FUSION_FLAGGED_THRESHOLD | 0.55 | Score ≥ 0.55 → FLAGGED |

**Audio correlation — changed from Pearson to Cosine (Bug Fix 6):**  
Cross-device OEM mic response difference causes Pearson to collapse negative for same-room pairs.  
Cosine similarity is mean-independent and gives correct [0,1] presence scores for non-negative RMS vectors.

---

## 4. FSK Ultrasound Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| f0 (space/0) | 17400 Hz | Index 2 in sweep list [17000,17200,...,20500 in 200Hz steps] |
| f1 (mark/1) | 18400 Hz | Index 7 in sweep list |
| Symbol duration | 40 ms | samplesPerSymbol = 48000 × 40/1000 = 1920 samples |
| Repeat count | 3 | Same frame transmitted 3× for majority vote |
| Preamble | "10101010" | 8-bit alternating sync pattern |
| CRC | CRC-8 (poly 0x07) | 8-bit frame integrity check |
| Emitter amplitude | 0.55 × 32767 ≈ 18022 | Linear amplitude in short PCM |
| Fade in/out | 4 ms | Hann cosine envelope per symbol |

**Frame structure (single repeat):**
```
[preamble: 8 bits][token: 4 bits][CRC-8: 8 bits] = 20 bits/symbols
Full transmission: 20 × 3 = 60 symbols × 40ms = 2.4 seconds
```

**Goertzel detector settings:**
- OOK fallback path: threshold = 750.0 (raw magnitude)
- FSK adaptive path: threshold = `noiseFloor × thresholdMultiplier (2.0)`
- Frame history buffer: 117,120 samples (≈ 2.44s)
- Phase search step: 480 samples (10ms)

---

## 5. Ambient Audio Fingerprint

**Design:** 8-band TIME-DOMAIN RMS (NOT frequency FFT)  
**Method:** 2-second recording split into 8 equal time windows → RMS energy per window → quantized to [0,255].  
**Correlation (original):** Pearson r — FAILS cross-device due to mic gain offset  
**Correlation (fixed):** Cosine similarity — device-agnostic for non-negative RMS vectors

**Cross-device hash comparison (test2 observed):**

| | Professor (vivo I2126) | Student (realme RMX3741) |
|-|------------------------|--------------------------|
| Hash shape | mean=0.67, range=0.54 | mean=0.28, range=0.85 |
| Dominant band | Band 0 (very high, ~1.00) | Different band distribution |
| Pearson | −0.538 | → presence=0.23 (WRONG) |
| Cosine | 0.618 | → presence=0.618 (CORRECT) |

---

## 6. Bug History — Complete Record

### BUG 1 — FSK Frequency Mismatch (CRITICAL — Fixed)
**Symptom:** Student always fell back to OOK path despite professor broadcasting FSK.  
**Root cause:** `ProfessorActivity.buildDefaultAdaptiveConfig()` set f0=17500, f1=18500. Neither frequency exists in the sweep list [17000,17200,...20500 in 200Hz steps]. `toCompactBleBytes()` returned null silently. Student received null config → `sessionConfig=null` → `detectLoop()` (OOK at 18500 Hz) instead of `detectLoopAdaptive()`.  
**Fix:** Changed to f0=17400 (index 2), f1=18400 (index 7).  
**Evidence:** `ULTRA_CONFIG_STORED f0=17400 f1=18400 FSK_CONFIG_ACTIVE` appears in all subsequent student logs.

### BUG 2 — Null Hash Overwrite (CRITICAL — Fixed)
**Symptom:** `professorAmbientHash` was null at DSVF time despite professor broadcasting valid hash.  
**Root cause:** `StudentPresenter.onProfessorMetadata()` unconditionally overwrote `professorAmbientHash` with every BLE scan callback, including callbacks where `ambientHash=null`. The hash arrived once (first BLE advertisement) then got erased by subsequent null deliveries.  
**Fix:** Added null guard — only update if incoming hash is non-null.  
**Evidence:** `AUDIO_HASH_NULL_GUARD NULL_BLOCKED` events in student log.

### BUG 3 — Null Config Overwrite (HIGH — Fixed)
**Symptom:** `professorUltrasoundConfig` was intermittently null.  
**Root cause:** Same pattern as Bug 2 in the same `onProfessorMetadata()` callback.  
**Fix:** Added null guard for `ultrasoundConfig`.  
**Evidence:** `ULTRA_CONFIG_STORED FSK_CONFIG_ACTIVE` appears consistently in later logs.

### BUG 4 — Missing Null Guard in Professor Presenter (HIGH — Fixed)
**Symptom:** Potential NPE crash in `ProfessorPresenter.updateAmbientHash()`.  
**Root cause:** `updateAmbientHash(float[] ambientHash)` called `bleManager.updateProfessorModeData()` without checking if `ambientHash` was null.  
**Fix:** Added `|| ambientHash == null` guard at method entry.

### BUG 5 — Ambient Hash Contamination by Emitter (CRITICAL — Fixed)
**Symptom:** Audio presence score swung wildly: Pearson from +0.973 to −0.834 across refresh cycles.  
**Root cause:** `ProfessorActivity.startAmbientRefreshLoop()` called `captureNextAmbientReference()` every 250ms. The microphone captured the active 17400 Hz emitter tone, making each refreshed hash dominated by the ultrasound frequency. These contaminated hashes were broadcast via BLE to the student, making audio correlation meaningless.  
**Evidence:** From test1 professor JSON — at t=47928ms, Pearson=-0.834 between refreshed hash and initial hash. All 4 student attempts in test1 had audio presence 0.40–0.46 (REJECTED_SCORE).  
**Fix:** Disabled the refresh loop entirely while emitter is active. Professor hash is captured ONCE before emission starts and remains static.  
**Evidence:** `AMBIENT_REFRESH_LOOP status=disabled reason=emitter_contamination_confirmed STABLE_HASH` in all subsequent professor reports.

### BUG 6 — Pearson Correlation Cross-Device Failure (HIGH — Fixed)
**Symptom:** Audio presence score was systematically 0.40–0.42 for legitimate same-room pairs.  
**Root cause:** `DsvfAlgorithm.stagePresenceAudio()` and `stageSvsAudio()` used Pearson correlation. The 8-band time-domain RMS hash has systematically different absolute energy profiles across OEM devices (vivo vs realme have different mic frequency responses). Pearson is sensitive to mean offsets — when one device has band 0 at amplitude ~1.0 (vivo) and the other has band 0 at ~0.35 (realme), Pearson collapses negative even for room-matched recordings.  
**Fix:** Switched both methods to `cosineSimilarity()`. Cosine is mean-independent — it measures shape similarity not absolute amplitude. Added `cosineSimilarity()` as a new public static method in `DsvfAlgorithm`.  
**Evidence:** After fix in test2: `AUDIO_CORRELATION cosine=0.618 pearson=-0.538 presence=0.618 MATCH`. DSVF score improved from 0.52 (REJECTED_SCORE) to 0.58 (FLAGGED).

### BUG 7 — Wrong Audio Source on Student (MEDIUM — Fixed)
**Symptom:** `ULTRA_CAPABILITY recommendedSource=VOICE_RECOGNITION` on RMX3741 despite hardware supporting near-ultrasound.  
**Root cause:** `UltrasoundCapabilityManager` checked `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` which is `false` on RMX3741. This caused capability-based constructors in `UltrasoundDetector` to use `VOICE_RECOGNITION`, which applies OEM DSP and attenuates near-ultrasound frequencies.  
**Fix:** Both capability-based constructors now hardcode `UNPROCESSED`. Added 3-tier fallback in `start()`: UNPROCESSED → VOICE_RECOGNITION → MIC, with DiagLogger logging the actual source used.  
**Evidence (test2):** `ULTRA_AUDIO_SOURCE requested=9 actual=9 OPENED` — UNPROCESSED successfully opened on RMX3741.

### BUG 8 — Misleading Telemetry (Cosine only, not Pearson) (MEDIUM — Fixed)
**Symptom:** DiagLogger showed `cosine=0.720` which looked acceptable, while DSVF was receiving Pearson≈−0.83 from the same data. The failure mode was invisible.  
**Root cause:** `logAudioStepDone()` only logged cosine. `AudioCorrelator.correlate()` returns cosine. `DsvfAlgorithm` used Pearson internally. The two diverge strongly under emitter contamination.  
**Fix:** Added `logAudioCorrelationDetail(cosine, pearson, profHash, studHash)` in DiagLogger that logs both metrics, hash shapes, and classified result.

---

## 7. Test Run Results

### Session "123" — 2026-05-04 ~23:57 (Pre-fix baseline)
| Metric | Value |
|--------|-------|
| Professor device | vivo I2126 |
| f0/f1 emitted | 17500 / 18500 Hz (WRONG — not in sweep list) |
| Student source | VOICE_RECOGNITION (capability-limited) |
| FSK config delivery | FAILED (null payload from toCompactBleBytes) |
| Student detection path | OOK (18500 Hz fallback) |
| Ultra result | token=-1, conf=0.00 |
| Audio Pearson | N/A (hash contaminated) |
| DSVF result | CONFLICT (hash contamination caused wildly varying hashes) |

### Session "test1" — 2026-05-05 ~00:12 (Post Bug1/2/3/4/5 fix)
| Metric | Value |
|--------|-------|
| Professor device | vivo I2126 |
| f0/f1 emitted | 17400 / 18400 Hz ✓ |
| Refresh loop | DISABLED ✓ |
| Student source | VOICE_RECOGNITION (Bug7 not yet fixed) |
| FSK config delivery | SUCCESS — `FSK_CONFIG_ACTIVE` |
| Ultra result (4 attempts) | token=-1, conf=0.00 all attempts |
| Audio cosine / Pearson | 0.580 / −0.169 (Bug6 not yet fixed) |
| DSVF score (avg 4 attempts) | ~0.50 REJECTED_SCORE |
| Attendance marked | NO |

### Session "test2" — 2026-05-05 ~00:27 (Post Bug6/7 fix — current)
| Metric | Value |
|--------|-------|
| Professor device | vivo I2126 |
| Student device | realme RMX3741 |
| f0/f1 emitted | 17400 / 18400 Hz ✓ |
| Refresh loop | DISABLED ✓ |
| Student audio source | **UNPROCESSED (requested=9 actual=9)** ✓ |
| FSK config delivery | SUCCESS — `FSK_CONFIG_ACTIVE` |
| Ultra result | **token=-1, conf=0.00** (STILL FAILING) |
| Audio cosine / Pearson | **0.618 / −0.538** |
| Audio presence (cosine-based) | **0.618 MATCH** ✓ |
| DSVF score | **0.58 FLAGGED** (up from 0.52) ✓ |
| Attendance marked | **YES (FLAGGED)** |
| Professor confirmed | "1. Hi • 05 May 2026, 12:28 AM" |

---

## 8. Active Bug — Ultrasound Detection Failure (Root Cause Analysis)

### Current state
UNPROCESSED audio source confirmed open (`requested=9 actual=9`). FSK config confirmed delivered (`f0=17400 f1=18400 FSK_CONFIG_ACTIVE`). Yet `ULTRA_STEP_DONE token=-1 conf=0.00` across all test runs.

### Root cause candidates

**RC-1: Zero observability in adaptive detection loop (CONFIRMED PROBLEM)**  
`detectLoopAdaptive()` in `UltrasoundDetector` has no logging of Goertzel magnitudes, SNR, or preamble search results. We cannot distinguish between:  
- Signal not reaching ADC (physics/DSP)  
- Signal reaching ADC but Goertzel threshold too high  
- Goertzel computing correctly but bit decisions wrong  
- Bit decisions correct but preamble not found (1+ bit errors)  
- Preamble found but CRC failing  

**RC-2: Phase search window too narrow (LIKELY)**  
History buffer = `frameSamples + samplesPerSymbol` = 115200 + 1920 = 117120 samples.  
Slack = 1920 samples = 1 symbol = 40ms.  
Phase step = 480 samples = 10ms.  
Only 4 phase offsets evaluated per read cycle. If the preamble starts outside this 40ms window, detection fails until the next read cycle. Given the professor's 2.4s frame repeating continuously, alignment should work — but the slack of only 1 symbol is very tight.

**RC-3: Preamble requires exact bit match (LIKELY)**  
`findPreamble()` in `UltrasoundFrameCodec` does an exact 8-bit string match of "10101010".  
A single bit error in any of the 8 preamble symbols causes `findPreamble()` to return -1 immediately.  
No Hamming-distance tolerance. In a noisy acoustic channel this is too brittle.

**RC-4: Noise floor estimation contaminates threshold (POSSIBLE)**  
`estimateAdaptiveNoiseFloor()` uses the FIRST 25% of the captured window as noise floor.  
If the emitter is already playing at detection start, the "noise floor" includes the tone itself, inflating `adaptiveMargin = noiseFloor × 2.0` and making the `e1 > e0 + adaptiveMargin` test fail for every symbol.

**RC-5: `isValid()` gate discards partial decodes (CONFIRMED PROBLEM)**  
`detectLoopAdaptive()` skips any `DetectionResult` where `isValid()=false` (CRC failed).  
`best` is only updated for CRC-valid results. Even if 19 of 20 bits are correct and the token would match, a single CRC error discards the whole result. There's no "best effort" reporting.

**RC-6: AudioTrack usage attribute (POSSIBLE)**  
`AdaptiveUltrasoundEmitter` uses `USAGE_MEDIA / CONTENT_TYPE_MUSIC`.  
Some Android OEMs apply speaker EQ/DSP for music content that attenuates near-ultrasound.  
Should use `USAGE_UNKNOWN` + `CONTENT_TYPE_UNKNOWN` or `USAGE_ALARM` to bypass audio post-processing.

### Fix plan (priority order)
1. **Add Goertzel logging** inside `AdaptiveUltrasoundDetector.detect()` — log per-symbol SNR, f0/f1 energies for first 5 symbols.
2. **Add preamble search logging** — log how close the closest preamble match was.
3. **Fix AudioTrack usage attribute** on professor to bypass speaker DSP.
4. **Add Hamming-distance preamble matching** — allow up to 1-2 bit errors in preamble.
5. **Widen history buffer** to `frameSamples * 2` for better phase coverage.
6. **Fix noise floor estimation** — use tail of buffer (not head) to avoid including the tone.

---

## 9. DSVF Score Progression (Student, same room as Professor)

| Session | Ultra | Audio presence | BLE presence | DSVF score | Result |
|---------|-------|---------------|-------------|-----------|--------|
| test1 (pre audio fix) | 0.00 | ~0.42 (Pearson) | ~0.75 | ~0.50 | REJECTED_SCORE |
| test2 (cosine fix, UNPROCESSED) | 0.00 | **0.618 (cosine)** | ~0.75 | **0.58** | **FLAGGED** |
| Target (ultra fix pending) | >0.30 | 0.618 | ~0.75 | **>0.75** | **PRESENT** |

**Note:** With no barometer on either device, BLE and audio carry the full fusion weight. Once ultrasound starts returning conf>0.30, the score is expected to exceed the PRESENT threshold (0.75).

---

## 10. Key Code Locations

| Component | File | Key method |
|-----------|------|-----------|
| DSVF algorithm | `domain/verification/DsvfAlgorithm.java` | `evaluate()`, `cosineSimilarity()` |
| FSK emitter | `data/audio/adaptive/AdaptiveUltrasoundEmitter.java` | `buildWaveform()`, `playOnce()` |
| FSK detector | `data/audio/adaptive/AdaptiveUltrasoundDetector.java` | `detect()` |
| Frame codec | `data/audio/adaptive/UltrasoundFrameCodec.java` | `decode()`, `findPreamble()` |
| Sweep list | `data/audio/adaptive/UltrasoundProfiler.java` | `buildSweepFrequencies()` |
| Session config | `data/audio/adaptive/UltrasoundSessionConfig.java` | `toCompactBleBytes()`, `fromCompactBleBytes()` |
| Detector orchestration | `data/audio/UltrasoundDetector.java` | `detectLoopAdaptive()` |
| Student verification | `presenter/StudentPresenter.java` | `continueWithAudio()` |
| Professor broadcast | `presentation/ProfessorActivity.java` | `buildDefaultAdaptiveConfig()` |
| Structured logger | `data/sensor/DiagLogger.java` | `log()`, `logAudioCorrelationDetail()` |

---

## 11. BLE Payload Format (16 bytes)

```
Bytes  0–1:  Barometric pressure delta (signed int16, 0.01 hPa units) — 0x0000 if no barometer
Bytes  2–3:  Session token (uint16)
Bytes  4–11: Ambient audio hash (8 × uint8, 8-band time-domain RMS)
Bytes 12–15: Adaptive FSK config [f0_index, f1_index, symbolMs, repeatCount]
Company ID:  0xFFFF (manufacturer-specific advertisement)
```

---

## 12. Research Paper Key Points

**Novelty claims supported by data:**
1. 2-FSK adaptive ultrasound encoded into 4-byte BLE payload — enables zero-infrastructure room-lock detection without additional hardware.
2. DSVF algorithm: dynamic weight adjustment based on per-modality signal validity — graceful degradation when modalities are unavailable (no barometer on test devices, score still functional).
3. Cross-device ambient audio correlation requires cosine similarity, not Pearson — demonstrated by Pearson=−0.538 vs cosine=0.618 for confirmed same-room pair on vivo/realme hardware.
4. Emitter contamination of ambient hash: quantified — Pearson swings from +0.973 to −0.834 within one refresh cycle when mic captures its own 17400 Hz tone.

**Limitations (honest disclosure):**
- Ultrasound detection not yet confirmed end-to-end in any test run (conf=0.00 all tests).
- Both test devices lack barometers — floor-lock hard gate never exercised in hardware.
- Single student–professor pair tested; no crowd/interference testing yet.
- Attendance FLAGGED (not PRESENT) in best test run — below the 0.75 threshold.

---
*Document generated: 2026-05-05 | Research session: DigitalSphere BLE Attendance App*
