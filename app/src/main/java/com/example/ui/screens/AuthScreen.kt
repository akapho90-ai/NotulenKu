package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.core.AppConstants
import com.example.data.auth.attemptAutoSignIn
import com.example.data.auth.isValidEmailFormat
import com.example.data.auth.mapFirebaseAuthError
import com.example.data.auth.onGoogleSignInClicked
import com.example.data.auth.validateLoginInput
import com.example.data.auth.validateRegistrationInput
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val LightBg = Color(0xFFF5F8FF)
private val DarkBg = Color(0xFF0B1120)
private val AuthBlue = Color(0xFF2F6BFF)
private val AuthBlueTop = Color(0xFF3B82F6)
private val AuthBlueBottom = Color(0xFF2563EB)
private val HeadingNavy = Color(0xFF0F1E4A)
private val SubtitleBlueGrey = Color(0xFF5B6B8C)
private val BorderLight = Color(0xFFDCE3F0)
private val InactiveTabBgLight = Color(0xFFF4F7FD)
private val MutedSlate = Color(0xFF64748B)
private val ErrorCrimson = Color(0xFFDC2626)
private val MintGreen = Color(0xFF34D399)

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    enableAutoSignIn: Boolean = true,
    onEmailSignIn: (suspend (String, String) -> Unit)? = null,
    onEmailSignUp: (suspend (String, String) -> Unit)? = null,
    onPasswordReset: (suspend (String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val credentialManager = remember { CredentialManager.create(context) }
    val darkTheme = isSystemInDarkTheme()

    val authPrefs = remember {
        context.getSharedPreferences("notulen_auth_prefs", Context.MODE_PRIVATE)
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Masuk, 1 = Daftar
    var showForgotPassword by remember { mutableStateOf(false) }

    // Login state (hanya email yang dapat diingat, tidak pernah menyimpan password)
    var loginEmail by remember {
        val savedEmail = authPrefs.getString("remembered_email", null)
            ?: authPrefs.getString("remembered_identifier", "")?.takeIf { isValidEmailFormat(it) }
            ?: ""
        mutableStateOf(savedEmail)
    }
    var loginPassword by remember { mutableStateOf("") }
    var loginPasswordVisible by remember { mutableStateOf(false) }
    var rememberMe by remember {
        mutableStateOf(authPrefs.getBoolean("remember_me", true))
    }

    // Register state (menggunakan email sebagai identitas login)
    var regEmail by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var regConfirmPassword by remember { mutableStateOf("") }
    var regPasswordVisible by remember { mutableStateOf(false) }
    var regConfirmVisible by remember { mutableStateOf(false) }

    // Forgot password state
    var forgotInput by remember { mutableStateOf("") }
    var forgotStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var isGoogleLoading by remember { mutableStateOf(false) }
    var isFormLoading by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var emailAlreadyInUse by remember { mutableStateOf(false) }
    var appear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appear = true
        if (enableAutoSignIn && rememberMe) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = onAuthSuccess,
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val bgColor = if (darkTheme) DarkBg else LightBg
    val cardBg = if (darkTheme) Color(0xFF111827) else Color.White
    val inactiveTabBg = if (darkTheme) Color(0xFF1E293B) else InactiveTabBgLight
    val titleColor = if (darkTheme) Color(0xFFF8FAFC) else HeadingNavy
    val subtitleColor = if (darkTheme) Color(0xFFCBD5E1) else SubtitleBlueGrey
    val fieldBorderColor = if (darkTheme) Color(0xFF334155) else BorderLight
    val fieldTextColor = if (darkTheme) Color(0xFFF8FAFC) else HeadingNavy
    val mutedColor = if (darkTheme) Color(0xFF94A3B8) else MutedSlate

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Soft blue organic waves in corners/sides matching reference
        SoftBlueWavesBackground(darkTheme = darkTheme)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center
        ) {
            val isCompactHeight = maxHeight < 760.dp
            val illustrationHeight = when {
                maxHeight < 660.dp -> 108.dp
                isCompactHeight -> 132.dp
                else -> 158.dp
            }

            AnimatedVisibility(
                visible = appear,
                enter = fadeIn() + scaleIn(initialScale = 0.96f)
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 430.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = if (isCompactHeight) 8.dp else 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Top Hero Vector Illustration
                    AuthHeroIllustration(
                        height = illustrationHeight,
                        darkTheme = darkTheme,
                        bgColor = bgColor
                    )

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

                    // Title & Subtitle
                    Text(
                        text = AppConstants.APP_NAME,
                        fontSize = if (isCompactHeight) 24.sp else 27.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = titleColor,
                        textAlign = TextAlign.Center,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = AppConstants.APP_SUBTITLE,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AuthBlue,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Rekam, transkrip, dan dapatkan notulen\nrapat secara otomatis dengan AI.",
                        fontSize = if (isCompactHeight) 13.sp else 14.sp,
                        lineHeight = 19.sp,
                        color = subtitleColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 18.dp))

                    // Main Auth Card
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = cardBg,
                        border = BorderStroke(1.dp, if (darkTheme) Color(0xFF1E293B) else Color(0xFFE6EEFC)),
                        shadowElevation = if (darkTheme) 2.dp else 10.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (!showForgotPassword) {
                                // Tabs: Masuk | Daftar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                ) {
                                    AuthCardTab(
                                        label = "Masuk",
                                        icon = Icons.Rounded.Person,
                                        selected = selectedTab == 0,
                                        enabled = !isFormLoading && !isGoogleLoading,
                                        cardBg = cardBg,
                                        inactiveBg = inactiveTabBg,
                                        mutedColor = mutedColor,
                                        onClick = {
                                            selectedTab = 0
                                            formError = null
                                            emailAlreadyInUse = false
                                        },
                                        modifier = Modifier.weight(1f).testTag("auth_tab_login")
                                    )
                                    AuthCardTab(
                                        label = "Daftar",
                                        icon = Icons.Rounded.PersonAdd,
                                        selected = selectedTab == 1,
                                        enabled = !isFormLoading && !isGoogleLoading,
                                        cardBg = cardBg,
                                        inactiveBg = inactiveTabBg,
                                        mutedColor = mutedColor,
                                        onClick = {
                                            selectedTab = 1
                                            formError = null
                                            emailAlreadyInUse = false
                                        },
                                        modifier = Modifier.weight(1f).testTag("auth_tab_register")
                                    )
                                }
                                HorizontalDivider(color = fieldBorderColor.copy(alpha = 0.75f))
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = if (isCompactHeight) 14.dp else 18.dp),
                                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 10.dp else 12.dp)
                            ) {
                                when {
                                    showForgotPassword -> {
                                        // Forgot Password sub-view
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                enabled = !isFormLoading,
                                                onClick = {
                                                    showForgotPassword = false
                                                    forgotStatus = null
                                                }
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                                    contentDescription = "Kembali masuk",
                                                    tint = titleColor
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Lupa Password",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = titleColor
                                            )
                                        }
                                        Text(
                                            text = "Masukkan email akun Anda untuk menerima tautan pengaturan ulang password.",
                                            fontSize = 12.5.sp,
                                            color = subtitleColor
                                        )
                                        FieldLabel("Email", titleColor)
                                        RoundedAuthTextField(
                                            value = forgotInput,
                                            onValueChange = { forgotInput = it; forgotStatus = null },
                                            placeholder = "Masukkan email",
                                            leadingIcon = Icons.Outlined.Email,
                                            borderColor = fieldBorderColor,
                                            textColor = fieldTextColor,
                                            mutedColor = mutedColor,
                                            keyboardType = KeyboardType.Email,
                                            imeAction = ImeAction.Done,
                                            modifier = Modifier.testTag("forgot_identifier_input")
                                        )
                                        if (forgotStatus != null) {
                                            val (ok, msg) = forgotStatus!!
                                            Text(
                                                text = msg,
                                                fontSize = 12.sp,
                                                color = if (ok) Color(0xFF059669) else ErrorCrimson
                                            )
                                        }
                                        PrimaryArrowButton(
                                            text = "Kirim tautan",
                                            isLoading = isFormLoading,
                                            enabled = !isFormLoading,
                                            onClick = {
                                                if (isFormLoading) return@PrimaryArrowButton
                                                focusManager.clearFocus()
                                                val clean = forgotInput.trim()
                                                if (clean.isBlank()) {
                                                    forgotStatus = false to "Masukkan alamat email terlebih dahulu."
                                                    return@PrimaryArrowButton
                                                }
                                                if (!isValidEmailFormat(clean)) {
                                                    forgotStatus = false to "Format email tidak valid."
                                                    return@PrimaryArrowButton
                                                }
                                                isFormLoading = true
                                                forgotStatus = null
                                                scope.launch {
                                                    try {
                                                        if (onPasswordReset != null) {
                                                            onPasswordReset.invoke(clean)
                                                        } else {
                                                            Firebase.auth.sendPasswordResetEmail(clean).await()
                                                        }
                                                        forgotStatus = true to "Jika akun terdaftar, tautan reset sudah dikirim ke email."
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: FirebaseNetworkException) {
                                                        forgotStatus = false to mapFirebaseAuthError(e, isRegister = false).message
                                                    } catch (_: Exception) {
                                                        // Keep neutral privacy-safe confirmation for unknown account
                                                        forgotStatus = true to "Jika akun terdaftar, tautan reset sudah dikirim ke email."
                                                    } finally {
                                                        isFormLoading = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.testTag("forgot_submit_button")
                                        )
                                    }

                                    selectedTab == 0 -> {
                                        // TAB MASUK
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            FieldLabel("Email", titleColor)
                                            RoundedAuthTextField(
                                                value = loginEmail,
                                                onValueChange = {
                                                    loginEmail = it
                                                    formError = null
                                                },
                                                placeholder = "Masukkan email",
                                                leadingIcon = Icons.Outlined.Email,
                                                borderColor = fieldBorderColor,
                                                textColor = fieldTextColor,
                                                mutedColor = mutedColor,
                                                keyboardType = KeyboardType.Email,
                                                imeAction = ImeAction.Next,
                                                modifier = Modifier.testTag("login_identifier_input")
                                            )
                                        }

                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            FieldLabel("Password", titleColor)
                                            RoundedAuthTextField(
                                                value = loginPassword,
                                                onValueChange = {
                                                    loginPassword = it
                                                    formError = null
                                                },
                                                placeholder = "Masukkan password Anda",
                                                leadingIcon = Icons.Outlined.Lock,
                                                borderColor = fieldBorderColor,
                                                textColor = fieldTextColor,
                                                mutedColor = mutedColor,
                                                isPassword = true,
                                                passwordVisible = loginPasswordVisible,
                                                onTogglePasswordVisible = { loginPasswordVisible = !loginPasswordVisible },
                                                imeAction = ImeAction.Done,
                                                onDone = { focusManager.clearFocus() },
                                                modifier = Modifier.testTag("login_password_input")
                                            )
                                        }

                                        // Remember Me + Forgot Password
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable(enabled = !isFormLoading && !isGoogleLoading) {
                                                        rememberMe = !rememberMe
                                                        authPrefs.edit().apply {
                                                            putBoolean("remember_me", rememberMe)
                                                            if (!rememberMe) {
                                                                remove("remembered_email")
                                                                remove("remembered_identifier")
                                                            }
                                                        }.apply()
                                                    }
                                                    .padding(vertical = 6.dp, horizontal = 2.dp)
                                            ) {
                                                Checkbox(
                                                    checked = rememberMe,
                                                    enabled = !isFormLoading && !isGoogleLoading,
                                                    onCheckedChange = { checked ->
                                                        rememberMe = checked
                                                        authPrefs.edit().apply {
                                                            putBoolean("remember_me", checked)
                                                            if (!checked) {
                                                                remove("remembered_email")
                                                                remove("remembered_identifier")
                                                            }
                                                        }.apply()
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = AuthBlue,
                                                        uncheckedColor = fieldBorderColor,
                                                        checkmarkColor = Color.White
                                                    ),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Ingat saya",
                                                    fontSize = 13.sp,
                                                    color = titleColor
                                                )
                                            }
                                            Text(
                                                text = "Lupa password?",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AuthBlue,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable(enabled = !isFormLoading && !isGoogleLoading) {
                                                        forgotInput = loginEmail.trim()
                                                        forgotStatus = null
                                                        formError = null
                                                        showForgotPassword = true
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 8.dp)
                                                    .testTag("forgot_password_link")
                                            )
                                        }

                                        PrimaryArrowButton(
                                            text = "Masuk",
                                            isLoading = isFormLoading,
                                            enabled = !isFormLoading && !isGoogleLoading,
                                            onClick = {
                                                if (isFormLoading || isGoogleLoading) return@PrimaryArrowButton
                                                focusManager.clearFocus()
                                                formError = null
                                                val emailTrim = loginEmail.trim()
                                                val validationError = validateLoginInput(emailTrim, loginPassword)
                                                if (validationError != null) {
                                                    formError = validationError
                                                    return@PrimaryArrowButton
                                                }
                                                isFormLoading = true
                                                scope.launch {
                                                    try {
                                                        if (onEmailSignIn != null) {
                                                            onEmailSignIn.invoke(emailTrim, loginPassword)
                                                        } else {
                                                            Firebase.auth.signInWithEmailAndPassword(emailTrim, loginPassword).await()
                                                        }
                                                        authPrefs.edit().apply {
                                                            if (rememberMe) {
                                                                putString("remembered_email", emailTrim)
                                                            } else {
                                                                remove("remembered_email")
                                                            }
                                                            remove("remembered_identifier")
                                                        }.apply()
                                                        onAuthSuccess()
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        formError = mapFirebaseAuthError(e, isRegister = false).message
                                                    } finally {
                                                        isFormLoading = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.testTag("login_submit_button")
                                        )

                                        if (formError != null) {
                                            Text(
                                                text = formError!!,
                                                fontSize = 12.sp,
                                                color = ErrorCrimson,
                                                maxLines = 3,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("auth_form_error")
                                            )
                                        }

                                        AuthDivider(label = "atau masuk dengan", borderColor = fieldBorderColor, textColor = mutedColor)

                                        GoogleOutlineButton(
                                            isLoading = isGoogleLoading,
                                            enabled = !isGoogleLoading && !isFormLoading,
                                            borderColor = fieldBorderColor,
                                            textColor = titleColor,
                                            onClick = {
                                                if (isGoogleLoading || isFormLoading) return@GoogleOutlineButton
                                                formError = null
                                                isGoogleLoading = true
                                                onGoogleSignInClicked(
                                                    context = context,
                                                    credentialManager = credentialManager,
                                                    onAuthSuccess = {
                                                        isGoogleLoading = false
                                                        onAuthSuccess()
                                                    },
                                                    onAuthError = { msg ->
                                                        isGoogleLoading = false
                                                        formError = msg
                                                    },
                                                    scope = scope,
                                                    onAuthCancelled = { isGoogleLoading = false }
                                                )
                                            }
                                        )
                                    }

                                    else -> {
                                        // TAB DAFTAR
                                        val emailValid = regEmail.isBlank() || isValidEmailFormat(regEmail.trim())
                                        val passStrength = computePasswordStrength(regPassword)
                                        val confirmMatch = regConfirmPassword.isBlank() || regConfirmPassword == regPassword

                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            FieldLabel("Email", titleColor)
                                            RoundedAuthTextField(
                                                value = regEmail,
                                                onValueChange = {
                                                    regEmail = it
                                                    formError = null
                                                    emailAlreadyInUse = false
                                                },
                                                placeholder = "nama@perusahaan.com",
                                                leadingIcon = Icons.Outlined.Email,
                                                borderColor = fieldBorderColor,
                                                textColor = fieldTextColor,
                                                mutedColor = mutedColor,
                                                keyboardType = KeyboardType.Email,
                                                imeAction = ImeAction.Next,
                                                modifier = Modifier.testTag("register_email_input")
                                            )
                                            if (!emailValid) {
                                                Text("Format email tidak valid.", fontSize = 11.sp, color = ErrorCrimson)
                                            }
                                        }

                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            FieldLabel("Password", titleColor)
                                            RoundedAuthTextField(
                                                value = regPassword,
                                                onValueChange = {
                                                    regPassword = it
                                                    formError = null
                                                },
                                                placeholder = "Minimal 8 karakter",
                                                leadingIcon = Icons.Outlined.Lock,
                                                borderColor = fieldBorderColor,
                                                textColor = fieldTextColor,
                                                mutedColor = mutedColor,
                                                isPassword = true,
                                                passwordVisible = regPasswordVisible,
                                                onTogglePasswordVisible = { regPasswordVisible = !regPasswordVisible },
                                                imeAction = ImeAction.Next,
                                                modifier = Modifier.testTag("register_password_input")
                                            )
                                            if (regPassword.isNotEmpty()) {
                                                PasswordStrengthBar(strength = passStrength)
                                            }
                                        }

                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            FieldLabel("Konfirmasi password", titleColor)
                                            RoundedAuthTextField(
                                                value = regConfirmPassword,
                                                onValueChange = {
                                                    regConfirmPassword = it
                                                    formError = null
                                                },
                                                placeholder = "Ulangi password",
                                                leadingIcon = Icons.Outlined.Lock,
                                                borderColor = fieldBorderColor,
                                                textColor = fieldTextColor,
                                                mutedColor = mutedColor,
                                                isPassword = true,
                                                passwordVisible = regConfirmVisible,
                                                onTogglePasswordVisible = { regConfirmVisible = !regConfirmVisible },
                                                imeAction = ImeAction.Done,
                                                onDone = { focusManager.clearFocus() },
                                                modifier = Modifier.testTag("register_confirm_input")
                                            )
                                            if (!confirmMatch) {
                                                Text("Konfirmasi password tidak cocok.", fontSize = 11.sp, color = ErrorCrimson)
                                            }
                                        }

                                        PrimaryArrowButton(
                                            text = "Daftar",
                                            isLoading = isFormLoading,
                                            enabled = !isFormLoading && !isGoogleLoading,
                                            onClick = {
                                                if (isFormLoading || isGoogleLoading) return@PrimaryArrowButton
                                                focusManager.clearFocus()
                                                formError = null
                                                emailAlreadyInUse = false
                                                val emailTrim = regEmail.trim()
                                                val validationError = validateRegistrationInput(
                                                    email = emailTrim,
                                                    password = regPassword,
                                                    confirmPassword = regConfirmPassword
                                                )
                                                if (validationError != null) {
                                                    val alreadyShownInline =
                                                        (!emailValid && validationError == "Format email tidak valid.") ||
                                                        (!confirmMatch && validationError == "Konfirmasi password tidak cocok.")
                                                    formError = if (alreadyShownInline) null else validationError
                                                    return@PrimaryArrowButton
                                                }
                                                isFormLoading = true
                                                scope.launch {
                                                    try {
                                                        if (onEmailSignUp != null) {
                                                            onEmailSignUp.invoke(emailTrim, regPassword)
                                                        } else {
                                                            Firebase.auth.createUserWithEmailAndPassword(emailTrim, regPassword).await()
                                                        }
                                                        if (rememberMe) {
                                                            authPrefs.edit()
                                                                .putString("remembered_email", emailTrim)
                                                                .remove("remembered_identifier")
                                                                .apply()
                                                        }
                                                        onAuthSuccess()
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        val feedback = mapFirebaseAuthError(e, isRegister = true)
                                                        formError = feedback.message
                                                        emailAlreadyInUse = feedback.isEmailAlreadyInUse
                                                    } finally {
                                                        isFormLoading = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.testTag("register_submit_button")
                                        )

                                        if (formError != null) {
                                            Text(
                                                text = formError!!,
                                                fontSize = 12.sp,
                                                color = ErrorCrimson,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("auth_form_error")
                                            )
                                            if (emailAlreadyInUse) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    TextButton(
                                                        onClick = {
                                                            loginEmail = regEmail.trim()
                                                            formError = null
                                                            emailAlreadyInUse = false
                                                            selectedTab = 0
                                                        },
                                                        modifier = Modifier.testTag("collision_to_login_button")
                                                    ) {
                                                        Text("Ke tab Masuk", color = AuthBlue, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                                    }
                                                    TextButton(
                                                        onClick = {
                                                            forgotInput = regEmail.trim()
                                                            forgotStatus = null
                                                            formError = null
                                                            emailAlreadyInUse = false
                                                            showForgotPassword = true
                                                        },
                                                        modifier = Modifier.testTag("collision_to_forgot_button")
                                                    ) {
                                                        Text("Lupa password?", color = AuthBlue, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                                    }
                                                }
                                            }
                                        }

                                        AuthDivider(label = "atau masuk dengan", borderColor = fieldBorderColor, textColor = mutedColor)

                                        GoogleOutlineButton(
                                            isLoading = isGoogleLoading,
                                            enabled = !isGoogleLoading && !isFormLoading,
                                            borderColor = fieldBorderColor,
                                            textColor = titleColor,
                                            onClick = {
                                                if (isGoogleLoading || isFormLoading) return@GoogleOutlineButton
                                                formError = null
                                                emailAlreadyInUse = false
                                                isGoogleLoading = true
                                                onGoogleSignInClicked(
                                                    context = context,
                                                    credentialManager = credentialManager,
                                                    onAuthSuccess = {
                                                        isGoogleLoading = false
                                                        onAuthSuccess()
                                                    },
                                                    onAuthError = { msg ->
                                                        isGoogleLoading = false
                                                        formError = msg
                                                    },
                                                    scope = scope,
                                                    onAuthCancelled = { isGoogleLoading = false }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(if (isCompactHeight) 14.dp else 18.dp))

                    // Bottom prompt: Belum punya akun? Daftar sekarang
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedTab == 0) "Belum punya akun? " else "Sudah punya akun? ",
                            fontSize = 13.5.sp,
                            color = titleColor
                        )
                        Text(
                            text = if (selectedTab == 0) "Daftar sekarang" else "Masuk sekarang",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuthBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !isFormLoading && !isGoogleLoading) {
                                    showForgotPassword = false
                                    formError = null
                                    emailAlreadyInUse = false
                                    selectedTab = if (selectedTab == 0) 1 else 0
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .testTag("switch_auth_mode_link")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Shield icon + "Data Anda aman bersama kami"
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VerifiedUser,
                            contentDescription = "Keamanan terjamin",
                            tint = mutedColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Data Anda aman bersama kami",
                            fontSize = 12.sp,
                            color = mutedColor
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SoftBlueWavesBackground(darkTheme: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val alphaScale = if (darkTheme) 0.16f else 1f

        // Top-left large flowing blue wave
        val topLeftOuter = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.44f, 0f)
            cubicTo(w * 0.34f, h * 0.10f, w * 0.16f, h * 0.16f, 0f, h * 0.24f)
            close()
        }
        drawPath(
            path = topLeftOuter,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF3B82F6).copy(alpha = 0.34f * alphaScale),
                    Color(0xFF93C5FD).copy(alpha = 0.14f * alphaScale)
                ),
                start = Offset(0f, 0f),
                end = Offset(w * 0.44f, h * 0.24f)
            )
        )
        val topLeftInner = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.28f, 0f)
            cubicTo(w * 0.22f, h * 0.08f, w * 0.10f, h * 0.12f, 0f, h * 0.17f)
            close()
        }
        drawPath(
            path = topLeftInner,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF2563EB).copy(alpha = 0.42f * alphaScale),
                    Color(0xFF60A5FA).copy(alpha = 0.22f * alphaScale)
                ),
                start = Offset(0f, 0f),
                end = Offset(w * 0.28f, h * 0.17f)
            )
        )

        // Right-middle subtle translucent wave
        val rightMid = Path().apply {
            moveTo(w, h * 0.32f)
            cubicTo(w * 0.72f, h * 0.38f, w * 0.68f, h * 0.56f, w, h * 0.68f)
            close()
        }
        drawPath(
            path = rightMid,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF93C5FD).copy(alpha = 0.20f * alphaScale),
                    Color(0xFF60A5FA).copy(alpha = 0.08f * alphaScale)
                ),
                start = Offset(w * 0.72f, h * 0.32f),
                end = Offset(w, h * 0.68f)
            )
        )

        // Bottom-left soft wave
        val bottomLeft = Path().apply {
            moveTo(0f, h * 0.78f)
            cubicTo(w * 0.16f, h * 0.84f, w * 0.20f, h * 0.94f, w * 0.08f, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = bottomLeft,
            color = Color(0xFF93C5FD).copy(alpha = 0.16f * alphaScale)
        )

        // Bottom-right flowing blue wave
        val bottomRightOuter = Path().apply {
            moveTo(w, h * 0.84f)
            cubicTo(w * 0.80f, h * 0.88f, w * 0.72f, h * 0.95f, w * 0.64f, h)
            lineTo(w, h)
            close()
        }
        drawPath(
            path = bottomRightOuter,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF60A5FA).copy(alpha = 0.28f * alphaScale),
                    Color(0xFF3B82F6).copy(alpha = 0.42f * alphaScale)
                ),
                start = Offset(w * 0.64f, h),
                end = Offset(w, h * 0.84f)
            )
        )
        val bottomRightInner = Path().apply {
            moveTo(w, h * 0.90f)
            cubicTo(w * 0.86f, h * 0.93f, w * 0.82f, h * 0.97f, w * 0.76f, h)
            lineTo(w, h)
            close()
        }
        drawPath(
            path = bottomRightInner,
            color = Color(0xFF2563EB).copy(alpha = 0.36f * alphaScale)
        )
    }
}

