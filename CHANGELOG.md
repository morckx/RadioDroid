# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## Development snapshot [0.86.903-morckx] - 2025-07-05

### Fixed

- Fixed app crash when importing playlists with failed network requests
- Fixed fragment context crash in FragmentPlayerFull when called after detachment
- Fixed fragment deselection behavior for all devices (not just TV)
- Migrated back navigation to AndroidX OnBackPressedDispatcher for Android 16+ compatibility
- Resolved lint errors for gesture back navigation compatibility

### Added

- New setting to enable/disable radio-browser server availability checking
  - You can try this if your connection to the radio-browser server is is broken. I helps, however, only when the problem is that mirrors are listed in the DNS record, which do not work.

### Changed

- Updated OkHttp to version 5.0.0
- Updated Iconics library to version 5.4.0
- Updated Gradle to version 8.13
- Updated all other dependencies to latest versions
- Improved radio-browser server management: skip unavailable servers automatically
- Improved error handling in playlist import functionality
- Updated back button handling to use modern AndroidX APIs
- Enhanced TV remote control compatibility for drawer navigation

## Development snapshot [0.86.902-morckx] - 2025-06-11

- Fixed recording on newer Android versions
- Fixed playlist loading and saving on newer Android versions
- Fixed radio-browser server fallback
- Migrated from abandoned ExoPlayer to Media3
- Migrated from abandoned Picasso library to Glide
- Target SDK bumped from 34 to 36
- All dependencies updated
- Improved navigation on Android TV:
  - Station list can now be navigated with Channel Up/Down keys
  - Fast forward and rewind skips to the next/previous station
  - Use the 0 to key to open the drawer
- Improved UI updates in full player mode

## Development snapshot [0.86.900-morckx] - 2025-01-05

- Fixed radio-browser server fallback
- ExoPlayer library updated to 2.18.7
- All dependencies updated
- Target SDK updated to 34
  - but still tested and working on API level 16 (Jelly Bean)
- Norwegian Bokmål translation added
- Galician translation added
- Esperanto translation added
- Brazilian Portuguese translation updated

### Added
- Android Auto support
  - (you need to allow *Unknown Sources* in the Android Auto development settings as described [here](https://developer.android.com/training/cars/testing#step1))
- Option to call battery optimization settings

## [0.86] - 2023-09-28
### Added
- Auto stop support for auto start-play

### Changed
- Enabled android tv again
- Distribute package as AAB on play store from now on
- Sorting of entries from loaded files is now the same as the file

## [0.85] - 2023-09-27
### Fixed
- Building works again
- File dialog on android 13 uses system dialog and works now

### Added
- Translations: norwegian(nb), basque(eu)

### Changed
- Server fallback should work now even when the server return 502

## [0.84] - 2020-12-28
### Added
- Refreshable favorites and history lists
- Mark removed stations red, and broken stations yellow
- Translation updates
- Adaptive launcher icon
- Testing framework
- Stop button to MPD
- Very basic android TV support
- LastFM Api key changeable by user in settings menu

### Fixed
- Recording in android 10
- Correctly display audio players in list of external play
- Play audio warnings as music and not as alarm
- False negatives in hls stream detection

## [0.83] - 2020-04-15
### Changed
- "Remove from favorites" usability
- Track history with icons disabled (#774)

### Fixed
- Added fallback if dns resolve does not return anything
- Fix state updating of record button (#785)
- Show previously picked time when editing alarm's time (#784)
- Start recording after storage permissions are granted (#783)

## [0.82] - 2020-03-07
### Fixed
- Audio focus on pause
- Sudden stop of playback after it beeing resumed after connection loss

### Changed
- Swap station name and track name in full screen player

## [0.81] - 2020-03-03
### Added
- Export history to m3u

### Fixed
- Make sure all.api.radio-browser.info is not used directly
- Play time in fullscreen player
- Some crashes
- Stop notification relaunch after stop
- External player interactions
- Autostart of notification

### Changed
- Library: material 1.2.0-alpha05
- Library: gson 2.8.6
- Library: cast 18.1.0
- Library: lifecycle 2.2.0
- Library: searchpreference 2.0.0

## [0.80] - 2020-02-10
### Added
- Fullscreen player
- Password support for MPD
- Show warning for use of metered connections
- Flag symbols in countries tab
- History of the played tracks
- Stations search now shows results as you type
- Option to resume on wired or bluetooth device reconnection

### Fixed
- Connection issues with android 4 for most people

### Changed
- Library: OKhttp 3.12.8
- Library: Cast 18.0.0
- Use countrycode field from API instead of country field
- Reworked user interface for MPD which now allows explicit management of several servers
- Improved user interface of recordings

### Removed
- Server selection from settings. There is an automatic fallback now.
- Old main server is not used anymore (www.radio-browser.info/webservice)

