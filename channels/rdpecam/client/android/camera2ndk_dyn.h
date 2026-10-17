/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * MS-RDPECAM Android client — runtime loader untuk NDK Camera2/MediaNDK.
 *
 * Pustaka libcamera2ndk/libmediandk hanya ada mulai API 24. Build dengan
 * minSdk 23 (Android 6) tidak boleh meng-link langsung ke keduanya, jadi
 * seluruh fungsi diselesaikan lewat dlopen/dlsym saat entry HAL dipanggil.
 * Di perangkat API 23 dlopen gagal, entry mengembalikan error, dan kanal
 * rdpecam dimatikan dengan aman tanpa crash; di API 24+ semua berfungsi
 * normal lewat pointer yang sama.
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

#ifndef FREERDP_CHANNEL_RDPECAM_CLIENT_ANDROID_CAMERA2NDK_DYN_H
#define FREERDP_CHANNEL_RDPECAM_CLIENT_ANDROID_CAMERA2NDK_DYN_H

#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <dlfcn.h>
#include <stdint.h>

typedef struct
{
	ACameraManager* (*fn_Manager_create)(void);
	void (*fn_Manager_delete)(ACameraManager*);
	camera_status_t (*fn_Manager_getCameraIdList)(ACameraManager*, ACameraIdList**);
	void (*fn_Manager_deleteCameraIdList)(ACameraIdList*);
	camera_status_t (*fn_Manager_getCameraCharacteristics)(ACameraManager*, const char*,
	                                                       ACameraMetadata**);
	camera_status_t (*fn_Manager_openCamera)(ACameraManager*, const char*,
	                                         ACameraDevice_StateCallbacks*, ACameraDevice**);
	void (*fn_Device_close)(ACameraDevice*);
	camera_status_t (*fn_Device_createCaptureRequest)(const ACameraDevice*,
	                                                  ACameraDevice_request_template,
	                                                  ACaptureRequest**);
	camera_status_t (*fn_Device_createCaptureSession)(ACameraDevice*,
	                                                  const ACaptureSessionOutputContainer*,
	                                                  const ACameraCaptureSession_stateCallbacks*,
	                                                  ACameraCaptureSession**);
	camera_status_t (*fn_Session_setRepeatingRequest)(ACameraCaptureSession*,
	                                                  ACameraCaptureSession_captureCallbacks*, int,
	                                                  ACaptureRequest**, int*);
	camera_status_t (*fn_Session_stopRepeating)(ACameraCaptureSession*);
	camera_status_t (*fn_Session_close)(ACameraCaptureSession*);
	camera_status_t (*fn_OutputTarget_create)(ANativeWindow*, ACameraOutputTarget**);
	void (*fn_OutputTarget_free)(ACameraOutputTarget*);
	camera_status_t (*fn_Request_addTarget)(ACaptureRequest*, const ACameraOutputTarget*);
	camera_status_t (*fn_Request_removeTarget)(ACaptureRequest*, const ACameraOutputTarget*);
	void (*fn_Request_free)(ACaptureRequest*);
	camera_status_t (*fn_SessionOutput_create)(ANativeWindow*, ACaptureSessionOutput**);
	void (*fn_SessionOutput_free)(ACaptureSessionOutput*);
	camera_status_t (*fn_SessionOutputContainer_create)(ACaptureSessionOutputContainer**);
	void (*fn_SessionOutputContainer_free)(ACaptureSessionOutputContainer*);
	camera_status_t (*fn_SessionOutputContainer_add)(ACaptureSessionOutputContainer*,
	                                                 const ACaptureSessionOutput*);
	camera_status_t (*fn_Metadata_getConstEntry)(const ACameraMetadata*, uint32_t,
	                                             ACameraMetadata_const_entry**);
	void (*fn_Metadata_free)(ACameraMetadata*);
	media_status_t (*fn_ImageReader_new)(int32_t, int32_t, int32_t, int32_t, AImageReader**);
	void (*fn_ImageReader_delete)(AImageReader*);
	media_status_t (*fn_ImageReader_getWindow)(AImageReader*, ANativeWindow**);
	media_status_t (*fn_ImageReader_setImageListener)(AImageReader*, AImageReader_ImageListener*);
	media_status_t (*fn_ImageReader_acquireLatestImage)(AImageReader*, AImage**);
	void (*fn_Image_delete)(AImage*);
	media_status_t (*fn_Image_getWidth)(const AImage*, int32_t*);
	media_status_t (*fn_Image_getHeight)(const AImage*, int32_t*);
	media_status_t (*fn_Image_getPlaneData)(const AImage*, int, uint8_t**, int*);
	media_status_t (*fn_Image_getPlanePixelStride)(const AImage*, int, int32_t*);
	media_status_t (*fn_Image_getPlaneRowStride)(const AImage*, int, int32_t*);
} XyCam2Ndk;

