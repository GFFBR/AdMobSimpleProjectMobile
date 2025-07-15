cmake_minimum_required(VERSION 3.18.1)

project("unlock_security")

add_library(
    unlock-jni
    SHARED
    Main.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    unlock-jni
    ${log-lib}
)
