/*
   Android FreeRDP JNI Wrapper

   Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.collection.LongSparseArray;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LibFreeRDP
{
	private static final String TAG = "LibFreeRDP";
	private static EventListener listener;
	private static volatile java.util.function.Consumer<String> nativeCallbackErrorSink;
	private static boolean mHasH264 = false;
	private static boolean mHasCameraRedirection = false;

	private static final LongSparseArray<Boolean> mInstanceState = new LongSparseArray<>();

	public static final long VERIFY_CERT_FLAG_NONE = 0x00;
	public static final long VERIFY_CERT_FLAG_LEGACY = 0x02;
	public static final long VERIFY_CERT_FLAG_REDIRECT = 0x10;
	public static final long VERIFY_CERT_FLAG_GATEWAY = 0x20;
	public static final long VERIFY_CERT_FLAG_CHANGED = 0x40;
	public static final long VERIFY_CERT_FLAG_MISMATCH = 0x80;
	public static final long VERIFY_CERT_FLAG_MATCH_LEGACY_SHA1 = 0x100;
	public static final long VERIFY_CERT_FLAG_FP_IS_PEM = 0x200;

	// Keep in sync with android_freerdp.c.
	public static final int EXPERIMENTAL_REMOTEAPP = 0;
	public static final int EXPERIMENTAL_CAMERA = 1;

	private static boolean tryLoad(String[] libraries)
	{
		boolean success = false;
		final String LD_PATH = System.getProperty("java.library.path");
		for (String lib : libraries)
		{
			try
			{
				Log.v(TAG, "Trying to load library " + lib + " from LD_PATH: " + LD_PATH);
				System.loadLibrary(lib);
				success = true;
			}
			catch (UnsatisfiedLinkError e)
			{
				Log.e(TAG, "Failed to load library " + lib + ": " + e);
				success = false;
				break;
			}
		}

		return success;
	}

	private static boolean tryLoad(String library)
	{
		return tryLoad(new String[] { library });
	}

	static
	{
		try
		{
			tryLoad("kzdesk-quic");
			System.loadLibrary("freerdp-android");

			/* Load dependent libraries too to trigger JNI_OnLoad calls */
			String version = freerdp_get_jni_version();
			String[] versions = version.split("[\\.-]");
			if (versions.length > 0)
			{
				System.loadLibrary("freerdp-client" + versions[0]);
				System.loadLibrary("freerdp" + versions[0]);
				System.loadLibrary("winpr" + versions[0]);
			}
			Pattern pattern = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*");
			Matcher matcher = pattern.matcher(version);
			if (!matcher.matches() || (matcher.groupCount() < 3))
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			int major = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
			int minor = Integer.parseInt(Objects.requireNonNull(matcher.group(2)));
			int patch = Integer.parseInt(Objects.requireNonNull(matcher.group(3)));

			if (major > 2)
				mHasH264 = freerdp_has_h264();
			else if (minor > 5)
				mHasH264 = freerdp_has_h264();
			else if ((minor == 5) && (patch >= 1))
				mHasH264 = freerdp_has_h264();
			else
				throw new RuntimeException("APK broken: native library version " + version +
				                           " does not meet requirements!");
			mHasCameraRedirection = freerdp_has_camera_redirection();
			Log.i(TAG, "Successfully loaded native library. H264 is " +
			               (mHasH264 ? "supported" : "not available") + ", camera redirection is " +
			               (mHasCameraRedirection ? "supported" : "not available"));
		}
		catch (UnsatisfiedLinkError e)
		{
			Log.e(TAG, "Failed to load library: " + e);
			throw e;
		}
	}

	public static boolean hasH264Support()
	{
		return mHasH264;
	}

	public static boolean hasCameraRedirectionSupport()
	{
		return mHasCameraRedirection;
	}

	private static native boolean freerdp_has_h264();

	private static native boolean freerdp_has_camera_redirection();

	private static native String freerdp_get_jni_version();

	private static native String freerdp_get_version();

	private static native String freerdp_get_build_revision();

	private static native String freerdp_get_build_config();

	private static native long freerdp_new(Context context);

	private static native void freerdp_free(long inst);

	private static native boolean freerdp_parse_arguments(long inst, String[] args);

	private static native boolean freerdp_connect(long inst);

	private static native boolean freerdp_disconnect(long inst);

	private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
	                                                      int width, int height);

	private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);

	private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);

	private static native boolean freerdp_send_unicodekey_event(long inst, int keycode,
	                                                            boolean down);

	private static native boolean freerdp_is_unicode_input_supported(long inst);

	private static native boolean freerdp_send_clipboard_data(long inst, String data);

	private static native boolean freerdp_send_clipboard_image_data(long inst, byte[] data,
	                                                                String mimeType);

	private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);
	private static native boolean freerdp_send_monitor_layout_with_scale(long inst, int width, int height,
	                                                                    int desktopScaleFactor);

	private static native String freerdp_get_last_error_string(long inst);

	private static native String freerdp_quic_probe_host(String host, int port, int timeoutMs);

	private static native String freerdp_quic_engine_info();

	public static String quicProbeHost(String host, int port, int timeoutMs)
	{
		try
		{
			String res = freerdp_quic_probe_host(host, port, timeoutMs);
			return res != null ? res : "{\"ok\":false}";
		}
		catch (Throwable t)
		{
			return "{\"ok\":false}";
		}
	}

	public static String getQuicEngineInfo()
	{
		try
		{
			String info = freerdp_quic_engine_info();
			return info != null ? info : "libkzdesk-quic.so";
		}
		catch (Throwable t)
		{
			return "libkzdesk-quic.so";
		}
	}

	/** Read the current native error while the instance is still alive. */
	public static String getLastErrorString(long inst)
	{
		String detail = freerdp_get_last_error_string(inst);
		return detail != null ? detail : "";
	}

	public static void setEventListener(EventListener l)
	{
		listener = l;
	}

	/** Install app-owned durable logging for native-to-Java callback failures. */
	public static void setNativeCallbackErrorSink(java.util.function.Consumer<String> sink)
	{
		nativeCallbackErrorSink = sink;
	}

	/** Called from android_jni_callback.c after a Java exception is cleared. */
	private static void OnNativeCallbackException(String message)
	{
		Log.e(TAG, message);
		java.util.function.Consumer<String> sink = nativeCallbackErrorSink;
		if (sink != null)
		{
			try
			{
				sink.accept(message);
			}
			catch (RuntimeException e)
			{
				Log.e(TAG, "native callback error sink failed", e);
			}
		}
	}

	public static long newInstance(Context context)
	{
		long instance = freerdp_new(context);
		if (instance == 0L)
			throw new IllegalStateException("FreeRDP gagal membuat session native.");
		return instance;
	}

	public static void freeInstance(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				freerdp_disconnect(inst);
			}
			while (mInstanceState.get(inst, false))
			{
				try
				{
					mInstanceState.wait();
				}
				catch (InterruptedException e)
				{
					throw new RuntimeException();
				}
			}
		}
		freerdp_free(inst);
	}

	public static boolean connect(long inst)
	{
		// Mark as active before starting the native worker. freeInstance() must
		// wait for its terminal callback even while the handshake is in progress;
		// otherwise it can free rdpContext while android_thread_func uses it.
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				throw new RuntimeException("instance already connected");
			}
			mInstanceState.put(inst, true);
		}

		boolean started;
		try
		{
			started = freerdp_connect(inst);
		}
		catch (RuntimeException | Error e)
		{
			synchronized (mInstanceState)
			{
				mInstanceState.remove(inst);
				mInstanceState.notifyAll();
			}
			throw e;
		}

		if (!started)
		{
			synchronized (mInstanceState)
			{
				mInstanceState.remove(inst);
				mInstanceState.notifyAll();
			}
		}
		return started;
	}

	public static boolean disconnect(long inst)
	{
		synchronized (mInstanceState)
		{
			if (mInstanceState.get(inst, false))
			{
				return freerdp_disconnect(inst);
			}
			return true;
		}
	}

	public static boolean cancelConnection(long inst)
	{
		return freerdp_disconnect(inst);
	}

	private static String addFlag(String name, boolean enabled)
	{
		if (enabled)
		{
			return "+" + name;
		}
		return "-" + name;
	}

	public static boolean setConnectionInfo(Context context, long inst, BookmarkBase bookmark)
	{
		BookmarkBase.ScreenSettings screenSettings = bookmark.getActiveScreenSettings();
		BookmarkBase.AdvancedSettings advanced = bookmark.getAdvancedSettings();
		BookmarkBase.DebugSettings debug = bookmark.getDebugSettings();

		String arg;
		ArrayList<String> args = new ArrayList<>();

		args.add(TAG);
		args.add("/gdi:sw");

		final String clientName = ApplicationSettingsActivity.getClientName(context);
		if (!clientName.isEmpty())
		{
			args.add("/client-hostname:" + clientName);
		}
		String certName = "";
		if (bookmark.getType() != BookmarkBase.TYPE_MANUAL)
		{
			return false;
		}

		int port = bookmark.getPort();
		String hostname = bookmark.getHostname();

		args.add("/v:" + hostname);
		args.add("/port:" + port);

		final int level = advanced.getTlsSecLevel();
		List<String> tls = new ArrayList<>();

		if (level >= 0)
		{
			tls.add("seclevel:" + level);
		}

		final int tlsMinLevel = advanced.getTlsMinLevel();
		if (tlsMinLevel >= 0)
		{
			tls.add("enforce:" + tlsMinLevel);
		}

		if (!tls.isEmpty())
		{
			StringBuilder sb = new StringBuilder();
			for (String s : tls)
			{
				if (sb.length() > 0)
				{
					sb.append(',');
				}
				sb.append(s);
			}
			args.add("/tls:" + sb);
		}

		arg = bookmark.getUsername();
		if (!arg.isEmpty())
		{
			args.add("/u:" + arg);
		}
		arg = bookmark.getDomain();
		if (!arg.isEmpty())
		{
			args.add("/d:" + arg);
		}
		arg = bookmark.getPassword();
		if (!arg.isEmpty())
		{
			args.add("/p:" + arg);
		}

		args.add(String.format(java.util.Locale.US, "/size:%dx%d", screenSettings.getWidth(),
		                       screenSettings.getHeight()));
		args.add("/bpp:" + screenSettings.getColors());

		if (screenSettings.isCustomScale())
		{
			args.add("/scale-desktop:" + screenSettings.getScaleDesktop());
			args.add("/scale-device:" + screenSettings.getScaleDevice());
		}
		else
		{
			args.add("/scale:" + screenSettings.getScalePreset());
		}

		if (advanced.getConsoleMode())
		{
			args.add("/admin");
		}

		if (advanced.getVmConnectMode())
		{
			String guid = advanced.getVmConnectGuid();
			if (!guid.isEmpty())
				args.add("/vmconnect:" + guid);
			else
				args.add("/vmconnect");
		}

		switch (advanced.getSecurity())
		{
			case 3: // NLA
				args.add("/sec:nla");
				break;
			case 2: // TLS
				args.add("/sec:tls");
				break;
			case 1: // RDP
				args.add("/sec:rdp");
				break;
			default:
				break;
		}

		if (!certName.isEmpty())
		{
			args.add("/cert-name:" + certName);
		}

		BookmarkBase.PerformanceFlags flags = bookmark.getActivePerformanceFlags();
		if (flags.getRemoteFX())
		{
			args.add("/rfx");
			args.add("/network:auto");
		}

		if (flags.getGfx())
		{
			args.add("/gfx");
			args.add("/network:auto");
		}

		if (flags.getH264() && mHasH264)
		{
			args.add("/gfx:AVC444");
			args.add("/network:auto");
		}

		args.add(addFlag("wallpaper", flags.getWallpaper()));
		args.add(addFlag("window-drag", flags.getFullWindowDrag()));
		args.add(addFlag("menu-anims", flags.getMenuAnimations()));
		args.add(addFlag("themes", flags.getTheming()));
		args.add(addFlag("fonts", flags.getFontSmoothing()));
		args.add(addFlag("aero", flags.getDesktopComposition()));

		if (!advanced.getRemoteProgram().isEmpty())
		{
			args.add("/app:program:" + advanced.getRemoteProgram());
			if (!advanced.getWorkDir().isEmpty())
				args.add("/app:workdir:" + advanced.getWorkDir());
		}
		else
		{
			if (!advanced.getAlternateShell().isEmpty())
				args.add("/shell:" + advanced.getAlternateShell());
			if (!advanced.getWorkDir().isEmpty())
				args.add("/shell-dir:" + advanced.getWorkDir());
		}

		args.add(addFlag("async-channels", debug.getAsyncChannel()));
		args.add(addFlag("async-update", debug.getAsyncUpdate()));

		if (advanced.getRedirectSDCard())
		{
			String path = android.os.Environment.getExternalStorageDirectory().getPath();
			args.add("/drive:KzDesk," + path);
		}

		String info = advanced.getLoadBalanceInfo();
		if (!info.isEmpty())
		{
			args.add("/load-balance-info:" + info);
		}
		args.add("/clipboard");
		args.add("/disp");

		if (advanced.getRedirectPrinter())
			args.add("/printer:aFreeRDP Print,Microsoft Print to PDF,default");

		// Gateway enabled?
		if (bookmark.getType() == BookmarkBase.TYPE_MANUAL && bookmark.getEnableGatewaySettings())
		{
			BookmarkBase.GatewaySettings gateway = bookmark.getGatewaySettings();

			StringBuilder carg = new StringBuilder();
			carg.append(String.format(java.util.Locale.US, "/gateway:g:%s:%d",
			                          gateway.getHostname(), gateway.getPort()));

			arg = gateway.getUsername();
			if (!arg.isEmpty())
			{
				carg.append(",u:" + arg);
			}
			arg = gateway.getDomain();
			if (!arg.isEmpty())
			{
				carg.append(",d:" + arg);
			}
			arg = gateway.getPassword();
			if (!arg.isEmpty())
			{
				carg.append(",p:" + arg);
			}
			args.add(carg.toString());
		}

		/* 0 ... local
		   1 ... remote
		   2 ... disable */
		args.add("/audio-mode:" + advanced.getRedirectSound());
		if (advanced.getRedirectSound() == 0)
		{
			args.add("/sound");
		}

		if (advanced.getRedirectMicrophone())
		{
			args.add("/microphone");
		}

		if (advanced.getRedirectCamera() && mHasCameraRedirection)
		{
			args.add("/dvc:rdpecam");
		}

		args.add("/kbd:unicode:on");
		args.add("/cert:ignore");
		args.add("/log-level:" + debug.getDebugLevel());
		String[] arrayArgs = args.toArray(new String[0]);
		return freerdp_parse_arguments(inst, arrayArgs);
	}

	public static boolean setConnectionInfo(Context context, long inst, Uri openUri)
	{
		ArrayList<String> args = new ArrayList<>();

		// Parse URI from query string. Same key overwrite previous one
		// freerdp://user@ip:port/connect?sound=&rfx=&p=password&clipboard=%2b&themes=-

		// Now we only support Software GDI
		args.add(TAG);
		args.add("/gdi:sw");

		final String clientName = ApplicationSettingsActivity.getClientName(context);
		if (!clientName.isEmpty())
		{
			args.add("/client-hostname:" + clientName);
		}

		// Parse hostname and port. Set to 'v' argument
		String hostname = openUri.getHost();
		int port = openUri.getPort();
		if (hostname != null)
		{
			if (hostname.indexOf(':') >= 0 && !hostname.startsWith("["))
				hostname = "[" + hostname + "]";
			hostname = hostname + ((port == -1) ? "" : (":" + port));
			args.add("/v:" + hostname);
		}

		String user = openUri.getUserInfo();
		if (user != null)
		{
			args.add("/u:" + user);
		}

		for (String key : openUri.getQueryParameterNames())
		{
			String value = openUri.getQueryParameter(key);

			if (value.isEmpty())
			{
				// Query: key=
				// To freerdp argument: /key
				args.add("/" + key);
			}
			else if (value.equals("-") || value.equals("+"))
			{
				// Query: key=- or key=+
				// To freerdp argument: -key or +key
				args.add(value + key);
			}
			else
			{
				// Query: key=value
				// To freerdp argument: /key:value
				if (key.equals("drive") && (value.equals("sdcard") || value.equals("KzDesk")))
				{
					String driveName = value;
					// Special for local app-drive redirect.
					//
					// PENTING: sejak Android 11 app TIDAK bisa membaca
					// /storage/emulated/0 sembarangan, jadi mengoper path itu
					// bikin drive di remote muncul tapi isinya kosong/error.
					// Urutan pilihan path:
					//   1. seluruh storage HP  -> hanya kalau user memberi
					//      izin "semua file" (MANAGE_EXTERNAL_STORAGE);
					//   2. folder milik app    -> selalu bisa dibaca/tulis
					//      tanpa izin apa pun.
					String path = appDrivePath(context);
					value = driveName + "," + path;
				}

				args.add("/" + key + ":" + value);
			}
		}

		String[] arrayArgs = args.toArray(new String[0]);
		return freerdp_parse_arguments(inst, arrayArgs);
	}

	public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int width,
	                                     int height)
	{
		return freerdp_update_graphics(inst, bitmap, x, y, width, height);
	}

	public static boolean sendCursorEvent(long inst, int x, int y, int flags)
	{
		return freerdp_send_cursor_event(inst, x, y, flags);
	}

	public static boolean sendKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_key_event(inst, keycode, down);
	}

	public static boolean sendUnicodeKeyEvent(long inst, int keycode, boolean down)
	{
		return freerdp_send_unicodekey_event(inst, keycode, down);
	}

	public static boolean isUnicodeInputSupported(long inst)
	{
		return freerdp_is_unicode_input_supported(inst);
	}

	public static boolean sendClipboardData(long inst, String data)
	{
		return freerdp_send_clipboard_data(inst, data);
	}

	public static boolean sendClipboardImageData(long inst, byte[] data, String mimeType)
	{
		return freerdp_send_clipboard_image_data(inst, data, mimeType);
	}

	public static boolean sendMonitorLayout(long inst, int width, int height)
	{
		return freerdp_send_monitor_layout(inst, width, height);
	}

	public static boolean sendMonitorLayout(long inst, int width, int height, int desktopScaleFactor)
	{
		return freerdp_send_monitor_layout_with_scale(inst, width, height, desktopScaleFactor);
	}

	private static void OnConnectionSuccess(long inst)
	{
		if (listener != null)
			listener.OnConnectionSuccess(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.append(inst, true);
			mInstanceState.notifyAll();
		}
	}

	private static void OnConnectionFailure(long inst)
	{
		if (listener != null)
			listener.OnConnectionFailure(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.remove(inst);
			mInstanceState.notifyAll();
		}
	}

	private static void OnPreConnect(long inst)
	{
		if (listener != null)
			listener.OnPreConnect(inst);
	}

	private static void OnDisconnecting(long inst)
	{
		if (listener != null)
			listener.OnDisconnecting(inst);
	}

	private static void OnDisconnected(long inst)
	{
		if (listener != null)
			listener.OnDisconnected(inst);
		synchronized (mInstanceState)
		{
			mInstanceState.remove(inst);
			mInstanceState.notifyAll();
		}
	}

	private static void OnSettingsChanged(long inst, int width, int height, int bpp)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnSettingsChanged(width, height, bpp);
	}

	private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
	                                      StringBuilder password)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return false;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnAuthenticate(username, domain, password);
		return false;
	}

	private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
	                                             StringBuilder domain, StringBuilder password)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return false;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnGatewayAuthenticate(username, domain, password);
		return false;
	}

	private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
	                                       String subject, String issuer, String fingerprint,
	                                       long flags)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return 0;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnVerifiyCertificateEx(host, port, commonName, subject, issuer,
			                                              fingerprint, flags);
		return 0;
	}

	private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
	                                                String commonName, String subject,
	                                                String issuer, String fingerprint,
	                                                String oldSubject, String oldIssuer,
	                                                String oldFingerprint, long flags)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return 0;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			return uiEventListener.OnVerifyChangedCertificateEx(host, port, commonName, subject,
			                                                    issuer, fingerprint, oldSubject,
			                                                    oldIssuer, oldFingerprint, flags);
		return 0;
	}

	private static boolean OnExperimentalFeature(long inst, int feature)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return true;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener == null)
			return true;
		return uiEventListener.OnExperimentalFeature(feature);
	}

	private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnGraphicsUpdate(x, y, width, height);
	}

	private static void OnGraphicsResize(long inst, int width, int height, int bpp)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnGraphicsResize(width, height, bpp);
	}

	private static void OnRemoteClipboardChanged(long inst, String data)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRemoteClipboardChanged(data);
	}

	private static void OnRemoteClipboardImageChanged(long inst, byte[] data)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRemoteClipboardImageChanged(data);
	}

	private static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX,
	                                 int hotY)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnPointerSet(pixels, width, height, hotX, hotY);
	}

	private static void OnPointerSetNull(long inst)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnPointerSetNull();
	}

	private static void OnPointerSetDefault(long inst)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnPointerSetDefault();
	}

	private static void OnRailWindowUpdate(long inst, long windowId, int width, int height,
	                                       int[] pixels)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRailWindowUpdate(windowId, width, height, pixels);
	}

	private static void OnRailWindowMove(long inst, long windowId, int x, int y, int w, int h)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRailWindowMove(windowId, x, y, w, h);
	}

	private static void OnRailWindowHide(long inst, long windowId)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRailWindowHide(windowId);
	}

	private static void OnRailWindowDestroy(long inst, long windowId)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRailWindowDestroy(windowId);
	}

	private static void OnRailSessionEnd(long inst)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRailSessionEnd();
	}

	private static void OnRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId)
	{
		SessionState s = GlobalApp.getSession(inst);
		if (s == null)
			return;
		UIEventListener uiEventListener = s.getUIEventListener();
		if (uiEventListener != null)
			uiEventListener.OnRailMonitoredDesktop(windowIds, activeWindowId);
	}

	/** True kalau app punya akses "semua file" (Android 11+). */
	public static boolean hasAllFilesAccess()
	{
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
			return android.os.Environment.isExternalStorageManager();
		return true;
	}

	/**
	 * Folder HP yang di-redirect jadi drive di remote.
	 *
	 * Tanpa izin "semua file" Android modern hanya mengizinkan folder milik
	 * app, jadi itu yang dipakai; hasilnya drive tetap jalan (bisa baca/tulis
	 * lewat File Manager remote, lewat path Android/data/<paket>/files/Share).
	 */
	public static String appDrivePath(Context context)
	{
		if (hasAllFilesAccess())
			return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();

		java.io.File dir = context.getExternalFilesDir("Share");
		if (dir == null)
			dir = context.getFilesDir();
		if (dir != null && !dir.exists())
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		return dir != null ? dir.getAbsolutePath() : "/";
	}

	public static String getVersion()
	{
		return freerdp_get_version();
	}

	public static String getBuildConfig()
	{
		return freerdp_get_build_config();
	}

	/**
	 * Ringkasan fitur penting dari build config native (OPENSLES, FFMPEG, dst).
	 * Dipakai layar diagnostik app: menjawab "kanal audio/mic ada atau tidak"
	 * tanpa harus menebak dari kode.
	 */
	public static String getFeatureSummary()
	{
		String cfg = freerdp_get_build_config();
		if (cfg == null)
			return "-";
		StringBuilder sb = new StringBuilder();
		String[] keys = { "WITH_OPENSLES", "WITH_FFMPEG", "WITH_OPENH264", "WITH_OPUS",
			"CHANNEL_RDPSND_CLIENT", "CHANNEL_AUDIN_CLIENT", "CHANNEL_CLIPRDR_CLIENT",
			"CHANNEL_RDPECAM_CLIENT", "CHANNEL_DISP_CLIENT" };
		for (String key : keys)
		{
			int idx = cfg.indexOf(key + "=");
			if (idx < 0)
				continue;
			String rest = cfg.substring(idx + key.length() + 1);
			int end = rest.indexOf(' ');
			String value = end < 0 ? rest : rest.substring(0, end);
			if (sb.length() > 0)
				sb.append(" · ");
			sb.append(key).append('=').append(value);
		}
		return sb.length() == 0 ? "-" : sb.toString();
	}

	public interface EventListener
	{
		void OnPreConnect(long instance);

		void OnConnectionSuccess(long instance);

		void OnConnectionFailure(long instance);

		void OnDisconnecting(long instance);

		void OnDisconnected(long instance);
	}

	public interface UIEventListener
	{
		void OnSettingsChanged(int width, int height, int bpp);

		boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
		                       StringBuilder password);

		boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
		                              StringBuilder password);

		int OnVerifiyCertificateEx(String host, long port, String commonName, String subject, String issuer,
		                         String fingerprint, long flags);

		int OnVerifyChangedCertificateEx(String host, long port, String commonName, String subject, String issuer,
		                               String fingerprint, String oldSubject, String oldIssuer,
		                               String oldFingerprint, long flags);

		boolean OnExperimentalFeature(int feature);

		void OnGraphicsUpdate(int x, int y, int width, int height);

		void OnGraphicsResize(int width, int height, int bpp);

		void OnRemoteClipboardChanged(String data);

		void OnRemoteClipboardImageChanged(byte[] data);

		void OnPointerSet(int[] pixels, int width, int height, int hotX, int hotY);

		void OnPointerSetNull();

		void OnPointerSetDefault();

		void OnRailWindowUpdate(long windowId, int width, int height, int[] pixels);

		void OnRailWindowMove(long windowId, int x, int y, int w, int h);

		void OnRailWindowHide(long windowId);

		void OnRailWindowDestroy(long windowId);

		void OnRailSessionEnd();

		void OnRailMonitoredDesktop(long[] windowIds, long activeWindowId);
	}
}
