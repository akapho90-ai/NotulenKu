# Runbook Operasional NotulenKu (Catatan Rapat AI)

## 1. Deploy Backend `meeting-api` ke Supabase (SumoPod AI + Notulen Pribadi)

Kode Edge Function terbaru berada di `supabase/functions/meeting-api/index.ts`.
Fungsi ini mempertahankan seluruh endpoint penyimpanan rapat yang sudah berjalan (`savePersonalMeeting`, `listPersonalMeetings`, `getPersonalMeetingDetail`, `updatePersonalMeeting`, `deletePersonalMeeting`, `linkSupabaseAccount`) sekaligus menambahkan `processAi` dan `checkAiHealth` yang memanggil SumoPod memakai secret `SUMOPOD_KEY`.

### Prasyarat Secret di Supabase Project `ojmdiiwowiqvcxgexxgr`
Pastikan secret berikut sudah aktif di **Supabase Dashboard → Project Settings → Edge Functions → Secrets** (JANGAN pernah menyimpan nilainya di source code atau repository):
- `SUMOPOD_KEY` — API key SumoPod untuk `https://ai.sumopod.com/v1`
- `SUPABASE_URL` — `https://ojmdiiwowiqvcxgexxgr.supabase.co`
- `SUPABASE_SERVICE_ROLE_KEY` — Service role key Supabase
- `SUPABASE_ANON_KEY` atau `SUPABASE_PUBLISHABLE_KEY` — Untuk proses penautan akun opsional
- `FIREBASE_PROJECT_ID` — `gen-lang-client-0830960267` (opsional, sudah menjadi default di kode)

### Langkah Deploy
```bash
supabase functions deploy meeting-api \
  --project-ref ojmdiiwowiqvcxgexxgr \
  --no-verify-jwt
```
> **Penting:** Bendera `--no-verify-jwt` diperlukan karena autentikasi menggunakan **Firebase ID Token** (`Authorization: Bearer <firebase_id_token>`), yang diverifikasi secara kriptografis (RS256 via Google JWKS: signature, `iss`, `aud`, `exp`, `iat`, `auth_time`, `sub`) di dalam fungsi `meeting-api`.

### Verifikasi Setelah Deploy (Wajib Sebelum Build APK Produksi)
1. **Cek Endpoint Kesehatan AI:**
   Kirim `POST https://ojmdiiwowiqvcxgexxgr.supabase.co/functions/v1/meeting-api/checkAiHealth` dengan header `Authorization: Bearer <FIREBASE_ID_TOKEN>` dan body `{"data":{}}`.
   Pastikan respons `200 OK` berisi `result.ok = true`.
2. **Verifikasi Audio Live SumoPod (`gemini/gemini-3.5-flash-lite`):**
   Siapkan rekaman uji singkat (.m4a 5–10 detik berisi suara jelas), encode ke base64 secara lokal, lalu kirim ke `POST .../meeting-api/processAi`:
   ```json
   {
     "data": {
       "task": "audio_transcription",
       "audioBase64": "<BASE64_AUDIO_M4A_UJI>",
       "audioMimeType": "audio/mp4",
       "preferredLanguage": "id"
     }
   }
   ```
   - Jika respons `200 OK` dan teks transkrip sesuai isi rekaman uji, dukungan audio `input_audio` pada SumoPod terverifikasi.
   - Jika SumoPod menolak payload `input_audio` (misalnya `400/422` unsupported media/format), catat pesan error persis dari respons backend untuk menyesuaikan format audio atau endpoint SumoPod yang didukung sebelum rilis.

---

## 2. Konfigurasi Login Google, `google-services.json`, & Keystore Tetap

### Identitas Proyek (Jangan Diubah)
- Android Package Name: `com.aistudio.notulenrapat.kxmpzq`
- Firebase Project ID: `gen-lang-client-0830960267`
- Firebase Project Number: `699450924977`

