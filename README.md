# Video Downloader (Android app)

Paste a YouTube, Instagram or Facebook link (or use Share > Video Downloader from those apps), pick a quality or audio format (MP3, M4A, Opus, WAV, FLAC), and tap Download. Files are saved in Downloads/VideoDownloader.

GitHub builds the APK for you. Nothing to install on a computer.

## Steps

1. Make a new repository on github.com. Upload the files from this folder. GitHub does not unzip a .zip, so extract it first and upload the files inside, not the .zip itself.
   - The workflow must end up at `.github/workflows/build.yml`. If your phone can't upload folders: Add file > Create new file, type `.github/workflows/build.yml` as the name, and paste the contents of `build.yml`.
2. Open the Actions tab. "Build Android app" starts by itself and takes about 5 to 10 minutes.
3. When it shows a green tick, open Releases on the repo page, download `app-debug.apk`, and install it. Allow "install unknown apps" if Android asks.

## Notes

- Needs Android 10 or newer on a 64-bit phone.
- If downloads stop working later, go to Actions > Build Android app > Run workflow, then install the new APK. This rebuilds with the newest yt-dlp.
- Posts that need a login, and DRM-protected services such as Prime Video or JioHotstar, will not download.
- If the build shows a red cross, open the run and copy the error text.
- The app uses youtubedl-android (GPL-3.0), so keep your repo's source public if you share the APK.
- Only download content you own or have permission to save. Downloading can break a site's Terms of Service.
