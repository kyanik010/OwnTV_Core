cmake_minimum_required(VERSION 3.22.1)

project(owntv_audiomix_gst LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED GSTREAMER_ROOT_ANDROID)
    message(FATAL_ERROR "GSTREAMER_ROOT_ANDROID is not defined")
endif()

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GSTREAMER_ROOT "${GSTREAMER_ROOT_ANDROID}/armv7")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GSTREAMER_ROOT "${GSTREAMER_ROOT_ANDROID}/arm64")
elseif(ANDROID_ABI STREQUAL "x86")
    set(GSTREAMER_ROOT "${GSTREAMER_ROOT_ANDROID}/x86")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GSTREAMER_ROOT "${GSTREAMER_ROOT_ANDROID}/x86_64")
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()

list(APPEND CMAKE_MODULE_PATH "${GSTREAMER_ROOT}/share/cmake")

include("${GSTREAMER_ROOT}/share/gst-android/ndk-build/plugins.cmake")

set(GSTREAMER_PLUGINS
    ${GSTREAMER_PLUGINS_CORE}
    ${GSTREAMER_PLUGINS_PLAYBACK}
    ${GSTREAMER_PLUGINS_CODECS}
    ${GSTREAMER_PLUGINS_CODECS_RESTRICTED}
    ${GSTREAMER_PLUGINS_EFFECTS}
    ${GSTREAMER_PLUGINS_NET}
    ${GSTREAMER_PLUGINS_SYS}
)

set(GStreamer_EXTRA_DEPS
    gstreamer-video-1.0
    glib-2.0
)

set(G_IO_MODULES openssl)

find_library(ANDROID_LIB android REQUIRED)
find_library(LOG_LIB log REQUIRED)

find_package(GStreamerMobile COMPONENTS ${GSTREAMER_PLUGINS} ca_certificates REQUIRED)

add_library(owntv_audiomix_gst SHARED audiomix_gst.cpp)

target_compile_definitions(owntv_audiomix_gst PRIVATE
    GST_USE_UNSTABLE_API
)

target_link_libraries(owntv_audiomix_gst
    PRIVATE
    GStreamer::mobile
    ${ANDROID_LIB}
    ${LOG_LIB}
)
