# Spesifikasi Teknis: Laguku

Pemutar musik Android asli (*native*) dengan ukuran APK rilis **di bawah 5,0 MB**, antarmuka **Liquid Glass** *seamless* berbasis *Progressive Disclosure* & *Thumb-Zone*, pipa DSP PCM 10-band, kecerdasan Bluetooth per perangkat, serta lirik sinkron bilingual bergaya Spotify.

---

## 1. Identitas & Batasan Arsitektur

- **Nama Aplikasi:** Laguku
- **Package ID / Namespace:** `com.laguku.player`
- **Target SDK:** `minSdk = 26` (Android 8.0) hingga `targetSdk = 35` (Android 15), mendukung layar 60 Hz maupun 120 Hz.
- **Bahasa & UI Toolkit:** Kotlin murni di atas Android View System (`Activity`, `View`, `RecyclerView`, custom `Drawable`).
- **Aturan Zero Bloat:**
  - Dilarang memakai Jetpack Compose, `AppCompat`, `MaterialComponents`, Glide/Coil, ExoPlayer/Media3, atau pustaka parser audio pihak ketiga.
  - Dependensi eksternal dibatasi hanya pada Kotlin stdlib, `androidx.core:core-ktx`, dan `androidx.recyclerview:recyclerview`.
  - Seluruh ikon memakai XML Vector Drawable tanpa aset PNG statis.
- **Build & Kesiapan Android 16/17:** `compileSdk = 36`, `minSdk = 26`, `targetSdk = 35`, mendukung *Predictive Back* (`OnBackInvokedCallback` API 33+), *edge-to-edge* `WindowInsets`, tata letak adaptif tanpa penguncian orientasi, 100% Kotlin murni (patuh 16 KB page size), R8 *full mode* (`isMinifyEnabled = true`), `isShrinkResources = true`, ABI `arm64-v8a`, batas APK rilis **< 5,0 MB**.

---

## 2. Modul & Fitur Lanjutan

- **Pipa DSP PCM (`audio/TenBandBiquadEq.kt`, `LosslessCutoffAuditor.kt`, `AudioDspChain.kt`, `AudioEngine.kt`):**
  - **10-Band Cascaded Biquad Peaking IIR EQ** (`31..16000` Hz, `-12..+12` dB, `Q = 1.414`, *auto-preamp*, *soft-clipper*; pemetaan ke `Equalizer` sistem khusus fallback `MediaPlayer`).
  - **Real-Time Lossless Cutoff Auditor** (Goertzel 10/16/18/19/20 kHz berjendela Hann selama 3 detik pertama untuk mendeteksi trek *upscale*).
  - **Akustik & Playback:** Stereo Crossfeed (`300us` + LPF 700 Hz), Smart Loudness Leveler (RMS adaptif bila tanpa ReplayGain), Auto-Trim Silence (`-65 dB`), L/R Balance & Mono Downmix, fade-in/out (`280ms`/`220ms`), *crossfade* `1.8s` khusus *Shuffle* (album berurutan tetap *gapless*), BT Wake-Up Pre-Roll (`150ms`), *Native Speed & Pitch* (`0.5x..2.0x`), AB-Loop, Chapter Markers, Dynamic Sample Rate Passthrough, dan `PARTIAL_WAKE_LOCK` hemat baterai.
- **Kecerdasan Bluetooth, Tag Writer, Koleksi Cerdas, & Otomasi (`service/`, `metadata/`, `data/`):**
  - **Bluetooth Profile Manager & Multi-Click TWS Mapper:** Profil EQ/crossfeed/safe-volume/latensi lirik per `AudioDeviceInfo.productName`, kompensasi latensi lirik BT otomatis (`0ms` pada kabel/speaker), *auto-play/auto-pause* opsional, AVRCP metadata lengkap, dan deteksi ketukan 1x/2x/3x/tahan.
  - **Otomasi:** Morning Fade-In Alarm 30 detik (`AlarmScheduler` + `AlarmReceiver`), Quick Settings Tile (`LagukuTileService`), dan App Shortcuts (`shortcuts.xml`).
  - **Metadata & Koleksi:** `BinaryTagWriter` (edit tag atomik FLAC/MP3/M4A + patch `stco`/`co64`), `PlaybackStateStore` (Resume Memory 100 lagu, offset lirik, statistik dengar offline < 50 KB), `LibraryScanner` (pinning, 4 Smart Auto-Playlists, filter < 30 detik, blacklist folder, *Health Scanner*), serta `LagukuBackupManager` (backup/restore 1-berkas JSON anti *path traversal*).
