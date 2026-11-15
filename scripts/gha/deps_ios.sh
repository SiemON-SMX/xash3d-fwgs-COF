#!/bin/bash

cd "$GITHUB_WORKSPACE" || exit 1

# not checked out by default, iOS builds ref_gles1
git submodule update --init --checkout 3rdparty/nanogl || exit 1

git clone https://github.com/libsdl-org/SDL -b "release-$SDL_VERSION"

cd SDL/Xcode/SDL || exit 1
xcodebuild -scheme xcFramework-iOS -target xcFramework-iOS build -configuration Release
sudo cp -vr Products/SDL2.xcframework/ios-arm64/SDL2.framework /Library/Frameworks

cd "$GITHUB_WORKSPACE" || exit 1

git clone --recursive https://github.com/SiemON-SMX/hlsdk-portable hlsdk -b CryOfFear --depth=1
