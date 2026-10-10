cmake_minimum_required(VERSION 3.22.1)
project(feldnative C CXX)

add_library(feldnative SHARED feldhash.cpp)
target_include_directories(feldnative PRIVATE third_party/xxhash)
target_compile_options(feldnative PRIVATE -O3 -fvisibility=hidden)
find_library(log-lib log)
target_link_libraries(feldnative ${log-lib})
