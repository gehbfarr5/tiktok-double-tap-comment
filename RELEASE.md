# Release Checklist

1. Build a clean release APK:

   ```bash
   ./gradlew clean assembleRelease
   ```

2. Verify the LSPosed entry exists:

   ```bash
   unzip -p app/build/outputs/apk/release/app-release.apk assets/xposed_init
   ```

   Expected:

   ```text
   dev.tiktok.doubletapcomment.hook.MainHook
   ```

3. Install the APK, enable the module in LSPosed, select TikTok scope, then force-stop TikTok.

4. Test:

   - Double-tap feed video opens comments.
   - Double-tap does not like the video.
   - Right-side like button still works.
   - Right-side comment button still works.

5. Attach `app/build/outputs/apk/release/app-release.apk` to the GitHub release.
