# ShizukuTrackpad

Non-rooted Android (Kotlin, Jetpack Compose) app that turns your smartphone screen into a precision OLED trackpad and PC shortcut deck for an external monitor using **Shizuku** (`UserService`) and hidden `InputManager` APIs.

## Features

- **External Display Auto-Detection**: Queries connected displays via `DisplayManager`, automatically routes cursor & key events to the external monitor (`displayId != 0`), and supports display `0` selection for local testing.
- **Shizuku `UserService` Input Injection**:
  - Uses `org.lsposed.hiddenapibypass:hiddenapibypass` (`HiddenApiBypass.addHiddenApiExemptions("")`) inside `InputInjectorService`.
  - Routes all `MotionEvent` and `KeyEvent` instances to the target display via `setDisplayId(targetDisplayId)` reflection before calling `InputManager.getInstance().injectInputEvent(event, 0)` (ASYNC mode).
  - Maintains virtual cursor coordinates `(cursorX, cursorY)` clamped to the target display resolution (rotation-aware).
- **Multi-Touch Trackpad Gestures**:
  - **1-finger drag**: Cursor movement (`SOURCE_MOUSE` + `TOOL_TYPE_MOUSE`, `ACTION_HOVER_MOVE`).
  - **1-finger tap**: Standard left-click (`ACTION_DOWN` → `ACTION_BUTTON_PRESS` → `ACTION_BUTTON_RELEASE` → `ACTION_UP` with `BUTTON_PRIMARY`).
  - **2-finger tap**: Standard right-click (`BUTTON_SECONDARY`).
  - **2-finger drag**: Vertical & horizontal mouse wheel scrolling (`ACTION_SCROLL` with `AXIS_VSCROLL` / `AXIS_HSCROLL`, with natural scroll direction toggle).
  - **Double-tap & hold drag**: Window dragging / text selection (`BUTTON_PRIMARY` held with `ACTION_MOVE` until release).
  - **Integrated Clickpad Floor (`L | R`)**: Optional flush hardware-style bottom left/right click zones (left zone supports hold-to-drag).
- **PC Shortcut Tray & Soft Keyboard (Korean / ASCII) Integration**:
  - Collapsible tactile keycap bar (1 unified row in landscape, 2 rows in portrait) with `Esc`, `Tab`, `Ctrl+C`, `Ctrl+V`, `Alt+Tab`, `Win+D`, Sticky modifiers (`Ctrl`, `Alt`, `Shift`, `Win`), arrows, `Backspace`, and `Enter`.
  - Dual-mode soft keyboard bridge: invisible `BasicTextField` for zero-latency direct ASCII/symbol/Backspace injection + auto-switching 1-line **Korean Input Bar** (`injectPasteText` clipboard + `Ctrl+V`).
- **OLED Hardware UI**:
  - Pure `#000000` trackpad canvas, `FLAG_KEEP_SCREEN_ON` toggle, DPI sensitivity multiplier (`0.5x ~ 3.0x`) in Settings bottom sheet, and custom monochrome-ready Android Adaptive Icon.

## Requirements

- Android 10 (API 29) or higher
- [Shizuku](https://shizuku.rikka.app/) installed and running (Wireless Debugging or ADB, no root required)

## Build

```bash
./gradlew :app:clean :app:assembleDebug
```

Install the generated debug APK at `app/build/outputs/apk/debug/app-debug.apk`, start Shizuku, and grant permission from the top bar in the app.
