cmake_minimum_required(VERSION 3.22.1)
project(viture_camera_bridge)

add_library(viture_camera_bridge SHARED viture_camera_bridge.cpp)

target_compile_options(viture_camera_bridge PRIVATE -Wall -Wextra -Werror)
target_link_options(viture_camera_bridge PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(viture_camera_bridge log dl)
