/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Event System
 *
 * Copyright 2010-2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
 * Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#include <freerdp/config.h>

#include <winpr/crt.h>

#include <limits.h>
#include <string.h>

#include <freerdp/freerdp.h>
#include <freerdp/input.h>
#include <freerdp/log.h>

#define TAG CLIENT_TAG("android")

#include "android_freerdp.h"
#include "android_cliprdr.h"

BOOL android_push_event(freerdp* inst, ANDROID_EVENT* event)
{
	androidContext* aCtx;
	ANDROID_EVENT_QUEUE* queue;
	BOOL success = FALSE;

	if (!inst || !inst->context || !event)
		return FALSE;

	aCtx = (androidContext*)inst->context;
	queue = aCtx->event_queue;
	if (!queue)
		return FALSE;

	/* Producers run on UI/clipboard threads while the RDP worker drains the
	 * queue. Protect both the count and the circular backing array. */
	EnterCriticalSection(&queue->lock);
	if (!queue->events || queue->size <= 0 || !queue->isSet)
		goto finish;

	/* Pointer motion carries only the latest position before the next button,
	 * key, wheel, clipboard, or disconnect event. Collapse adjacent pure moves
	 * so a slow network cannot build a trail of stale cursor coordinates. */
	if (event->type == EVENT_TYPE_CURSOR && queue->count > 0)
	{
		ANDROID_EVENT_CURSOR* incoming = (ANDROID_EVENT_CURSOR*)event;
		if (incoming->flags == PTR_FLAGS_MOVE)
		{
			const size_t tail = ((size_t)queue->head + (size_t)queue->count - 1u) %
			                    (size_t)queue->size;
			ANDROID_EVENT* last = queue->events[tail];
			if (last && last->type == EVENT_TYPE_CURSOR)
			{
				ANDROID_EVENT_CURSOR* previous = (ANDROID_EVENT_CURSOR*)last;
				if (previous->flags == PTR_FLAGS_MOVE)
				{
					success = SetEvent(queue->isSet);
					if (success)
					{
						previous->x = incoming->x;
						previous->y = incoming->y;
						android_event_free(event);
					}
					goto finish;
				}
			}
		}
	}

	if (queue->count >= queue->size)
	{
		size_t new_size = (size_t)queue->size;
		ANDROID_EVENT** new_events = nullptr;
		do
		{
			if (new_size > (size_t)INT_MAX - 128u ||
			    new_size > SIZE_MAX / sizeof(ANDROID_EVENT*) - 128u)
				goto finish;

			new_size += 128ull;
		} while (new_size <= (size_t)queue->count);

		new_events = (ANDROID_EVENT**)calloc(new_size, sizeof(ANDROID_EVENT*));
		if (!new_events)
			goto finish;

		/* Linearize only when capacity grows; ordinary enqueue/dequeue remains
		 * O(1), regardless of how many events are waiting. */
		for (size_t i = 0; i < (size_t)queue->count; i++)
		{
			const size_t source = ((size_t)queue->head + i) % (size_t)queue->size;
			new_events[i] = queue->events[source];
		}
		free(queue->events);
		queue->events = new_events;
		queue->size = (int)new_size;
		queue->head = 0;
	}

	const size_t insert = ((size_t)queue->head + (size_t)queue->count) % (size_t)queue->size;
	queue->events[insert] = event;
	queue->count++;
	success = SetEvent(queue->isSet);
	if (!success)
	{
		queue->count--;
		queue->events[insert] = nullptr;
	}

finish:
	LeaveCriticalSection(&queue->lock);
	return success;
}

static ANDROID_EVENT* android_pop_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event = nullptr;

	EnterCriticalSection(&queue->lock);
	if (queue->events && queue->size > 0 && queue->count > 0)
	{
		event = queue->events[queue->head];
		queue->events[queue->head] = nullptr;
		queue->head = (queue->head + 1) % queue->size;
		queue->count--;
	}
	LeaveCriticalSection(&queue->lock);
	return event;
}

