# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

# Declares and names the project.

project("videopro")

# 1. Define where the headers (include folder) are
include_directories(${CMAKE_SOURCE_DIR}/include)

# 2. Path to your compiled .so files
set(FFMPEG_LIB_PATH ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 3. Import each FFmpeg library module
set(ffmpeg_libs avcodec avfilter avformat avutil swresample swscale)

foreach(lib ${ffmpeg_libs})
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION
                          ${FFMPEG_LIB_PATH}/lib${lib}.so)
endforeach()

# 4. Create your own native library and link FFmpeg to it
add_library(videopro SHARED native-lib.cpp)

target_link_libraries(videopro
                      ${ffmpeg_libs}
                      android
                      jnigraphics
                      log)
