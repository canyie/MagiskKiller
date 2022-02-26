cmake_minimum_required(VERSION 3.4.1)

add_library(safetychecker SHARED main.cpp)

find_library(log-lib log)
target_link_libraries(safetychecker ${log-lib})
