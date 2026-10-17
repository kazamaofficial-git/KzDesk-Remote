/*
   Android Clipboard ContentProvider

   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.utils;

import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public class ClipboardImageProvider extends ContentProvider
{
	public static final String AUTHORITY = "com.freerdp.freerdpcore.clipboard";
	public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/image");
	public static final Uri TEXT_CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/text");
	/* Keep ordinary clips as text for compatibility; larger text uses a URI so
	 * it is streamed instead of being parcelled through Binder's small limit. */
	private static final int INLINE_TEXT_CHAR_LIMIT = 128 * 1024;

	private static volatile byte[] sImageData;
	private static volatile String sTextData;

	public static ClipData createTextClip(Context context, String label, String text)
	{
		String value = text == null ? "" : text;
		sImageData = null;
		if (value.length() <= INLINE_TEXT_CHAR_LIMIT)
		{
			sTextData = null;
			return ClipData.newPlainText(label, value);
		}

		sTextData = value;
		return ClipData.newUri(context.getContentResolver(), label, TEXT_CONTENT_URI);
	}

	public static void clearTextData()
	{
		sTextData = null;
	}

	public static void setImageData(byte[] data)
	{
		sTextData = null;
		sImageData = data;
	}

	@Override public boolean onCreate()
	{
		return true;
	}

	@Override public ParcelFileDescriptor openFile(Uri uri, String mode)
	{
		if (mode == null || !mode.startsWith("r"))
			return null;

		if ("text".equals(uri.getLastPathSegment()))
		{
			String text = sTextData;
			if (text == null)
				return null;
			try
			{
				ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
				final ParcelFileDescriptor writeEnd = pipe[1];
				Thread t = new Thread(() -> {
					try (Writer writer = new OutputStreamWriter(
						         new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd),
						         StandardCharsets.UTF_8))
					{
						for (int start = 0; start < text.length(); start += 8192)
							writer.write(text, start, Math.min(8192, text.length() - start));
					}
					catch (IOException e)
					{
						// pipe closed by reader
					}
				});
				t.setDaemon(true);
				t.start();
				return pipe[0];
			}
			catch (IOException e)
			{
				return null;
			}
		}

		if (!"image".equals(uri.getLastPathSegment()))
			return null;
		byte[] data = sImageData;
		if (data == null)
			return null;

		try
		{
			ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
			final ParcelFileDescriptor writeEnd = pipe[1];
			Thread t = new Thread(() -> {
				try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd))
				{
					out.write(data);
				}
				catch (IOException e)
				{
					// pipe closed by reader
				}
			});
			t.setDaemon(true);
			t.start();
			return pipe[0];
		}
		catch (IOException e)
		{
			return null;
		}
	}

	@Override public String getType(Uri uri)
	{
		return "text".equals(uri.getLastPathSegment()) ? "text/plain" : "image/png";
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
	                    String sortOrder)
	{
		return null;
	}

	@Override public Uri insert(Uri uri, ContentValues values)
	{
		return null;
	}

	@Override public int delete(Uri uri, String selection, String[] selectionArgs)
	{
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs)
	{
		return 0;
	}
}
