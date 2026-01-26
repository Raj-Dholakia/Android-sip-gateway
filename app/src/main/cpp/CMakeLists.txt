cmake_minimum_required(VERSION 3.10)

project("gsm_audio")

# tinyalsa library
add_library(tinyalsa STATIC
    tinyalsa/pcm.c
    tinyalsa/mixer.c
)

target_include_directories(tinyalsa PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/tinyalsa/include
)

# GSM Audio JNI library
add_library(gsm_audio SHARED
    gsm_audio_jni.c
)

target_include_directories(gsm_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/tinyalsa/include
)

target_link_libraries(gsm_audio
    tinyalsa
    log
)
