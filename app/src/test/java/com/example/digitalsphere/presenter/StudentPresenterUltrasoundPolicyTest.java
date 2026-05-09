package com.example.digitalsphere.presenter;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudentPresenterUltrasoundPolicyTest {

    @Test
    public void shouldFuseUltrasound_acceptsConfidenceAtRoomLockFloor() {
        StudentPresenter.UltrasoundSnapshot snapshot =
                StudentPresenter.UltrasoundSnapshot.matched(3, 0.30f);

        assertTrue(StudentPresenter.shouldFuseUltrasound(snapshot, 3));
    }

    @Test
    public void shouldFuseUltrasound_rejectsWeakGhostDetections() {
        StudentPresenter.UltrasoundSnapshot snapshot =
                StudentPresenter.UltrasoundSnapshot.matched(3, 0.19f);

        assertFalse(StudentPresenter.shouldFuseUltrasound(snapshot, 3));
    }

    @Test
    public void shouldFuseUltrasound_rejectsUnknownProfessorToken() {
        StudentPresenter.UltrasoundSnapshot snapshot =
                StudentPresenter.UltrasoundSnapshot.matched(3, 0.85f);

        assertFalse(StudentPresenter.shouldFuseUltrasound(snapshot, -1));
    }
}
