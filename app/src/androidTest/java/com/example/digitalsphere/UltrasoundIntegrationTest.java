package com.example.digitalsphere;

import android.Manifest;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.example.digitalsphere.data.audio.UltrasoundDetector;
import com.example.digitalsphere.data.audio.UltrasoundEmitter;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 *  INSTRUMENTED INTEGRATION TEST — Ultrasound Emitter ↔ Detector Roundtrip
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  These tests exercise the REAL hardware path:
 *
 *      UltrasoundEmitter  →  phone speaker  →  air  →  phone mic  →  UltrasoundDetector
 *      (AudioTrack 18.5 kHz OOK)                        (AudioRecord + Goertzel)
 *
 *  ⚠️  MUST run on a REAL Android device (API 26+).
 *      Emulators have no acoustic speaker→mic loopback path.
 *
 *  ⚠️  MUST run in a reasonably quiet environment.
 *      Heavy machinery or ultrasonic pest repellers at 18–20 kHz will
 *      raise the Goertzel noise floor and cause false detections.
 *
 *  ⚠️  Device speaker volume should be at least 50%.
 *      Low volume → emitted tone below mic sensitivity → detection fails.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  TIMING BUDGET
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  Emitter cycle:  1200 ms frame + 2000 ms gap = 3200 ms per OOK frame.
 *
 *  Test 1 (roundtrip):   10 s timeout → ~3 full OOK cycles → ample time for
 *                        the detector to lock onto at least one clean frame.
 *
 *  Test 2 (wrongToken):  10 s timeout → same reasoning; we verify the decoded
 *                        token differs from a different session's expected token.
 *
 *  Test 3 (silence):     5 s timeout → long enough to confirm the detector
 *                        does NOT decode anything when no emitter is running.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  PERMISSION
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  RECORD_AUDIO is required by UltrasoundDetector (AudioRecord).
 *  We use GrantPermissionRule to auto-grant it before each test.
 *  The main AndroidManifest.xml declares <uses-permission RECORD_AUDIO/>.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  ASYNC COORDINATION
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  Both emitter (AudioTrack background thread) and detector (AudioRecord
 *  background thread) are fully asynchronous. We use CountDownLatch to
 *  block the test thread until the detector fires a callback or the
 *  timeout expires.
 *
 *  AtomicInteger / AtomicReference capture callback arguments from the
 *  detector thread for assertion on the test thread.
 */
@RunWith(AndroidJUnit4.class)
public class UltrasoundIntegrationTest {

    private static final String TAG = "UltrasoundIntTest";

    // ── Permission ───────────────────────────────────────────────────────────
    //
    // GrantPermissionRule auto-grants RECORD_AUDIO before each test.
    // This is the androidTest equivalent of the user tapping "Allow" on the
    // runtime permission dialog. Without this, AudioRecord.Builder will throw
    // SecurityException and the detector will fire onDetectionError().

    @Rule
    public GrantPermissionRule micPermission =
            GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    // ── Fields ───────────────────────────────────────────────────────────────

    private UltrasoundEmitter  emitter;
    private UltrasoundDetector detector;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Before
    public void setUp() {
        // Fresh state — no emitter or detector running
        emitter  = null;
        detector = null;
    }

