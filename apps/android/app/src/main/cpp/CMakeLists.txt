cmake_minimum_required(VERSION 3.22.1)
project(gbotneedle CXX)

# Cactus's prebuilt runtime (Apache-2.0), fetched from
# huggingface.co/Cactus-Compute/cactus releases/4.0.0/android-arm64.
add_library(cactus STATIC IMPORTED)
set_target_properties(cactus PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/cactus/${ANDROID_ABI}/libcactus.a)

add_library(gbotneedle SHARED needle.cpp)
target_include_directories(gbotneedle PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/cactus)
target_link_libraries(gbotneedle cactus android log)
