package com.freerdp.freerdpcore.presentation;

import static org.junit.Assert.assertFalse;

import android.view.MotionEvent;

import org.junit.Test;

public class TouchEventCompatTest {
    @Test
    public void api27TouchPathDoesNotInvokeApi29MotionEventMethod() {
        // A null event proves the pre-29 path returns before dereferencing it.
        assertFalse(TouchEventCompat.isTwoFingerSwipe((MotionEvent) null, 27));
    }

    @Test
    public void missingEventIsSafelyIgnoredOnModernApisToo() {
        assertFalse(TouchEventCompat.isTwoFingerSwipe((MotionEvent) null, 35));
    }
}
