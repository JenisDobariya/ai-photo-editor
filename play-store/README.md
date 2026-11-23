# Play Store assets: Gallerai

Everything needed to update the Google Play listing for **Gallerai 1.0.6** (package `com.photo_editor.my_photo_editor`, versionCode 6).

```
play-store/
├── listing/en-US/
│   ├── title.txt                     App name (28 / 30)
│   ├── short_description.txt         Short description (80 / 80)
│   ├── full_description.txt          Full description (~2,400 / 4,000)
│   └── release_notes_1.0.6.txt       "What's new" for this release (~350 / 500)
├── policy/
│   ├── console_checklist.md          Step-by-step Play Console checklist for this update
│   ├── data_safety.md                Exact Data safety answers + related declarations
│   └── privacy_policy.md             Updated privacy policy text (fill in the [brackets])
└── graphics/
    ├── icon/icon_512.png                               App icon, 512×512 PNG
    ├── feature-graphic/feature_graphic_1024x500.png    Feature graphic, 1024×500 PNG
    ├── screenshots/phone/01…08_*.png                   8 phone screenshots, 1080×1920 (9:16), the Play maximum
    └── source/                                         Editable sources for all graphics
        ├── icon.html, feature_graphic.html, screenshot.html
        ├── shots/                                      Real app screens used in the designs
        └── render.py                                   Re-renders every PNG
```

## Where each file goes in Play Console
**Grow → Store presence → Main store listing**
- App name ← `title.txt`
- Short description ← `short_description.txt`
- Full description ← `full_description.txt`
- App icon ← `icon_512.png`
- Feature graphic ← `feature_graphic_1024x500.png`
- Phone screenshots ← all 8 files in `screenshots/phone/`, uploaded in number order (Play allows 2 to 8)

**Release → Production (or Internal testing) → Create new release**
- Release notes ← `release_notes_1.0.6.txt`

**Policy → App content:** follow `policy/data_safety.md` and `policy/console_checklist.md`.

## Editing the graphics
1. Edit the HTML in `graphics/source/` (text, colours or layout).
2. Run `python render.py` from that folder (needs Python with Pillow, and Chrome or Edge installed).
3. The PNGs in `icon/`, `feature-graphic/` and `screenshots/phone/` are overwritten at the exact sizes Play requires, with no transparency.

To add more screenshots, put a clean phone capture (status bar cropped, **no personal photos**) in `source/shots/`. Add a slide in `screenshot.html` and a line in `JOBS` in `render.py`.

## Notes
- The icon is the app's own launcher artwork, converted 1:1 from `app/src/main/res/drawable/ic_launcher_*.xml`.
- Screenshots 02 and 07 are real app screens with no personal photos. 01, 03, 04, 05 and 06 recreate the real screens (same layout, colours and text) in `screenshot.html`, with illustrated pictures drawn in code in place of personal photos. 08 is a feature overview.
- Order: 01 Gallery · 02 Smart AI tools · 03 AI Cutout · 04 Copy text · 05 Clean up · 06 Video player · 07 Create · 08 Everything in one gallery.
- Wording follows Play's metadata rules: no "best", "#1", "free" or emoji in the title, no claims of "no tracking" or "fully offline", and no other apps' brand names.
- Recommended before publishing: a trademark search for "Gallerai". An older Android app called "GallerAI" exists.
