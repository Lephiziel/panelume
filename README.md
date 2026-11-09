<p align="center">
  <img src="docs/hero.svg" width="1000" alt="Panelume — a native Android reader for vertical comics">
</p>

<h1 align="center">Panelume</h1>

<p align="center">
  <strong>Read manhwa, manga, manhua, webtoons and comics on Android.</strong><br>
  Scroll through a chapter and come back to the exact spot you left.
</p>

<p align="center">
  <a href="https://github.com/Lephiziel/panelume/releases/download/v0.2.3/Panelume-0.2.3.apk"><img src="https://img.shields.io/badge/Download_APK-0.2.3-F7AE72?style=for-the-badge&logo=android&logoColor=20211F" alt="Download Panelume 0.2.3 APK"></a>
</p>

<p align="center">
  <strong>Ad-free</strong> · Android 8.0+ · English and Russian
</p>

<p align="center">
  <a href="#install-panelume">How to install</a> ·
  <a href="docs/README.ru.md">Русский</a> ·
  <a href="ROADMAP.md">Roadmap</a> ·
  <a href="https://github.com/Lephiziel/panelume/issues/1">Share feedback</a>
</p>

## Install Panelume

1. On your phone, tap **Download APK** above or open the [release page](https://github.com/Lephiziel/panelume/releases/tag/v0.2.3) and select `Panelume-0.2.3.apk` under **Assets**.
2. Open the downloaded file and follow Android’s installation prompt. If Android asks, allow your browser or file manager to install apps.
3. Open **Panelume**, search for a title and choose a source. Tap a chapter to start reading.

**Already using Panelume Next 0.2.2?** Install this APK over it to keep your library and progress. Panelume 0.2.1 has a separate library.

## What you can read

Read **manhwa, manga, manhua, webtoons and other comics**. Search the available English-language catalogues or import your own **PDF, CBZ or ZIP** files from **Library → +**.

**Ad-free reading.** Panelume itself shows no ads. External sites may show ads or require an episode to be unlocked on their site.

## Reading in Panelume

<p align="center">
  <img src="docs/reading-flow.svg" width="640" alt="Find a title, read or save a chapter, and pick up where you left off">
</p>

| Feature | What you get |
| :-- | :-- |
| **Continuous scroll** | Panels join into a single vertical strip. Tap the page to hide the controls. |
| **Pick up where you left off** | Chapter bookmarks remember the page and offset. The home screen puts your latest chapter one tap away. |
| **Take a series offline** | Save individual chapters or queue a series. See progress, retry failed downloads or cancel from Downloads. Series posters stay visible offline once loaded. |
| **Bring your own files** | Open PDF, CBZ or ZIP image archives from your device or a direct HTTPS link. |
| **Track reading time** | See totals by day and chapter. The timer runs while the reader is visible, the app has focus and the device is awake and unlocked. |

## Online catalogues

| Source | Available content |
| :-- | :-- |
| **WEBTOON** | English catalogue and publicly readable episodes. Episodes that require unlocking open at the source. |
| **Weeb Central** | English catalogue and public chapter lists. |
| **MangaRead** | English catalogue and public chapter images. |
| **MangaDex** | Korean series with English chapters, with source and translation-group credits. |

Try **Viral Hit** or **How to Fight** to compare listings across sources. Chapter availability depends on the source.

## Feedback and contributions

- Share your experience in the [reader feedback thread](https://github.com/Lephiziel/panelume/issues/1). English and Russian are welcome.
- Use the [issue picker](https://github.com/Lephiziel/panelume/issues/new/choose) for bug reports and feature requests. For a bug, include your app version, Android version and steps to reproduce.
- See [what changed](CHANGELOG.md) and [what's planned](ROADMAP.md).

## For developers

<details>
<summary>Build from source</summary>

[Android build checks](https://github.com/Lephiziel/panelume/actions/workflows/android.yml)

Use **JDK 17**, **Android SDK Platform 35** and **Gradle 8.9**. Open the project in Android Studio or run:

```sh
gradle assembleDebug
```

For a standalone Linux build, also install Android Build Tools 35.0.0 and `zip`, then set `ANDROID_SDK_ROOT`:

```sh
./tools/build-apk.sh
adb install -r out/Panelume-0.2.3.apk
```

To update an existing installation, sign with its original key. The standalone script accepts a keystore path through `PANELUME_DEBUG_KEYSTORE`.

</details>

## Credits

The WEBTOON adapter ports episode discovery and page extraction from [Zehina’s Webtoon-Downloader](https://github.com/Zehina/Webtoon-Downloader). See its [MIT notice](app/src/main/assets/third-party/webtoon-downloader-MIT.txt).
