# AI Notulen Rapat

Aplikasi Android mobile-first (Kotlin + Jetpack Compose + Material 3) untuk merekam/mengunggah audio rapat, membuat transkrip berlabel pembicara, menyusun notulen AI terstruktur, melakukan tanya jawab lintas rapat, dan mengekspor infografis visual 4:5 — dirancang sebagai SaaS multi-tenant berbasis organisasi.

## Fitur Utama
- **Multi-Tenant Organisasi**: Isolasi data ketat per organisasi (`tenants`), peran `owner`, `admin`, dan `member`, serta pemilih workspace aktif.
- **Autentikasi Aman**: Google Sign-In menggunakan Jetpack `CredentialManager` dan Firebase Auth.
- **Rekam & Unggah Audio**: Perekaman `.m4a` (AAC 16kHz mono) dengan waveform real-time, jeda/lanjut, `keepScreenOn`, dan unggah via Android Storage Access Framework (`OpenDocument`).
- **Mesin AI (SumoPod AI via backend Supabase `meeting-api`)**: Transkripsi pembicara, ringkasan & action items, analisis rapat, koreksi transkrip (*diff*), tanya jawab dengan kutipan sumber, dan data infografis visual.
- **Mode Visual & Ekspor**: Gaya Kartu Poin, Peta Pikiran, dan Linimasa (rasio 4:5) serta ekspor ke PDF, TXT, SRT, PNG, JPG, dan CSV.
- **Hub Admin**: Routing model per tugas, Playground perbandingan 2 model, pemakaian & biaya + tarif manual, manajemen tim/undangan, dan log audit.

## Dokumentasi Terkait
- `docs/ARCHITECTURE.md` — Arsitektur Android, koleksi Firestore, peran, dan layanan AI.
- `docs/SECURITY_REVIEW.md` — Tinjauan keamanan, RLS Firestore, App Check, dan perlindungan injeksi prompt.
- `docs/RUNBOOK.md` — Panduan operasional dan penanganan error umum.
