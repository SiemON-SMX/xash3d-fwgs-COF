# Reverse engineered Cry of Fear for Xash3D FWGS Engine <img align="right" width="128" height="128" src="https://github.com/SiemON-SMX/xash3d-fwgs-COF/raw/master/game_launch/icon-xash-material.png" alt="Xash3D FWGS icon" />
[![GitHub Actions Status](https://github.com/SiemON-SMX/xash3d-fwgs-COF/actions/workflows/c-cpp.yml/badge.svg)](https://github.com/SiemON-SMX/xash3d-fwgs-COF/actions/workflows/c-cpp.yml) \
[![Discord Server](https://img.shields.io/discord/1480939659021520979?logo=Discord&label=SiemON%20ports%20%28SP%20OF%29)](https://discord.gg/38YzeFrtvJ) \
[![Download Continuous Build](https://img.shields.io/badge/downloads-continuous-orange)](https://github.com/SiemON-SMX/xash3d-fwgs-COF/releases/tag/continuous)

A modified fork of Xash3D FWGS for Cry of Fear. Adds the engine-side support for the CoF client renderer: Paranoia GL hacks, GL state handoff, gl4es fixes, The renderer itself (GLSL Cg water, spotlight shader, dynamic lights, bump/specular, post-effects, glows, sky, decals) runs in the game's client library.

[Android]: Added Game Downloader for download Cry of Fear quickly. Thanks to [hasandramali](https://github.com/hasandramali).
[Touch]: Removed some touch buttons that's isn't nesscary for game.

## Installation & Running
0) Get Cry of Fear binaries: you can use [testing](https://github.com/SiemON-SMX/xash3d-fwgs-COF/releases/tag/continuous) build or you can compile engine from source code.
1) Copy engine binaries to some directory. (Don't worry, all binaries are prebuilt)
2) Copy `CryOfFear` directory from [Cry of Fear](https://store.steampowered.com/app/223710/Cry_of_Fear/) to directory with engine binaries.
3) Run the main executable (`xash3d.exe` or AppImage).

For additional info, run Xash3D with `-help` command line key.

### Android
0) Install the APK file.
1) Click to download button for download game files from STEAM.
2) Run Cry of Fear from within the app.

## Reporting issues
* Issues are accepted in English.
* You can report issues from our [Discord server](https://discord.gg/38YzeFrtvJ) too.

## Build instructions
We are using Waf build system. If you have some Waf-related questions, I recommend you to read [Waf Book](https://waf.io/book/).

**NOTE: NEVER USE GitHub's ZIP ARCHIVES. GitHub doesn't include external dependencies we're using!**

### Prerequisites
If your CPU is x86 compatible and you're on Windows or Linux, we are building 32-bit code by default. This was done to maintain compatibility with Steam releases of Half-Life and based on it's engine games.
Even if Xash3D FWGS does support targetting 64-bit, you can't load games without recompiling them from source code!

If your CPU is NOT x86 compatible or you decided build 64-bit version of engine, you may want to compile [Half-Life SDK](https://github.com/SiemON-SMX/hlsdk-portable).
This repository contains our fork of HLSDK and restored source code for Half-Life expansions and some mods.

#### Windows (Visual Studio)
* Install Visual Studio.
* Install latest [Python](https://python.org) **OR** run `cinst python.install` if you have Chocolatey.
* Install latest [Git](https://git-scm.com/download/win) **OR** run `cinst git.install` if you have Chocolatey.
* Download [SDL2](https://libsdl.org/download-2.0.php) development package for Visual Studio.
* Clone this repository: `git clone --recursive https://github.com/SiemON-SMX/xash3d-fwgs-COF`.
* Make sure you have at least 12GB of free space to store all build-time dependencies: ~10GB for Visual Studio, 300 MB for Git, 100 MB for Python and other.

#### GNU/Linux
##### Debian/Ubuntu
* Only for 32-bit engine on 64-bit x86 operating system:
  * Enable i386 on your system: `$ sudo dpkg --add-architecture i386`.
  * Install `aptitude` ([why?](https://github.com/FWGS/xash3d-fwgs/issues/1828#issuecomment-2415131759)):  `$ sudo apt update && sudo apt upgrade && sudo apt install aptitude`
  * Install development tools: `$ sudo aptitude --without-recommends install git build-essential gcc-multilib g++-multilib libsdl2-dev:i386 libfreetype-dev:i386 libopus-dev:i386 libbz2-dev:i386 libvorbis-dev:i386 libopusfile-dev:i386 libogg-dev:i386`.
  * Set PKG_CONFIG_PATH environment variable to point at 32-bit libraries: `$ export PKG_CONFIG_PATH=/usr/lib/i386-linux-gnu/pkgconfig`.

* For 64-bit engine on 64-bit x86 and other non-x86 systems:
  * Install development tools: `$ sudo apt install git build-essential python libsdl2-dev libfreetype6-dev libopus-dev libbz2-dev libvorbis-dev libopusfile-dev libogg-dev`.

* Clone this repostory: `$ git clone --recursive https://github.com/SiemON-SMX/xash3d-fwgs-COF`.

##### RedHat/Fedora
* Only for 32-bit engine on 64-bit x86 operating system:
  * Install development tools: `$ sudo dnf install git gcc gcc-c++ glibc-devel.i686 SDL3-devel.i686 sdl2-compat-devel.i686 opus-devel.i686 freetype-devel.i686 bzip2-devel.i686 libvorbis-devel.i686 opusfile-devel.i686 libogg-devel.i686 libatomic.i686`.
  * Set PKG_CONFIG_PATH environment variable to point at 32-bit libraries: `$ export PKG_CONFIG_PATH=/usr/lib/pkgconfig`.

* For 64-bit engine on 64-bit x86 and other non-x86 systems:
  * Install development tools: `$ sudo dnf install git gcc gcc-c++ SDL3-devel sdl2-compat-devel opus-devel freetype-devel bzip2-devel libvorbis-devel opusfile-devel libogg-devel`.

* Clone this repostory: `$ git clone --recursive https://github.com/SiemON-SMX/xash3d-fwgs-COF`.

#### Android (Windows/Linux/macOS)
* Install [Android Studio](https://developer.android.com/studio) (or the command line tools).
* Install [Python](https://python.org) (at least 2.7, latest is better).
* Install [Git](https://git-scm.com/download/win).
* Install [Ninja](https://ninja-build.org/).
* Install [CMake](https://cmake.org/) (for some dependencies).

* Clone this repostory: `$ git clone --recursive https://github.com/SiemON-SMX/xash3d-fwgs-COF`.
* Check out nanogl and gl4es, they aren't checked out by default: `$ git submodule update --init --checkout 3rdparty/nanogl 3rdparty/gl4es/gl4es`.

#### iOS/iPadOS
* Install Xcode from the appstore.
* Install [Homebrew package manager](https://brew.sh).

* Install build dependencies by running: `brew install python`.

* Clone the SDL2 repo `$ git clone --recursive https://github.com/libsdl-org/SDL.git -b SDL2` and compile the iOS framework by navigating to SDL/Xcode/SDL and opening the Xcode project.

* Clone this repository: `$ git clone --recursive https://github.com/SiemON-SMX/xash3d-fwgs-COF`.
* Check out nanogl, it isn't checked out by default: `$ git submodule update --init --checkout 3rdparty/nanogl`.

### Building
#### Windows (Visual Studio)
0) Open command line.
1) Navigate to `xash3d-fwgs-COF` directory.
2) (optional) Examine which build options are available: `waf --help`.
3) Configure build: `waf configure --sdl2=c:/path/to/SDL2`.
4) Compile: `waf build`.
5) Install: `waf install --destdir=c:/path/to/any/output/directory`.

#### Linux
If compiling 32-bit on amd64, make sure `PKG_CONFIG_PATH` from the previous step is set correctly, prior to running configure.

0) (optional) Examine which build options are available: `./waf --help`.
1) Configure build: `./waf configure` (you need to pass `-8` to compile 64-bit engine on 64-bit x86 processor).
2) Compile: `./waf build`.
3) Install: `./waf install --destdir=/path/to/any/output/directory`.

#### Android (Windows/Linux/macOS)
To build you should clone [SDL](https://github.com/libsdl-org/SDL) from `SDL2` branch and [HLSDK-portable](https://github.com/SiemON-SMX/hlsdk-portable) `CryOfFear` branch repositories to 3rdparty folder, after that you should be able to open the project in Android Studio from `android` directory or manually call Gradle to build the APK.

#### iOS/iPadOS (MacOS only)
0) (optional) Examine which build options are available: `./waf --help`.
1) Configure build: `./waf configure --ios --enable-bundled-deps --sdl2 (path/to/SDL2.framework)`, set `--ios-simulator` instead of `--ios` if you want to build for simulator.
2) Compile `./waf build`.
3) Navigate to `build` and copy your compiled SDL2.framework there, then add your game dylibs to `build/ios/libs/(gamedir)/(dlls/cl_dlls)`(You can also run `scripts/ios/buildhlsdk.sh` instead to automatically create an ipa with hlsdk dylibs)
4) Run `scripts/ios/createipa.sh` to create an installable ipa

### Running tests

Tests are enabled with `--enable-tests` passed to `./waf configure` and can be run with `./waf --alltests`.

This builds both standalone unit tests and a separate engine test binary (`xash3d_tests`) that embeds engine-level tests. The engine test binary requires no game assets.
