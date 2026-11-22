cmake_minimum_required(VERSION 3.22.1)
project("amkt_crypto" LANGUAGES CXX)

add_library(
    amkt_crypto
    SHARED
    amkt_crypto.cpp
)

target_compile_features(amkt_crypto PRIVATE cxx_std_17)
target_compile_options(amkt_crypto PRIVATE -fvisibility=hidden -O2)

find_library(log-lib log)

target_link_libraries(
    amkt_crypto
    ${log-lib}
)
