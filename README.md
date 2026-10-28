# TV Trackpad Android (Mandiri Tanpa Mac / Node.js)

Aplikasi Android mandiri untuk mengontrol TV LG webOS (Trackpad kursor, Scroll 2 jari, Drag 3 jari, Keyboard, Volume, Back, Home) langsung dari HP Android tanpa perlu server Node.js/Mac.

## Fitur Utama
- **100% Mandiri di Android**: Koneksi WebSocket (`wss://:3001` dengan bypass SSL sertifikat lokal TV LG dan `ws://:3000`) berjalan langsung secara native di Android.
- **Auto-Scan TV LG di WiFi**: Memindai TV LG otomatis lewat protokol SSDP (`urn:lge-com:service:webos-second-screen:1`) dan pemindaian cepat subnet WiFi pada port `3001` / `3000`.
- **Input IP Manual**: Cadangan input IP manual kapan saja dengan mengetuk bar status atau ikon TV di kanan atas.
- **Simpan Izin Otomatis**: Menyimpan `client-key` TV LG di memori HP sehingga tidak perlu klik *YA / OK* berulang kali di TV setiap buka aplikasi.

---

## Cara Build APK Otomatis Lewat GitHub (Tanpa Install Android Studio)

1. Buat repository baru di akun **GitHub** kamu (bisa Public atau Private).
2. Ekstrak isi file `LG-Trackpad-Android.zip` ini.
3. Upload semua file & folder (termasuk folder `.github`, `app`, `build.gradle`, `settings.gradle`, `gradle.properties`) ke repository GitHub tersebut.
   - **Tips penting jika upload lewat browser GitHub**: Pastikan folder `.github/workflows/build-apk.yml` ikut ter-upload, atau gunakan menu **Add file > Create new file**, beri nama `.github/workflows/build-apk.yml`, lalu paste isi file `build-apk.yml`.
4. Buka tab **Actions** di repository GitHub kamu.
5. Tunggu proses workflow **Build Android APK** selesai (sekitar 1 - 2 menit, sampai centang hijau).
6. Klik workflow yang sudah selesai tersebut, gulir ke bagian bawah (**Artifacts**), lalu klik **TV-Trackpad-APK** untuk mengunduh file APK-nya.
7. Ekstrak hasil download tersebut dan install `TV-Trackpad.apk` di HP Android kamu.
