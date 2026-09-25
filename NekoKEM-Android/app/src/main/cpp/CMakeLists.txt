cmake_minimum_required(VERSION 3.22.1)

project(nekokem_android LANGUAGES C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

if(NOT ANDROID)
    message(FATAL_ERROR "NekoKEM Android native code requires the Android NDK")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported")
endif()

get_filename_component(
    NEKOKEM_REPOSITORY_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.."
    ABSOLUTE
)

set(
    OPENSSL_ANDROID_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/openssl/arm64-v8a"
)
set(OPENSSL_CRYPTO_LIBRARY "${OPENSSL_ANDROID_ROOT}/lib/libcrypto.a")

if(NOT EXISTS "${OPENSSL_CRYPTO_LIBRARY}")
    message(
        FATAL_ERROR
        "Android arm64 OpenSSL is missing. Run scripts/build-openssl-android.sh"
    )
endif()

add_library(openssl_crypto STATIC IMPORTED GLOBAL)
set_target_properties(
    openssl_crypto
    PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_CRYPTO_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_ANDROID_ROOT}/include"
)

set(
    NEKOKEM_CORE_SOURCES
    "${NEKOKEM_REPOSITORY_ROOT}/nekokem_core/src/nekokem.c"
    "${NEKOKEM_REPOSITORY_ROOT}/nekokem_core/src/key_management.c"
    "${NEKOKEM_REPOSITORY_ROOT}/nekokem_core/src/nekokem_v3.c"
    "${NEKOKEM_REPOSITORY_ROOT}/src/kem.c"
    "${NEKOKEM_REPOSITORY_ROOT}/src/hybrid.c"
    "${NEKOKEM_REPOSITORY_ROOT}/src/aes.c"
    "${NEKOKEM_REPOSITORY_ROOT}/src/file.c"
    "${NEKOKEM_REPOSITORY_ROOT}/src/file_v3.c"
    "${NEKOKEM_REPOSITORY_ROOT}/src/secure_mem.c"
    "${NEKOKEM_REPOSITORY_ROOT}/src/private_key.c"
)

# main.c and cli.c are intentionally absent: Android links only reusable Core.
add_library(nekokem_core STATIC ${NEKOKEM_CORE_SOURCES})
set_target_properties(nekokem_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_include_directories(
    nekokem_core
    PUBLIC "${NEKOKEM_REPOSITORY_ROOT}/nekokem_core/include"
    PRIVATE "${NEKOKEM_REPOSITORY_ROOT}/src"
)

# The Android NDK toolchain supplies its supported _FORTIFY_SOURCE setting.
target_compile_definitions(
    nekokem_core
    PRIVATE
        _POSIX_C_SOURCE=200809L
)

target_compile_options(
    nekokem_core
    PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Wconversion
        -Wshadow
        -Wformat=2
        -Wstrict-prototypes
        -Werror
        -fstack-protector-strong
)

target_link_libraries(nekokem_core PUBLIC openssl_crypto)

add_library(nekokem_jni SHARED jni_bridge.c)
target_sources(nekokem_jni PRIVATE key_management_jni.c)
target_sources(nekokem_jni PRIVATE password_jni.c)
target_sources(nekokem_jni PRIVATE progress_jni.c)
target_include_directories(
    nekokem_jni
    PRIVATE "${NEKOKEM_REPOSITORY_ROOT}/nekokem_core/src"
)

target_compile_options(
    nekokem_jni
    PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Wconversion
        -Wshadow
        -Wformat=2
        -Wstrict-prototypes
        -Werror
        -fstack-protector-strong
)

target_link_options(
    nekokem_jni
    PRIVATE
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,--exclude-libs,ALL
)

find_library(ANDROID_LOG_LIBRARY log REQUIRED)
find_library(ANDROID_DL_LIBRARY dl REQUIRED)

target_link_libraries(
    nekokem_jni
    PRIVATE
        nekokem_core
        "${ANDROID_LOG_LIBRARY}"
        "${ANDROID_DL_LIBRARY}"
)