- **Antarmuka Seamless & Progressive Disclosure (`ui/`):**
  - **Now Playing:** 3 mode (*Sampul*, *Disk Vinyl + Cincin PCM*, *Lirik Bilingual + Depth Focus Blur `RenderEffect`/opasitas*), Zen Lyric Mode (`340ms`), pil offset lirik, *Share Lyric Quote Card* (`FileProvider`), Mode Malam AMOLED (`#000000`), serta kapsul seekbar ber-waveform + indikator AB-loop & chapter.
  - **Lembar Bawah Terpadu:** `AudioLabSheetView` (3 tab: *EQ 10-Band*, *Akustik & BT*, *Tempo & Loop*), `LibraryUtilitiesSheetView`, dan `InsightsSheetView`.

---

## 2. Anggaran Ukuran APK Rilis

| Komponen | Estimasi Ukuran (R8) | Keterangan |
| :--- | :---: | :--- |
| Runtime Kotlin & AndroidX Minimal | ~650 KB | Hanya `core-ktx` dan `recyclerview` |
| Mesin Audio, `MediaSession`, & Service | ~420 KB | `MediaExtractor` + `MediaCodec` + `AudioTrack` |
| Perpustakaan, Folder, & Playlist `.m3u8` | ~380 KB | Query `MediaStore` + pemindai folder langsung |
| Ikon Vektor XML & Layout Antarmuka | ~180 KB | 100% vektor terkompresi |
| Mesin Lirik Sinkron Spotify-Style | ~140 KB | Custom `RecyclerView` + *smooth scroller* + *tap-to-seek* |
| Renderer Liquid Glass & StackBlur 64x64 | ~110 KB | Custom `Drawable` & manipulasi piksel cepat |
| Parser Tag Biner Mandiri | ~85 KB | FLAC, OGG/Opus, ID3v2, MP4/ALAC, WAV |
| **Total Ukuran APK Rilis** | **~1,96 MB** | Menyisakan ruang aman ~440 KB di bawah batas 2,4 MB |

---

## 3. Struktur Direktori Proyek

```text
laguku/
├── AGENTS.md
├── SPEC.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── kotlin/com/laguku/player/
        │   │   ├── LagukuApp.kt
        │   │   ├── MainActivity.kt
        │   │   ├── model/
        │   │   │   └── AudioModels.kt
        │   │   ├── metadata/
        │   │   │   └── BinaryTagReader.kt
        │   │   ├── lyrics/
        │   │   │   └── LrcParser.kt
        │   │   ├── audio/
        │   │   │   └── AudioEngine.kt
        │   │   ├── service/
        │   │   │   └── PlaybackService.kt
        │   │   ├── data/
        │   │   │   └── LibraryScanner.kt
        │   │   ├── widget/
        │   │   │   └── LagukuPillWidgetProvider.kt
        │   │   └── ui/
        │   │       ├── glass/
        │   │       │   ├── StackBlur.kt
        │   │       │   ├── CoverPaletteExtractor.kt
        │   │       │   └── LiquidGlassDrawable.kt
        │   │       ├── player/
        │   │       │   ├── NowPlayingView.kt
        │   │       │   ├── LyricsRecyclerAdapter.kt
        │   │       │   └── SeekCapsuleView.kt
        │   │       ├── library/
        │   │       │   └── LibraryView.kt
        │   │       └── motion/
        │   │           └── SpringAnimatorHelper.kt
        │   └── res/
        │       ├── drawable/
        │       ├── layout/
        │       ├── values/
        │       └── xml/
        └── test/kotlin/com/laguku/player/
            ├── BinaryTagAndLrcTest.kt
            ├── LibraryAndPlaylistTest.kt
            ├── GlassAndPaletteTest.kt
            ├── MotionTimingTest.kt
            └── WidgetPillTest.kt
```

---

## 4. Mesin Audio Universal & Pemutaran Latar Belakang

