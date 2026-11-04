# SpeedG

**English** | [العربية](README.ar.md)

Known in Arabic as **زخم** (Zakhm), meaning "momentum".

An Android app that shows your car's speed and G-force using your phone's sensors, with optional speed from OBD-II or Android Auto. No account, no ads and no extra hardware required.

<p align="center">
  <img src="docs/images/portrait.png" alt="SpeedG in portrait, waiting for a GPS signal" height="520">
</p>
<p align="center">
  <img src="docs/images/landscape.png" alt="SpeedG in landscape" width="720">
</p>

*Screenshots were taken indoors while parked, so the app is still waiting for a GPS signal and no speed is shown.*

> **Project status:** SpeedG has run on an Android 13 phone, and its calculations pass tests on simulated drives. It has not been checked on a real drive yet, so its road accuracy is still unmeasured. OBD-II and Android Auto have not been tested with a real car or adapter.

## Features

- **Speed** in km/h or mph, combining GPS with the accelerometer to reduce lag
- **Choice of speed source**: phone GPS, a Bluetooth OBD-II adapter or Android Auto, switching to GPS if a car source stops
- **Saved cars** that each keep their own speed source, calibration and peak values
- **Automatic car recognition** by VIN over OBD-II, or by make, model and year over Android Auto
- **G-force meter** that separates acceleration, braking and cornering, always using the phone's sensors
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

If you move the phone in its mount, tap the settings icon, then **Calibrate**.

### Speed from your car (optional)

Choose the source under **Speed source** in settings.

- **OBD-II:** you need a classic Bluetooth ELM327 adapter, as Bluetooth LE adapters aren't supported. Pair it in Android's settings, then choose it in SpeedG.
- **Android Auto:** turn on Android Auto's developer mode and allow **Unknown sources**. Keep SpeedG open on both the car screen and your phone. Not every car shares its speed.

Android Auto can't tell two cars of the same model and year apart. OBD-II can, if the car shares its VIN.

**Stay safe:** set the app up before driving, and let a passenger handle the phone while the car is moving.

## How it works

| Reading | Sensors | Method |
|---|---|---|
| Speed | GPS and accelerometer | A Kalman filter uses acceleration between GPS fixes, while GPS corrects the drift |
| Down and hills | Accelerometer and gyroscope | The gyroscope tracks tilt, and GPS-derived motion corrects it over time |
| Forward direction | GPS and accelerometer | Straight-line speed changes are matched with the force the phone feels |

## Privacy

- Your location is used only to read GPS speed. It is never saved or sent anywhere.
- Drive recordings contain sensor readings and speed, but no position or VIN. They stay on your phone unless you share them.
- Saved cars, including any VIN or Bluetooth adapter address, stay in the app's private storage on your phone.
- Bluetooth permission is only used to reach your OBD-II adapter.
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
