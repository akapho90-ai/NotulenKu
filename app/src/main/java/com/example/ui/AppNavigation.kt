package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.R
import com.example.core.AppConstants
import com.example.data.auth.resolveGoogleWebClientId
import com.example.data.ai.AiService
import com.example.data.ai.ConnectionVaultStore
import com.example.data.auth.authStateFlow
import com.example.data.auth.signOutUser
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.data.preferences.UserPrefsDataStore
import com.example.data.repository.MeetingRepository
import com.example.data.repository.PersonalMeetingRepository
import com.example.data.repository.TenantRepository
import com.example.ui.components.TenantSelectorChip
import com.example.ui.components.TenantSwitcherSheet
import com.example.ui.screens.AdminHubScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MeetingDetailScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.QaScreen
import com.example.ui.screens.RecordScreen
import com.example.ui.screens.SetupCheckItem
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandIndigo
import com.example.ui.viewmodel.MeetingViewModel
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WorkspaceSnapshot
import com.example.ui.viewmodel.WorkspaceViewModel
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch
import java.io.File

private object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val RECORD_PATTERN = "record?mode={mode}"
    fun record(mode: Int = 0) = "record?mode=$mode"
    const val QA = "qa"
    const val PROFILE = "profile"
    const val MEETING_DETAIL_PATTERN = "meeting/{meetingId}?tab={tab}"
    fun meetingDetail(meetingId: String, tab: Int = 0) = "meeting/$meetingId?tab=$tab"
    const val ADMIN_PATTERN = "admin?tab={tab}"
    fun admin(tab: Int = 0) = "admin?tab=$tab"
}

private data class NavTabItem(
    val route: String,
    val navTarget: String,
    val label: String,
    val icon: ImageVector,
    val isCenterRecord: Boolean = false
)

