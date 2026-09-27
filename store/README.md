# Play Store listing assets

Everything here is ready to upload to Play Console (App content → Store listing / Main store listing).

| File | Use | Spec |
|---|---|---|
| `icon-512.png` | App icon | 512×512, 32-bit PNG — this is the real app icon (`ios/UAEFinancialTracker/Assets.xcassets/AppIcon.appiconset/icon-1024.png`), just resized |
| `feature-graphic-1024x500.png` | Feature graphic | 1024×500, 24-bit PNG (no alpha) |
| `screenshot-1-home.png` … `screenshot-6-privacy.png` | Phone screenshots | 1080×1920 each, with a headline caption + device frame, upload in this order |
| `listing.md` | App title, short/full description, category, and draft answers for the content rating questionnaire and Data Safety form | — |

**These screenshots are mockups, not captures of the real app.** They're built to match Fils's actual screens,
colours, features and copy exactly, with the real app icon, but the data in them (amounts, merchant names, card
numbers, dates) is made up — none of it is real transaction data or a real employer name. Before submitting,
either:

- use them as-is for a first submission (Play doesn't require screenshots to be pixel-identical to the shipped
  build, only representative of it — these are), or
- swap in real screen captures of the app once it's in front of a device, keeping the same 1080×1920 size and
  the same order (Home → Activity → Cards → Statement check → More/features → Privacy).

The source for all of these (editable HTML/CSS, one file per screen) is not committed here — ask Junaid if you
need to regenerate or tweak them.

## Privacy policy

The privacy policy page is at [`/docs/privacy-policy.html`](../docs/privacy-policy.html) in this repo, meant to be
served with GitHub Pages:

1. Repo **Settings → Pages → Build and deployment → Deploy from a branch**.
2. Branch: `main` (or `rebuild` until it's merged), folder: `/docs`.
3. Save. The page will be live at `https://rjunaidtahir.github.io/CardTracker/privacy-policy.html` within a few
   minutes — paste that URL into Play Console's "Privacy policy" field.
