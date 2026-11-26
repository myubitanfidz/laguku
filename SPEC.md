### 1. Dokumen Spesifikasi Lengkap (`SPEC.md`)

Simpan blok berikut sebagai file `SPEC.md` di root folder proyek `laguku`:

```markdown
# Spesifikasi Teknis: Laguku

Pemutar musik Android asli (*native*) dengan ukuran APK rilis **di bawah 2,4 MB**, tampilan **Liquid Glass** ringan, latar belakang buram adaptif dari sampul lagu, serta lirik sinkron yang menggulir presisi bergaya Spotify.

---

## 1. Identitas & Batasan Arsitektur

- **Nama Aplikasi:** Laguku
- **Package ID:** `com.laguku.player`
- **Target SDK:** `minSdk = 26` (Android 8.0) hingga `targetSdk = 35` (Android 15), mendukung penuh layar 60 Hz maupun 120 Hz.
- **Bahasa & UI Toolkit:** Kotlin murni di atas Android View System (`Activity`, `View`, `RecyclerView`, custom `Drawable`).
- **Aturan Zero Bloat:**
  - Dilarang memakai Jetpack Compose, `AppCompat`, `MaterialComponents`, Glide/Coil, ExoPlayer/Media3, atau pustaka parser audio pihak ketiga.
  - Dependensi eksternal dibatasi hanya pada Kotlin stdlib, `androidx.core:core-ktx`, dan `androidx.recyclerview:recyclerview`.
  - Seluruh ikon memakai XML Vector Drawable tanpa aset PNG statis.
- **Konfigurasi Build Rilis:** R8 *full mode* (`isMinifyEnabled = true`), `isShrinkResources = true`, dan filter arsitektur `arm64-v8a` (dengan opsi ABI splits).

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
        │       └── values/
        └── test/kotlin/com/laguku/player/
            ├── BinaryTagAndLrcTest.kt
            ├── LibraryAndPlaylistTest.kt
            └── GlassAndPaletteTest.kt

```

---

## 4. Mesin Audio Universal & Pemutaran Latar Belakang

### 4.1 Dukungan Format Audio

* **Jalur Utama (`MediaExtractor` + `MediaCodec` + `AudioTrack`):** Memutar **FLAC (16/24-bit hingga 192 kHz)**, **ALAC (`.m4a`)**, **Opus (`.opus`/`.ogg`)**, **OGG Vorbis**, **AAC**, **MP3**, **WAV**, dan **AMR**.
* **Fallback Otomatis:** Jika perangkat memiliki codec khusus vendor yang tidak bisa didekode langsung ke PCM, mesin otomatis beralih ke `android.media.MediaPlayer` tanpa memutus antrean.
* **Gapless Playback:** Menyiapkan *buffer* dan dekoder trek berikutnya sebelum aliran PCM trek aktif berakhir sehingga pergantian lagu dalam satu album mengalir tanpa jeda.
* **ReplayGain:** Membaca nilai `REPLAYGAIN_TRACK_GAIN` dari metadata untuk menyeimbangkan volume antarlagu secara otomatis.
* **Lencana Kualitas Audio Real-Time:** Menampilkan format, kedalaman bit, dan frekuensi sampel trek aktif di header pemutar (misalnya `FLAC • 24-bit • 48 kHz` atau `OPUS • 160 kbps`).

### 4.2 `PlaybackService` & Integrasi Sistem

* Berjalan sebagai `ForegroundService` bertipe `mediaPlayback` yang terhubung ke `MediaSession`.
* Menampilkan sampul album, judul, artis, tombol kontrol (*Prev*, *Play/Pause*, *Next*), dan bilah progres di layar kunci, panel notifikasi Android, serta perangkat Bluetooth.
* Menangani perubahan fokus audio (*audio focus*) saat ada panggilan masuk dan menjeda lagu otomatis ketika kabel earphone tercabut (`ACTION_AUDIO_BECOMING_NOISY`).

---

## 5. Parser Tag Biner Mandiri & Mesin Lirik Spotify-Style

### 5.1 `BinaryTagReader.kt` (< 15 KB Kode)

Membaca metadata langsung dari struktur biner file tanpa pustaka pihak ketiga:

