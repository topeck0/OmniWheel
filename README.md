# OmniWheel

Turn your Android phone into a virtual steering wheel for PC racing games.

## What is it?

OmniWheel consists of two applications:

- **OmniWheel Android** — Your phone becomes a steering wheel, pedals, buttons, and gyroscope controller
- **OmniWheel PC** — Receives input from the phone and translates it into a virtual steering wheel via vJoy

This is **not** a normal gamepad. It behaves like a real steering wheel with spring-return physics.

## Status

> **Phase: Skeleton Build** — Apps compile and run. Features being added incrementally.

## Downloads

See [Releases](https://github.com/topeck0/OmniWheel/releases) for the latest builds.

## Requirements

### Android
- Android 5.0+ (API 21)
- Supports 32-bit (armv7) and 64-bit (arm64) devices

### Windows
- Windows 10/11
- [vJoy](https://sourceforge.net/projects/vjoystick/) installed
- .NET 8.0 Runtime (included in self-contained build)

## Building

### Android
```bash
cd android
./gradlew assembleDebug
```

### Windows
```bash
cd windows/src/OmniWheelPC
dotnet publish -c Release -r win-x64 --self-contained
```

## Architecture

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the complete engineering specification.

## License

MIT
