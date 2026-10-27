package com.example.ownscan

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ownscan.ui.icons.AppIcons
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

// -------------------------------------------------------------
// APP-LEVEL LAUNCH LOCK GATE SCREEN
// -------------------------------------------------------------

@Composable
fun AppLockGateScreen(
    activity: Activity,
    colors: AppColors,
    onUnlocked: () -> Unit
) {
    LaunchedEffect(Unit) {
        if (BiometricAuthManager.isAuthAvailable(activity)) {
            BiometricAuthManager.authenticate(
                activity = activity as FragmentActivity,
                title = "Unlock ScanVault",
                subtitle = "Biometric authentication required to open app",
                onSuccess = onUnlocked,
                onError = {}
            )
        } else {
            onUnlocked()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                painter = AppIcons.LockVector,
                contentDescription = "Locked",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "ScanVault is Locked",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Text(
                text = "Biometric authentication is required to access your documents.",
                color = colors.textMuted,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    BiometricAuthManager.authenticate(
                        activity = activity as FragmentActivity,
                        title = "Unlock ScanVault",
                        subtitle = "Biometric authentication required to open app",
                        onSuccess = onUnlocked,
                        onError = { err ->
                            Toast.makeText(activity, err, Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Unlock with Biometrics", color = Color.White)
            }
        }
    }
}

// -------------------------------------------------------------
// FIRST-TIME LAUNCH ONBOARDING SCREEN
// -------------------------------------------------------------

@Composable
fun OnboardingAuthScreen(
    activity: Activity,
    onFinish: () -> Unit
) {
    val colors = LocalAppColors.current
    var isSigningIn by remember { mutableStateOf(false) }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isSigningIn = false
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    Toast.makeText(activity, "Welcome, ${account.displayName ?: account.email}!", Toast.LENGTH_SHORT).show()
                    onFinish()
                } else {
                    Toast.makeText(activity, "Sign-in failed: Account was null", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                if (e.statusCode != 12501) { // 12501 is user cancelled
                    Toast.makeText(activity, "Sign-in error: ${e.localizedMessage ?: "Code ${e.statusCode}"}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Sign-in error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF0F6E56)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "ScanVault Logo",
                    modifier = Modifier.size(92.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to ScanVault",
                color = colors.textPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Fast, private document scanning. Store documents locally on your device, or seamlessly sync with Google Drive.",
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(44.dp))

            if (isSigningIn) {
                CircularProgressIndicator(color = colors.accent)
                Spacer(modifier = Modifier.height(18.dp))
                Text("Signing in with Google...", color = colors.textMuted, fontSize = 14.sp)
            } else {
                Button(
                    onClick = {
                        isSigningIn = true
                        val client = DriveBackupManager.getGoogleSignInClient(activity)
                        signInLauncher.launch(client.signInIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = AppIcons.BackupDriveVector,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Sign in with Google", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, colors.cardBorder)
                ) {
                    Text("Continue without Sign In", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

