# Brand

One mark, one definition. The heart-with-pulse path lives in
`generate-icons.py` and every platform asset is derived from it — never
redrawn by hand.

```
heart  M50 86C50 86 12 60 12 36C12 21 24 12 36 12C43 12 48 16 50 21
       C52 16 57 12 64 12C76 12 88 21 88 36C88 60 50 86 50 86Z
pulse  M23 46H35L41 33L50 60L58 40L64 46H77   (stroke 8, round caps)
```

| | light | dark |
|---|---|---|
| background | `#3730A3` → `#7C3AED` | `#1E1B4B` → `#4C1D95` |
| heart | `#FFFFFF` | `#F5F3FF` |
| pulse | `#4C1D95` | `#2E1065` |

Accent (iOS tint): `#7C3AED`, dark `#A77CF6`.

## Regenerating

```
python3 brand/generate-icons.py brand/out
cp brand/out/icon-*.png ios/Assets.xcassets/AppIcon.appiconset/
```

iOS ships one 1024 px image per appearance — light, dark and tinted — in
`AppIcon.appiconset`; the system does the masking and rounding, so the source
is full-bleed and square. Icon Composer's `.icon` format is GUI-authored only,
so the asset catalog is the reproducible path.

Wear draws the same paths as vector drawables in an `<adaptive-icon>`:
background, foreground and a `monochrome` layer for Android 13+ themed icons.
The foreground glyph spans ~62 of the 108 viewport so it fills the 66 dp
circle the launcher guarantees. `ic_stat_dreamfit` is the silhouette alone —
notification icons are mask-tinted, so colour there is discarded.
