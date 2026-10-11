# maplite-leak-test

Minimal repro for the lite-mode `MapView` leak behind uTag's map widget (follow-up to
KieronQuinn/uTag#282). It renders off-screen lite maps in a loop, the way uTag's
`generateGoogleMap()` does for widgets, in two modes:

- `current`: uTag's `generateGoogleMap()` as of 1.0.19 (a new MapView per render, destroyed in `finally`)
- `reuse`: a verbatim copy of the proposed fix (one MapView reused for every render)

```
adb shell am start -n dev.chicho.mapleak/.MainActivity --es mode current --ei count 30
adb logcat -s MAPLEAK                                # wait for DONE
adb shell dumpsys meminfo dev.chicho.mapleak | grep Views
```

## Results (Pixel 9a, Android 17, Play services Maps 19.1.0, maps_core_dynamite 263020506)

| Mode | Renders | Views retained after GC | Native heap |
|---|---|---|---|
| `current` | 30 | 761 | 16 → 18 MB |
| `reuse` | 30 | 36 | flat |

Same numbers on two separate runs.

**Caveat:** the manifest has a placeholder API key, so tiles never load and every render hits
the 5 s timeout. This proves the leak on the create/destroy path and that reusing one MapView
bounds it. It does **not** exercise the success path (onMapLoaded → snapshot) on a reused map.
To check that, put a real key in `AndroidManifest.xml` and rerun the `reuse` mode: `ok` should
match the render count.
