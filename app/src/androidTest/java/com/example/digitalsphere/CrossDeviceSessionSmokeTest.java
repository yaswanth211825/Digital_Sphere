package com.example.digitalsphere;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.digitalsphere.presentation.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.anyOf;

@RunWith(AndroidJUnit4.class)
public class CrossDeviceSessionSmokeTest {

    @Test
    public void runRoleFlow() throws Exception {
        String role = InstrumentationRegistry.getArguments().getString("role", "student");
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            if ("professor".equalsIgnoreCase(role)) {
                runProfessorFlow();
            } else {
                runStudentFlow();
            }
        }
    }

    private void runProfessorFlow() throws Exception {
        onView(withId(R.id.btn_professor)).perform(click());
        onView(withId(R.id.et_session_name)).perform(replaceText("CS101"), closeSoftKeyboard());
        onView(withId(R.id.et_duration)).perform(replaceText("5"), closeSoftKeyboard());
        onView(withId(R.id.btn_start_session)).perform(scrollTo(), click());

        Thread.sleep(8000L);

        onView(withId(R.id.tv_session_status))
                .check(matches(withText(containsString("Broadcasting"))));
        onView(withId(R.id.tv_ultrasound_status))
                .check(matches(anyOf(
                        withText(containsString("Emitting")),
                        withText(containsString("Failed")),
                        withText(containsString("Skipped"))
                )));

        Thread.sleep(18000L);
    }

    private void runStudentFlow() throws Exception {
        onView(withId(R.id.btn_student)).perform(click());
        onView(withId(R.id.et_student_name)).perform(replaceText("TestUser"), closeSoftKeyboard());
        onView(withId(R.id.et_session_id)).perform(replaceText("CS101"), closeSoftKeyboard());
        onView(withId(R.id.btn_scan)).perform(scrollTo(), click());

        Thread.sleep(18000L);

        onView(withId(R.id.tv_verify_room))
                .check(matches(not(withText("—"))));
        onView(withId(R.id.tv_verify_overall))
                .check(matches(not(withText("—"))));
        onView(withId(R.id.tv_status))
                .check(matches(anyOf(
                        withText(containsString("Attendance marked")),
                        withText(containsString("Broadcasting your name")),
                        withText(containsString("Already marked"))
                )));
    }
}
