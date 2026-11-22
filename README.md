# ISS Tracker

An Android app that tells you when the International Space Station passes over you, where to look,
and when you can see it with your own eyes. It works offline, doesn't need Google Play Services,
and keeps your location on your phone.

## Features

- **Pass list** — every pass for the next 10 days, grouped by day: rise → highest point → set, with
  compass directions, maximum elevation and duration. Passes you can see with the naked eye
  (the ISS is sunlit while your sky is dark) are marked with a ★.
- **Next / current pass** — a countdown to the next pass, or live azimuth and elevation during one.
- **Pass details** — a sky plot of the path (centre = straight up, edge = horizon, north up) with the
  part that is visible to the naked eye highlighted.
- **Find in sky** — point the phone at the ISS: a ring shows where to move the phone, with
  "turn right / tilt up" guidance and a vibration when you're on target. Use the back of the phone
  (held like a camera) or its top edge (e.g. strapped along an antenna, binoculars or a camera).
  The compass is corrected to true north. While the ISS is below the horizon it points at where
  the next pass will rise.
- **Reminders** — tap the bell on a pass to get a notification before it rises or reaches its
  highest point (5–60 minutes before, default 15). Uses exact alarms, survives reboots, and follows
  updated orbit data.
- **Location** — your current position (GPS / network) or a saved location you enter by hand.
- **Minimum elevation** — hide low passes behind trees and buildings (0° / 5° / 10° / 20°).
- **Themes** — system, light, dark, and a red-on-black night mode that keeps your eyes dark-adapted.
- **Details screen** — distance, range rate, sunlight, sun elevation and the age of the orbit data.

## Privacy

- Your location is only used on the phone to calculate the passes. It is never sent anywhere.
- The only network request is downloading the ISS orbit data (TLE) from
  [CelesTrak](https://celestrak.org/), about once a day. It contains no personal data.
- No ads, no analytics, no Google Play Services.

## Requirements

- Android 12 (API 31) or newer.
- Works on de-Googled Android such as GrapheneOS: it uses the platform `LocationManager`
  (AOSP fused provider, GPS or network), not Google's location services.

### Permissions

| Permission | Why |
|---|---|
| Internet | Download orbit data from CelesTrak. |
| Location (precise or approximate) | Calculate passes for where you are. Approximate is enough for pass times; precise helps "Find in sky". Optional if you use a saved location. |
| Notifications | Pass reminders. |
| Exact alarms (`USE_EXACT_ALARM`) | Reminders arrive on time, also when the phone is idle. |
| Run at startup | Re-arm reminders after a reboot. |

## How it works

- **Orbit data:** the current two-line element set (TLE) for the ISS (NORAD 25544) from CelesTrak's GP
  API, cached on the phone and refreshed daily in the background (WorkManager). The app warns when
  the data is more than 3 days old.
- **Orbit model:** a Kotlin port of the SGP4 propagator from David Vallado's reference implementation
  (*Revisiting Spacetrack Report #3*, AIAA 2006-6753). Only near-earth orbits (period under 225 minutes,
  like the ISS) are supported.
- **Look angles:** TEME → Earth-fixed via GMST, observer on the WGS-84 ellipsoid, giving azimuth,
  elevation, range and range rate.
- **Visibility:** a low-precision solar ephemeris and a cylindrical Earth shadow; a pass is visible
  when the ISS is sunlit, above the horizon, and the sun is more than 6° below your horizon.
- **Pass prediction:** 30-second search steps, bisection for rise/set and golden-section search for
  the highest point (about 0.1 s precision).
- **Find in sky:** the rotation-vector sensor gives the phone's orientation; the target direction is
  rotated into the phone's frame, and the compass is corrected with the magnetic declination
  (`GeomagneticField`).

### Accuracy

The calculations are unit-tested against independent, well-established implementations:

| Check | Compared with | Worst difference |
|---|---|---|
| SGP4 positions (7 test orbits) | python-sgp4 (Vallado's code) | < 0.00001 mm |
| Azimuth / elevation | Skyfield | 0.0012° |
| Range | Skyfield | 33 m |
| Sun direction | Skyfield + JPL DE421 | 0.010° |
| Pass rise / highest / set times (66 passes) | Skyfield `find_events` | 0.27 s |
| Visible-to-the-eye flag | Skyfield | 66 / 66 identical |

The real-world accuracy is limited by the orbit data itself: TLEs drift by a few km per day, which
is why the app refreshes them daily.

## Building

Open the project in Android Studio, or from the command line (JDK 17+):

```sh
./gradlew assembleDebug          # build the debug APK
./gradlew testDebugUnitTest      # run the unit tests
./gradlew lintDebug              # Android lint
```

The debug APK ends up in `app/build/outputs/apk/debug/`.

### Regenerating the test reference data

`tools/generate_orbit_reference.py` produces `ReferenceData.kt` (used by the orbit tests) with
python-sgp4 and Skyfield. Run it from a scratch directory outside the project, because Skyfield
downloads the JPL ephemeris `de421.bsp` (~17 MB) into the current directory:

```sh
python -m venv venv
venv/bin/pip install sgp4 skyfield          # venv\Scripts\pip on Windows
venv/bin/python <project>/tools/generate_orbit_reference.py
```

## Project structure

```
app/src/main/java/io/github/kevinnortier/isstracker/
├── orbit/       TLE parser, SGP4, frames, look angles, sun, pass prediction (pure Kotlin)
├── data/        TLE download + cache + daily refresh, settings (DataStore)
├── location/    device location (LocationManager)
├── notify/      pass reminders: planning, exact alarms, notifications, boot receiver
├── sensors/     orientation sensor and pointing math
└── ui/          Compose screens: passes, pass details, find in sky, details, settings
```

## Credits

- Orbit data: [CelesTrak](https://celestrak.org/) (Dr T.S. Kelso).
- SGP4: David A. Vallado, Paul Crawford, Richard Hujsak, T.S. Kelso, *Revisiting Spacetrack Report #3*
  (AIAA 2006-6753).
- Test references: [python-sgp4](https://pypi.org/project/sgp4/) (Brandon Rhodes) and
  [Skyfield](https://rhodesmill.org/skyfield/).

## License

Copyright (C) 2026 Kevin Nortier

ISS Tracker is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version (SPDX: `GPL-3.0-or-later`).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[LICENSE](LICENSE) file for the full text.
