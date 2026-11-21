package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.repository.CordlyRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.cordly.AppAppearanceStore
import com.example.ui.cordly.AppEntranceIntroOverlay
import com.example.ui.cordly.AppLanguageManager
import com.example.ui.cordly.AppThemeMode
import com.example.ui.cordly.CordlyHomeScreen
import com.example.ui.cordly.CordlyViewModel
import com.example.ui.cordly.LocalAppLogoVariant
import com.example.ui.cordly.LocalAppStrings
import com.example.ui.cordly.LocalOnAppLogoVariantSelected
import com.example.ui.cordly.LocalOnLanguageSelected
import com.example.ui.cordly.LocalSelectedLanguageCode
import com.example.ui.theme.CordlyTheme
import com.example.ui.theme.LocalAppAccentColor
import com.example.ui.theme.LocalAppThemeMode
import com.example.ui.theme.LocalOnAppAccentColorSelected
import com.example.ui.theme.LocalOnAppThemeModeSelected
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val view = LocalView.current

            var selectedLanguageCode by remember {
                mutableStateOf(AppAppearanceStore.loadLanguageCode(context))
            }
            var selectedThemeMode by remember {
                mutableStateOf(AppAppearanceStore.loadThemeMode(context))
            }
            var selectedAccentColor by remember {
                mutableStateOf(AppAppearanceStore.loadAccentColor(context))
            }
            var selectedLogoVariant by remember {
                mutableStateOf(AppAppearanceStore.loadLogoVariant(context))
            }

            SideEffect {
                if (!view.isInEditMode) {
                    val window = (view.context as? Activity)?.window
                    if (window != null) {
                        val insetsController = WindowCompat.getInsetsController(window, view)
                        val useLightIcons = selectedThemeMode == AppThemeMode.DAY
                        insetsController.isAppearanceLightStatusBars = useLightIcons
                        insetsController.isAppearanceLightNavigationBars = useLightIcons
                    }
                }
            }

            val appStrings = remember(selectedLanguageCode) {
                AppLanguageManager.stringsFor(selectedLanguageCode)
            }
            val layoutDirection = remember(selectedLanguageCode) {
                AppLanguageManager.layoutDirectionFor(selectedLanguageCode)
            }

            CompositionLocalProvider(
                LocalAppStrings provides appStrings,
                LocalSelectedLanguageCode provides selectedLanguageCode,
                LocalOnLanguageSelected provides { newCode ->
                    selectedLanguageCode = AppAppearanceStore.saveLanguageCode(context, newCode)
                },
                LocalLayoutDirection provides layoutDirection,
                LocalAppThemeMode provides selectedThemeMode,
                LocalOnAppThemeModeSelected provides { newMode ->
                    selectedThemeMode = AppAppearanceStore.saveThemeMode(context, newMode)
                },
                LocalAppAccentColor provides selectedAccentColor,
                LocalOnAppAccentColorSelected provides { newAccent ->
                    selectedAccentColor = AppAppearanceStore.saveAccentColor(context, newAccent)
                },
                LocalAppLogoVariant provides selectedLogoVariant,
                LocalOnAppLogoVariantSelected provides { variant ->
                    selectedLogoVariant = AppAppearanceStore.applyAppLogoVariant(context, variant)
                }
            ) {
                CordlyTheme(
                    themeMode = selectedThemeMode,
                    accentColor = selectedAccentColor
                ) {
                    var showEntranceIntro by rememberSaveable { mutableStateOf(true) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        CordlyAppNavigation()
                        if (showEntranceIntro) {
                            AppEntranceIntroOverlay(
                                onFinished = { showEntranceIntro = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CordlyAppNavigation(auth: FirebaseAuth = Firebase.auth) {
    var currentUser by remember(auth) { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        val tokenListener = FirebaseAuth.IdTokenListener { firebaseAuth ->
            if (firebaseAuth.currentUser != null && currentUser?.uid != firebaseAuth.currentUser?.uid) {
                currentUser = firebaseAuth.currentUser
            }
        }
        auth.addAuthStateListener(authListener)
        auth.addIdTokenListener(tokenListener)
        currentUser = auth.currentUser
        onDispose {
            auth.removeAuthStateListener(authListener)
            auth.removeIdTokenListener(tokenListener)
        }
    }

    LaunchedEffect(auth) {
        val pending = auth.pendingAuthResult
        if (pending != null) {
            try {
                pending.await()
            } catch (_: Exception) {
            }
            currentUser = auth.currentUser
        }
    }

    val user = currentUser

    if (user == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = auth.currentUser ?: Firebase.auth.currentUser
            }
        )
    } else {
        val uid = user.uid
        key(uid) {
            val viewModel: CordlyViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        CordlyViewModel(
                            repository = CordlyRepository(db, auth),
                            currentUserId = uid
                        )
                    }
                }
            )
            CordlyHomeScreen(viewModel = viewModel)
        }
    }
}
