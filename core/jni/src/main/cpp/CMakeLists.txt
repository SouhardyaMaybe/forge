cmake_minimum_required(VERSION 3.22.1)

project("forge_native" LANGUAGES C CXX)

# No STL is needed: the native layer is plain C over POSIX/bionic only.
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(forge_jni SHARED
        forge_jni.c)

find_library(log_lib log)
target_link_libraries(forge_jni ${log_lib})
