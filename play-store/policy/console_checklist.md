# Play Console checklist: Gallerai 1.0.6 (versionCode 6)

Package: `com.photo_editor.my_photo_editor` (unchanged, so this ships as a normal update)

## Before upload
- [ ] Build a **signed .aab** with the upload key `myphotoeditor.jks`. The APKs in `release-build/` are signed with the debug key for testing only.
- [ ] versionCode 6 is higher than the live version (5).
- [ ] Update the hosted **privacy policy** using `policy/privacy_policy.md`, and keep the same URL as the one in Play Console and in the app's Profile → Privacy Policy.

## App content
- [ ] **Advertising ID:** No. The ad-ID permission is removed in 1.0.6.
- [ ] **Ads:** No ads.
- [ ] **Data safety:** see `policy/data_safety.md`.
- [ ] **Photo and video permissions:** gallery app, full library access is core functionality (wording in `data_safety.md`).
- [ ] **Content rating:** keep the existing rating unless asked; there's no user-generated sharing or social features.

## Store listing (Main store listing → English (United States))
- [ ] App name: `listing/en-US/title.txt` (30 max)
- [ ] Short description: `listing/en-US/short_description.txt` (80 max)
- [ ] Full description: `listing/en-US/full_description.txt` (4000 max)
- [ ] App icon: `graphics/icon/icon_512.png` (512×512 PNG)
- [ ] Feature graphic: `graphics/feature-graphic/feature_graphic_1024x500.png` (1024×500, no transparency)
- [ ] Phone screenshots: all 8 in `graphics/screenshots/phone/` (upload in number order; Play allows 2 to 8)
- [ ] Category: Photography

## Release
- [ ] Release notes: `listing/en-US/release_notes_1.0.6.txt` (500 max)
- [ ] Optional: try **Internal testing** first, install it from Play on your phone, then promote to Production.

## Wording rules followed in this folder (keep them if you edit)
- No "best", "#1", "top", "free" or prices in the title or icon, and no emoji in the title.
- No claims of "no tracking" or "fully offline" (the app uses Firebase Analytics, and the AI models download once).
- Locked Folder is not described as "encrypted" or a "secure vault".
- No other apps' brand names (MX, Google Lens, Google Photos…) in the listing.
