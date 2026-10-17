/*
   Android Clipboard Manager Proxy

   Copyright 2013 Thincast Technologies GmbH
   Copyright 2013 Martin Fleisz <martin.fleisz@thincast.com>
   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class ClipboardManagerProxy
{
	public static ClipboardManagerProxy getClipboardManager(Context ctx)
	{
		return new HCClipboardManager(ctx);
	}

	public abstract void setClipboardData(String data);

	public abstract void setClipboardImage(byte[] pngData);

	public abstract void addClipboardChangedListener(OnClipboardChangedListener listener);

	public abstract void removeClipboardboardChangedListener(OnClipboardChangedListener listener);

	public abstract void getPrimaryClipManually();

	public interface OnClipboardChangedListener
	{
		void onClipboardChanged(String data);

		void onClipboardImageChanged(byte[] data, String mimeType);
	}

	private static class HCClipboardManager
	    extends ClipboardManagerProxy implements ClipboardManager.OnPrimaryClipChangedListener
	{
		private final Context mContext;
		private final ClipboardManager mClipboardManager;
		private OnClipboardChangedListener mListener;
		private final ExecutorService mClipboardReader = Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "KzDesk-clipboard-reader");
			thread.setDaemon(true);
			return thread;
		});

		public HCClipboardManager(Context ctx)
		{
			mContext = ctx;
			mClipboardManager = (ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);
		}

		@Override public void setClipboardData(String data)
		{
			try
			{
				mClipboardManager.setPrimaryClip(
				    ClipboardImageProvider.createTextClip(mContext, "rdp-clipboard", data));
			}
			catch (RuntimeException e)
			{
				// A platform clipboard failure is not an RDP transport failure.
			}
		}

		@Override public void setClipboardImage(byte[] pngData)
		{
			ClipboardImageProvider.setImageData(pngData);
			ClipData clip = new ClipData("rdp-clipboard", new String[] { "image/png" },
			                             new ClipData.Item(ClipboardImageProvider.CONTENT_URI));
			mClipboardManager.setPrimaryClip(clip);
		}

		@Override public void onPrimaryClipChanged()
		{
			ClipData clip;
			try
			{
				clip = mClipboardManager.getPrimaryClip();
			}
			catch (RuntimeException e)
			{
				// Clipboard binder failures (including oversized clips) must not drop RDP.
				return;
			}
			String label = clip != null && clip.getDescription() != null
			                   ? String.valueOf(clip.getDescription().getLabel())
			                   : "";
			if (clip == null || clip.getItemCount() == 0 ||
			    "rdp".equals(label) || "rdp-clipboard".equals(label))
				return;

			ClipData.Item item = clip.getItemAt(0);
			Uri uri = item.getUri();
			if (!ClipboardImageProvider.TEXT_CONTENT_URI.equals(uri))
				ClipboardImageProvider.clearTextData();

			CharSequence cs = item.getText();
			if (cs != null)
			{
				String text = cs.toString();
				mClipboardReader.execute(() -> {
					OnClipboardChangedListener listener = mListener;
					if (listener != null)
						listener.onClipboardChanged(text);
				});
				return;
			}

			if (uri != null)
			{
				String mimeType = mContext.getContentResolver().getType(uri);
				if (mimeType != null && mimeType.startsWith("text/"))
				{
					mClipboardReader.execute(() -> {
						try
						{
							CharSequence text = item.coerceToText(mContext);
							OnClipboardChangedListener listener = mListener;
							if (text != null && listener != null)
								listener.onClipboardChanged(text.toString());
						}
						catch (RuntimeException e)
						{
							// Unavailable or revoked clipboard URI; keep the session alive.
						}
					});
				}
				else if (mimeType != null && mimeType.startsWith("image/"))
				{
					try (InputStream is = mContext.getContentResolver().openInputStream(uri))
					{
						if (is != null)
						{
							ByteArrayOutputStream baos = new ByteArrayOutputStream();
							byte[] buf = new byte[8192];
							int n;
							while ((n = is.read(buf)) != -1)
								baos.write(buf, 0, n);
							if (mListener != null)
								mListener.onClipboardImageChanged(baos.toByteArray(), mimeType);
						}
					}
					catch (IOException e)
					{
						// not an accessible image
					}
				}
			}
		}

		@Override public void addClipboardChangedListener(OnClipboardChangedListener listener)
		{
			mListener = listener;
			mClipboardManager.addPrimaryClipChangedListener(this);
		}

		@Override
		public void removeClipboardboardChangedListener(OnClipboardChangedListener listener)
		{
			mListener = null;
			mClipboardManager.removePrimaryClipChangedListener(this);
		}

		@Override public void getPrimaryClipManually()
		{
			onPrimaryClipChanged();
		}
	}
}
