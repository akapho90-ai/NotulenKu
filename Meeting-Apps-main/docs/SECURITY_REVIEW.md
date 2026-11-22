# Tinjauan Keamanan (Security Review)

1. **Zero Hardcoded Secrets & Keys**
   - Tidak ada API key AI, token OAuth web client ID hardcode, atau kredensial akun demo yang disimpan di kode sumber atau repositori.
   - Autentikasi layanan AI ditangani melalui **Firebase ID Token** yang diverifikasi backend Supabase `meeting-api`, dengan secret `SUMOPOD_KEY` hanya di sisi server. Firebase AI Logic tidak digunakan.
   - **Firebase App Check** (Debug provider untuk debug build, reCAPTCHA untuk rilis) tetap dipakai untuk melindungi layanan Firebase.

2. **Isolasi Multi-Tenant & Firestore Security Rules (`firestore.rules`)**
   - Aturan default menolak seluruh akses (`allow read, write: if false;`).
   - Semua operasi `list` memeriksa array keanggotaan terdenormalisasi (`memberUids`, `visibleToUids`, `adminUids`) atau kepemilikan `userId` tanpa `get()` di query list.
   - Aksi administratif (`taskConfigs`, `modelPrices`, `invites`, `auditLogs`, pengaturan tenant) diverifikasi melalui `isTenantAdmin(tenantId)` dan pembatasan field (`affectedKeys().hasOnly(...)`).
   - Profil pengguna `/users/{userId}` hanya dapat diakses dan dihapus oleh pemilik UID itu sendiri.

3. **Privasi Data Rapat & Anti-Prompt Injection**
   - `usageLogs` dan `auditLogs` tidak pernah menyimpan teks transkrip, isi prompt, atau audio.
   - Seluruh prompt AI di `AiService.kt` membungkus konten transkrip dengan pembatas eksplisit (`<TRANSKRIP_DATA>...</TRANSKRIP_DATA>` / `<KONTEKS_RAPAT>...</KONTEKS_RAPAT>`) serta menginstruksikan model mengabaikan perintah di dalam data.
   - Keluaran AI divalidasi dengan `Schema` JSON terstruktur dan dirender menggunakan komponen teks Compose (bebas risiko eksekusi HTML/XSS).

4. **Izin Perangkat (Least-Privilege & Google Play Policy)**
   - Hanya mendeklarasikan `INTERNET`, `ACCESS_NETWORK_STATE`, dan `RECORD_AUDIO` (diminta saat runtime).
   - Pemilihan berkas audio menggunakan Android Storage Access Framework (`OpenDocument("audio/*")`) tanpa izin penyimpanan luas.
   - Berkas ekspor sementara (`.pdf`, `.png`, `.jpg`, `.txt`, `.srt`, `.csv`) dibagikan secara aman melalui `FileProvider`.
