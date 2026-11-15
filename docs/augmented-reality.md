# World-space story reader

The app uses the configured backend for authentication, library content, story pages, media and parental PIN validation. Data and AR mocks remain disabled by default; staging and production always use real repositories and native ARCore. No offline account or database snapshot is included in this change.

## Backend configuration

`API_BASE_URL` can be passed at build time and takes precedence over the environment file:

```sh
flutter run --dart-define=APP_ENV=development --dart-define=API_BASE_URL=http://YOUR_COMPUTER_LAN_IP:8000
flutter build apk --release --dart-define=APP_ENV=production --dart-define=API_BASE_URL=https://YOUR_DEPLOYED_BACKEND
```

Replace these example addresses with the backend reachable from the device. The Android emulator can use the development default `http://10.0.2.2:8000`. Local HTTP is permitted in Android debug builds; production uses HTTPS. The staging and production URLs in the checked-in environment files are placeholders until deployment.

The backend links a story page to its illustration through `visual_config.asset_id`. The reader requests `/api/v1/media/assets/{asset_id}` with the signed-in user's bearer token. The 2D reader uses the existing authenticated image cache. For native AR, Dart downloads the image through the authenticated Dio client and transfers bytes to Kotlin; Kotlin never receives credentials or a backend URL. Late image downloads cannot overwrite a newer selected page.

## Rendering and placement

The native OpenGL scene draws the camera and a textured page with a back and thickness. Its transform is projection × camera view × ARCore anchor × local movement × yaw × tilt. There is no screen position clamp or camera-following story overlay on Android. Page changes retain the anchor. A failed image request shows the current page text with a placeholder and reports the failure rather than displaying an unrelated sample illustration.

Placement prefers a tracked horizontal plane polygon, then its extents, horizontal depth hits, oriented feature points and ARCore Instant Placement. Depth is enabled where supported. Estimated supports are labelled separately. If geometry is slow to appear while camera tracking is active, manual placement accepts an approximate distance and creates a world anchor along the downward reticle ray. This estimates support; it does not claim a detected table. See the [ARCore hit-test guide](https://developers.google.com/ar/develop/java/hit-test/developer-guide) and [Instant Placement guide](https://developers.google.com/ar/develop/java/instant-placement/developer-guide).

Move mode intersects finger motion with the support plane. Pinch changes physical size and two-finger twist rotates the page. Rotate mode maps horizontal drag to yaw and vertical drag to inclination. With physics enabled, releasing a tilted page topples it onto the support plane with damped contact. Stand up resets inclination without losing placement. Disabling physics keeps the selected inclination. This is a hinge/contact simulation; it does not implement collisions with furniture or falling off table edges.

3D preview pauses the camera and uses a separate virtual scene without physics. Returning to AR resumes the preserved session and anchor. Repositioning from preview resumes scanning. Anchors last for the current session, not across app restarts.

## Verification

```sh
flutter analyze
flutter test
cd android
./gradlew :app:testReleaseUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Flutter tests cover real repository selection, authenticated media downloads, out-of-order responses, page changes and controls. JVM tests cover placement eligibility and contact physics. The Android instrumentation test uses a synthetic bitmap in a real GLES2 context to check projection, looking away and preview without shipping account data. A compatible physical phone is still required to verify table detection and tracking in a real room.
