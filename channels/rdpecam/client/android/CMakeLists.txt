# FreeRDP: A Remote Desktop Protocol Implementation
# MS-RDPECAM Android NDK Camera2 HAL
#
# Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

if(ANDROID)

  define_channel_client_subsystem("rdpecam" "android" "")

  set(${MODULE_PREFIX}_SRCS camera_android.c)

  # libcamera2ndk/libmediandk hanya ada mulai API 24; build minSdk 23 tidak
  # boleh meng-link langsung. Deklarasi tipe dipaksa terlihat lewat makro
  # __ANDROID_API__ per-TU, dan seluruh fungsi dimuat runtime via dlopen di
  # camera2ndk_dyn.h (kanal mati aman di perangkat API 23).
  set_source_files_properties(camera_android.c PROPERTIES
                              COMPILE_DEFINITIONS "__ANDROID_API__=24")

  set(${MODULE_PREFIX}_LIBS winpr freerdp)

  include_directories(..)

  add_channel_client_subsystem_library(${MODULE_PREFIX} ${MODULE_NAME} ${CHANNEL_NAME} "" TRUE "")

endif()