### 4.1 Dukungan Format Audio
- **Jalur Utama (`MediaExtractor` + `MediaCodec` + `AudioTrack`):** Memutar **FLAC (16/24-bit hingga 192 kHz)**, **ALAC (`.m4a`)**, **Opus (`.opus`/`.ogg`)**, **OGG Vorbis**, **AAC**, **MP3**, **WAV**, dan **AMR**.
- **Fallback Otomatis:** Jika perangkat memiliki codec khusus vendor yang tidak bisa didekode langsung ke PCM, mesin otomatis beralih ke `android.media.MediaPlayer` tanpa memutus antrean.
- **Gapless Playback:** Menyiapkan *buffer* dan dekoder trek berikutnya sebelum aliran PCM trek aktif berakhir sehingga pergantian lagu dalam satu album mengalir tanpa jeda.
- **ReplayGain:** Membaca nilai `REPLAYGAIN_TRACK_GAIN` dari metadata untuk menyeimbangkan volume antarlagu secara otomatis.
- **Lencana Kualitas Audio Real-Time:** Menampilkan format, kedalaman bit, dan frekuensi sampel trek aktif di header pemutar (contoh: `FLAC • 24-bit • 48 kHz` atau `OPUS • 160 kbps`).

### 4.2 `PlaybackService` & Integrasi Sistem
- Berjalan sebagai `ForegroundService` bertipe `mediaPlayback` yang terhubung ke `MediaSession`.
- Menampilkan sampul album, judul, artis, tombol kontrol (*Prev*, *Play/Pause*, *Next*), dan bilah progres di layar kunci, panel notifikasi Android, serta perangkat Bluetooth.
- Menangani perubahan fokus audio (*audio focus*) saat panggilan masuk dan menjeda lagu otomatis ketika kabel earphone dicabut (`ACTION_AUDIO_BECOMING_NOISY`).

---

## 5. Parser Tag Biner Mandiri & Mesin Lirik Spotify-Style

### 5.1 `BinaryTagReader.kt` (< 15 KB Kode)
Membaca metadata langsung dari struktur biner berkas tanpa pustaka pihak ketiga:
- **FLAC (`fLaC`):** Blok `METADATA_BLOCK_VORBIS_COMMENT` (`TITLE`, `ARTIST`, `ALBUM`, `TRACKNUMBER`, `REPLAYGAIN_TRACK_GAIN`, `LYRICS`, `UNSYNCEDLYRICS`) dan `METADATA_BLOCK_PICTURE` untuk gambar sampul 1:1.
- **OGG & Opus (`OggS`):** Paket komentar `OpusTags` / `\x03vorbis` beserta gambar sampul tertanam.
- **MP3 & WAV (`ID3v2.3` / `ID3v2.4`):** Frame `TIT2`, `TPE1`, `TALB`, `TRCK`, `TXXX`, `USLT`, `SYLT`, dan `APIC`.
- **M4A & ALAC (`moov.udta.meta.ilst`):** Atom `©nam`, `©ART`, `©alb`, `trkn`, `©lyr`, `covr`, dan `----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN`.
- **Fallback Berkas Pendamping:** Jika tag lirik atau sampul tertanam kosong, otomatis memuat `<nama_lagu>.lrc` serta `cover.jpg` / `folder.jpg` di direktori yang sama.

### 5.2 `LrcParser.kt` & Tampilan Lirik Spotify-Style
- **Format LRC:** Mendukung timestamp standar `[mm:ss.xx]`, multi-timestamp `[00:15.00][01:20.00]`, tag `[offset:+/-ms]`, token karaoke per kata `<mm:ss.xx>`, serta teks lirik statis tanpa timestamp.
- **Pencarian Indeks Cepat:** Pencarian baris aktif menggunakan *binary search* `O(log N)` berdasarkan posisi milidetik pemutar.
- **Hierarki Tipografi & Warna:**
  - **Baris Aktif:** Ukuran `22sp` Bold, putih penuh (`100%` opasitas), skala halus `1.03x`.
  - **Baris Mendatang:** Ukuran `18sp` Medium, putih lembut (`64%` opasitas).
  - **Baris Lewat:** Ukuran `18sp` Medium, putih redup (`34%` opasitas).
