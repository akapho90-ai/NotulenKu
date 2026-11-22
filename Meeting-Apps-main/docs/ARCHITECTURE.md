# Arsitektur AI Notulen Rapat (Android)

## Ringkasan Komponen
- **UI & Navigasi (Jetpack Compose + Material 3)**:
  - `NotulenAppRoot` (`AppNavigation.kt`): Memantau `Firebase.auth.authStateFlow()`, mengarahkan pengguna tak terautentikasi ke `AuthScreen`, dan mengisolasi state ViewModel per `uid`.
  - Layar utama adaptif: `NavigationBar` (5 tab pada `< 600dp`) dan `NavigationRail` (`>= 600dp`) untuk Beranda, Riwayat, Rekam, Tanya, dan Profil.
- **Autentikasi & Keamanan Transport**:
  - `configureAppCheck` (`AppCheckInit.kt`) dipasang di `MainActivity.onCreate()` sebelum `setContent`.
  - `AuthManager.kt` menjalankan silent auto sign-in (`GetGoogleIdOption`) dan tombol masuk eksplisit (`GetSignInWithGoogleOption`).
- **Data Layer (Cloud Firestore Enterprise DB)**:
  - Menggunakan instance bernama (`firestore_database_id`) di `TenantRepository.kt` dan `MeetingRepository.kt`.
  - **Koleksi**:
    - `/users/{userId}`: Profil privat (`userId`, `displayName`, `username`, `email`, `photoUrl`, `activeTenantId`).
    - `/tenants/{tenantId}`: Organisasi (`ownerId`, `memberUids`, `adminUids`, `aiMode`, `watermarkEnabled`, `maxFileMb`, `retentionDays`, `monthlyBudgetUsd`, `status`).
    - `/tenants/{tenantId}/members/{memberUid}`: Anggota & peran (`owner`, `admin`, `member`).
    - `/tenants/{tenantId}/meetings/{meetingId}`: Metadata rapat, segmen transkrip, notulen, analisis, dan data visual dengan kontrol visibilitas `visibleToUids`.
    - `/tenants/{tenantId}/qaMessages/{msgId}`: Riwayat tanya jawab privat per pengguna.
    - `/tenants/{tenantId}/taskConfigs/{taskId}`: Routing model per tugas (`audio_transcription`, `meeting_summary`, `meeting_analysis`, `meeting_qa`, `action_item_extraction`, `transcript_correction`, `structured_visual_data`).
    - `/tenants/{tenantId}/usageLogs/{logId}`: Metadata token, latensi, durasi audio, dan estimasi biaya (tanpa teks transkrip/prompt).
    - `/tenants/{tenantId}/auditLogs/{logId}`: Jejak audit aksi administratif.
    - `/tenants/{tenantId}/modelPrices/{priceId}`: Tarif token manual per model.
    - `/tenants/{tenantId}/invites/{inviteId}`: Undangan tim.
- **Mesin AI (`AiService.kt`)**:
  - Seluruh pemanggilan AI diarahkan ke backend Supabase `meeting-api` (SumoPod AI) melalui `MeetingBackendAiClient.kt`, dengan Firebase ID Token sebagai autentikasi. Firebase tidak dipakai sebagai mesin AI.
  - Firebase hanya dipakai untuk autentikasi (Google Sign-In & Email) dan UID pengguna.
  - Seluruh komponen UI bersifat provider/model-agnostik dan membaca model dari `taskConfigs`.
