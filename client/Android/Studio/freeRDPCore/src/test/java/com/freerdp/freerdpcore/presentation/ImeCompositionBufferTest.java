package com.freerdp.freerdpcore.presentation;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImeCompositionBufferTest
{
	@Test
	public void composingUpdatesStreamOnlyTheChangedSuffixAndCommitDoesNotDuplicate()
	{
		ImeCompositionBuffer buffer = new ImeCompositionBuffer();
		ImeCompositionBuffer.Edit first = buffer.setComposingText("h");
		assertEquals(0, first.backspaces);
		assertEquals("h", first.text);

		ImeCompositionBuffer.Edit second = buffer.setComposingText("hello");
		assertEquals(0, second.backspaces);
		assertEquals("ello", second.text);
		assertEquals("hello", buffer.getComposingText());

		ImeCompositionBuffer.Edit committed = buffer.commitText("hello");
		assertEquals(0, committed.backspaces);
		assertEquals("", committed.text);
		buffer.finishComposingText();
		assertEquals("", buffer.getComposingText());
	}

	@Test
	public void autocorrectReplacesOnlyChangedSuffixAndFinishDoesNotResend()
	{
		ImeCompositionBuffer buffer = new ImeCompositionBuffer();
		assertEquals("helo", buffer.setComposingText("helo").text);
		ImeCompositionBuffer.Edit corrected = buffer.setComposingText("hello");
		assertEquals(1, corrected.backspaces);
		assertEquals("lo", corrected.text);
		buffer.finishComposingText();
		assertEquals("", buffer.commitText("hello").text);
		assertEquals("hello", buffer.commitText("hello").text);
	}

	@Test
	public void cancellationRemovesPreviouslyStreamedComposingText()
	{
		ImeCompositionBuffer buffer = new ImeCompositionBuffer();
		buffer.setComposingText("draft");
		ImeCompositionBuffer.Edit cancelled = buffer.setComposingText("");
		assertEquals(5, cancelled.backspaces);
		assertEquals("", cancelled.text);
	}

	@Test
	public void deletionEditsStreamedCompositionAndDeletesBeyondIt()
	{
		ImeCompositionBuffer buffer = new ImeCompositionBuffer();
		buffer.setComposingText("ab");
		assertEquals(1, buffer.deleteBeforeCursor(1));
		assertEquals("a", buffer.getComposingText());
		assertEquals(3, buffer.deleteBeforeCursor(3));
		assertEquals("", buffer.getComposingText());
	}

	@Test
	public void replacingOrDeletingSupplementaryUnicodeDoesNotSplitSurrogatePair()
	{
		ImeCompositionBuffer buffer = new ImeCompositionBuffer();
		ImeCompositionBuffer.Edit initial = buffer.setComposingText("A\uD83D\uDE00");
		assertEquals("A\uD83D\uDE00", initial.text);

		ImeCompositionBuffer.Edit replaced = buffer.setComposingText("A\uD83D\uDE01");
		assertEquals(1, replaced.backspaces);
		assertEquals("\uD83D\uDE01", replaced.text);
		assertEquals(1, buffer.deleteBeforeCursor(1));
		assertEquals("A", buffer.getComposingText());
	}

	@Test
	public void forwardDeleteDoesNotDiscardTrackedComposition()
	{
		ImeCompositionBuffer buffer = new ImeCompositionBuffer();
		buffer.setComposingText("abc");
		assertEquals(1, buffer.deleteAfterCursor(1));
		assertEquals("abc", buffer.getComposingText());
	}

	@Test
	public void duplicatePrintableSoftImeEventsAreSuppressedOnlyDuringActiveComposition()
	{
		assertFalse(ImeCompositionBuffer.isPrintableImeTextEvent(
		        KeyEvent.FLAG_SOFT_KEYBOARD, KeyEvent.ACTION_DOWN, 'x', false));
		assertTrue(ImeCompositionBuffer.isPrintableImeTextEvent(
		        KeyEvent.FLAG_SOFT_KEYBOARD, KeyEvent.ACTION_DOWN, 'x', true));
		assertFalse(ImeCompositionBuffer.isPrintableImeTextEvent(
		        KeyEvent.FLAG_SOFT_KEYBOARD, KeyEvent.ACTION_DOWN, '5', true));
		assertFalse(ImeCompositionBuffer.isPrintableImeTextEvent(
		        KeyEvent.FLAG_SOFT_KEYBOARD, KeyEvent.ACTION_DOWN, ' ', true));
		assertFalse(ImeCompositionBuffer.isPrintableImeTextEvent(
		        0, KeyEvent.ACTION_DOWN, 'x', true));
		assertFalse(ImeCompositionBuffer.isPrintableImeTextEvent(
		        0, KeyEvent.ACTION_DOWN, 'x', false));
		assertFalse(ImeCompositionBuffer.isPrintableImeTextEvent(
		        KeyEvent.FLAG_SOFT_KEYBOARD, KeyEvent.ACTION_DOWN, '\n', false));
	}

	@Test
	public void finishFollowedByCommitWithTrailingSpaceEmitsOnlySuffixAndKeyEventResetsState()
	{
		ImeCompositionBuffer buffer = new ImeCompositionBuffer();
		assertEquals("halo", buffer.setComposingText("halo").text);
		buffer.finishComposingText();
		ImeCompositionBuffer.Edit trailing = buffer.commitText("halo ");
		assertEquals(0, trailing.backspaces);
		assertEquals(" ", trailing.text);

		assertEquals("tes", buffer.setComposingText("tes").text);
		buffer.onKeyEventDispatched();
		assertFalse(buffer.hasComposingText());
		ImeCompositionBuffer.Edit nextWord = buffer.setComposingText("baru");
		assertEquals(0, nextWord.backspaces);
		assertEquals("baru", nextWord.text);
	}
}