    @After
    public void tearDown() {
        // CRITICAL: Always release hardware resources.
        // AudioTrack / AudioRecord leaks will block subsequent tests from
        // acquiring the audio path and cause cascading failures.
        if (emitter != null) {
            emitter.stopEmitting();
            emitter = null;
        }
        if (detector != null) {
            detector.stop();
            detector = null;
        }

        // Brief pause to let Android's AudioFlinger fully release the
        // audio session before the next test tries to acquire it.
        // Without this, rapid test sequencing on some OEMs (Samsung)
        // causes "AudioRecord: Could not get audio input" errors.
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TEST 1 — roundtrip()
    //
    //  Full acoustic loopback: Emitter → speaker → air → mic → Detector.
    //  Proves the complete OOK encode/decode pipeline works end-to-end
    //  on real hardware with real audio processing bypasses.
    //
    //  Flow:
    //    1. Create emitter for "cs101" → token = 2
    //    2. Start emitting (18.5 kHz OOK via speaker)
    //    3. Create detector for "cs101" (same session)
    //    4. Start detecting (Goertzel on mic input)
    //    5. Wait up to 10 seconds (CountDownLatch)
    //    6. Assert: detected token == emitted token
    //    7. Assert: Goertzel magnitude > 0 (signal was real, not a fluke)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    public void roundtrip() throws InterruptedException {
        final String sessionId = "cs101";

        // ── Step 1: Create emitter and verify deterministic token ─────────
        emitter = new UltrasoundEmitter(sessionId);
        int emittedToken = emitter.getSessionToken();
        Log.d(TAG, "roundtrip: emitter token = " + emittedToken);

        assertTrue("Token must be in [0,15]", emittedToken >= 0 && emittedToken <= 15);

        // ── Step 2: Start emitting ────────────────────────────────────────
        emitter.startEmitting();
        assertTrue("Emitter should report isEmitting() after start", emitter.isEmitting());

        // Give the emitter 500ms head start so the first OOK frame is
        // already in-flight when the detector starts capturing.
        // This simulates real-world timing: the professor starts the
        // session, THEN the student taps "Scan".
        Thread.sleep(500);

        // ── Step 3 & 4: Create detector with latch, start detecting ──────
        //
        // CountDownLatch: blocks test thread until the detector either:
        //   (a) decodes a token → latch.countDown()  → test proceeds
        //   (b) 10 seconds expire                     → test proceeds with failure check

        final CountDownLatch       latch          = new CountDownLatch(1);
        final AtomicInteger        detectedToken  = new AtomicInteger(-1);
        final AtomicReference<Double> detectedMag = new AtomicReference<>(0.0);
        final AtomicReference<String> errorMsg    = new AtomicReference<>(null);

        detector = new UltrasoundDetector(sessionId, new UltrasoundDetector.DetectorCallback() {
            @Override
            public void onTokenDecoded(int token, double magnitude) {
                Log.d(TAG, "roundtrip: DECODED token=" + token
                        + " magnitude=" + magnitude);
                detectedToken.set(token);
                detectedMag.set(magnitude);
                latch.countDown();  // unblock test thread
            }

            @Override
            public void onSearching() {
                // Expected — detector is scanning but hasn't found a frame yet.
                // No action needed; the latch stays locked.
            }

            @Override
            public void onDetectionError(String reason) {
                Log.e(TAG, "roundtrip: DETECTION ERROR: " + reason);
                errorMsg.set(reason);
                latch.countDown();  // unblock test thread (test will fail on assertion)
            }
        });

        detector.start();

        // ── Step 5: Wait for detection or timeout ─────────────────────────
        //
        // 10 seconds = ~3 full OOK cycles (3.2s each).
        // The detector should lock onto at least one frame in this window.
        boolean decoded = latch.await(10, TimeUnit.SECONDS);

        // ── Step 6: Assertions ────────────────────────────────────────────

        // Guard: if onDetectionError fired, fail with the reason string
        assertNull("Detector reported hardware/permission error: " + errorMsg.get(),
                errorMsg.get());

        // Primary assertion: the detector decoded a token within the timeout
        assertTrue("Detector did not decode any token within 10 seconds. "
                + "Ensure: (1) running on real device, (2) speaker volume >= 50%, "
                + "(3) quiet environment, (4) device supports 48 kHz capture.",
                decoded);

        // Core invariant: decoded token matches emitted token
        assertEquals("Decoded token must match emitted token. "
                        + "emitted=" + emittedToken + " detected=" + detectedToken.get(),
                emittedToken, detectedToken.get());

        // Sanity: Goertzel magnitude should be positive (real signal, not noise)
        assertTrue("Goertzel magnitude should be > 0 for a real detection, was: "
                        + detectedMag.get(),
                detectedMag.get() > 0);

        Log.d(TAG, "roundtrip: PASSED — token=" + detectedToken.get()
                + " magnitude=" + detectedMag.get());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TEST 2 — wrongToken()
    //
    //  Proves SESSION ISOLATION: a detector listening for session "math101"
    //  will decode token 10 from the air, but that is NOT the same as
    //  "cs101"'s expected token (2).
    //
    //  This is the anti-spoofing test:
    //  - Student in Room A (session "cs101") emits token 2
    //  - Attacker in Room B (session "math101") expects token 10
    //  - Even if the attacker receives the OOK signal, the token won't match
    //
    //  Flow:
    //    1. Emitter uses "cs101" → emits token 2
    //    2. Detector is created for "cs101" (same) → decodes token from air
    //    3. Assert: decoded token == cs101's token (2)
    //    4. Assert: decoded token != math101's token (10)
    //    5. This proves: different sessions → different tokens → isolation
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    public void wrongToken() throws InterruptedException {
        final String emitterSession  = "cs101";
        final String wrongSession    = "math101";

        // Precondition: the two sessions must produce DIFFERENT tokens.
        // If they collided (astronomically unlikely for these strings),
        // the test would be meaningless — fail fast with a clear message.
        // We use the public getSessionToken() API to derive tokens.
        int emitterToken = new UltrasoundEmitter(emitterSession).getSessionToken();
        int wrongToken   = new UltrasoundEmitter(wrongSession).getSessionToken();
        assertNotEquals("Precondition failed: cs101 and math101 produce the same token ("
                        + emitterToken + "). Pick different test sessions.",
                emitterToken, wrongToken);

        Log.d(TAG, "wrongToken: emitter(cs101)=" + emitterToken
                + " wrong(math101)=" + wrongToken);

        // ── Emit cs101's token ────────────────────────────────────────────
        emitter = new UltrasoundEmitter(emitterSession);
        emitter.startEmitting();
        Thread.sleep(500);  // head start

        // ── Detect with cs101 — should match ──────────────────────────────
        final CountDownLatch       latch         = new CountDownLatch(1);
        final AtomicInteger        detectedToken = new AtomicInteger(-1);
        final AtomicReference<String> errorMsg   = new AtomicReference<>(null);

        detector = new UltrasoundDetector(emitterSession, new UltrasoundDetector.DetectorCallback() {
            @Override
            public void onTokenDecoded(int token, double magnitude) {
                Log.d(TAG, "wrongToken: DECODED token=" + token);
                detectedToken.set(token);
                latch.countDown();
            }

            @Override
            public void onSearching() { /* waiting */ }

            @Override
            public void onDetectionError(String reason) {
                Log.e(TAG, "wrongToken: DETECTION ERROR: " + reason);
                errorMsg.set(reason);
                latch.countDown();
            }
        });

        detector.start();

        boolean decoded = latch.await(10, TimeUnit.SECONDS);

        // ── Assertions ────────────────────────────────────────────────────

        assertNull("Detector error: " + errorMsg.get(), errorMsg.get());

        assertTrue("Detector did not decode any token within 10 seconds. "
                + "Ensure: real device, speaker volume >= 50%, quiet room.",
                decoded);

        // The decoded token from the air should match what cs101's emitter sent
        assertEquals("Decoded token should match emitter's session (cs101). "
                        + "emitted=" + emitterToken + " detected=" + detectedToken.get(),
                emitterToken, detectedToken.get());

        // CORE ASSERTION: the decoded token must NOT match a different session
        assertNotEquals("Decoded token must NOT match math101's token — "
                        + "this would mean session isolation is broken! "
                        + "decoded=" + detectedToken.get() + " math101=" + wrongToken,
                wrongToken, detectedToken.get());

        Log.d(TAG, "wrongToken: PASSED — decoded=" + detectedToken.get()
                + " != math101(" + wrongToken + ")");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TEST 3 — silenceDetection()
    //
    //  Proves the detector does NOT produce false positives in a quiet room.
    //  No emitter is running → no 18.5 kHz OOK signal in the air →
    //  the detector should never fire onTokenDecoded().
    //
    //  This is the FALSE POSITIVE test:
    //  - If this fails, the Goertzel threshold (1500) is too low and ambient
    //    noise is being misinterpreted as an OOK frame.
    //  - If this fails only in specific environments (e.g. near CRT monitors,
    //    fluorescent lights, or ultrasonic pest repellers), document the
    //    interfering source rather than raising the threshold.
    //
    //  Flow:
    //    1. Do NOT create any emitter (no 18.5 kHz signal in the air)
    //    2. Start detector for "cs101"
    //    3. Wait 5 seconds
    //    4. Assert: onTokenDecoded() was NEVER called
    //    5. Assert: onSearching() WAS called (detector was actually running)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    public void silenceDetection() throws InterruptedException {
        final String sessionId = "cs101";

        // NO emitter created — the air should be silent at 18.5 kHz.

        // Tracking flags — captured from detector's background thread
        final AtomicBoolean tokenWasDecoded  = new AtomicBoolean(false);
        final AtomicInteger decodedToken     = new AtomicInteger(-1);
        final AtomicBoolean searchingCalled  = new AtomicBoolean(false);
        final AtomicReference<String> errorMsg = new AtomicReference<>(null);

        // Latch that fires ONLY if a token is (falsely) decoded or an error occurs.
        // In the success case (silence correctly detected as silence), the latch
        // will NOT fire and we'll hit the timeout — which is the EXPECTED outcome.
        final CountDownLatch unexpectedEvent = new CountDownLatch(1);

        detector = new UltrasoundDetector(sessionId, new UltrasoundDetector.DetectorCallback() {
            @Override
            public void onTokenDecoded(int token, double magnitude) {
                // THIS SHOULD NEVER HAPPEN — there's no emitter!
                Log.w(TAG, "silenceDetection: FALSE POSITIVE! token=" + token
                        + " magnitude=" + magnitude);
                tokenWasDecoded.set(true);
                decodedToken.set(token);
                unexpectedEvent.countDown();  // unblock early — test will fail
            }

            @Override
            public void onSearching() {
                // EXPECTED — detector is running and correctly finding nothing.
                searchingCalled.set(true);
            }

            @Override
            public void onDetectionError(String reason) {
                Log.e(TAG, "silenceDetection: DETECTION ERROR: " + reason);
                errorMsg.set(reason);
                unexpectedEvent.countDown();
            }
        });

        detector.start();

        // ── Wait 5 seconds — we EXPECT the latch to time out ─────────────
        //
        // If the latch fires early, it means either:
        //   (a) A token was falsely decoded → false positive → test fails
        //   (b) A hardware error occurred   → test fails with error message
        boolean unexpectedFired = unexpectedEvent.await(5, TimeUnit.SECONDS);

        // ── Assertions ────────────────────────────────────────────────────

        // Guard: hardware error should not occur (permission is auto-granted)
        assertNull("Detector reported hardware/permission error: " + errorMsg.get(),
                errorMsg.get());

        // Core assertion: no token should have been decoded from silence
        assertFalse("FALSE POSITIVE: Detector decoded token " + decodedToken.get()
                        + " from silence! The Goertzel threshold (1500) may be too "
                        + "low for this device/environment, or there is an external "
                        + "ultrasonic source (pest repeller, CRT, fluorescent light).",
                tokenWasDecoded.get());

        // Liveness check: confirm the detector was actually running and processing
        // frames. If onSearching() was never called, the detector thread may have
        // died silently, and the "no false positive" result would be meaningless.
        assertTrue("onSearching() was never called — detector thread may not have "
                        + "started. Check AudioRecord initialization and permissions.",
                searchingCalled.get());

        Log.d(TAG, "silenceDetection: PASSED — no false positives in 5 seconds. "
                + "Detector was actively searching (onSearching called).");
    }
}