static BOOL android_process_event(ANDROID_EVENT_QUEUE* queue, freerdp* inst)
{
	rdpContext* context;

	WINPR_ASSERT(queue);
	WINPR_ASSERT(inst);

	context = inst->context;
	WINPR_ASSERT(context);

	for (;;)
	{
		BOOL rc = FALSE;
		androidContext* afc = (androidContext*)context;
		ANDROID_EVENT* event = android_pop_event(queue);
		if (!event)
			break;

		switch (event->type)
		{
			case EVENT_TYPE_KEY:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_keyboard_event(context->input, key_event->flags,
				                                       key_event->scancode);
			}
			break;

			case EVENT_TYPE_KEY_UNICODE:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_unicode_keyboard_event(context->input, key_event->flags,
				                                               key_event->scancode);
			}
			break;

			case EVENT_TYPE_CURSOR:
			{
				ANDROID_EVENT_CURSOR* cursor_event = (ANDROID_EVENT_CURSOR*)event;

				rc = freerdp_input_send_mouse_event(context->input, cursor_event->flags,
				                                    cursor_event->x, cursor_event->y);
			}
			break;

			case EVENT_TYPE_CLIPBOARD:
			{
				ANDROID_EVENT_CLIPBOARD* clipboard_event = (ANDROID_EVENT_CLIPBOARD*)event;
				const char* mimeType = clipboard_event->mimeType;
				if (afc->clipboard)
				{
					UINT32 formatId = ClipboardRegisterFormat(afc->clipboard, mimeType);
					UINT32 size = clipboard_event->data_length;

					if (size)
						ClipboardSetData(afc->clipboard, formatId, clipboard_event->data, size);
					else
						ClipboardEmpty(afc->clipboard);
				}

				if (afc->cliprdr)
					(void)android_cliprdr_send_client_format_list(afc->cliprdr);

				/* Never tear down the RDP session if a clipboard announcement fails. */
				rc = TRUE;
			}
			break;

			case EVENT_TYPE_DISCONNECT:
				/* A disconnect control event is successfully consumed. */
				rc = TRUE;
				break;

			default:
				WLog_WARN(TAG, "Ignoring unknown Android event type %d", event->type);
				rc = TRUE;
				break;
		}

		if (!rc)
		{
			WLog_WARN(TAG, "Transient Android input/channel event failure (type=%d); keeping session alive",
			          event->type);
		}
		android_event_free(event);
	}

	return TRUE;
}

HANDLE android_get_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return nullptr;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return nullptr;

	return aCtx->event_queue->isSet;
}

BOOL android_check_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return FALSE;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return FALSE;

	if (WaitForSingleObject(aCtx->event_queue->isSet, 0) == WAIT_OBJECT_0)
	{
		if (!ResetEvent(aCtx->event_queue->isSet))
			return FALSE;

		if (!android_process_event(aCtx->event_queue, inst))
			return FALSE;
	}

	return TRUE;
}

ANDROID_EVENT_KEY* android_event_key_new(int flags, UINT16 scancode)
{
	ANDROID_EVENT_KEY* event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_KEY;
	event->flags = flags;
	event->scancode = scancode;
	return event;
}

static void android_event_key_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_KEY* android_event_unicodekey_new(UINT16 flags, UINT16 key)
{
	ANDROID_EVENT_KEY* event;
	event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_KEY_UNICODE;
	event->flags = flags;
	event->scancode = key;
	return event;
}

static void android_event_unicodekey_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_CURSOR* android_event_cursor_new(UINT16 flags, UINT16 x, UINT16 y)
{
	ANDROID_EVENT_CURSOR* event;
	event = (ANDROID_EVENT_CURSOR*)calloc(1, sizeof(ANDROID_EVENT_CURSOR));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_CURSOR;
	event->x = x;
	event->y = y;
	event->flags = flags;
	return event;
}

static void android_event_cursor_free(ANDROID_EVENT_CURSOR* event)
{
	free(event);
}

ANDROID_EVENT* android_event_disconnect_new(void)
{
	ANDROID_EVENT* event;
	event = (ANDROID_EVENT*)calloc(1, sizeof(ANDROID_EVENT));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_DISCONNECT;
	return event;
}

static void android_event_disconnect_free(ANDROID_EVENT* event)
{
	free(event);
}