- **Gulir & Interaksi:**
  - Setiap pergantian baris, `LinearSmoothScroller` menggeser daftar dengan kurva `PathInterpolator(0.22f, 1f, 0.36f, 1f)` selama `340ms` (transisi skala baris `260ms`) sehingga baris aktif selalu terkunci di **40% dari atas layar**.
  - **Tap-to-Seek:** Mengetuk baris lirik mana saja melompatkan posisi lagu ke detik tersebut disertai getaran halus (*haptic tick*).
  - **Jeda Gulir Manual:** Saat pengguna menggulir lirik secara manual, auto-scroll berhenti sementara dan muncul pil kaca mengambang **"Kembali ke lirik aktif"** di bagian bawah layar. Lirik kembali mengikuti lagu otomatis setelah 4 detik tanpa sentuhan atau saat pil diketuk.

---

## 6. Estetika Liquid Glass & Latar Belakang Buram Adaptif

### 6.1 Latar Buram Adaptif Tanpa Beban GPU Per-Frame
- Saat lagu berganti, gambar sampul didekode di *background thread* dengan `inSampleSize` ke bitmap mikro **`64x64` piksel** (hemat memori < 120 KB meski sampul asli 3000x3000 piksel).
- Diproses menggunakan algoritma **StackBlur 2-pass** (`radius = 12`, eksekusi < 3 ms), direntangkan memenuhi layar dengan interpolasi *bilinear*, dan dilapisi *scrim* gelap `#7A080A0F` agar teks putih selalu terbaca tajam.
- Pergantian latar antarlagu menggunakan transisi *crossfade* `420ms` dengan kurva `PathInterpolator(0.22f, 1f, 0.36f, 1f)` (termasuk transisi warna dasar `deepBackdrop`).

### 6.2 Ekstraksi Palet Warna & `LiquidGlassDrawable`
- **`CoverPaletteExtractor.kt`:** Mengambil 3 warna kunci dari histogram piksel sampul:
  1. `deepBackdrop` (warna dasar gelap untuk lapisan latar).
  2. `accentGlow` (warna aksen cerah untuk *seekbar* dan tombol utama).
  3. `glassTint` (warna bias kaca lembut untuk panel).
- **`LiquidGlassDrawable.kt`:** Custom `Drawable` dengan sudut membulat `22dp`, isi berlapis (`14%` warna aksen di atas dasar gelap `56%`), pantulan kilau lembut di tepi atas kaca, serta garis tepi **Specular Rim Highlight** (`1.2dp` gradasi putih `34%` di kiri atas menuju putih `5%` di kanan bawah).
- **Mikro-Interaksi:** Tombol utama menerapkan respons tekan fisik (`scale 0.97` dalam `140ms` *press-in* / `220ms` *release* dengan kurva `PathInterpolator(0.22f, 1f, 0.36f, 1f)`) dan menghormati pengaturan sistem `ANIMATOR_DURATION_SCALE` (*reduced motion*).

---

## 7. Susunan Layar, Navigasi, & Utilitas (Aturan Bottom-Half Thumb-Zone)

Seluruh kontrol interaktif yang sering dipakai wajib berada di **50% area bawah layar** (jangkauan satu ibu jari), sedangkan setengah atas layar difokuskan untuk tampilan visual dan area baca:

1. **Layar Utama (*Now Playing* / `NowPlayingView.kt`):**
   - **Setengah Atas (Display-Only):** Hanya berisi info album dan lencana kualitas audio pasif (`FLAC • 24-bit • 48 kHz`), sampul album 1:1 bersudut membulat `20dp`, atau area lirik sinkron (tetap mendukung *tap-to-seek* pada baris lirik). Dilarang menaruh tombol kontrol utama di pojok atas layar.
   - **Setengah Bawah (*Control Dock*, urut atas ke bawah):**
     1. Judul lagu & nama artis beserta tombol cepat **Favorit** dan **Antrean**.
     2. `SeekCapsuleView`: bilah progres kapsul kaca 1:1 mengikuti jari (*touch tracking*) dengan angka durasi *tabular*.
     3. Barisan tombol pemutar utama (*Shuffle*, *Previous*, *Play/Pause* kapsul aksen, *Next*, *Repeat*) dengan area sentuh minimal `48dp`.
     4. Pil kontrol sekunder di bagian paling bawah: sakelar **Sampul / Lirik**, **Equalizer**, dan **Sleep Timer** dengan padding aman `WindowInsets` (`navigationBars`).