@Composable
fun NotulenAppRoot(
    prefs: UserPrefsDataStore
) {
    val currentUser by Firebase.auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    if (currentUser != null) {
        key(currentUser!!.uid) {
            AuthenticatedShell(
                userId = currentUser!!.uid,
                fallbackDisplayName = currentUser!!.displayName
                    ?: currentUser!!.email?.substringBefore("@")
                    ?: "Pengguna",
                prefs = prefs
            )
        }
    } else {
        AuthScreen(onAuthSuccess = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedShell(
    userId: String,
    fallbackDisplayName: String,
    prefs: UserPrefsDataStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val vaultStore = remember { ConnectionVaultStore(context.applicationContext) }
    val sharedAiService = remember(vaultStore) { AiService(vaultStore) }

    val workspaceVm: WorkspaceViewModel = viewModel(
        key = "ws_$userId",
        factory = viewModelFactory {
            initializer {
                val appCtx = context.applicationContext
                WorkspaceViewModel(
                    tenantRepo = TenantRepository(appCtx),
                    aiService = sharedAiService,
                    prefs = prefs,
                    currentUserId = userId,
                    vaultStore = vaultStore
                )
            }
        }
    )

    val meetingVm: MeetingViewModel = viewModel(
        key = "mtg_$userId",
        factory = viewModelFactory {
            initializer {
                val appCtx = context.applicationContext
                MeetingViewModel(
                    personalRepo = PersonalMeetingRepository(appCtx),
                    meetingRepo = MeetingRepository(appCtx),
                    tenantRepo = TenantRepository(appCtx),
                    aiService = sharedAiService,
                    currentUserId = userId,
                    ownerDisplayNameProvider = {
                        Firebase.auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: fallbackDisplayName
                    },
                    appContext = appCtx
                )
            }
        }
    )

    DisposableEffect(userId) {
        onDispose {
            meetingVm.clearSessionState()
        }
    }

    LaunchedEffect(userId) {
        meetingVm.refreshMeetings(showLoading = false)
    }

    val workspaceState by workspaceVm.workspaceState.collectAsStateWithLifecycle()
    val snapshot = (workspaceState as? UiState.Success<WorkspaceSnapshot>)?.data
    val activeTenant = snapshot?.activeTenant

    LaunchedEffect(activeTenant?.id, activeTenant?.name, activeTenant?.aiMode, activeTenant?.watermarkEnabled) {
        meetingVm.setActiveTenant(activeTenant)
    }

    val meetingsState by meetingVm.meetingsState.collectAsStateWithLifecycle()
    val detailState by meetingVm.detailState.collectAsStateWithLifecycle()
    val processingState by meetingVm.processingState.collectAsStateWithLifecycle()
    val pendingLocalJobs by meetingVm.pendingLocalJobs.collectAsStateWithLifecycle()
    val periodicSummaryState by meetingVm.periodicSummaryState.collectAsStateWithLifecycle()
    val accountLinkRequired by meetingVm.accountLinkRequired.collectAsStateWithLifecycle()
    val linkAccountBusy by meetingVm.linkAccountBusy.collectAsStateWithLifecycle()
    val qaMessages by meetingVm.qaMessages.collectAsStateWithLifecycle()
    val qaLoading by meetingVm.qaLoading.collectAsStateWithLifecycle()
    val aiTaskBusy by meetingVm.aiTaskBusy.collectAsStateWithLifecycle()
    val correctionItems by meetingVm.correctionItems.collectAsStateWithLifecycle()

    val connections by workspaceVm.connections.collectAsStateWithLifecycle()
    val members by workspaceVm.members.collectAsStateWithLifecycle()
    val invites by workspaceVm.invites.collectAsStateWithLifecycle()
    val taskConfigs by workspaceVm.taskConfigs.collectAsStateWithLifecycle()
    val modelPrices by workspaceVm.modelPrices.collectAsStateWithLifecycle()
    val usageLogs by workspaceVm.usageLogs.collectAsStateWithLifecycle()
    val auditLogs by workspaceVm.auditLogs.collectAsStateWithLifecycle()
    val connectionHealthy by workspaceVm.connectionHealthy.collectAsStateWithLifecycle()
    val playgroundResults by workspaceVm.playgroundResults.collectAsStateWithLifecycle()
    val playgroundLoading by workspaceVm.playgroundLoading.collectAsStateWithLifecycle()

    val themeMode by workspaceVm.themeMode.collectAsStateWithLifecycle()
    val defaultTranscriptLang by workspaceVm.defaultTranscriptLang.collectAsStateWithLifecycle()
    val defaultExportFormat by workspaceVm.defaultExportFormat.collectAsStateWithLifecycle()
    val defaultVisualStyle by workspaceVm.defaultVisualStyle.collectAsStateWithLifecycle()
    val defaultHistoryView by workspaceVm.defaultHistoryView.collectAsStateWithLifecycle()
    val reduceMotion by workspaceVm.reduceMotion.collectAsStateWithLifecycle()
    val customAvatarPath by workspaceVm.customAvatarPath.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val workspaceMsg by workspaceVm.actionMessage.collectAsStateWithLifecycle()
    val meetingMsg by meetingVm.uiMessage.collectAsStateWithLifecycle()

    LaunchedEffect(workspaceMsg) {
        val msg = workspaceMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        workspaceVm.clearActionMessage()
    }
    LaunchedEffect(meetingMsg) {
        val msg = meetingMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        meetingVm.clearUiMessage()
    }

    var showTenantSheet by remember { mutableStateOf(false) }
    var showCreateTenantDialog by remember { mutableStateOf(false) }
    var newTenantName by remember { mutableStateOf("") }
    var showLinkAccountDialog by remember { mutableStateOf(false) }
    var linkEmail by remember(snapshot?.profile?.email) {
        mutableStateOf(snapshot?.profile?.email ?: Firebase.auth.currentUser?.email.orEmpty())
    }
    var linkPassword by remember { mutableStateOf("") }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME

    val topLevelTabs = remember {
        listOf(
            NavTabItem(Routes.HOME, Routes.HOME, "Beranda", Icons.Rounded.Home),
            NavTabItem(Routes.HISTORY, Routes.HISTORY, "Riwayat", Icons.Rounded.History),
            NavTabItem(Routes.RECORD_PATTERN, Routes.record(0), "Rekam", Icons.Rounded.Mic, isCenterRecord = true),
            NavTabItem(Routes.QA, Routes.QA, "Tanya", Icons.Rounded.AutoAwesome),
            NavTabItem(Routes.PROFILE, Routes.PROFILE, "Profil", Icons.Rounded.Person)
        )
    }

    val isTopLevelScreen = currentRoute == Routes.HOME ||
        currentRoute == Routes.HISTORY ||
        currentRoute.startsWith("record") ||
        currentRoute == Routes.QA ||
        currentRoute == Routes.PROFILE

    fun navigateTopTab(target: String) {
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val effectivePhoto = remember(customAvatarPath, snapshot?.profile?.photoUrl) {
        customAvatarPath?.takeIf { File(it).exists() } ?: snapshot?.profile?.photoUrl
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets.navigationBars,
            topBar = {
                if (isTopLevelScreen) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        shadowElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = AppConstants.APP_NAME,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = AppConstants.APP_SUBTITLE,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TenantSelectorChip(
                                tenantName = activeTenant?.name ?: "Memuat...",
                                onClick = { showTenantSheet = true }
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (isTopLevelScreen && !useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        topLevelTabs.forEach { tab ->
                            val selected = if (tab.isCenterRecord) {
                                currentRoute.startsWith("record")
                            } else {
                                currentRoute == tab.route
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopTab(tab.navTarget) },
                                modifier = Modifier.testTag("nav_tab_${tab.label.lowercase()}"),
                                icon = {
                                    if (tab.isCenterRecord) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.linearGradient(listOf(BrandBlue, BrandIndigo))
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label,
                                                tint = Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    } else {
                                        Icon(imageVector = tab.icon, contentDescription = tab.label)
                                    }
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isTopLevelScreen && useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        topLevelTabs.forEach { tab ->
                            val selected = if (tab.isCenterRecord) {
                                currentRoute.startsWith("record")
                            } else {
                                currentRoute == tab.route
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopTab(tab.navTarget) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                        if (snapshot?.isAdminOrOwner == true) {
                            NavigationRailItem(
                                selected = currentRoute.startsWith("admin"),
                                onClick = { navController.navigate(Routes.admin(0)) },
                                icon = {
                                    Icon(
                                        Icons.Rounded.AdminPanelSettings,
                                        contentDescription = "Admin"
                                    )
                                },
                                label = { Text("Admin") }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME
                    ) {
                        composable(Routes.HOME) {
                            DashboardScreen(
                                displayName = snapshot?.profile?.displayName ?: fallbackDisplayName,
                                photoUrl = effectivePhoto,
                                activeTenant = activeTenant,
                                isAdminOrOwner = snapshot?.isAdminOrOwner == true,
                                connectionHealthy = connectionHealthy,
                                connections = connections,
                                usageLogs = usageLogs,
                                modelPrices = modelPrices,
                                auditLogs = auditLogs,
                                reduceMotion = reduceMotion,
                                meetingsState = meetingsState,
                                accountLinkRequired = accountLinkRequired,
                                onRetryLoad = { meetingVm.refreshMeetings(showLoading = true) },
                                onOpenAccountLink = { showLinkAccountDialog = true },
                                onRecordClick = { navigateTopTab(Routes.record(0)) },
                                onUploadClick = { navigateTopTab(Routes.record(1)) },
                                onAskAiClick = { navigateTopTab(Routes.QA) },
                                onVisualClick = { meetingId ->
                                    navController.navigate(Routes.meetingDetail(meetingId, tab = 3))
                                },
                                onMeetingClick = { meetingId ->
                                    navController.navigate(Routes.meetingDetail(meetingId, tab = 0))
                                },
                                onSeeAllMeetings = { navigateTopTab(Routes.HISTORY) },
                                onToggleActionItem = { meeting, itemId ->
                                    meetingVm.toggleActionItem(meeting, itemId)
                                },
                                onRetryMeeting = { meeting ->
                                    meetingVm.retryMeeting(activeTenant, meeting, taskConfigs, modelPrices)
                                },
                                onOpenAdminSettings = { tab ->
                                    if (snapshot?.isAdminOrOwner == true) {
                                        navController.navigate(Routes.admin(tab))
                                    }
                                },
                                onCheckAiHealth = { workspaceVm.checkAiHealth() },
                                periodicSummaryState = periodicSummaryState,
                                onGeneratePeriodicSummary = { periodType, userMeetings ->
                                    meetingVm.generatePeriodicSummary(
                                        periodType = periodType,
                                        tenant = activeTenant,
                                        allMeetings = userMeetings,
                                        taskConfigs = taskConfigs,
                                        prices = modelPrices
                                    )
                                },
                                onClearPeriodicSummary = { meetingVm.clearPeriodicSummary() }
                            )
                        }

                        composable(Routes.HISTORY) {
                            HistoryScreen(
                                meetingsState = meetingsState,
                                isAdminOrOwner = snapshot?.isAdminOrOwner == true,
                                currentUserId = userId,
                                defaultViewMode = defaultHistoryView,
                                accountLinkRequired = accountLinkRequired,
                                onRetryLoad = { meetingVm.refreshMeetings(showLoading = true) },
                                onOpenAccountLink = { showLinkAccountDialog = true },
                                onMeetingClick = { meetingId ->
                                    navController.navigate(Routes.meetingDetail(meetingId, tab = 0))
                                },
                                onRenameMeeting = { meeting, newTitle ->
                                    meetingVm.renameMeeting(meeting.id, newTitle)
                                },
                                onDeleteMeeting = { meeting ->
                                    meetingVm.deleteMeeting(activeTenant, meeting)
                                },
                                onToggleFavorite = { meeting ->
                                    meetingVm.toggleFavorite(meeting)
                                },
                                onUpdateTags = { meeting, tags ->
                                    meetingVm.updateTags(meeting, tags)
                                },
                                onBulkToggleFavorite = { list, fav ->
                                    meetingVm.bulkToggleFavorite(list, fav)
                                },
                                onBulkAddTag = { list, tag ->
                                    meetingVm.bulkAddTag(list, tag)
                                },
                                onBulkDelete = { list ->
                                    meetingVm.bulkDeleteMeetings(activeTenant, list)
                                },
                                onNotify = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                },
                                onRecordFirst = { navigateTopTab(Routes.record(0)) }
                            )
                        }

                        composable(
                            route = Routes.RECORD_PATTERN,
                            arguments = listOf(
                                navArgument("mode") {
                                    type = NavType.IntType
                                    defaultValue = 0
                                }
                            )
                        ) { entry ->
                            val mode = entry.arguments?.getInt("mode") ?: 0
                            RecordScreen(
                                activeTenant = activeTenant,
                                initialMode = mode,
                                processingState = processingState,
                                onProcessAudio = { judul, file, mimeType, durationSec ->
                                    meetingVm.processAudioMeeting(
                                        tenant = activeTenant,
                                        judul = judul,
                                        audioFile = file,
                                        mimeType = mimeType,
                                        durasiDetik = durationSec,
                                        taskConfigs = taskConfigs,
                                        prices = modelPrices,
                                        preferredLanguage = defaultTranscriptLang
                                    )
                                },
                                onRetrySave = { meetingVm.retrySaveMeeting() },
                                onOpenAccountLink = { showLinkAccountDialog = true },
                                onOpenMeetingDetail = { meetingId ->
                                    meetingVm.dismissProcessingBanner()
                                    navController.navigate(Routes.meetingDetail(meetingId, tab = 0))
                                },
                                onDismissProcessing = { meetingVm.dismissProcessingBanner() },
                                currentUserId = userId,
                                pendingLocalJobs = pendingLocalJobs,
                                onRetryProcessing = {
                                    meetingVm.retryActiveProcessing(activeTenant, taskConfigs, modelPrices)
                                },
                                onResumeLocalJob = { job ->
                                    meetingVm.resumeLocalJob(job, activeTenant, taskConfigs, modelPrices)
                                },
                                onDiscardLocalJob = { mId -> meetingVm.discardLocalJob(mId) }
                            )
                        }

                        composable(Routes.QA) {
                            val readyMeetings = (meetingsState as? UiState.Success)?.data.orEmpty()
                            QaScreen(
                                meetings = readyMeetings,
                                messages = qaMessages,
                                isLoading = qaLoading,
                                onAskQuestion = { q, scopeMode, selectedMtgId ->
                                    meetingVm.askQuestion(
                                        tenant = activeTenant,
                                        question = q,
                                        scope = scopeMode,
                                        selectedMeetingId = selectedMtgId,
                                        allMeetings = readyMeetings,
                                        taskConfigs = taskConfigs,
                                        prices = modelPrices
                                    )
                                },
                                onOpenMeeting = { meetingId ->
                                    navController.navigate(Routes.meetingDetail(meetingId, tab = 0))
                                },
                                onClearHistory = {
                                    meetingVm.clearQaHistory(activeTenant)
                                }
                            )
                        }

                        composable(Routes.PROFILE) {
                            val allMeetings = (meetingsState as? UiState.Success)?.data.orEmpty()
                            ProfileScreen(
                                profile = snapshot?.profile,
                                activeTenant = activeTenant,
                                roleInTenant = snapshot?.roleInActiveTenant ?: "member",
                                isAdminOrOwner = snapshot?.isAdminOrOwner == true,
                                isPlatformAdmin = snapshot?.isPlatformAdmin == true,
                                customAvatarPath = customAvatarPath,
                                meetings = allMeetings,
                                usageLogs = usageLogs,
                                themeMode = themeMode,
                                defaultTranscriptLang = defaultTranscriptLang,
                                defaultExportFormat = defaultExportFormat,
                                defaultVisualStyle = defaultVisualStyle,
                                defaultHistoryView = defaultHistoryView,
                                reduceMotion = reduceMotion,
                                onUpdateDisplayName = { workspaceVm.updateDisplayName(it) },
                                onUpdateUsername = { workspaceVm.updateUsername(it) },
                                onUpdateAvatarPath = { workspaceVm.setCustomAvatarPath(it) },
                                onOpenTenantSwitcher = { showTenantSheet = true },
                                onCreateOrganization = { workspaceVm.createOrganization(it) },
                                onThemeModeChange = { workspaceVm.setThemeMode(it) },
                                onDefaultTranscriptLangChange = { workspaceVm.setDefaultTranscriptLang(it) },
                                onDefaultExportFormatChange = { workspaceVm.setDefaultExportFormat(it) },
                                onDefaultVisualStyleChange = { workspaceVm.setDefaultVisualStyle(it) },
                                onDefaultHistoryViewChange = { workspaceVm.setDefaultHistoryView(it) },
                                onReduceMotionChange = { workspaceVm.setReduceMotion(it) },
                                onOpenAdminHub = { tab ->
                                    if (snapshot?.isAdminOrOwner == true) {
                                        navController.navigate(Routes.admin(tab))
                                    }
                                },
                                onDeleteAccount = {
                                    workspaceVm.deleteAccountProfile {
                                        signOutUser(credentialManager, scope)
                                    }
                                },
                                onSignOut = {
                                    signOutUser(credentialManager, scope)
                                }
                            )
                        }

                        composable(
                            route = Routes.MEETING_DETAIL_PATTERN,
                            arguments = listOf(
                                navArgument("meetingId") { type = NavType.StringType },
                                navArgument("tab") {
                                    type = NavType.IntType
                                    defaultValue = 0
                                }
                            )
                        ) { entry ->
                            val meetingId = entry.arguments?.getString("meetingId").orEmpty()
                            val detailTab = entry.arguments?.getInt("tab") ?: 0
                            LaunchedEffect(meetingId) {
                                meetingVm.loadMeetingDetail(meetingId)
                            }
                            val cachedMeeting = (meetingsState as? UiState.Success)?.data?.firstOrNull { it.id == meetingId }
                            val resolvedDetailState = detailState
                            val meeting = (resolvedDetailState as? UiState.Success)?.data?.takeIf { it.id == meetingId }
                                ?: cachedMeeting
                            MeetingDetailScreen(
                                meeting = meeting,
                                detailState = resolvedDetailState,
                                activeTenant = activeTenant,
                                aiBusy = aiTaskBusy,
                                corrections = correctionItems,
                                initialTab = detailTab,
                                defaultVisualStyle = defaultVisualStyle,
                                onRetryLoad = { meetingVm.loadMeetingDetail(meetingId, forceSpinner = true) },
                                onBack = { navController.popBackStack() },
                                onRenameMeeting = { newTitle ->
                                    meetingVm.renameMeeting(meetingId, newTitle)
                                },
                                onRenameSpeaker = { oldName, newName ->
                                    meeting?.let { meetingVm.renameSpeaker(it, oldName, newName) }
                                },
                                onEditSegment = { idx, newText ->
                                    meeting?.let { meetingVm.editSegmentText(it, idx, newText) }
                                },
                                onToggleActionItem = { itemId ->
                                    meeting?.let { meetingVm.toggleActionItem(it, itemId) }
                                },
                                onSaveMinutesManual = { ringkasan, poin, keputusan, risiko ->
                                    meeting?.let { meetingVm.updateSummaryFields(it, ringkasan, poin, keputusan, risiko) }
                                },
                                onRegenerateSummary = {
                                    meeting?.let { meetingVm.regenerateSummary(activeTenant, it, taskConfigs, modelPrices) }
                                },
                                onRunAnalysis = {
                                    meeting?.let { meetingVm.runMeetingAnalysis(activeTenant, it, taskConfigs, modelPrices) }
                                },
                                onRunTranscriptCorrection = {
                                    meeting?.let { meetingVm.runTranscriptCorrection(activeTenant, it, taskConfigs, modelPrices) }
                                },
                                onAcceptCorrection = { item ->
                                    meeting?.let { meetingVm.acceptCorrection(it, item) }
                                },
                                onRejectCorrection = { item -> meetingVm.rejectCorrection(item) },
                                onGenerateVisual = {
                                    meeting?.let { meetingVm.generateVisualData(activeTenant, it, taskConfigs, modelPrices) }
                                },
                                onNotify = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }

                        composable(
                            route = Routes.ADMIN_PATTERN,
                            arguments = listOf(
                                navArgument("tab") {
                                    type = NavType.IntType
                                    defaultValue = 0
                                }
                            )
                        ) { entry ->
                            val adminTab = entry.arguments?.getInt("tab") ?: 0
                            if (snapshot?.isAdminOrOwner == true) {
                                val hasWebClientId = remember {
                                    resolveGoogleWebClientId(context) != null
                                }
                                val dbId = remember {
                                    runCatching { context.getString(R.string.firestore_database_id) }.getOrDefault("")
                                }
                                val liveSetupChecks = remember(
                                    activeTenant?.id,
                                    activeTenant?.name,
                                    taskConfigs.size,
                                    modelPrices.size,
                                    connections,
                                    connectionHealthy
                                ) {
                                    val aiReady = connections.count { it.kind == "ai" && it.enabled }
                                    val sttReady = connections.count { it.kind == "stt" && it.enabled }
                                    listOf(
                                        SetupCheckItem(
                                            title = "Firebase Auth (Google Sign-In)",
                                            passed = Firebase.auth.currentUser != null && hasWebClientId,
                                            detail = if (Firebase.auth.currentUser != null) {
                                                "Sesi Google Sign-In aktif dan Client ID terkonfigurasi."
                                            } else {
                                                "Client ID atau sesi Google Sign-In belum aktif."
                                            }
                                        ),
                                        SetupCheckItem(
                                            title = "Cloud Firestore Multi-Tenant",
                                            passed = activeTenant != null && dbId.isNotBlank(),
                                            detail = if (activeTenant != null) {
                                                "Database ($dbId) terhubung ke workspace ${activeTenant.name}."
                                            } else {
                                                "Workspace aktif belum dimuat."
                                            }
                                        ),
                                        SetupCheckItem(
                                            title = "Firebase App Check",
                                            passed = FirebaseApp.getApps(context).isNotEmpty(),
                                            detail = "Proteksi App Check diinisialisasi saat aplikasi dimulai."
                                        ),
                                        SetupCheckItem(
                                            title = "Koneksi AI & STT Aktif",
                                            passed = aiReady > 0 && sttReady > 0,
                                            detail = "$aiReady koneksi AI aktif, $sttReady koneksi STT aktif (brankas lokal Keystore)."
                                        ),
                                        SetupCheckItem(
                                            title = "Routing Tugas AI & Tarif Model",
                                            passed = taskConfigs.size >= AppConstants.AI_TASKS.size,
                                            detail = "${taskConfigs.size}/${AppConstants.AI_TASKS.size} tugas aktif, ${modelPrices.size} tarif model tersimpan."
                                        ),
                                        SetupCheckItem(
                                            title = "Layanan SumoPod AI (Backend Supabase)",
                                            passed = connectionHealthy != false,
                                            detail = when (connectionHealthy) {
                                                true -> "Backend meeting-api Supabase & SumoPod AI siap digunakan."
                                                false -> "Pemeriksaan backend SumoPod AI gagal; pastikan endpoint processAi/checkAiHealth sudah dideploy & SUMOPOD_KEY aktif."
                                                null -> "Belum diuji pada sesi ini (tarik Beranda atau ketuk lencana AI)."
                                            }
                                        )
                                    )
                                }
                                AdminHubScreen(
                                    tenant = activeTenant,
                                    isOwner = snapshot.isOwner,
                                    isPlatformAdmin = snapshot.isPlatformAdmin,
                                    initialTab = adminTab,
                                    setupChecks = liveSetupChecks,
                                    connections = connections,
                                    members = members,
                                    invites = invites,
                                    taskConfigs = taskConfigs,
                                    modelPrices = modelPrices,
                                    usageLogs = usageLogs,
                                    auditLogs = auditLogs,
                                    playgroundResults = playgroundResults,
                                    playgroundLoading = playgroundLoading,
                                    onBack = { navController.popBackStack() },
                                    onUpsertConnection = { conn, key -> workspaceVm.upsertConnection(conn, key) },
                                    onToggleConnection = { cid -> workspaceVm.toggleConnection(cid) },
                                    onDeleteConnection = { cid -> workspaceVm.deleteConnection(cid) },
                                    onTestConnection = { conn, key, cb -> workspaceVm.testCustomConnection(conn, key, cb) },
                                    onFetchModels = { conn, key, cb -> workspaceVm.fetchModelsForConnection(conn, key, cb) },
                                    onSaveTaskConfig = { cfg ->
                                        activeTenant?.let { workspaceVm.saveTaskConfig(it, cfg) }
                                    },
                                    onApplyConfigToAllTasks = { cfg ->
                                        activeTenant?.let { workspaceVm.applyConfigToAllTasks(it, cfg) }
                                    },
                                    onTestTaskConfig = { cfg ->
                                        activeTenant?.let { workspaceVm.testTaskConfig(it, cfg) }
                                    },
                                    onRunPlayground = { prompt, sample, cA, mA, cB, mB, temp ->
                                        activeTenant?.let {
                                            workspaceVm.runPlaygroundComparison(it, prompt, sample, cA, mA, cB, mB, temp)
                                        }
                                    },
                                    onSaveModelPrice = { price ->
                                        activeTenant?.let { workspaceVm.saveModelPrice(it, price) }
                                    },
                                    onSaveTenantSettings = { name, aiMode, watermark, maxMb, retDays, budget ->
                                        activeTenant?.let {
                                            workspaceVm.updateTenantSettings(
                                                tenant = it,
                                                name = name,
                                                aiMode = aiMode,
                                                watermarkEnabled = watermark,
                                                maxFileMb = maxMb,
                                                retentionDays = retDays,
                                                monthlyBudgetUsd = budget,
                                                isOwner = snapshot.isOwner
                                            )
                                        }
                                    },
                                    onCreateInvite = { email, role ->
                                        activeTenant?.let { workspaceVm.createInvite(it, email, role) }
                                    },
                                    onRevokeInvite = { inviteId ->
                                        activeTenant?.let { workspaceVm.revokeInvite(it, inviteId) }
                                    },
                                    onUpdateMemberRole = { member, role ->
                                        activeTenant?.let { workspaceVm.updateMemberRole(it, member, role) }
                                    },
                                    onDeleteTenant = {
                                        activeTenant?.let { tenant ->
                                            workspaceVm.deleteTenant(tenant) {
                                                navController.popBackStack()
                                            }
                                        }
                                    }
                                )
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Akses terbatas untuk Admin atau Owner organisasi.")
                                    TextButton(onClick = { navController.popBackStack() }) {
                                        Text("Kembali")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTenantSheet) {
        TenantSwitcherSheet(
            tenants = snapshot?.tenants.orEmpty(),
            activeTenantId = activeTenant?.id,
            onSelect = { selected ->
                workspaceVm.selectTenant(selected.id)
                showTenantSheet = false
            },
            onCreateNew = {
                showTenantSheet = false
                showCreateTenantDialog = true
            },
            onDismiss = { showTenantSheet = false }
        )
    }

    if (showCreateTenantDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTenantDialog = false },
            title = { Text("Buat Organisasi Baru") },
            text = {
                OutlinedTextField(
                    value = newTenantName,
                    onValueChange = { newTenantName = it },
                    label = { Text("Nama organisasi") },
                    placeholder = { Text("Misalnya: Tim Produk Nusantara") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newTenantName.isNotBlank()) {
                        workspaceVm.createOrganization(newTenantName)
                        newTenantName = ""
                        showCreateTenantDialog = false
                    }
                }) { Text("Buat") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTenantDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showLinkAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!linkAccountBusy) showLinkAccountDialog = false },
            title = { Text("Tautkan Akun Supabase") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Masukkan kata sandi akun Supabase Anda untuk memverifikasi kepemilikan sebelum menautkan dengan akun Firebase ini.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = linkEmail,
                        onValueChange = { linkEmail = it },
                        label = { Text("Email Supabase") },
                        singleLine = true,
                        enabled = !linkAccountBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = linkPassword,
                        onValueChange = { linkPassword = it },
                        label = { Text("Kata sandi Supabase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !linkAccountBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !linkAccountBusy && linkEmail.isNotBlank() && linkPassword.isNotBlank(),
                    onClick = {
                        meetingVm.linkSupabaseAccount(linkEmail, linkPassword) {
                            linkPassword = ""
                            showLinkAccountDialog = false
                        }
                    }
                ) {
                    Text(if (linkAccountBusy) "Memverifikasi..." else "Verifikasi & Tautkan")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !linkAccountBusy,
                    onClick = { showLinkAccountDialog = false }
                ) { Text("Batal") }
            }
        )
    }
}