* **FLAC (`fLaC`):** Blok `METADATA_BLOCK_VORBIS_COMMENT` (`TITLE`, `ARTIST`, `ALBUM`, `TRACKNUMBER`, `REPLAYGAIN_TRACK_GAIN`, `LYRICS`, `UNSYNCEDLYRICS`) dan `METADATA_BLOCK_PICTURE` untuk gambar sampul 1:1.
* **OGG & Opus (`OggS`):** Paket komentar `OpusTags` / `\x03vorbis` beserta gambar sampul tertanam.
* **MP3 & WAV (`ID3v2.3` / `ID3v2.4`):** Frame `TIT2`, `TPE1`, `TALB`, `TRCK`, `TXXX`, `USLT`, `SYLT`, dan `APIC`.
* **M4A & ALAC (`moov.udta.meta.ilst`):** Atom `©nam`, `©ART`, `©alb`, `trkn`, `©lyr`, `covr`, dan `----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN`.
* **Fallback File Pendamping:** Jika tag lirik atau sampul tertanam kosong, otomatis membaca `<nama_lagu>.lrc` serta `cover.jpg` / `folder.jpg` di direktori yang sama.

### 5.2 `LrcParser.kt` & Tampilan Lirik Spotify-Style

* **Format LRC:** Mendukung timestamp standar `[mm:ss.xx]`, multi-timestamp `[00:15.00][01:20.00]`, tag `[offset:+/-ms]`, token karaoke per kata `<mm:ss.xx>`, serta teks lirik statis tanpa timestamp.
* **Pencarian Indeks Cepat:** Pencarian baris aktif menggunakan *binary search* `O(log N)` berdasarkan posisi milidetik pemutar.
* **Hierarki Tipografi & Warna:**
* **Baris Aktif:** Ukuran `22sp` Bold, putih penuh (`100%` opasitas), skala halus `1.03x`.
* **Baris Mendatang:** Ukuran `18sp` Medium, putih lembut (`64%` opasitas).
* **Baris Lewat:** Ukuran `18sp` Medium, putih redup (`34%` opasitas).


* **Gulir & Interaksi:**
* Setiap pergantian baris, `LinearSmoothScroller` menggeser daftar dengan kurva `cubic-bezier(0.23, 1, 0.32, 1)` selama `260ms` sehingga baris aktif selalu terkunci di **40% dari atas layar**.
* **Tap-to-Seek:** Mengetuk baris lirik mana saja melompatkan posisi lagu ke detik tersebut disertai getaran halus (*haptic tick*).
* **Jeda Gulir Manual:** Saat pengguna menggulir lirik secara manual, auto-scroll berhenti sementara dan muncul pil kaca mengambang **"Kembali ke lirik aktif"** di bagian bawah layar. Lirik kembali mengikuti lagu otomatis setelah 4 detik tanpa sentuhan atau saat pil diketuk.



---

## 6. Estetika Liquid Glass & Latar Belakang Buram Adaptif

### 6.1 Latar Buram Adaptif Tanpa Beban GPU Per-Frame

* Saat lagu berganti, gambar sampul didekode di *background thread* dengan `inSampleSize` ke bitmap mikro **`64x64` piksel** (hemat memori < 120 KB meski sampul asli 3000x3000 piksel).
* Diproses menggunakan algoritma **StackBlur 2-pass** (`radius = 12`, eksekusi < 3 ms), direntangkan memenuhi layar dengan interpolasi *bilinear*, dan dilapisi *scrim* gelap `#7A080A0F` agar teks putih selalu terbaca tajam.
* Pergantian latar antarlagu menggunakan transisi *crossfade* `350ms`.

### 6.2 Ekstraksi Palet Warna & `LiquidGlassDrawable`

* **`CoverPaletteExtractor.kt`:** Mengambil 3 warna kunci dari histogram piksel sampul:
1. `deepBackdrop` (warna dasar gelap untuk lapisan latar).
2. `accentGlow` (warna aksen cerah untuk *seekbar* dan tombol utama).
3. `glassTint` (warna bias kaca lembut untuk panel).


* **`LiquidGlassDrawable.kt`:** Custom `Drawable` dengan sudut membulat `22dp`, isi berlapis (`14%` warna aksen di atas dasar gelap `56%`), pantulan kilau lembut di tepi atas kaca, serta garis tepi **Specular Rim Highlight** (`1.2dp` gradasi putih `34%` di kiri atas menuju putih `5%` di kanan bawah).
* **Mikro-Interaksi:** Tombol utama menerapkan respons tekan fisik (`scale 0.97` dalam `110ms` ease-out) dan menghormati pengaturan sistem `ANIMATOR_DURATION_SCALE` (*reduced motion*).

---

## 7. Susunan Layar, Navigasi, & Utilitas