@Composable
private fun AuthHeroIllustration(
    height: Dp,
    darkTheme: Boolean,
    bgColor: Color
) {
    val scale = (height / 158.dp).coerceIn(0.65f, 1.0f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.TopCenter
    ) {
        // Thin concentric arcs + 5 silhouettes meeting around table + presentation screen
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val h = size.height
            val arcColor = if (darkTheme) Color(0xFF334155) else Color(0xFFCBD9F5)

            // Outer & inner thin circular guide arcs behind the hero
            drawArc(
                color = arcColor.copy(alpha = 0.65f),
                startAngle = 195f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(cx - h * 0.92f, h * 0.08f),
                size = Size(h * 1.84f, h * 1.65f),
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawArc(
                color = arcColor.copy(alpha = 0.45f),
                startAngle = 195f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(cx - h * 0.64f, h * 0.24f),
                size = Size(h * 1.28f, h * 1.25f),
                style = Stroke(width = 1.dp.toPx())
            )

            // Presentation board behind center table
            val screenW = h * 0.68f
            val screenH = h * 0.34f
            val screenLeft = cx - screenW / 2f
            val screenTop = h * 0.48f
            drawRoundRect(
                color = if (darkTheme) Color(0xFF1E293B) else Color(0xFFE6EEFF),
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            drawRoundRect(
                color = if (darkTheme) Color(0xFF334155) else Color(0xFFC5D7FA),
                topLeft = Offset(screenLeft, screenTop),
                size = Size(screenW, screenH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
            // Mini chart bars on left of presentation board
            val chartColor = if (darkTheme) Color(0xFF60A5FA).copy(alpha = 0.55f) else Color(0xFF8CB0F5)
            val barW = screenW * 0.07f
            val barGap = screenW * 0.04f
            val barBase = screenTop + screenH * 0.78f
            listOf(0.28f, 0.46f, 0.36f).forEachIndexed { idx, frac ->
                val bx = screenLeft + screenW * 0.14f + idx * (barW + barGap)
                val bh = screenH * frac
                drawRoundRect(
                    color = chartColor,
                    topLeft = Offset(bx, barBase - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
            // Mini text lines on right of presentation board
            listOf(0.26f, 0.46f, 0.66f).forEach { yFrac ->
                drawLine(
                    color = chartColor.copy(alpha = 0.7f),
                    start = Offset(screenLeft + screenW * 0.54f, screenTop + screenH * yFrac),
                    end = Offset(screenLeft + screenW * 0.86f, screenTop + screenH * yFrac),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 5 Meeting Participant Silhouettes (soft bluish-grey)
            val silBack = if (darkTheme) Color(0xFF475569).copy(alpha = 0.65f) else Color(0xFF9DB8E8).copy(alpha = 0.72f)
            val silMid = if (darkTheme) Color(0xFF64748B).copy(alpha = 0.78f) else Color(0xFF83A4DF).copy(alpha = 0.85f)
            val silFront = if (darkTheme) Color(0xFF94A3B8).copy(alpha = 0.85f) else Color(0xFF6C92D6).copy(alpha = 0.90f)

            fun drawPersonSilhouette(centerX: Float, baseY: Float, s: Float, tint: Color) {
                // Head
                drawCircle(
                    color = tint,
                    radius = 7.5.dp.toPx() * s,
                    center = Offset(centerX, baseY - 24.dp.toPx() * s)
                )
                // Shoulders / torso arc
                val torso = Path().apply {
                    moveTo(centerX - 15.dp.toPx() * s, baseY)
                    cubicTo(
                        centerX - 14.dp.toPx() * s, baseY - 15.dp.toPx() * s,
                        centerX + 14.dp.toPx() * s, baseY - 15.dp.toPx() * s,
                        centerX + 15.dp.toPx() * s, baseY
                    )
                    close()
                }
                drawPath(torso, color = tint)
            }

            val baseLine = h * 0.92f
            drawPersonSilhouette(cx, baseLine - 6.dp.toPx() * scale, 0.85f * scale, silBack)
            drawPersonSilhouette(cx - 42.dp.toPx() * scale, baseLine - 2.dp.toPx() * scale, 0.94f * scale, silMid)
            drawPersonSilhouette(cx + 42.dp.toPx() * scale, baseLine - 2.dp.toPx() * scale, 0.94f * scale, silMid)
            drawPersonSilhouette(cx - 72.dp.toPx() * scale, baseLine + 3.dp.toPx() * scale, 1.02f * scale, silFront)
            drawPersonSilhouette(cx + 72.dp.toPx() * scale, baseLine + 3.dp.toPx() * scale, 1.02f * scale, silFront)

            // Curved conference table + laptop silhouette
            val tablePath = Path().apply {
                moveTo(cx - 88.dp.toPx() * scale, h * 0.96f)
                cubicTo(
                    cx - 45.dp.toPx() * scale, h * 0.84f,
                    cx + 45.dp.toPx() * scale, h * 0.84f,
                    cx + 88.dp.toPx() * scale, h * 0.96f
                )
                lineTo(cx + 96.dp.toPx() * scale, h)
                lineTo(cx - 96.dp.toPx() * scale, h)
                close()
            }
            drawPath(
                path = tablePath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        if (darkTheme) Color(0xFF334155) else Color(0xFFA7C1EE),
                        bgColor
                    ),
                    startY = h * 0.84f,
                    endY = h
                )
            )
            // Smooth bottom fade into background
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, bgColor.copy(alpha = 0.88f), bgColor),
                    startY = h * 0.82f,
                    endY = h
                ),
                topLeft = Offset(0f, h * 0.82f),
                size = Size(size.width, h * 0.18f)
            )
        }

        // 4 Floating Mini White Cards
        val cardSize = (38 * scale).dp
        val cardRadius = (11 * scale).dp
        val floatingCardBg = if (darkTheme) Color(0xFF1E293B) else Color.White

        // Top-Left: Document icon
        FloatingFeatureCard(
            bgColor = floatingCardBg,
            size = cardSize,
            radius = cardRadius,
            modifier = Modifier.offset(x = (-92 * scale).dp, y = (18 * scale).dp)
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = AuthBlueTop, modifier = Modifier.size((19 * scale).dp))
        }
        // Top-Right: Bar chart icon
        FloatingFeatureCard(
            bgColor = floatingCardBg,
            size = cardSize,
            radius = cardRadius,
            modifier = Modifier.offset(x = (92 * scale).dp, y = (18 * scale).dp)
        ) {
            Icon(Icons.Rounded.BarChart, contentDescription = null, tint = AuthBlueBottom, modifier = Modifier.size((19 * scale).dp))
        }
        // Bottom-Left: Chat bubble icon
        FloatingFeatureCard(
            bgColor = floatingCardBg,
            size = (34 * scale).dp,
            radius = (10 * scale).dp,
            modifier = Modifier.offset(x = (-108 * scale).dp, y = (70 * scale).dp)
        ) {
            Icon(Icons.Rounded.ChatBubble, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size((16 * scale).dp))
        }
        // Bottom-Right: Green check badge
        FloatingFeatureCard(
            bgColor = if (darkTheme) Color(0xFF064E3B) else Color(0xFFE6FBF2),
            size = (34 * scale).dp,
            radius = (10 * scale).dp,
            modifier = Modifier.offset(x = (108 * scale).dp, y = (66 * scale).dp)
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MintGreen, modifier = Modifier.size((18 * scale).dp))
        }

        // Center Gradient Blue App Icon with White Microphone + Soundwave bars
        val iconBoxSize = (70 * scale).dp
        Box(
            modifier = Modifier
                .offset(y = (4 * scale).dp)
                .size(iconBoxSize)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape((22 * scale).dp), ambientColor = AuthBlue, spotColor = AuthBlue)
                .clip(RoundedCornerShape((22 * scale).dp))
                .background(Brush.verticalGradient(listOf(AuthBlueTop, AuthBlueBottom))),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size((44 * scale).dp)) {
                val w = size.width
                val h = size.height
                val strokeW = 2.6.dp.toPx() * scale

                // Microphone capsule
                val capW = w * 0.28f
                val capH = h * 0.44f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset((w - capW) / 2f, h * 0.12f),
                    size = Size(capW, capH),
                    cornerRadius = CornerRadius(capW / 2f, capW / 2f)
                )
                // U-cradle arc around mic
                drawArc(
                    color = Color.White,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.27f, h * 0.26f),
                    size = Size(w * 0.46f, h * 0.42f),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
                // Mic vertical stem + base
                drawLine(
                    color = Color.White,
                    start = Offset(w / 2f, h * 0.68f),
                    end = Offset(w / 2f, h * 0.84f),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.34f, h * 0.85f),
                    end = Offset(w * 0.66f, h * 0.85f),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                // Soundwave bars left & right
                val barStroke = 2.2.dp.toPx() * scale
                val leftBars = listOf(0.05f to 0.16f, 0.12f to 0.28f, 0.19f to 0.18f)
                leftBars.forEach { (xFrac, halfH) ->
                    drawLine(
                        color = Color.White.copy(alpha = 0.92f),
                        start = Offset(w * xFrac, h * (0.46f - halfH)),
                        end = Offset(w * xFrac, h * (0.46f + halfH)),
                        strokeWidth = barStroke,
                        cap = StrokeCap.Round
                    )
                    val rx = 1f - xFrac
                    drawLine(
                        color = Color.White.copy(alpha = 0.92f),
                        start = Offset(w * rx, h * (0.46f - halfH)),
                        end = Offset(w * rx, h * (0.46f + halfH)),
                        strokeWidth = barStroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingFeatureCard(
    bgColor: Color,
    size: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(radius),
        color = bgColor,
        shadowElevation = 5.dp,
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.7f)),
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun AuthCardTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    cardBg: Color,
    inactiveBg: Color,
    mutedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) AuthBlue else mutedColor
    Column(
        modifier = modifier
            .background(if (selected) cardBg else inactiveBg)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = contentColor
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(if (selected) AuthBlue else Color.Transparent)
        )
    }
}

