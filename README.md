# SpeedG

**English** | [العربية](README.ar.md)

An Android app that shows your car's speed and G-force using only your phone's sensors. No account, no ads and no extra hardware.

<p align="center">
  <img src="docs/images/portrait.png" alt="SpeedG in portrait, waiting for a GPS signal" height="520">
</p>
<p align="center">
  <img src="docs/images/landscape.png" alt="SpeedG in landscape" width="720">
</p>

*Screenshots were taken indoors while parked, so the app is still waiting for a GPS signal and no speed is shown.*

> **Project status:** SpeedG has run on an Android 13 phone, and its calculations pass tests on simulated drives. It has not been checked on a real drive yet, so its road accuracy is still unmeasured.

## Features

- **Speed** in km/h or mph, combining GPS with the accelerometer to reduce lag
- **G-force meter** that separates acceleration, braking and cornering
- **Peak values** for each direction, with a reset button
- **Any mounting angle**: the app works out which way is down and forward
- **Hill correction**, so a slope doesn't read as acceleration
- **Honest readings**: speed is marked as an estimate when GPS is lost, then hidden after a few seconds
- **Screen modes**: Auto, Portrait or Landscape
- **Drive recording** to a file you can share, containing no location
- **Update alerts** when a new version is released

## Install

1. Download the latest `SpeedG-*.apk` from [Releases](https://github.com/alequab/SpeedG/releases/latest).
2. Open the file on your phone and allow installs from that source if Android asks.
3. Allow precise location when SpeedG asks.

SpeedG needs Android 8.0 or newer and a phone with GPS and an accelerometer. A gyroscope is recommended.

## First use

1. Fix the phone firmly in a mount while the car is parked, and keep it still for a few seconds.
2. Wait outside until the GPS message clears.
3. On a safe, straight road, speed up and slow down gently a few times until the app says **Calibrated**.

Tap **Calibrate** if you move the phone in its mount.

**Stay safe:** set the app up before driving, and let a passenger handle the phone while the car is moving.

## How it works

| Reading | Sensors | Method |
|---|---|---|
| Speed | GPS and accelerometer | A Kalman filter uses acceleration between GPS fixes, while GPS corrects the drift |
| Down and hills | Accelerometer and gyroscope | The gyroscope tracks tilt, and GPS-derived motion corrects it over time |
| Forward direction | GPS and accelerometer | Straight-line speed changes are matched with the force the phone feels |

## Privacy

- Your location is used only to read GPS speed. It is never saved or sent anywhere.
- Drive recordings contain sensor readings and speed, but no position. They stay on your phone unless you share them.
- Once each time the app starts, it asks an update server for the newest version number. That request sends no sensor or location data, though the server can see your IP address, as with any internet request.

## Build from source

You need [Android Studio](https://developer.android.com/studio) or the Android SDK with platform 37.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Builds from source don't include update alerts.

## License

SpeedG is free for **individuals** to use, modify and share for personal purposes. **Companies and organizations may not use it** without written permission, including freelancers and one-person businesses using it for their work. It may not be sold. See [LICENSE](LICENSE) for the full terms.

Because of these limits, SpeedG is source-available rather than open source. The third-party components it includes keep their own licenses, listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