1. **Layar Utama (*Now Playing*):**
* Header minimalis berisi nama album dan lencana kualitas audio.
* Sampul album 1:1 bersudut membulat `20dp` dengan bayangan elevasi lembut.
* Sakelar mode tampilan: **Sampul Penuh** vs **Fokus Lirik**.
* `SeekCapsuleView`: bilah progres kapsul kaca yang mendukung geser 1:1 mengikuti jari (*touch tracking*) dengan angka durasi *tabular*.
* Barisan tombol kontrol: *Shuffle*, *Previous*, *Play/Pause* (kapsul aksen), *Next*, dan *Repeat*.


2. **Perpustakaan & Folder (`LibraryView.kt`):**
* Navigasi 5 tab kapsul kaca: **Lagu**, **Folder** (penjelajah direktori langsung tanpa duplikasi), **Album**, **Artis**, dan **Playlist** (mendukung impor/ekspor playlist `.m3u8`).


3. **Pemutar Mini Melayang (*Floating Mini Player*):**
* Pil Liquid Glass melayang di atas bilah navigasi bawah berisi sampul mini, judul lagu, garis progres tipis di sisi bawah kapsul, dan tombol *Play/Pause* instan.


4. **Utilitas Audio Bawaan:**
* **Equalizer 5-Band:** Menggunakan `android.media.audiofx.Equalizer` bawaan sistem.
* **Sleep Timer:** Menghentikan pemutaran otomatis setelah durasi pilihan dengan penurunan volume bertahap (*fade-out*) selama 5 detik terakhir.



---

## 8. Keamanan & Ketahanan Data

* **Validasi Parser Biner:** Setiap pembacaan panjang blok/frame/atom di `BinaryTagReader.kt` dibatasi terhadap ukuran sisa stream dan batas maksimum aman (maks. 8 MB untuk sampul, 512 KB untuk teks/lirik) guna mencegah `OutOfMemoryError` atau *infinite loop* pada file korup.
* **Perlindungan Path Traversal Playlist:** Jalur file di dalam `.m3u8` divalidasi dengan `canonicalPath` agar tetap berada di dalam direktori penyimpanan musik yang diizinkan.
* **Storage Scoped Access:** Menggunakan izin `READ_MEDIA_AUDIO` (Android 13+) dan `READ_EXTERNAL_STORAGE` (Android 8-12) tanpa meminta izin akses semua berkas yang tidak perlu.

---

## 9. Kriteria Selesai (*Definition of Done*)

1. Seluruh unit test JVM (`./gradlew testDebugUnitTest`) lulus untuk parser tag biner (FLAC, Opus, MP3, M4A), parser LRC, StackBlur, ekstraksi palet warna, dan parser playlist `.m3u8`.
2. Build rilis (`./gradlew assembleRelease`) menghasilkan berkas `.apk` dengan ukuran total **< 2,4 MB**.
3. Tidak ada kebocoran memori `Bitmap` atau pemrosesan berat di *main thread* selama pemutaran dan pengguliran lirik.

```

---

### 2. Prompt Siap Pakai untuk Antigravity IDE

Buat folder baru bernama `laguku`, simpan `SPEC.md` di atas ke dalamnya, lalu tempel prompt berikut ke Antigravity IDE untuk membangun seluruh kodenya:

```text
[ponytail](slashCommand;ponytail) [karpathy-guidelines](slashCommand;karpathy-guidelines) [caveman](slashCommand;caveman)

Bertindaklah sebagai **Orchestrator** yang menerapkan protokol [poteto-mode](slashCommand;poteto-mode), [architect](slashCommand;architect), [lean-build](slashCommand;lean-build), [principle-foundational-thinking](slashCommand;principle-foundational-thinking), [principle-sequence-verifiable-units](slashCommand;principle-sequence-verifiable-units), dan [principle-guard-the-context-window](slashCommand;principle-guard-the-context-window) untuk membangun proyek pemutar musik Android asli bernama **Laguku** (`com.laguku.player`) sesuai dokumen `SPEC.md` dengan ukuran APK rilis **< 2,4 MB**.

