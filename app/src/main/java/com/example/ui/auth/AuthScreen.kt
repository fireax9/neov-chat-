package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.foundation.clickable
import com.example.ui.cordly.AppLanguageManager
import com.example.ui.cordly.AppThemeMode
import com.example.ui.cordly.LocalAppStrings
import com.example.ui.cordly.LocalOnLanguageSelected
import com.example.ui.cordly.LocalSelectedLanguageCode
import com.example.ui.cordly.WorldLanguagePickerDialog
import com.example.ui.theme.LocalAppThemeMode
import com.example.ui.theme.LocalOnAppThemeModeSelected
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.ui.theme.CordlyAmber
import com.example.ui.theme.CordlyBlurple
import com.example.ui.theme.CordlyCanvasBg
import com.example.ui.theme.CordlyCardSurface
import com.example.ui.theme.CordlyCrimson
import com.example.ui.theme.CordlyCyan
import com.example.ui.theme.CordlyEmerald
import com.example.ui.theme.CordlyRailBg
import com.example.ui.theme.CordlySurfaceDark
import com.example.ui.theme.CordlyTextPrimary
import com.example.ui.theme.CordlyTextSecondary
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun isRecoverableCredentialError(error: Throwable): Boolean {
    if (error is NoCredentialException ||
        error is GetCredentialProviderConfigurationException ||
        error is GetCredentialUnsupportedException
    ) {
        return true
    }
    val msg = (error.message ?: "").lowercase()
    return msg.contains("10:") ||
        msg.contains("16:") ||
        msg.contains("28444") ||
        msg.contains("developer") ||
        msg.contains("matching credential") ||
        msg.contains("no credentials") ||
        msg.contains("not configured") ||
        msg.contains("reauth") ||
        msg.contains("provider")
}

private suspend fun signInWithCredentialResult(
    credential: androidx.credentials.Credential
): Boolean {
    if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        Firebase.auth.signInWithCredential(authCredential).await()
        return Firebase.auth.currentUser != null
    }
    return false
}

@Suppress("DEPRECATION")
private fun getRuntimeSigningSha1Hex(context: Context): String? = try {
    val pm = context.packageManager
    val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()?.toByteArray()
    } else {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull()?.toByteArray()
    } ?: return null
    MessageDigest.getInstance("SHA-1")
        .digest(sigBytes)
        .joinToString(":") { "%02X".format(it) }
} catch (_: Throwable) {
    null
}

private fun formatAuthErrorMessage(
    context: Context,
    error: Throwable,
    certHashMismatchMessage: String
): String {
    val raw = error.message ?: error.localizedMessage ?: ""
    val lower = raw.lowercase()
    return if (lower.contains("certificate hash") ||
        lower.contains("invalid_cert_hash") ||
        lower.contains("17064")
    ) {
        val runtimeSha1 = getRuntimeSigningSha1Hex(context) ?: "unavailable"
        Log.e(
            "Auth",
            "Firebase Auth rejected APK certificate hash for ${context.packageName} (runtime SHA-1=$runtimeSha1)",
            error
        )
        "$certHashMismatchMessage\nSHA-1: $runtimeSha1"
    } else {
        error.localizedMessage ?: "Google sign-in failed. Please try again."
    }
}

