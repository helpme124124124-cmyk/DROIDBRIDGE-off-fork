cmake_minimum_required(VERSION 3.22.1)
project(javalauncher_native)

add_library(javalauncher_native SHARED native-lib.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(
        javalauncher_native
        ${log-lib}
        ${dl-lib}
)