2. **Perpustakaan & Folder (`LibraryView.kt`):**
   - Daftar lagu, folder (penjelajah direktori langsung tanpa duplikasi), album, artis, atau playlist mengisi area atas hingga tengah layar.
   - Kunci **Floating Mini Player** tepat di atas bilah navigasi (sampul mini, judul lagu, garis progres tipis di sisi bawah kapsul, dan tombol *Play/Pause* instan).
   - Tempatkan **5 Tab Kapsul Kaca (`Lagu`, `Folder`, `Album`, `Artis`, `Playlist`, mendukung impor/ekspor `.m3u8`)** bersama tombol aksi cepat (**Putar Acak Semua** / **Cari**) di bagian **paling bawah layar** dengan padding aman `WindowInsets` (`navigationBars`).
3. **Utilitas Audio Bawaan:**
   - **Equalizer 5-Band:** Menggunakan `android.media.audiofx.Equalizer` bawaan sistem.
   - **Sleep Timer:** Menghentikan pemutaran otomatis setelah durasi pilihan dengan penurunan volume bertahap (*fade-out*) selama 5 detik terakhir.
4. **Widget Layar Beranda Liquid Glass (`widget/LagukuPillWidgetProvider.kt`):**
   - **Kapsul Adaptif (*Resizable Pill*):** Mendukung pengubahan ukuran horizontal & vertikal (`minWidth=220dp`, `minHeight=64dp`, `minResizeWidth=180dp`, `minResizeHeight=56dp`, `targetCellWidth=4`, `targetCellHeight=1`) dengan tiga mode tata letak otomatis:
     - `COMPACT` (`minHeightDp < 110 && minWidthDp < 220`): menyembunyikan tombol *Previous* agar tata letak kapsul tetap lapang.
     - `STANDARD` (`minHeightDp < 110 && minWidthDp >= 220`): sampul 1:1, judul, artis, serta tombol *Previous*, *Play/Pause*, dan *Next* (>= `44dp`×`48dp` area sentuh).
     - `EXPANDED` (`minHeightDp >= 110`): menambahkan baris info album & lencana kualitas audio.
   - **Komposisi Kaca & Tanpa Polling:** Ditenagai pembaruan berbasis event dari `PlaybackService` (`onTrackChanged`, `onPlaybackStateChanged`, `onQualityChanged`, `onQueueCompleted`, dan penyelesaian `loadArtworkAsync`) tanpa *timer polling*. Latar buram ringan (`<= 320x120`) dikomposisi di *background thread* memakai `StackBlur` (`64x64`, `radius = 12`), `CoverPaletteExtractor` (`deepBackdrop` / `accentGlow`), kilau tepi atas, dan *specular rim* `1.2dp`. Mengetuk area sampul/teks membuka `MainActivity` langsung ke `NowPlayingView` (`EXTRA_OPEN_NOW_PLAYING`).

---

## 8. Keamanan & Ketahanan Data

- **Validasi Parser Biner:** Setiap pembacaan panjang blok/frame/atom di `BinaryTagReader.kt` dibatasi terhadap ukuran sisa stream dan batas maksimum aman (maks. 8 MB untuk sampul, 512 KB untuk teks/lirik) guna mencegah `OutOfMemoryError` atau *infinite loop* pada berkas korup.
- **Perlindungan Path Traversal Playlist:** Jalur berkas di dalam `.m3u8` divalidasi dengan `canonicalPath` agar tetap berada di dalam direktori penyimpanan musik yang diizinkan.
- **Storage Scoped Access:** Menggunakan izin `READ_MEDIA_AUDIO` (Android 13+) dan `READ_EXTERNAL_STORAGE` (Android 8-12) tanpa meminta izin akses seluruh berkas yang tidak perlu.

---

## 9. Kriteria Selesai (*Definition of Done*)

1. Seluruh unit test JVM (`./gradlew testDebugUnitTest`) lulus untuk parser tag biner (FLAC, Opus, MP3, M4A), parser LRC, StackBlur, ekstraksi palet warna, dan parser playlist `.m3u8`.
2. Build rilis (`./gradlew assembleRelease`) menghasilkan berkas `.apk` dengan ukuran total **< 2,4 MB**.
3. Tidak ada kebocoran memori `Bitmap` atau pemrosesan berat di *main thread* selama pemutaran dan pengguliran lirik.