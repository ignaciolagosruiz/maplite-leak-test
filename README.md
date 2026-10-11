# maplite-leak-test

Minimal repro for the lite-mode `MapView` leak seen in uTag's map widget
(KieronQuinn/uTag#282 follow-up). Renders off-screen lite maps in a loop in two modes:

- `current`: uTag's `generateGoogleMap()` as of 1.0.19 (new MapView per render, destroyed in `finally`)
- `reuse`: one MapView reused for every render

```
adb shell am start -n dev.chicho.mapleak/.MainActivity --es mode current --ei count 60
adb logcat -s MAPLEAK
adb shell dumpsys meminfo dev.chicho.mapleak | grep Views
```
