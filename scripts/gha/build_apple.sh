#!/bin/bash

. scripts/lib.sh

# Keep the minimum macOS version consistent across arm64 and amd64 builds.
export MACOSX_DEPLOYMENT_TARGET=14.8

cd "$GITHUB_WORKSPACE" || die

pushd hlsdk || die
./waf configure build install --destdir=../bin || die
popd || die

if [ -d "bin/valve" ]; then
	mkdir -p bin/CryOfFear
	cp -r bin/valve/* bin/CryOfFear/
	rm -rf bin/valve
fi

./waf configure --enable-utils --enable-tests --enable-lto --enable-tui || die_configure
./waf build -v || die
./waf install --destdir=bin || die

cp -vr /Library/Frameworks/SDL2.framework bin

mkdir -p artifacts/
tar -cJvf "artifacts/cry-of-fear-apple-$ARCH.tar.xz" -C bin . # skip the bin directory from resulting tar archive