suspend fun signInWithGoogleBrowser(
    context: Context,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    certHashMismatchMessage: String = "This app build's signing certificate (SHA-1) is not yet authorized in Firebase Console for Google Sign-In."
) {
    val activity = context.findActivity()
    if (activity == null) {
        onAuthError("Unable to open Google sign-in window.")
        return
    }
    try {
        val pendingTask = Firebase.auth.pendingAuthResult
        if (pendingTask != null) {
            pendingTask.await()
            if (Firebase.auth.currentUser != null) {
                onAuthSuccess()
                return
            }
        }
        val provider = OAuthProvider.newBuilder("google.com")
            .addCustomParameter("prompt", "select_account")
            .build()
        Firebase.auth.startActivityForSignInWithProvider(activity, provider).await()
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
        } else {
            onAuthError("Google sign-in did not return an active session. Please try again.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("Auth", "Browser Google Sign-In failed", e)
        onAuthError(formatAuthErrorMessage(context, e, certHashMismatchMessage))
    }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
): Job {
    return scope.launch {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return@launch
        }
        try {
            val pending = Firebase.auth.pendingAuthResult
            if (pending != null) {
                pending.await()
                if (Firebase.auth.currentUser != null) {
                    onAuthSuccess()
                    return@launch
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("Auth", "Pending OAuth result unavailable: ${e.message}")
        }

        val activity = context.findActivity()
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            onUnauthenticated()
            return@launch
        }
        if (activity == null || clientId.isBlank()) {
            onUnauthenticated()
            return@launch
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        try {
            val result = credentialManager.getCredential(activity, request)
            if (signInWithCredentialResult(result.credential)) {
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("Auth", "Silent auto sign-in unavailable: ${e.message}")
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {},
    certHashMismatchMessage: String = "This app build's signing certificate (SHA-1) is not yet authorized in Firebase Console for Google Sign-In."
): Job {
    return scope.launch {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return@launch
        }
        val activity = context.findActivity()
        if (activity == null) {
            onAuthError("Unable to find host screen for Google Sign-In.")
            return@launch
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            ""
        }
        if (clientId.isBlank()) {
            signInWithGoogleBrowser(activity, onAuthSuccess, onAuthError, certHashMismatchMessage)
            return@launch
        }

        try {
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()
            val result = credentialManager.getCredential(activity, request)
            if (signInWithCredentialResult(result.credential)) {
                onAuthSuccess()
            } else {
                signInWithGoogleBrowser(activity, onAuthSuccess, onAuthError, certHashMismatchMessage)
            }
        } catch (e: CancellationException) {
            if (e is GetCredentialCancellationException) {
                if (Firebase.auth.currentUser != null) {
                    onAuthSuccess()
                    return@launch
                }
                Log.w("Auth", "Google Sign-In cancelled or interrupted: ${e.message}", e)
                val msg = (e.message ?: "").lowercase()
                if (msg.contains("developer") || msg.contains("10:") || msg.contains("28444") || msg.contains("config")) {
                    signInWithGoogleBrowser(activity, onAuthSuccess, onAuthError, certHashMismatchMessage)
                } else {
                    onAuthCancelled()
                }
            } else {
                throw e
            }
        } catch (e: GetCredentialException) {
            Log.w("Auth", "Credential Manager primary sign-in failed, trying fallback: ${e.message}", e)
            if (Firebase.auth.currentUser != null) {
                onAuthSuccess()
                return@launch
            }
            if (isRecoverableCredentialError(e)) {
                try {
                    val fallbackOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .setAutoSelectEnabled(false)
                        .build()
                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(fallbackOption)
                        .build()
                    val fallbackResult = credentialManager.getCredential(activity, fallbackRequest)
                    if (signInWithCredentialResult(fallbackResult.credential)) {
                        onAuthSuccess()
                        return@launch
                    }
                } catch (fallbackErr: CancellationException) {
                    if (fallbackErr is GetCredentialCancellationException) {
                        if (Firebase.auth.currentUser != null) {
                            onAuthSuccess()
                            return@launch
                        }
                        onAuthCancelled()
                        return@launch
                    } else {
                        throw fallbackErr
                    }
                } catch (fallbackErr: Exception) {
                    Log.w("Auth", "GetGoogleIdOption fallback failed, switching to browser OAuth: ${fallbackErr.message}")
                }
                signInWithGoogleBrowser(activity, onAuthSuccess, onAuthError, certHashMismatchMessage)
            } else {
                onAuthError(formatAuthErrorMessage(context, e, certHashMismatchMessage))
            }
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            if (Firebase.auth.currentUser != null) {
                onAuthSuccess()
            } else if (isRecoverableCredentialError(e)) {
                signInWithGoogleBrowser(activity, onAuthSuccess, onAuthError, certHashMismatchMessage)
            } else {
                onAuthError(formatAuthErrorMessage(context, e, certHashMismatchMessage))
            }
        }
    }
}

fun signOut(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("Auth", "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val strings = LocalAppStrings.current
    val currentLanguageCode = LocalSelectedLanguageCode.current
    val onLanguageSelected = LocalOnLanguageSelected.current
    val currentThemeMode = LocalAppThemeMode.current
    val onThemeModeSelected = LocalOnAppThemeModeSelected.current

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var autoSignInJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        delay(2350)
        if (!isLoading) {
            autoSignInJob = attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = onAuthSuccess,
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(CordlyRailBg, CordlyCanvasBg, CordlySurfaceDark)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    val isNight = currentThemeMode == AppThemeMode.NIGHT
                    Surface(
                        color = CordlyCardSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CordlyBlurple.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onThemeModeSelected(if (isNight) AppThemeMode.DAY else AppThemeMode.NIGHT)
                            }
                            .testTag("auth_theme_toggle_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = if (isNight) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                                contentDescription = if (isNight) strings.nightModeLabel else strings.dayModeLabel,
                                tint = if (isNight) CordlyCyan else CordlyAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isNight) strings.nightModeLabel else strings.dayModeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = CordlyTextPrimary
                            )
                        }
                    }

                    Surface(
                        color = CordlyCardSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CordlyCyan.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showLanguagePicker = true }
                            .testTag("auth_language_picker_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Language,
                                contentDescription = strings.tabLanguages,
                                tint = CordlyCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = AppLanguageManager.displayLabel(currentLanguageCode),
                                style = MaterialTheme.typography.labelSmall,
                                color = CordlyTextPrimary
                            )
                        }
                    }
                }

                // Hero Artwork Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(26.dp))
                ) {
                    AsyncImage(
                        model = R.drawable.img_cordly_hero,
                        contentDescription = "neov chat community atmosphere",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.88f)
                                    )
                                )
                            )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Surface(
                            color = Color(0xFF5865F2),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                            modifier = Modifier.size(44.dp)
                        ) {
                            AsyncImage(
                                model = R.drawable.neov_icon_fg,
                                contentDescription = "neov chat logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(
                            color = CordlyBlurple.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(CordlyEmerald)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = strings.welcomeBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = CordlyTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.tagline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CordlyTextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(22.dp))

                // Feature Highlights
                FeatureRow(
                    icon = Icons.Rounded.Forum,
                    tint = CordlyCyan,
                    title = strings.featureDmTitle,
                    subtitle = strings.featureDmSubtitle
                )
                Spacer(modifier = Modifier.height(10.dp))
                FeatureRow(
                    icon = Icons.Rounded.Groups,
                    tint = CordlyBlurple,
                    title = strings.featureServerTitle,
                    subtitle = strings.featureServerSubtitle
                )
                Spacer(modifier = Modifier.height(10.dp))
                FeatureRow(
                    icon = Icons.Rounded.Lock,
                    tint = CordlyEmerald,
                    title = strings.featureSecureTitle,
                    subtitle = strings.featureSecureSubtitle
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn()
                ) {
                    Surface(
                        color = CordlyCrimson.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CordlyCrimson.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        autoSignInJob?.cancel()
                        errorMessage = null
                        isLoading = true
                        onGoogleSignInClicked(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                isLoading = false
                                onAuthSuccess()
                            },
                            onAuthError = { msg ->
                                isLoading = false
                                errorMessage = msg
                            },
                            scope = scope,
                            onAuthCancelled = {
                                isLoading = false
                                errorMessage = strings.signInCancelledHint
                            },
                            certHashMismatchMessage = strings.signInCertHashMismatch
                        )
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CordlyBlurple,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("google_sign_in_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = strings.signingIn,
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Login,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = strings.signInWithGoogle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        autoSignInJob?.cancel()
                        errorMessage = null
                        isLoading = true
                        scope.launch {
                            signInWithGoogleBrowser(
                                context = context,
                                onAuthSuccess = {
                                    isLoading = false
                                    onAuthSuccess()
                                },
                                onAuthError = { msg ->
                                    isLoading = false
                                    errorMessage = msg
                                },
                                certHashMismatchMessage = strings.signInCertHashMismatch
                            )
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, CordlyCyan.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("google_browser_sign_in_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        tint = CordlyCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.signInViaBrowser,
                        style = MaterialTheme.typography.labelLarge,
                        color = CordlyTextPrimary
                    )
                }
            }
        }
    }

    if (showLanguagePicker) {
        WorldLanguagePickerDialog(
            selectedCode = currentLanguageCode,
            onSelectLanguage = { code ->
                onLanguageSelected(code)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false }
        )
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String
) {
    Surface(
        color = CordlyCardSurface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Surface(
                color = tint.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CordlyTextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CordlyTextSecondary
                )
            }
        }
    }
}
