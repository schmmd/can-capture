# CAN Capture

Android app that records CAN bus traffic from a [`socketcand`](https://github.com/linux-can/socketcand)
server over the network and saves it as Vector ASCII (`.asc`) capture files.

## Features

- Connects to socketcand over plain TCP (default port 28600).
- Start / Stop recording with a large elapsed-time display, frame count, and last seen ID.
- On Stop, prompt to name the capture; it's saved to the app's internal storage.
- Captures tab lists previous recordings with date, duration, frame count, file size.
- Share via Android's system share sheet — Google Drive, Gmail, Files, etc. all
  appear automatically if installed.
- Single global host / port / bus, configured in Settings.

## Building

The project is configured for **Android Studio Koala (or newer)** with:

- Android Gradle Plugin 8.5.2
- Kotlin 2.0.21 + Compose Compiler plugin 2.0.21
- Compose BOM 2024.09.03
- min SDK 26 (Android 8.0), target / compile SDK 34, Java 17

1. Open this directory in Android Studio (`File → Open…` → select `can-capture`).
2. Studio will sync Gradle and create the Gradle wrapper.
3. Plug in an Android device (Developer Options → USB debugging) and Run.

If you'd rather build from the command line, run `gradle wrapper` once inside the
project to generate `gradlew`, then `./gradlew assembleDebug`.

## Running socketcand on the host

On a Linux machine with a CAN interface (real or virtual):

```bash
# real interface
sudo socketcand -i can0 -l 0.0.0.0 -p 28600

# virtual CAN for testing
sudo modprobe vcan
sudo ip link add dev vcan0 type vcan
sudo ip link set up vcan0
socketcand -i vcan0 -l 0.0.0.0 -p 28600

# generate test traffic
cangen vcan0 -g 50
```

In the app, open **Settings** and enter the host IP, port (`28600`), and bus
name (`can0` / `vcan0`). Then **Record → Start**.

## File format

Captures are Vector ASCII (`.asc`), single channel, hex base, absolute timestamps
relative to the first frame. The format is read by SavvyCAN, CANalyzer/CANoe,
`can-utils` (`asc2log`), and most automotive tooling.

## File locations on device

`/data/data/com.cancapture/files/captures/`

- `<name>.asc` — the capture
- `<name>.meta.json` — sidecar with duration / frame count / timestamp

## Limitations

- Recording must stay in the foreground. Backgrounding the app may eventually
  cause Android to kill the process and end the capture. (Adding a foreground
  service would lift this.)
- Cleartext TCP only (matches socketcand). The manifest enables
  `usesCleartextTraffic`; if you put socketcand behind TLS you'll need to adapt.
- Extended (29-bit) IDs, RTR frames, and standard data frames are supported.
  Error frames are dropped.
