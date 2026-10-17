package com.freerdp.freerdpcore.presentation;

import android.annotation.SuppressLint;
import android.os.Build;
import android.view.MotionEvent;

/** API-safe MotionEvent helpers for the app's minSdk 24 devices. */
public final class TouchEventCompat {
	private static final int API_Q = 29;

	private TouchEventCompat() {}

	public static boolean isTwoFingerSwipe(MotionEvent event)
	{
		return isTwoFingerSwipe(event, Build.VERSION.SDK_INT);
	}

	@SuppressLint("NewApi")
	static boolean isTwoFingerSwipe(MotionEvent event, int sdkInt)
	{
		// MotionEvent.getClassification() exists only on Android 10 / API 29+.
		// Keep the SDK check before the method invocation for Android 7–9.
		return sdkInt >= API_Q && event != null &&
		       event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE;
	}
}
