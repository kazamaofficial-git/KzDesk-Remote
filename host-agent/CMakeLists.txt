cmake_minimum_required(VERSION 3.16)
project(kzdesk_remote_host LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(kzdesk_quic SHARED src/kzdesk_quic.cpp)
target_include_directories(kzdesk_quic PUBLIC include)
if(WIN32)
  target_link_libraries(kzdesk_quic PRIVATE ws2_32 ole32)
endif()

add_library(kzdesk_host_core SHARED src/kzdesk_host_core.cpp)
target_include_directories(kzdesk_host_core PUBLIC include)
if(WIN32)
  target_link_libraries(kzdesk_host_core PRIVATE ws2_32 dxgi advapi32 iphlpapi user32 gdi32)
endif()

add_executable(KzDeskRemoteHost src/main_host.cpp)
target_include_directories(KzDeskRemoteHost PRIVATE include)
target_link_libraries(KzDeskRemoteHost PRIVATE kzdesk_quic kzdesk_host_core)
