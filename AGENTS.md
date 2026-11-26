# AGENTS.md - Laguku (`com.laguku.player`)

Panduan arsitektur wajib untuk pengembangan **Laguku**, pemutar musik Android asli (*native*) dengan ukuran APK rilis **< 5,0 MB**.

## 1. Batasan Keras (*Non-Negotiables*)

- **Namespace & Package ID:** Seluruh kode sumber dan konfigurasi Gradle wajib menggunakan `com.laguku.player`.
- **Zero UI/Media Bloat:**
  - Dilarang menambahkan Jetpack Compose, `AppCompat`, `MaterialComponents`, Glide, Coil, ExoPlayer/Media3, atau library parser audio eksternal.
  - Dependensi runtime dibatasi hanya pada Kotlin stdlib, `androidx.core:core-ktx`, dan `androidx.recyclerview:recyclerview`.
- **Anggaran Ukuran APK (< 5,0 MB):**
  - Wajib mengaktifkan `isMinifyEnabled = true` (R8 full mode), `isShrinkResources = true`, dan filter ABI `arm64-v8a` di `app/build.gradle.kts`.
  - Gunakan hanya XML Vector Drawable untuk ikon (dilarang menambah PNG/WebP statis).
- **Performa Render 60/120 FPS & Hemat Baterai:**
  - Pemrosesan gambar sampul (`64x64` StackBlur dan ekstraksi palet) wajib berjalan di *background thread*, tidak pernah di *main thread* atau `onDraw()`.
  - Hindari alokasi objek baru di dalam hot-loop PCM `AudioEngine`/`AudioDspChain`, `View.onDraw()`, dan `RecyclerView.onBindViewHolder()`.
  - Hentikan total animator disk/visualizer saat `onStop()` dan lepaskan `PARTIAL_WAKE_LOCK` seketika saat musik dijeda/berhenti.
- **Tata Letak Bottom-Half Thumb-Zone & Progressive Disclosure:**
  - Seluruh kontrol interaktif yang sering dipakai wajib berada di 50% area bawah layar (`WindowInsets` `navigationBars` safe padding, target sentuh minimal `48dp`), sedangkan 50% atas khusus area visual & baca (tanpa tombol kontrol utama di pojok atas).
  - Pengaturan DSP dikelompokkan ke `AudioLabSheetView` (3 tab kapsul), dan utilitas koleksi/otomasi ke `LibraryUtilitiesSheetView` & `InsightsSheetView`.
  - Dukung Android 16/17 (*Predictive Back* `OnBackInvokedCallback`, orientasi adaptif tanpa kunci kaku, *edge-to-edge*).

## 2. Peta Modul (`app/src/main/kotlin/com/laguku/player/`)

- `LagukuApp.kt` & `MainActivity.kt`: Siklus hidup aplikasi, izin media (`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`), dan navigasi utama.
- `model/AudioModels.kt`: Model data murni (`Track`, `AlbumGroup`, `FolderNode`, `LyricLine`, `CoverPalette`, `AudioQualityInfo`).
- `metadata/BinaryTagReader.kt`: Parser biner mandiri untuk FLAC, OGG/Opus, ID3v2 (MP3/WAV), MP4/ALAC, serta fallback `<nama>.lrc` dan `cover.jpg`/`folder.jpg`.
- `lyrics/LrcParser.kt`: Parser LRC standar, multi-timestamp, `[offset:]`, karaoke `<mm:ss.xx>`, serta pencarian indeks aktif `O(log N)`.
- `audio/AudioEngine.kt`: Pemutar audio berbasis `MediaExtractor` + `MediaCodec` + `AudioTrack` dengan *gapless pre-buffering*, ReplayGain, fallback `MediaPlayer`, `Equalizer` 5-band, dan *Sleep Timer*.
- `service/PlaybackService.kt`: `ForegroundService` (`mediaPlayback`) dengan integrasi `MediaSession`, kontrol layar kunci, dan `ACTION_AUDIO_BECOMING_NOISY`.
- `data/LibraryScanner.kt`: Pemindai koleksi `MediaStore.Audio`, struktur folder langsung, serta pembaca/penulis playlist `.m3u8`.
- `ui/glass/`: `StackBlur.kt`, `CoverPaletteExtractor.kt`, dan `LiquidGlassDrawable.kt`.
- `ui/player/`: `NowPlayingView.kt`, `LyricsRecyclerAdapter.kt`, dan `SeekCapsuleView.kt`.
- `ui/library/LibraryView.kt`: Navigasi 5 tab kapsul kaca (Lagu, Folder, Album, Artis, Playlist) dan *floating mini player*.
- `ui/motion/SpringAnimatorHelper.kt`: Animasi kurva `PathInterpolator(0.22f, 1f, 0.36f, 1f)` terkalibrasi (`420ms` crossfade latar/palet, `340ms` gulir lirik, `140ms` press-in / `220ms` release pada skala `0.97f`) serta kontrol *reduced motion*.
- `widget/LagukuPillWidgetProvider.kt`: *Resizable Pill Liquid Glass Home Screen Widget* (`COMPACT` < 220dp menyembunyikan tombol *Previous*, `STANDARD` >= 220dp, `EXPANDED` tinggi >= 110dp menampilkan baris album + kualitas) dengan komposisi latar buram `<= 320x120` di *background thread* (`StackBlur` 64x64 + `CoverPaletteExtractor` + kilau tepi `1.2dp`), tanpa *polling timer* (diperbarui murni lewat event `PlaybackService`).

## 3. Aturan Keamanan

- Batasi alokasi buffer di `BinaryTagReader.kt` terhadap ukuran sisa stream (maks. 8 MB untuk gambar sampul, maks. 512 KB untuk lirik/teks) agar berkas audio korup tidak memicu `OutOfMemoryError`.
- Validasi setiap entri path pada berkas `.m3u8` memakai `canonicalPath` untuk mencegah *path traversal*.

## 4. Perintah Verifikasi

- Unit test JVM: `./gradlew testDebugUnitTest`
- Build rilis terkompresi: `./gradlew assembleRelease`