ANDROID_EVENT_CLIPBOARD* android_event_clipboard_new(const void* data, size_t data_length,
                                                     const char* mimeType)
{
	ANDROID_EVENT_CLIPBOARD* event;
	event = (ANDROID_EVENT_CLIPBOARD*)calloc(1, sizeof(ANDROID_EVENT_CLIPBOARD));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_CLIPBOARD;
	event->mimeType = mimeType ? _strdup(mimeType) : nullptr;

	if (mimeType && !event->mimeType)
	{
		free(event);
		return nullptr;
	}

	/* The queued event stores its byte count in a signed int. */
	if (data_length > (size_t)(INT_MAX - 1))
	{
		WLog_WARN(TAG, "Clipboard payload exceeds Android event size limit");
		free(event->mimeType);
		free(event);
		return nullptr;
	}

	if (data && data_length > 0)
	{
		const BOOL isText = !mimeType || strcmp(mimeType, "text/plain") == 0;
		/* Text data needs a null terminator; image data is stored as-is. */
		event->data = isText ? calloc(data_length + 1, sizeof(char)) : malloc(data_length);

		if (!event->data)
		{
			free(event->mimeType);
			free(event);
			return nullptr;
		}

		memcpy(event->data, data, data_length);
		event->data_length = isText ? data_length + 1 : data_length;
	}

	return event;
}

static void android_event_clipboard_free(ANDROID_EVENT_CLIPBOARD* event)
{
	if (event)
	{
		free(event->data);
		free(event->mimeType);
		free(event);
	}
}

BOOL android_event_queue_init(freerdp* inst)
{
	androidContext* aCtx = (androidContext*)inst->context;
	ANDROID_EVENT_QUEUE* queue;
	queue = (ANDROID_EVENT_QUEUE*)calloc(1, sizeof(ANDROID_EVENT_QUEUE));

	if (!queue)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		return FALSE;
	}

	queue->size = 16;
	queue->count = 0;
	queue->head = 0;
	InitializeCriticalSection(&queue->lock);
	queue->isSet = CreateEventA(nullptr, TRUE, FALSE, nullptr);

	if (!queue->isSet)
	{
		DeleteCriticalSection(&queue->lock);
		free(queue);
		return FALSE;
	}

	queue->events = (ANDROID_EVENT**)calloc(queue->size, sizeof(ANDROID_EVENT*));

	if (!queue->events)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		(void)CloseHandle(queue->isSet);
		DeleteCriticalSection(&queue->lock);
		free(queue);
		return FALSE;
	}

	aCtx->event_queue = queue;
	return TRUE;
}

void android_event_queue_uninit(freerdp* inst)
{
	androidContext* aCtx;
	ANDROID_EVENT_QUEUE* queue;

	if (!inst || !inst->context)
		return;

	aCtx = (androidContext*)inst->context;
	queue = aCtx->event_queue;
	aCtx->event_queue = nullptr;

	if (queue)
	{
		EnterCriticalSection(&queue->lock);
		if (queue->events && queue->size > 0)
		{
			for (int i = 0; i < queue->count; i++)
			{
				const int index = (int)(((size_t)queue->head + (size_t)i) % (size_t)queue->size);
				android_event_free(queue->events[index]);
				queue->events[index] = nullptr;
			}
		}
		queue->count = 0;
		queue->head = 0;
		if (queue->isSet)
		{
			(void)CloseHandle(queue->isSet);
			queue->isSet = nullptr;
		}

		if (queue->events)
		{
			free(queue->events);
			queue->events = nullptr;
			queue->size = 0;
			queue->count = 0;
		}

		LeaveCriticalSection(&queue->lock);
		DeleteCriticalSection(&queue->lock);
		free(queue);
	}
}

void android_event_free(ANDROID_EVENT* event)
{
	if (!event)
		return;

	switch (event->type)
	{
		case EVENT_TYPE_KEY:
			android_event_key_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_KEY_UNICODE:
			android_event_unicodekey_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_CURSOR:
			android_event_cursor_free((ANDROID_EVENT_CURSOR*)event);
			break;

		case EVENT_TYPE_DISCONNECT:
			android_event_disconnect_free((ANDROID_EVENT*)event);
			break;

		case EVENT_TYPE_CLIPBOARD:
			android_event_clipboard_free((ANDROID_EVENT_CLIPBOARD*)event);
			break;

		default:
			break;
	}
}