Aktifkan skill spesialis berikut pada sub-agent sesuai perannya:
- **Arsitektur & Disiplin Kode**: [principle-boundary-discipline](slashCommand;principle-boundary-discipline) [principle-laziness-protocol](slashCommand;principle-laziness-protocol) [principle-experience-first](slashCommand;principle-experience-first) [principle-separate-before-serializing-shared-state](slashCommand;principle-separate-before-serializing-shared-state)
- **Desain Liquid Glass & Animasi**: [apple-design](slashCommand;apple-design) [emil-design-eng](slashCommand;emil-design-eng) [animate](slashCommand;animate) [animation-performance](slashCommand;animation-performance) [choreograph](slashCommand;choreograph) [gestures](slashCommand;gestures) [break-ui](slashCommand;break-ui)
- **Keamanan & Verifikasi**: [security-audit](slashCommand;security-audit) [tdd](slashCommand;tdd) [principle-prove-it-works](slashCommand;principle-prove-it-works) [verify-this](slashCommand;verify-this) [verify-and-stop](slashCommand;verify-and-stop) [deslop](slashCommand;deslop) [thermos](slashCommand;thermos)

### ATURAN EKSEKUSI WAJIB
1. **Baca `SPEC.md` terlebih dahulu** dan patuhi 100% batasan *Zero Bloat*: Kotlin murni + Android View System (`Activity`, `View`, `RecyclerView`, custom `Drawable`), tanpa Jetpack Compose, `AppCompat`, `MaterialComponents`, Glide/Coil, ExoPlayer/Media3, atau pustaka parser pihak ketiga.
2. **Kepemilikan File Eksklusif**: Setiap pekerja paralel hanya menyentuh daftar file miliknya agar bebas konflik.
3. **Target Ukuran Rilis < 2,4 MB**: Aktifkan R8 *full mode*, `isShrinkResources = true`, 100% aset XML Vector Drawable, dan target ABI `arm64-v8a`.

---

### GELOMBANG 1: SCAFFOLD PROYEK & MODUL INTI (JALANKAN PARALEL)

#### Pekerja 1A - Konfigurasi Gradle, Manifest, & `AGENTS.md`
- **File Eksklusif**: `AGENTS.md`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- **Spesifikasi**:
  1. Konfigurasikan proyek Android Gradle Kotlin DSL (`compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`, Java/Kotlin 17) dengan dependensi minimal (`androidx.core:core-ktx`, `androidx.recyclerview:recyclerview`, dan JUnit4 untuk unit test).
  2. Aktifkan R8 shrinker penuh (`isMinifyEnabled = true`, `isShrinkResources = true`) dan `ndk { abiFilters += "arm64-v8a" }` di `app/build.gradle.kts`.
  3. Konfigurasikan `AndroidManifest.xml` dengan izin `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` (maxSdkVersion 32), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, serta deklarasi `PlaybackService` dan `MainActivity`.
  4. Tulis `AGENTS.md` berisi indeks simbolik modul dan aturan anggaran ukuran APK < 2,4 MB.

#### Pekerja 1B - Parser Tag Biner Mandiri & Mesin Lirik (`model/`, `metadata/`, `lyrics/`)
- **File Eksklusif**: `app/src/main/kotlin/com/laguku/player/model/AudioModels.kt`, `app/src/main/kotlin/com/laguku/player/metadata/BinaryTagReader.kt`, `app/src/main/kotlin/com/laguku/player/lyrics/LrcParser.kt`, `app/src/test/kotlin/com/laguku/player/BinaryTagAndLrcTest.kt`
- **Spesifikasi**:
  1. `BinaryTagReader.kt`: Pembaca tag biner aman dengan batas alokasi memori ketat untuk **FLAC** (`VORBIS_COMMENT` & `PICTURE`), **OGG/Opus** (`OpusTags` / Vorbis comment), **MP3/WAV** (`ID3v2.3`/`ID3v2.4`: `TIT2`, `TPE1`, `TALB`, `TRCK`, `TXXX`, `USLT`, `SYLT`, `APIC`), dan **M4A/ALAC** (`©nam`, `©ART`, `©alb`, `trkn`, `©lyr`, `covr`), ditambah fallback `<nama>.lrc` dan `cover.jpg`/`folder.jpg`.
  2. `LrcParser.kt`: Parser LRC standar & multi-timestamp (`[00:15.00][01:20.00]`), `[offset:+/-ms]`, token karaoke `<mm:ss.xx>`, pencarian indeks aktif `O(log N)`, dan mode lirik statis.
  3. Tulis unit test lengkap di `BinaryTagAndLrcTest.kt`.