static XyCam2Ndk xy_cam2;
static int xy_cam2_state = -1; /* -1 belum dicoba, 0 ok, 1 gagal */

/** Muat libcamera2ndk + libmediandk sekali saja. 0 = sukses. */
static inline int xy_cam2_load(void)
{
	if (xy_cam2_state >= 0)
		return xy_cam2_state;

	void* cam = dlopen("libcamera2ndk.so", RTLD_NOW | RTLD_LOCAL);
	void* med = dlopen("libmediandk.so", RTLD_NOW | RTLD_LOCAL);
	if (!cam || !med)
	{
		if (cam)
			dlclose(cam);
		if (med)
			dlclose(med);
		xy_cam2_state = 1;
		return xy_cam2_state;
	}

#define XY_CAM2_SYM(handle, member, name)                            \
	do                                                               \
	{                                                                \
		xy_cam2.member = (__typeof__(xy_cam2.member))dlsym(handle, #name); \
		if (!xy_cam2.member)                                         \
		{                                                            \
			dlclose(cam);                                            \
			dlclose(med);                                            \
			xy_cam2_state = 1;                                       \
			return xy_cam2_state;                                    \
		}                                                            \
	} while (0)

	XY_CAM2_SYM(cam, fn_Manager_create, ACameraManager_create);
	XY_CAM2_SYM(cam, fn_Manager_delete, ACameraManager_delete);
	XY_CAM2_SYM(cam, fn_Manager_getCameraIdList, ACameraManager_getCameraIdList);
	XY_CAM2_SYM(cam, fn_Manager_deleteCameraIdList, ACameraManager_deleteCameraIdList);
	XY_CAM2_SYM(cam, fn_Manager_getCameraCharacteristics, ACameraManager_getCameraCharacteristics);
	XY_CAM2_SYM(cam, fn_Manager_openCamera, ACameraManager_openCamera);
	XY_CAM2_SYM(cam, fn_Device_close, ACameraDevice_close);
	XY_CAM2_SYM(cam, fn_Device_createCaptureRequest, ACameraDevice_createCaptureRequest);
	XY_CAM2_SYM(cam, fn_Device_createCaptureSession, ACameraDevice_createCaptureSession);
	XY_CAM2_SYM(cam, fn_Session_setRepeatingRequest, ACameraCaptureSession_setRepeatingRequest);
	XY_CAM2_SYM(cam, fn_Session_stopRepeating, ACameraCaptureSession_stopRepeating);
	XY_CAM2_SYM(cam, fn_Session_close, ACameraCaptureSession_close);
	XY_CAM2_SYM(cam, fn_OutputTarget_create, ACameraOutputTarget_create);
	XY_CAM2_SYM(cam, fn_OutputTarget_free, ACameraOutputTarget_free);
	XY_CAM2_SYM(cam, fn_Request_addTarget, ACaptureRequest_addTarget);
	XY_CAM2_SYM(cam, fn_Request_removeTarget, ACaptureRequest_removeTarget);
	XY_CAM2_SYM(cam, fn_Request_free, ACaptureRequest_free);
	XY_CAM2_SYM(cam, fn_SessionOutput_create, ACaptureSessionOutput_create);
	XY_CAM2_SYM(cam, fn_SessionOutput_free, ACaptureSessionOutput_free);
	XY_CAM2_SYM(cam, fn_SessionOutputContainer_create, ACaptureSessionOutputContainer_create);
	XY_CAM2_SYM(cam, fn_SessionOutputContainer_free, ACaptureSessionOutputContainer_free);
	XY_CAM2_SYM(cam, fn_SessionOutputContainer_add, ACaptureSessionOutputContainer_add);
	XY_CAM2_SYM(cam, fn_Metadata_getConstEntry, ACameraMetadata_getConstEntry);
	XY_CAM2_SYM(cam, fn_Metadata_free, ACameraMetadata_free);
	XY_CAM2_SYM(med, fn_ImageReader_new, AImageReader_new);
	XY_CAM2_SYM(med, fn_ImageReader_delete, AImageReader_delete);
	XY_CAM2_SYM(med, fn_ImageReader_getWindow, AImageReader_getWindow);
	XY_CAM2_SYM(med, fn_ImageReader_setImageListener, AImageReader_setImageListener);
	XY_CAM2_SYM(med, fn_ImageReader_acquireLatestImage, AImageReader_acquireLatestImage);
	XY_CAM2_SYM(med, fn_Image_delete, AImage_delete);
	XY_CAM2_SYM(med, fn_Image_getWidth, AImage_getWidth);
	XY_CAM2_SYM(med, fn_Image_getHeight, AImage_getHeight);
	XY_CAM2_SYM(med, fn_Image_getPlaneData, AImage_getPlaneData);
	XY_CAM2_SYM(med, fn_Image_getPlanePixelStride, AImage_getPlanePixelStride);
	XY_CAM2_SYM(med, fn_Image_getPlaneRowStride, AImage_getPlaneRowStride);
#undef XY_CAM2_SYM

	xy_cam2_state = 0;
	return 0;
}

/* Alihkan semua panggilan NDK ke pointer runtime; kode HAL tidak berubah. */
#define ACameraManager_create(...) (xy_cam2.fn_Manager_create(__VA_ARGS__))
#define ACameraManager_delete(...) (xy_cam2.fn_Manager_delete(__VA_ARGS__))
#define ACameraManager_getCameraIdList(...) (xy_cam2.fn_Manager_getCameraIdList(__VA_ARGS__))
#define ACameraManager_deleteCameraIdList(...) (xy_cam2.fn_Manager_deleteCameraIdList(__VA_ARGS__))
#define ACameraManager_getCameraCharacteristics(...) \
	(xy_cam2.fn_Manager_getCameraCharacteristics(__VA_ARGS__))
#define ACameraManager_openCamera(...) (xy_cam2.fn_Manager_openCamera(__VA_ARGS__))
#define ACameraDevice_close(...) (xy_cam2.fn_Device_close(__VA_ARGS__))
#define ACameraDevice_createCaptureRequest(...) (xy_cam2.fn_Device_createCaptureRequest(__VA_ARGS__))
#define ACameraDevice_createCaptureSession(...) (xy_cam2.fn_Device_createCaptureSession(__VA_ARGS__))
#define ACameraCaptureSession_setRepeatingRequest(...) \
	(xy_cam2.fn_Session_setRepeatingRequest(__VA_ARGS__))
#define ACameraCaptureSession_stopRepeating(...) (xy_cam2.fn_Session_stopRepeating(__VA_ARGS__))
#define ACameraCaptureSession_close(...) (xy_cam2.fn_Session_close(__VA_ARGS__))
#define ACameraOutputTarget_create(...) (xy_cam2.fn_OutputTarget_create(__VA_ARGS__))
#define ACameraOutputTarget_free(...) (xy_cam2.fn_OutputTarget_free(__VA_ARGS__))
#define ACaptureRequest_addTarget(...) (xy_cam2.fn_Request_addTarget(__VA_ARGS__))
#define ACaptureRequest_removeTarget(...) (xy_cam2.fn_Request_removeTarget(__VA_ARGS__))
#define ACaptureRequest_free(...) (xy_cam2.fn_Request_free(__VA_ARGS__))
#define ACaptureSessionOutput_create(...) (xy_cam2.fn_SessionOutput_create(__VA_ARGS__))
#define ACaptureSessionOutput_free(...) (xy_cam2.fn_SessionOutput_free(__VA_ARGS__))
#define ACaptureSessionOutputContainer_create(...) \
	(xy_cam2.fn_SessionOutputContainer_create(__VA_ARGS__))
#define ACaptureSessionOutputContainer_free(...) \
	(xy_cam2.fn_SessionOutputContainer_free(__VA_ARGS__))
#define ACaptureSessionOutputContainer_add(...) (xy_cam2.fn_SessionOutputContainer_add(__VA_ARGS__))
#define ACameraMetadata_getConstEntry(...) (xy_cam2.fn_Metadata_getConstEntry(__VA_ARGS__))
#define ACameraMetadata_free(...) (xy_cam2.fn_Metadata_free(__VA_ARGS__))
#define AImageReader_new(...) (xy_cam2.fn_ImageReader_new(__VA_ARGS__))
#define AImageReader_delete(...) (xy_cam2.fn_ImageReader_delete(__VA_ARGS__))
#define AImageReader_getWindow(...) (xy_cam2.fn_ImageReader_getWindow(__VA_ARGS__))
#define AImageReader_setImageListener(...) (xy_cam2.fn_ImageReader_setImageListener(__VA_ARGS__))
#define AImageReader_acquireLatestImage(...) (xy_cam2.fn_ImageReader_acquireLatestImage(__VA_ARGS__))
#define AImage_delete(...) (xy_cam2.fn_Image_delete(__VA_ARGS__))
#define AImage_getWidth(...) (xy_cam2.fn_Image_getWidth(__VA_ARGS__))
#define AImage_getHeight(...) (xy_cam2.fn_Image_getHeight(__VA_ARGS__))
#define AImage_getPlaneData(...) (xy_cam2.fn_Image_getPlaneData(__VA_ARGS__))
#define AImage_getPlanePixelStride(...) (xy_cam2.fn_Image_getPlanePixelStride(__VA_ARGS__))
#define AImage_getPlaneRowStride(...) (xy_cam2.fn_Image_getPlaneRowStride(__VA_ARGS__))

#endif /* FREERDP_CHANNEL_RDPECAM_CLIENT_ANDROID_CAMERA2NDK_DYN_H */
