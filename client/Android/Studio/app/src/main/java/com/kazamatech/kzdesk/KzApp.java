package com.kazamatech.kzdesk;

import android.os.Bundle;

/**
 * KzDesk Remote — application entry point.
 *
 * Extends the FreeRDP core GlobalApp so that session lifecycle handling,
 * LibFreeRDP event dispatch and screen on/off disconnect handling keep
 * working unchanged. Layers on: global crash logger (diagnosa untuk user).
 */
public class KzApp extends com.freerdp.freerdpcore.application.GlobalApp
{
	@Override public void onCreate()
	{
		// Initialize both durable logs before GlobalApp touches LibFreeRDP/JNI.
		// This preserves the last Java/native startup marker if JNI_OnLoad fails.
		com.kazamatech.kzdesk.security.CrashLog.INSTANCE.install(this);
		// Bahasa app (ID/EN) dipasang sebelum UI pertama dirender.
		com.kazamatech.kzdesk.ui.LangPrefs.INSTANCE.install(this);
		// Log native FreeRDP juga ditulis ke file: masalah audio/mikrofon/drive/
		// clipboard di perangkat user bisa dibaca dari app tanpa ADB.
		enableNativeFileLog();
		com.kazamatech.kzdesk.core.ConnectionLog.INSTANCE.init(this);
		com.kazamatech.kzdesk.core.ConnectionLog.INSTANCE.add("APP: entering GlobalApp.onCreate");
		try
		{
			com.freerdp.freerdpcore.services.LibFreeRDP.setNativeCallbackErrorSink(
			    message -> com.kazamatech.kzdesk.core.ConnectionLog.INSTANCE.add("JNI: " + message));
			super.onCreate();
			com.kazamatech.kzdesk.core.ConnectionLog.INSTANCE.add("APP: GlobalApp.onCreate completed");
			// Auto-update: cek berkala di latar (hanya menjadwalkan, tanpa jaringan di sini).
			com.kazamatech.kzdesk.update.UpdateJobService.Companion.schedule(this);
		}
		catch (Throwable t)
		{
			com.kazamatech.kzdesk.core.ConnectionLog.INSTANCE.addThrowable("APP: GlobalApp.onCreate FAILED", t);
			com.kazamatech.kzdesk.security.CrashLog.INSTANCE.note(this,
			    "GlobalApp.onCreate failed: " + t.getClass().getName() + ": " + t.getMessage());
			if (t instanceof RuntimeException)
				throw (RuntimeException)t;
			if (t instanceof Error)
				throw (Error)t;
			throw new RuntimeException(t);
		}
	}
	/**
	 * winpr (mesin log FreeRDP) membaca konfigurasi dari environment variable
	 * saat appender pertama kali dibuka. Di-set di sini supaya seluruh log
	 * native — pemuatan kanal audio/mikrofon, clipboard, drive, DISP — mendarat
	 * di file yang bisa dilihat user lewat layar diagnosa, bukan cuma logcat.
	 */
	private void enableNativeFileLog()
	{
		try
		{
			String dir = getFilesDir().getAbsolutePath();
			android.system.Os.setenv("WLOG_APPENDER", "file", true);
			android.system.Os.setenv("WLOG_LEVEL", "INFO", true);
			android.system.Os.setenv("WLOG_FILEAPPENDER_OUTPUT_FILE_PATH", dir, true);
			android.system.Os.setenv("WLOG_FILEAPPENDER_OUTPUT_FILE_NAME", "freerdp-native.log", true);
			com.kazamatech.kzdesk.core.ConnectionLog.INSTANCE.setNativeLogFile(
			    new java.io.File(dir, "freerdp-native.log").getAbsolutePath());
		}
		catch (Throwable t)
		{
			com.kazamatech.kzdesk.core.ConnectionLog.INSTANCE.add(
			    "APP: log native tidak bisa diaktifkan: " + t.getClass().getSimpleName());
		}
	}
}