@Composable
private fun FieldLabel(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

@Composable
private fun RoundedAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    borderColor: Color,
    textColor: Color,
    mutedColor: Color,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisible: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = mutedColor.copy(alpha = 0.85f), fontSize = 14.sp) },
        leadingIcon = {
            Icon(leadingIcon, contentDescription = null, tint = mutedColor, modifier = Modifier.size(20.dp))
        },
        trailingIcon = if (isPassword && onTogglePasswordVisible != null) {
            {
                IconButton(onClick = onTogglePasswordVisible) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = if (passwordVisible) "Sembunyikan password" else "Tampilkan password",
                        tint = mutedColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = textColor,
            unfocusedTextColor = textColor,
            focusedBorderColor = AuthBlue,
            unfocusedBorderColor = borderColor,
            cursorColor = AuthBlue
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun PrimaryArrowButton(
    text: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AuthBlue,
            contentColor = Color.White
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Memproses...", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun AuthDivider(
    label: String,
    borderColor: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = borderColor)
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = borderColor)
    }
}

@Composable
private fun GoogleOutlineButton(
    isLoading: Boolean,
    enabled: Boolean,
    borderColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("google_sign_in_button")
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AuthBlue, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Menghubungkan Google...", color = textColor, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        } else {
            GoogleColorLogo(modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Lanjut dengan Google",
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GoogleColorLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.21f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        // Red top
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = -145f,
            sweepAngle = 105f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Yellow left
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 145f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Green bottom
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 40f,
            sweepAngle = 105f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Blue right arc + crossbar
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -5f,
            sweepAngle = 50f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.50f, size.height * 0.50f),
            end = Offset(size.width * 0.94f, size.height * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt
        )
    }
}

@Composable
private fun PasswordStrengthBar(strength: Int) {
    val (label, color) = when {
        strength <= 1 -> "Lemah" to ErrorCrimson
        strength == 2 -> "Sedang" to Color(0xFFF59E0B)
        else -> "Kuat" to Color(0xFF10B981)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        (1..3).forEach { lvl ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (strength >= lvl) color else BorderLight)
            )
        }
        Text(text = label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private fun computePasswordStrength(password: String): Int {
    if (password.length < 8) return 1
    var score = 1
    if (password.any { it.isDigit() } && password.any { it.isLetter() }) score++
    if (password.any { !it.isLetterOrDigit() } || (password.any { it.isUpperCase() } && password.length >= 10)) score++
    return score.coerceIn(1, 3)
}
