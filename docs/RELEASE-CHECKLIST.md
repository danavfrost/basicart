# Release checklist

Steps to ship Basic Art to Google Play and the Apple App Store at **$0.99** (paid once, no in-app purchases).

## Both stores
- [ ] Confirm the final app id / bundle id: `com.halworks.basicart`. It can't be changed after the first upload.
- [ ] Confirm the developer / publisher name shown on the listings.
- [ ] Set up the paid-apps agreement, tax forms and bank details in each developer account (only the account owner can do this).
- [ ] Host the privacy policy at a public URL. The `PRIVACY.md` on GitHub is fine: `https://github.com/danavfrost/basicart/blob/main/PRIVACY.md`.
- [ ] Screenshots are in `store-assets/`, which stays out of git:
  - Android: phone 1080×2400, tablet 1200×1920, light and dark.
  - iOS: iPhone shots at 1206×2622, with 1290×2796 copies in `ios/6.9/`; iPad landscape.
- [ ] Short description: "A simple, basic art editing tool. No ads, no accounts, no internet."
- [ ] The listing text and screenshots must not say "free".

## Google Play
- [ ] Create an upload key, keep it safe and **back it up** (`keytool -genkeypair …`). Configure release signing in `android/app/build.gradle.kts` through a git-ignored `keystore.properties`.
- [ ] Turn on Play App Signing.
- [ ] Build the bundle: `./android/build-apks.sh`, which writes `android/app/build/outputs/bundle/release/app-release.aab`.
- [ ] Data safety: "No data collected or shared." No ads. Content rating questionnaire (expected rating: Everyone).
- [ ] Price: $0.99, available in all countries you choose.
- [ ] Target API level must meet Play's current requirement. Check the `targetSdk` in `android/app/build.gradle.kts` against Play's current rule before upload.
- [ ] Release first on internal testing, then production.

## Apple App Store
- [ ] Create the App ID and App Store Connect record. Set the Team ID in `ios/project.yml` and regenerate with `xcodegen generate`.
- [ ] Signing: automatic signing with the distribution certificate.
- [ ] Archive with Xcode (Product → Archive) and upload through the Organizer.
- [ ] App Privacy: "Data Not Collected".
- [ ] Export compliance: the app has no encryption beyond the OS's own. Set `ITSAppUsesNonExemptEncryption = NO` in Info.plist.
- [ ] Explain the photo-add permission in the listing text: it only saves your exports.
- [ ] Price tier: $0.99.
- [ ] Run TestFlight on a real iPhone and iPad first.

## GitHub (only after the owner approves)
- [ ] Make the repository public at `github.com/danavfrost/basicart`.
- [ ] Create a GitHub Release and attach `BasicArt.apk` (64-bit) and `BasicArt-32bit.apk`, signed with the release key. APKs are not committed to git.
- [ ] Release notes: the feature list and a pointer to `PRIVACY.md`.

## Final manual checks on real devices
- [ ] Galaxy S22 phone (64-bit) and Galaxy Tab A (32-bit): cold start, font browser scrolling, a 4K canvas with photos and glow text, GIF export, Save to Pictures, project zip export/import between the phone and the tablet.
- [ ] iPhone and iPad (TestFlight): the same, plus moving a project zip between Android and iOS.