#### Pekerja 1C - Mesin Audio Universal, Pemindai Koleksi, & `PlaybackService` (`audio/`, `data/`, `service/`)
- **File Eksklusif**: `app/src/main/kotlin/com/laguku/player/audio/AudioEngine.kt`, `app/src/main/kotlin/com/laguku/player/data/LibraryScanner.kt`, `app/src/main/kotlin/com/laguku/player/service/PlaybackService.kt`, `app/src/test/kotlin/com/laguku/player/LibraryAndPlaylistTest.kt`
- **Spesifikasi**:
  1. `AudioEngine.kt`: Pemutar audio universal untuk **FLAC, ALAC, Opus, OGG, AAC, MP3, WAV, AMR** berbasis `MediaExtractor` + `MediaCodec` + `AudioTrack` dengan *gapless pre-buffering*, skala gain `REPLAYGAIN_TRACK_GAIN`, fallback otomatis ke `MediaPlayer`, ekstraksi info kualitas (`codec`, `bitDepth`, `sampleRateHz`, `bitrateKbps`), serta `Equalizer` 5-band dan *Sleep Timer* dengan *fade-out* 5 detik.
  2. `LibraryScanner.kt`: Pemindai koleksi lokal (`MediaStore.Audio` + penjelajah folder langsung + pembaca/penulis playlist `.m3u8` yang terlindungi dari *path traversal*).
  3. `PlaybackService.kt`: `ForegroundService` dengan `MediaSession` (artwork layar kunci, notifikasi kontrol media, audio focus, dan pause otomatis saat `ACTION_AUDIO_BECOMING_NOISY`).
  4. Tulis unit test di `LibraryAndPlaylistTest.kt`.

---

### GELOMBANG 2: LIQUID GLASS RENDERER, SPOTIFY LYRICS UI, & VERIFIKASI

#### Pekerja 2A - Liquid Glass Engine, Latar Buram Sampul, & Seluruh UI (`ui/`, `res/`)
- **File Eksklusif**: Seluruh file di `app/src/main/kotlin/com/laguku/player/ui/**`, `app/src/main/kotlin/com/laguku/player/MainActivity.kt`, `app/src/main/kotlin/com/laguku/player/LagukuApp.kt`, `app/src/main/res/**`, `app/src/test/kotlin/com/laguku/player/GlassAndPaletteTest.kt`
- **Spesifikasi**:
  1. `StackBlur.kt` & `CoverPaletteExtractor.kt`: Dekode sampul 1:1 ke ukuran mikro `64x64` di thread latar, terapkan StackBlur 2-pass (`radius = 12`) + *scrim* gelap kontras, dan ekstrak 3 warna dominan (`deepBackdrop`, `accentGlow`, `glassTint`) dengan transisi *crossfade* `350ms` (`PathInterpolator(0.23f, 1f, 0.32f, 1f)`).
  2. `LiquidGlassDrawable.kt` & `SpringAnimatorHelper.kt`: Custom `Drawable` kaca cair (radius `22dp`, isi transparan berlapis, pantulan atas, **Specular Rim Highlight** `1.2dp` bergradasi) dan animasi tekan `scale 0.97` dalam `110ms` ease-out yang menghormati pengaturan sistem *reduced motion*.
  3. `NowPlayingView.kt`, `LyricsRecyclerAdapter.kt`, & `SeekCapsuleView.kt`:
     - Header kualitas audio, sampul 1:1 membulat `20dp`, sakelar **Sampul** vs **Fokus Lirik**, seekbar kapsul kaca 1:1 mengikuti jari dengan angka *tabular*, dan tombol kontrol utama.
     - Lirik sinkron Spotify-style: baris aktif `22sp` Bold putih `100%` terkunci halus di **40% tinggi layar**, baris mendatang `18sp` Medium `64%`, baris lewat `18sp` Medium `34%`, *tap-to-seek*, dan pil kaca mengambang **"Kembali ke lirik aktif"** saat digulir manual.
  4. `LibraryView.kt` & Floating Mini Player: Tab kapsul kaca (**Lagu**, **Folder**, **Album**, **Artis**, **Playlist**) beserta mini player melayang di atas navigasi bawah.
  5. Tulis unit test di `GlassAndPaletteTest.kt`.

---

### TAHAP AKHIR ORCHESTRATOR: VERIFIKASI & AUDIT UKURAN
1. Jalankan unit test (`./gradlew testDebugUnitTest`) dan kompilasi rilis (`./gradlew assembleRelease`) jika Android SDK/Gradle tersedia di mesin.
2. Periksa ukuran file `.apk` hasil build (atau estimasi ukuran DEX + resource) untuk membuktikan tetap di bawah **2,4 MB**.
3. Tinjau seluruh kode dengan [deslop](slashCommand;deslop) dan [thermos](slashCommand;thermos) untuk memastikan bebas kode mati dan menjaga standar performa 60/120 FPS.

```