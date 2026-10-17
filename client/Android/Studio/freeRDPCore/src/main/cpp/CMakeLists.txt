# FreeRDP: A Remote Desktop Protocol Implementation
# Android Client
#
# Copyright 2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
# Copyright 2013 Bernhard Miklautz <bernhard.miklautz@thincast.com>
# Copyright 2022 Ely Ronnen <elyronnen@gmail.com>
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

cmake_minimum_required(VERSION 3.13)

if(POLICY CMP0177)
  cmake_policy(SET CMP0177 NEW)
endif()
if(NOT FREERDP_DEFAULT_PROJECT_VERSION)
  set(FREERDP_DEFAULT_PROJECT_VERSION "1.0.0.0")
endif()

project("freerdp-android" LANGUAGES C CXX VERSION ${FREERDP_DEFAULT_PROJECT_VERSION})

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

message("project ${PROJECT_NAME} is using version ${PROJECT_VERSION}")

set(MODULE_NAME "freerdp-android")
set(MODULE_PREFIX "FREERDP_CLIENT_ANDROID")

get_filename_component(FREERDP_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)

include(GNUInstallDirs)

# Module path for our cmake helpers
list(APPEND CMAKE_MODULE_PATH "${FREERDP_SOURCE_DIR}/client/Android/cmake")
list(APPEND CMAKE_MODULE_PATH "${FREERDP_SOURCE_DIR}/cmake")

include(AndroidHelpers)
detect_ndk_root(NDK_ROOT)
detect_ndk_host_platform(NDK_HOST_PLATFORM)
get_android_api_level(NDK_API_LEVEL)

include(ExternalDeps)
set(${MODULE_PREFIX}_SRCS
    android_cliprdr.c
    android_cliprdr.h
    android_disp.c
    android_disp.h
    android_event.c
    android_event.h
    android_freerdp.c
    android_freerdp.h
    android_jni_utils.c
    android_jni_utils.h
    android_jni_callback.c
    android_jni_callback.h
    android_rail.c
    android_rail.h
)

add_library(${MODULE_NAME} SHARED ${${MODULE_PREFIX}_SRCS})

add_dependencies(${MODULE_NAME} copyLibs)
# KzDesk: dependensi TARGET-level ke ExternalProject 'freerdp'.
# copyLibs TIDAK depend ke freerdp (custom target tanpa deps) — tanpa baris
# ini, ninja boleh mulai compile android_*.c sebelum install step superbuild
# selesai, dan 'freerdp/config.h' (header di-generate) belum ada:
#   fatal error: 'freerdp/config.h' file not found
# Terlihat di CI RelWithDebInfo (release); race yang sama latent di Debug.
# Guard file-level di bawah tetap dipertahankan sebagai backstop.
add_dependencies(${MODULE_NAME} freerdp)

# --- KzDesk: header ordering guard -------------------------------------------
# 'freerdp/config.h' adalah header yang DI-GENERATE dan baru ter-install
# setelah install step dari ExternalProject 'freerdp' (superbuild) selesai.
# Pada varian RelWithDebInfo, ninja dapat mulai mengompilasi freerdp-android
# sebelum install step itu (dependensi phony via copyLibs tidak cukup),
# sehingga muncul "fatal error: freerdp/config.h file not found".
# Solusi: dependensi file-level eksplisit pada header yang di-generate.
# (Patched by KzDesk — lihat KZDESK-REMOTE-NOTICE.md)
set(FREERDP_GEN_CONFIG_HEADER
    "${DEPS_INSTALL_DIR}/${CMAKE_INSTALL_INCLUDEDIR}/freerdp3/freerdp/config.h")
add_custom_command(
  OUTPUT "${FREERDP_GEN_CONFIG_HEADER}"
  COMMAND ${CMAKE_COMMAND} -E echo "freerdp superbuild headers installed"
  DEPENDS freerdp
  COMMENT "Ensuring FreeRDP superbuild install has completed"
)
foreach(_kz_src IN LISTS ${MODULE_PREFIX}_SRCS)
  if(_kz_src MATCHES "\\.c$")
    set_property(SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/${_kz_src}"
                 APPEND PROPERTY OBJECT_DEPENDS "${FREERDP_GEN_CONFIG_HEADER}")
  endif()
endforeach()

find_library(log-lib log)
find_library(dl-lib dl)
find_library(jnigraphics-lib jnigraphics)

add_library(kzdesk-quic SHARED kzdesk_quic_client.cpp kzdesk_quic_client.h)
target_link_libraries(kzdesk-quic PRIVATE ${log-lib})

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries(
  ${MODULE_NAME} PRIVATE kzdesk-quic freerdp3-lib freerdp-client3-lib winpr3-lib ${log-lib} ${dl-lib} ${jnigraphics-lib}
)
