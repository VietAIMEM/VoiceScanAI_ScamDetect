cmake_minimum_required(VERSION 3.22.1)

project(scam_call_guard_native)

set(FASTTEXT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../fastText")
set(FASTTEXT_SRC "${FASTTEXT_ROOT}/src")

add_library(
    scam_fasttext
    SHARED
    fasttext_jni.cpp
    ${FASTTEXT_SRC}/args.cc
    ${FASTTEXT_SRC}/autotune.cc
    ${FASTTEXT_SRC}/densematrix.cc
    ${FASTTEXT_SRC}/dictionary.cc
    ${FASTTEXT_SRC}/fasttext.cc
    ${FASTTEXT_SRC}/loss.cc
    ${FASTTEXT_SRC}/matrix.cc
    ${FASTTEXT_SRC}/meter.cc
    ${FASTTEXT_SRC}/model.cc
    ${FASTTEXT_SRC}/productquantizer.cc
    ${FASTTEXT_SRC}/quantmatrix.cc
    ${FASTTEXT_SRC}/utils.cc
    ${FASTTEXT_SRC}/vector.cc
)

target_include_directories(
    scam_fasttext
    PRIVATE
    ${FASTTEXT_SRC}
)

target_compile_definitions(
    scam_fasttext
    PRIVATE
    FASTTEXT_ANDROID=1
)

find_library(log-lib log)

target_link_libraries(
    scam_fasttext
    ${log-lib}
)