### Penyebab Umum Gagal Login Google di Perangkat
1. `app/google-services.json` tidak sinkron atau tidak memiliki `oauth_client` bertipe `3` (Web Client ID) sehingga `default_web_client_id` kosong.
2. Sidik jari **SHA-1** dan **SHA-256** dari keystore yang dipakai menandatangani APK (Debug maupun Release/Upload) belum didaftarkan di **Firebase Console → Project Settings → Your apps → Android (`com.aistudio.notulenrapat.kxmpzq`)**.
3. Keystore rilis berubah-ubah antar build CI/CD.

### Fingerprint `debug.keystore` yang Teramati di Workspace Saat Ini
- **SHA-1:** `E7:52:C1:79:80:36:F4:B7:6E:54:4D:11:F2:82:16:31:4F:E9:11:D0`
- **SHA-256:** `51:CC:EB:83:65:E3:E3:C7:33:B2:EC:E0:6E:E7:D0:66:6F:CE:73:FF:4D:82:D6:87:A0:C2:C7:7E:D6:E4:87:7E`

### Langkah Menyiapkan Keystore Tetap & Firebase Console
1. **Gunakan 1 Keystore Rilis Tetap (alias `upload`)** — simpan aman di brankas tim & GitHub Actions Secrets (JANGAN commit ke repository):
   - `KEYSTORE_BASE64` (hasil `base64 -w 0 upload-keystore.jks`)
   - `STORE_PASSWORD`
   - `KEY_PASSWORD`
   - `GOOGLE_SERVICES_JSON_BASE64` (file `app/google-services.json` terbaru dari Firebase Console)
2. Ambil SHA-1 dan SHA-256 keystore rilis:
   ```bash
   keytool -list -v -keystore upload-keystore.jks -alias upload
   ```
3. Daftarkan **SHA-1** dan **SHA-256** (baik debug maupun release/upload, serta Google Play App Signing jika memakai Play Console) di **Firebase Console → Project Settings → Android App (`com.aistudio.notulenrapat.kxmpzq`)**.
4. Pastikan metode **Google** aktif di **Firebase Console → Authentication → Sign-in method**.
5. Unduh ulang `google-services.json` terbaru dari Firebase Console setelah menambahkan SHA-1/SHA-256, lalu pastikan nilai `default_web_client_id` tergenerate saat build.

### Peringatan Keamanan Data Saat Pembaruan APK
- **JANGAN uninstall APK lama** sebelum memastikan rekaman audio lokal yang masih dibutuhkan sudah diekspor/dicadangkan, karena file rekaman asli disimpan di penyimpanan privat aplikasi pada perangkat.
- Jika keystore rilis yang dipakai menandatangani APK lama berbeda atau hilang, Android **tidak dapat memasang APK baru sebagai update di atas APK lama** (akan gagal dengan konflik signature). Pertahankan keystore tetap untuk semua rilis berikutnya.

---

## 3. Penanganan Kendala Operasional di Aplikasi
- **Izin Mikrofon / Notifikasi Ditolak:** Buka Pengaturan Aplikasi → Izin → Aktifkan **Mikrofon** dan **Notifikasi** agar layanan perekaman latar belakang dapat berjalan stabil saat layar dikunci.
- **Perekaman Dijeda Otomatis saat Panggilan Masuk / Headset Terlepas:** NotulenKu otomatis menjeda rekaman secara aman tanpa menghapus bagian audio yang sudah terekam. Setelah panggilan selesai, ketuk **Lanjutkan Rekam** untuk meneruskan sesi yang sama atau **Selesai** untuk memproses rekaman yang sudah ada.
- **Transkrip atau Penyimpanan Gagal di Tengah Jalan:** Rekaman asli tetap tersimpan di perangkat. Ketuk **Proses Ulang** (atau **Coba Simpan Lagi** jika AI sudah selesai) untuk melanjutkan hanya dari segmen/tahap yang gagal tanpa mengulang bagian yang sudah berhasil.
