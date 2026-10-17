/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Display Update Virtual Channel
 *
 * Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <freerdp/config.h>

#include <winpr/assert.h>
#include <winpr/wlog.h>

#include <freerdp/channels/disp.h>
#include <freerdp/client/disp.h>

#include "android_disp.h"

#define TAG CLIENT_TAG("android.disp")

BOOL android_disp_init(androidContext* afc, DispClientContext* disp)
{
	WINPR_ASSERT(afc);
	WINPR_ASSERT(disp);

	afc->disp = disp;
	disp->custom = afc;

	WLog_DBG(TAG, "disp channel connected");
	return TRUE;
}

BOOL android_disp_uninit(androidContext* afc, DispClientContext* disp)
{
	WINPR_ASSERT(afc);
	WINPR_UNUSED(disp);

	afc->disp = nullptr;
	WLog_DBG(TAG, "disp channel disconnected");
	return TRUE;
}

static BOOL valid_desktop_scale_factor(UINT32 scale)
{
	switch (scale)
	{
		case 100:
		case 125:
		case 150:
		case 175:
		case 200:
		case 250:
		case 300:
		case 400:
		case 500:
			return TRUE;
		default:
			return FALSE;
	}
}

BOOL android_disp_send_monitor_layout(androidContext* afc, UINT32 width, UINT32 height)
{
	return android_disp_send_monitor_layout_with_scale(afc, width, height, 0);
}

BOOL android_disp_send_monitor_layout_with_scale(androidContext* afc, UINT32 width, UINT32 height,
                                                 UINT32 desktopScaleFactor)
{
	WINPR_ASSERT(afc);
	// MS-RDPEDISP requires an even width and dimensions between 200 and 8192.
	if ((width & 1U) != 0U)
		width--;
	if ((width < 200) || (height < 200) || (width > 8192) || (height > 8192))
		return FALSE;
	if ((desktopScaleFactor != 0) && !valid_desktop_scale_factor(desktopScaleFactor))
		return FALSE;

	DispClientContext* disp = afc->disp;
	if (!disp || !disp->SendMonitorLayout)
	{
		WLog_WARN(TAG, "disp channel not available");
		return FALSE;
	}

	rdpSettings* settings = afc->common.context.settings;
	if (!settings)
		return FALSE;

	DISPLAY_CONTROL_MONITOR_LAYOUT layout = WINPR_C_ARRAY_INIT;
	layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
	layout.Top = layout.Left = 0;
	layout.Width = width;
	layout.Height = height;
	layout.Orientation = freerdp_settings_get_uint16(settings, FreeRDP_DesktopOrientation);
	layout.DesktopScaleFactor = desktopScaleFactor != 0
	                                ? desktopScaleFactor
	                                : freerdp_settings_get_uint32(settings, FreeRDP_DesktopScaleFactor);
	if ((layout.DesktopScaleFactor < 100) || (layout.DesktopScaleFactor > 500))
		layout.DesktopScaleFactor = 100;
	layout.DeviceScaleFactor = freerdp_settings_get_uint32(settings, FreeRDP_DeviceScaleFactor);
	if ((layout.DeviceScaleFactor != 100) && (layout.DeviceScaleFactor != 140) &&
	    (layout.DeviceScaleFactor != 180))
		layout.DeviceScaleFactor = 100;

	/* MS-RDPEDISP 2.2.2.2.1: PhysicalWidth and PhysicalHeight MUST be in 10..1000 mm
	 * when DesktopScaleFactor / DeviceScaleFactor are specified. */
	const UINT32 physW = (width * 254U) / (96U * 10U);
	const UINT32 physH = (height * 254U) / (96U * 10U);
	layout.PhysicalWidth = (physW < 10U) ? 10U : ((physW > 1000U) ? 1000U : physW);
	layout.PhysicalHeight = (physH < 10U) ? 10U : ((physH > 1000U) ? 1000U : physH);

	UINT rc = disp->SendMonitorLayout(disp, 1, &layout);
	if (rc != CHANNEL_RC_OK)
	{
		WLog_ERR(TAG, "SendMonitorLayout failed: %" PRIu32, rc);
		return FALSE;
	}

	WLog_DBG(TAG, "SendMonitorLayout: %" PRIu32 "x%" PRIu32 " desktop-scale=%" PRIu32,
	         width, height, layout.DesktopScaleFactor);
	return TRUE;
}
