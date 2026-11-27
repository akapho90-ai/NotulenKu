# Stage 2 Processing Integrity

Branch `stage2-processing-integrity` melanjutkan baseline Stage 1 `bda795b`. Modul Android berada di root proyek. File audio Stage 1 yang sebelumnya berada di luar source set dipindahkan ke modul aktif. Sumber backend, aturan Firestore, dan migration yang hilang dari branch dipulihkan dari `main` pada `6bda015` dengan isi yang sama.

## Perubahan

- **Checkpoint.** Penulisan menyinkronkan file sementara dan memvalidasi isinya sebelum pemasangan. Recovery memeriksa identitas dan tahapan job, memuat cadangan saat file utama rusak atau hilang, dan menampilkan job yang hanya memiliki cadangan. Kegagalan checkpoint menghentikan langkah berikutnya. Retry memproses ulang bagian yang gagal dan memakai kembali transkrip atau ringkasan yang sudah tersimpan.
- **Audio dan transkrip.** MediaExtractor memeriksa sumber audio. Potongan dibatasi ukurannya dan disimpan di Chunks per ID rapat, dengan penyimpanan privat persisten sebagai cadangan. Potongan cache dari versi sebelumnya tetap dapat dibaca selama masa retensi. Proses mengabaikan timestamp priming AAC yang negatif saat membangun jendela normal. Normalisasi overlap mempertahankan aksara Unicode dan menghindari penghapusan ucapan di luar tumpang tindih atau dari pembicara berbeda.
- **File master rekaman.** Android scoped storage menolak penulisan langsung ke `Android/media/NotulenKu/Meet`. Master baru dan hasil recovery menggunakan `Android/media/com.aistudio.notulenrapat.kxmpzq/NotulenKu/Meet` dengan ekstensi `.m4a`. Jika media eksternal tidak bisa ditulis, aplikasi memakai folder privat persisten `files/recordings/masters`. File lama tetap berada di lokasi asalnya. Merge dan recovery menolak hasil jika bagian hilang atau rusak.
- **AI dan backend.** Permintaan HTTP dapat dibatalkan. Retry teks terbatas pada kegagalan sementara. Provider yang dipilih dan tidak tersedia memunculkan kegagalan. Respons transkripsi atau ringkasan kosong tidak menandai tahap selesai. Simpan ulang menggunakan ID rapat yang sama dan token Firebase dari akun pemilik checkpoint. Aplikasi mempertahankan checkpoint sampai server mengonfirmasi hasil. Penghapusan memilih backend sesuai jenis rapat.
- **UI.** Tombol pemrosesan menonaktifkan aksi duplikat selama job aktif. Status selesai baru tampil setelah simpan berhasil. Pengguna masih dapat melanjutkan job yang tersimpan setelah membuka ulang aplikasi.

## Pengujian yang dijalankan

| Pengujian | Hasil |
| --- | --- |
| `gradle testDebugUnitTest` dengan Java 21 | 39 lulus. Mencakup retry chunk, penggunaan ulang transkrip dan ringkasan, checkpoint gagal, isolasi owner, overlap Unicode, respons STT yang tidak valid, dan status Compose. |
| `gradle assembleDebug assembleDebugAndroidTest` | Lulus menggunakan konfigurasi Firebase dan penandatangan lokal untuk tes. |
| Instrumentation Android API 37 | 6 lulus. APK terpasang dan `MainActivity` terbuka. Pengujian mencakup WAV dan AAC sintetis, potongan AAC sampai jendela terakhir, pemulihan cadangan checkpoint, penulisan dan pembacaan master satu atau beberapa bagian, serta penolakan master yang tidak lengkap. |
| `node --test functions/test/*.test.js` | 10 lulus. Mencakup idempotensi, account linking, isolasi akun, dan routing AI. |
| `node --test firestore.test.js` pada emulator Firestore 1.22.0 | 5 lulus dengan emulator ber-locale `en_US` dan proyek demo lokal. |

## Batas yang masih berlaku

- Ringkasan otomatis mendukung transkrip sampai 120.000 karakter. Transkrip yang lebih panjang tetap berada di checkpoint dan aplikasi menampilkan batas tersebut. Backend juga memiliki batas jumlah segmen dan field yang diperiksa sebelum pengiriman.
- Setelah proses Android dihentikan, pengguna melanjutkan job dari checkpoint saat membuka kembali aplikasi. Tes lokal memakai audio sintetis dan backend tes. SumoPod, login Firebase, Supabase produksi, penagihan model, dan identitas pembicara lintas chunk belum diuji dengan akun nyata.
- Uji perangkat fisik masih perlu dilakukan untuk perekaman mikrofon, background dan layar terkunci, rekaman panjang sampai seek dan playback, penghentian proses saat setiap tahap, gangguan jaringan saat simpan, dan perpindahan akun termasuk account linking.

## Verifikasi chunk dan interupsi

- Chunk sementara memakai `Android/media/com.aistudio.notulenrapat.kxmpzq/NotulenKu/Chunks/<meetingId>/chunk_0000.m4a`. Jika penyimpanan media gagal, aplikasi memakai `files/processing_audio_chunks/<meetingId>/`. Retensi dihitung 72 jam sejak file dibuat. Aplikasi memeriksa file kedaluwarsa saat layar pemrosesan diinisialisasi atau percobaan job selesai, serta melindungi job aktif dan folder Meet. Pemrosesan memecah audio dengan jendela maksimal 180 detik jika sumber melewati batas langsung yang sudah ada, yaitu 240 detik atau 8 MiB. Jendela berurutan memakai overlap 2 detik. Emulator menghasilkan jendela `0–180`, `178–358`, dan `356–370` dari AAC 370 detik, mengirim chunk ke adapter STT dengan respons tiruan, dan membangun ulang chunk setelah cache dibersihkan. Master tetap utuh.
- Panggilan telepon emulator menjeda capture, membekukan timer, dan menolak resume saat masih terganggu. Resume manual setelah panggilan selesai menghasilkan master yang bisa dibaca. Tes WhatsApp dan Firebase produksi masih perlu dilakukan di perangkat fisik.
