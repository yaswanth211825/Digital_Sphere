package com.example.digitalsphere.helper;

import com.example.digitalsphere.contract.IProfessorView;
import java.util.ArrayList;
import java.util.List;

/** Instrumented-test manual spy for IProfessorView. */
public class FakeProfessorView implements IProfessorView {

    public String       shownSessionId;
    public String       lastError;
    public String       lastTimer;
    public List<String> lastAttendanceList   = new ArrayList<>();
    public int          lastAttendanceCount  = 0;
    public boolean      startEnabled         = true;
    public boolean      stopEnabled          = false;
    public boolean      loading              = false;
    public boolean      sessionExpiredFired  = false;
    public boolean      beaconStartedFired   = false;
    public boolean      sessionStoppedFired  = false;
    public final List<String> errors         = new ArrayList<>();

    @Override public void showSessionId(String id)              { shownSessionId = id; }
    @Override public void updateTimer(String t)                 { lastTimer = t; }
    @Override public void onSessionExpired()                    { sessionExpiredFired = true; }
    @Override public void onBeaconStarted()                     { beaconStartedFired = true; }
    @Override public void onSessionStopped()                    { sessionStoppedFired = true; }
    @Override public void updateAttendanceList(List<String> l)  { lastAttendanceList = new ArrayList<>(l); }
    @Override public void updateAttendanceCount(int c)          { lastAttendanceCount = c; }
    @Override public void setLoading(boolean b)                 { loading = b; }
    @Override public void setStartEnabled(boolean b)            { startEnabled = b; }
    @Override public void setStopEnabled(boolean b)             { stopEnabled = b; }
    @Override public void showError(String m)                   { lastError = m; errors.add(m); }
}
