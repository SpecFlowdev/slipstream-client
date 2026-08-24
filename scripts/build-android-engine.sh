#!/usr/bin/env bash
# Cross-compiles the slipstream tunnel for Android ABIs.
#
# Usage:  build-android-engine.sh <upstream-checkout> <output-dir> [abi...]
#
# The abis are Rust target triples; they default to the four Android ships.
# Output is one `libslipstream.so` per Android ABI directory, named that way
# because the APK's library directory is the only place an app is allowed to
# execute its own programs from.
#
# Everything the NDK needs is worked out here rather than in the workflow, so
# the same recipe builds the engine on its own and as part of the APK.
set -euo pipefail

UPSTREAM="${1:?usage: build-android-engine.sh <upstream> <out> [abi...]}"
OUT="${2:?usage: build-android-engine.sh <upstream> <out> [abi...]}"
shift 2
TARGETS=("$@")
if [[ ${#TARGETS[@]} -eq 0 ]]; then
  TARGETS=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android)
fi

# API 24 is the floor for the socket calls picoquic uses, and old enough to
# cover any device likely to run this.
API="${ANDROID_API:-24}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [[ -z "${NDK}" || ! -d "${NDK}" ]]; then
  NDK="$(ls -d "${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[[ -n "${NDK}" && -d "${NDK}" ]] || { echo "no Android NDK found" >&2; exit 1; }

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "${TOOLCHAIN}/clang" ]] || { echo "no clang in ${TOOLCHAIN}" >&2; exit 1; }
export ANDROID_NDK_HOME="${NDK}"
# openssl's Configure looks for the NDK under this name specifically.
export ANDROID_NDK_ROOT="${NDK}"

# OpenSSL's makefile still calls the binutils by their old triple-prefixed
# names. NDK r23 dropped those wrappers and ships one llvm-* copy of each, so
# `make install_dev` dies with a bare "not found" unless the names are put
# back. A directory of symlinks ahead of PATH is the whole fix.
SHIM="$(mktemp -d)"
trap 'rm -rf "${SHIM}"' EXIT
for triple in aarch64-linux-android armv7a-linux-androideabi arm-linux-androideabi \
              x86_64-linux-android i686-linux-android; do
  for tool in ar ranlib nm strip objcopy objdump readelf; do
    ln -sf "${TOOLCHAIN}/llvm-${tool}" "${SHIM}/${triple}-${tool}"
  done
done
export PATH="${SHIM}:${TOOLCHAIN}:${PATH}"

mkdir -p "${OUT}"
cd "${UPSTREAM}"

for target in "${TARGETS[@]}"; do
  # picoquic is built by CMake through the NDK's own toolchain file, which
  # wants Android's ABI names rather than Rust's target triples.
  case "${target}" in
    aarch64-linux-android)     CLANG=aarch64-linux-android;    ABI=arm64-v8a ;;
    armv7-linux-androideabi)   CLANG=armv7a-linux-androideabi; ABI=armeabi-v7a ;;
    x86_64-linux-android)      CLANG=x86_64-linux-android;     ABI=x86_64 ;;
    i686-linux-android)        CLANG=i686-linux-android;       ABI=x86 ;;
    *) echo "unknown target ${target}" >&2; exit 1 ;;
  esac

  UPPER="$(echo "${target}" | tr 'a-z-' 'A-Z_')"
  SUFFIX="${target//-/_}"
  export CC_${SUFFIX}="${TOOLCHAIN}/${CLANG}${API}-clang"
  export CXX_${SUFFIX}="${TOOLCHAIN}/${CLANG}${API}-clang++"
  export AR_${SUFFIX}="${TOOLCHAIN}/llvm-ar"
  export RANLIB_${SUFFIX}="${TOOLCHAIN}/llvm-ranlib"
  export CARGO_TARGET_${UPPER}_LINKER="${TOOLCHAIN}/${CLANG}${API}-clang"
  # The C shims beside the Rust are compiled by upstream's own build script,
  # which for Android targets reads these two and otherwise falls back to the
  # plain host `cc`. Without them everything compiles and the link then fails
  # with "is incompatible with aarch64linux", because the shims are x86-64.
  export RUST_ANDROID_GRADLE_CC="${TOOLCHAIN}/${CLANG}${API}-clang"
  export RUST_ANDROID_GRADLE_AR="${TOOLCHAIN}/llvm-ar"
  export ANDROID_ABI="${ABI}"
  export ANDROID_PLATFORM="android-${API}"
  # One build directory per ABI. The default is shared, so the second target
  # would otherwise link the first one's objects.
  export PICOQUIC_BUILD_DIR="${PWD}/.picoquic-build/${target}"

  # OpenSSL has to exist before picoquic is configured, and its library has to
  # be named outright: CMake's FindOpenSSL locates the headers from
  # OPENSSL_ROOT_DIR but finds the library with find_library, which the NDK's
  # toolchain file confines to the sysroot. A vendored libcrypto.a in cargo's
  # target directory is invisible to that, and configuring stops at
  # "missing: OPENSSL_CRYPTO_LIBRARY".
  #
  # Running the real build with picoquic's auto-build switched off gets
  # openssl-sys built with exactly the features the real build will use, and
  # stops at slipstream-ffi. That failure is the point of the pass.
  echo "--- ${target}: OpenSSL"
  PICOQUIC_AUTO_BUILD=0 cargo build --release \
    --target "${target}" \
    -p slipstream-client \
    --features slipstream-ffi/openssl-vendored,slipstream-ffi/picoquic-minimal-build \
    >/dev/null 2>&1 || true

  SSL_LIB="$(ls -d "target/${target}"/release/build/openssl-sys-*/out/openssl-build/install/lib 2>/dev/null | head -1 || true)"
  if [[ -n "${SSL_LIB}" && -f "${SSL_LIB}/libcrypto.a" ]]; then
    export OPENSSL_ROOT_DIR="$(dirname "${SSL_LIB}")"
    export OPENSSL_INCLUDE_DIR="${OPENSSL_ROOT_DIR}/include"
    export OPENSSL_CRYPTO_LIBRARY="${SSL_LIB}/libcrypto.a"
    export OPENSSL_SSL_LIBRARY="${SSL_LIB}/libssl.a"
    export OPENSSL_USE_STATIC_LIBS=TRUE
    echo "    ${SSL_LIB}"
  else
    echo "    none found; CMake will search for itself" >&2
  fi

  echo "--- ${target}: tunnel"
  cargo build --release \
    --target "${target}" \
    -p slipstream-client \
    --features slipstream-ffi/openssl-vendored,slipstream-ffi/picoquic-minimal-build

  mkdir -p "${OUT}/${ABI}"
  cp "target/${target}/release/slipstream-client" "${OUT}/${ABI}/libslipstream.so"
  "${TOOLCHAIN}/llvm-strip" "${OUT}/${ABI}/libslipstream.so"
  file "${OUT}/${ABI}/libslipstream.so" || true
done

echo
echo "built:"
find "${OUT}" -name '*.so' -exec ls -la {} \;
