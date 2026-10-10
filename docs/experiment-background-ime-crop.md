# Experiment: background framing and calmer UI

Branch: `experiment/background-ime-crop`

This branch is an isolated visual experiment based on the frozen 9.0 candidate. It does not change the recognition gate, course data, notification policy, signing metadata, or the frozen branch.

## Problem

Opening quick capture brings up the IME. Android may reduce the activity's effective layout height, so a cover-cropped background is measured against a shorter viewport. The same image then appears to zoom or change aspect ratio while the keyboard is open. A page transition can make this more visible because a subpage draws its own background layer.

The old image controls already stored a non-destructive `ImageCrop(centerX, centerY, zoom)`, but the only way to change it was through three sliders. That made positioning hard to discover and slow to tune.

## Changes

- `MainActivity` is explicitly `adjustNothing`: the activity keeps the physical full-screen background viewport while the IME overlays it.
- The existing `Scaffold.imePadding()` and `AppDialogHost.imePadding()` continue to keep editable page content and dialogs readable above the keyboard. The floating navigation bar remains at the physical bottom.
- `ImageCrop` now has a pure `pannedBy` helper. The image preview in Appearance is larger and draggable; the horizontal, vertical, and zoom sliders remain for precise adjustments.
- The existing preference keys and image files are reused. There is no migration, re-encoding, or data reset.
- Primary-page headings now share a small supporting line so the page purpose is visible without adding another card.
- Lightweight interaction motion is slightly shorter (quick feedback 160ms, selection pulse 120ms, navigation morph 240ms); page transition durations remain unchanged.

## Manual acceptance

1. Install an APK built from this branch over the current candidate.
2. Choose an image in Settings → Appearance → Page background → Image.
3. Drag the preview and confirm the focal point changes; adjust zoom and reopen the page to confirm persistence.
4. Open the + quick-capture editor and focus a text field. The image framing should not vertically squash or re-crop, and the navigation bar should remain at the physical bottom.
5. Move between Today, Schedule, Plan, and Settings while the IME is open and during a subpage transition. No page should reveal a second, differently cropped background.
6. Close the editor and keyboard. The framing should return to the same crop used before the IME opened.
7. Verify the default theme with no custom image is unchanged.

## Rollback

Delete or abandon this branch. The freeze branch remains untouched.
