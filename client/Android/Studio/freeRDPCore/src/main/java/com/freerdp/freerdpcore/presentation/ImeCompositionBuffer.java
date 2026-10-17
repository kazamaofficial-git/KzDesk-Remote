package com.freerdp.freerdpcore.presentation;

import android.view.KeyEvent;

/**
 * Tracks provisional IME text that has already been streamed to the remote
 * control. Each update replaces the prior provisional value by a minimal
 * suffix backspace + insertion, so commit does not type the text twice.
 */
final class ImeCompositionBuffer
{
	private static final int MAX_DELETE_UNITS = 256;
	private String composingText = "";
	private String justFinishedText;

	static final class Edit
	{
		final int backspaces;
		final String text;

		Edit(int backspaces, String text)
		{
			this.backspaces = backspaces;
			this.text = text;
		}
	}

	Edit setComposingText(CharSequence text)
	{
		justFinishedText = null;
		return replaceWith(text == null ? "" : text.toString());
	}

	boolean hasComposingText()
	{
		return !composingText.isEmpty();
	}

	String getComposingText()
	{
		return composingText;
	}

	/** Commit may correct the final composing value; send only that delta. */
	Edit commitText(CharSequence text)
	{
		String committed = text == null ? "" : text.toString();
		if (composingText.isEmpty() && justFinishedText != null)
		{
			String finished = justFinishedText;
			justFinishedText = null;
			if (finished.equals(committed))
				return new Edit(0, "");
			if (committed.startsWith(finished))
				return new Edit(0, committed.substring(finished.length()));
		}
		justFinishedText = null;
		Edit edit = replaceWith(committed);
		composingText = "";
		return edit;
	}

	/** Provisional text is remote; retain one value briefly to dedupe a final commit. */
	void finishComposingText()
	{
		if (!composingText.isEmpty())
			justFinishedText = composingText;
		composingText = "";
	}

	/**
	 * Clears tracked composition without emitting backspaces when a raw key
	 * event (Enter, Space, digits, navigation) is dispatched directly.
	 */
	void onKeyEventDispatched()
	{
		composingText = "";
		justFinishedText = null;
	}

	/**
	 * Deletes from the tracked composing suffix, plus any requested deletion
	 * outside it. The returned backspaces must all be sent to the remote.
	 */
	int deleteBeforeCursor(int beforeLength)
	{
		if (beforeLength <= 0)
			return 0;
		justFinishedText = null;

		int deleteUnits = Math.min(beforeLength, MAX_DELETE_UNITS);
		if (composingText.isEmpty())
			return deleteUnits;

		int start = Math.max(0, composingText.length() - deleteUnits);
		if (start > 0 && start < composingText.length() &&
		    Character.isHighSurrogate(composingText.charAt(start - 1)) &&
		    Character.isLowSurrogate(composingText.charAt(start)))
			start--;

		int composingBackspaces = Character.codePointCount(
		        composingText, start, composingText.length());
		int outsideBackspaces = Math.max(0, deleteUnits - (composingText.length() - start));
		composingText = composingText.substring(0, start);
		return composingBackspaces + outsideBackspaces;
	}

	/** Forward deletes do not alter the tracked composing text. */
	int deleteAfterCursor(int afterLength)
	{
		int deletes = Math.min(Math.max(afterLength, 0), MAX_DELETE_UNITS);
		if (deletes > 0)
			justFinishedText = null;
		return deletes;
	}

	private Edit replaceWith(String next)
	{
		int prefix = 0;
		int limit = Math.min(composingText.length(), next.length());
		while (prefix < limit && composingText.charAt(prefix) == next.charAt(prefix))
			prefix++;
		// Never leave a dangling high surrogate in the text sent for deletion.
		if (prefix > 0 && prefix < composingText.length() &&
		    Character.isHighSurrogate(composingText.charAt(prefix - 1)) &&
		    Character.isLowSurrogate(composingText.charAt(prefix)))
			prefix--;

		int backspaces = Character.codePointCount(composingText, prefix, composingText.length());
		String inserted = next.substring(prefix);
		composingText = next;
		return new Edit(backspaces, inserted);
	}

	/**
	 * Only suppress a printable soft-IME key event when an active composition
	 * is already streaming that word via setComposingText; never drop direct
	 * key events (digits, space, symbols, or non-composing letters) when idle.
	 */
	static boolean isPrintableImeTextEvent(int flags, int action, int unicodeChar,
	                                       boolean hasComposingText)
	{
		return hasComposingText && (flags & KeyEvent.FLAG_SOFT_KEYBOARD) != 0 &&
		       action == KeyEvent.ACTION_DOWN && unicodeChar > 0 &&
		       Character.isValidCodePoint(unicodeChar) && !Character.isISOControl(unicodeChar) &&
		       !Character.isWhitespace(unicodeChar) && !Character.isDigit(unicodeChar);
	}

	/** ACTION_MULTIPLE character payloads only duplicate when composition is active. */
	static boolean isImeMultipleTextEvent(int flags, int action, String characters,
	                                      boolean hasComposingText)
	{
		return hasComposingText && (flags & KeyEvent.FLAG_SOFT_KEYBOARD) != 0 &&
		       action == KeyEvent.ACTION_MULTIPLE && characters != null && !characters.isEmpty();
	}
}
