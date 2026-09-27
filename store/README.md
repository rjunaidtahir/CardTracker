# Play Store listing assets

Everything here is ready to upload to Play Console (App content → Store listing / Main store listing).

| File | Use | Spec |
|---|---|---|
| `icon-512.png` | App icon | 512×512, 32-bit PNG — this is the real app icon (`ios/UAEFinancialTracker/Assets.xcassets/AppIcon.appiconset/icon-1024.png`), just resized |
| `feature-graphic-1024x500.png` | Feature graphic | 1024×500, 24-bit PNG (no alpha) |
| `screenshot-1-home.png` … `screenshot-5-trends.png` | Phone screenshots | 1080×1920 each, with a headline caption, upload in this order |
| `listing.md` | App title, short/full description, category, and draft answers for the content rating questionnaire and Data Safety form | — |

**These screenshots are real captures of the app**, taken on-device, with a caption banner composited on top.
All amounts, merchant names, card last-4s, and account figures shown in them have been replaced with made-up
mock data — none of it is a real transaction, a real balance, or anyone's real merchant/employer/provider name.
The UI chrome (theme, layout, fonts, icons) is exactly what shipping users see. Order: Home (monthly trend) →
Activity → Cards → By category → 12-month trend & top merchants.

If you need to regenerate these from new device captures, the editing scripts (text-patch + caption compositor)
are not committed here — ask Junaid.

## Privacy policy

**Live at: https://rjunaidtahir.github.io/fils-privacy/** — paste this into Play Console's "Privacy policy" field.

[`/docs/privacy-policy.html`](../docs/privacy-policy.html) in *this* repo is the source of truth for the wording.
It's hosted from a separate public repo, `rjunaidtahir/fils-privacy` (its `index.html`), via GitHub Pages —
CardTracker itself stays private, so it can't serve Pages on the free plan.

**If you ever edit `docs/privacy-policy.html` again, copy it over to `fils-privacy/index.html` and push it there too
— the two are not linked automatically.**
