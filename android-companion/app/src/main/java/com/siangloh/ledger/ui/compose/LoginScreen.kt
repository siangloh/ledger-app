package com.siangloh.ledger.ui.compose

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.siangloh.ledger.R
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.AuthManager
import com.siangloh.ledger.data.BiometricHelper
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (username: String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val lastUsername = remember { AuthManager.getLastUsername(context) }
    var username by remember {
        mutableStateOf(NetworkHelper.getUsername(context).ifBlank { lastUsername })
    }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(NetworkHelper.getServerUrl(context)) }
    var showServerSettings by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val canUseBiometric = remember { AuthManager.canUseBiometricLogin(context) }
    val fragmentActivity = remember(context) { BiometricHelper.findFragmentActivity(context) }

    fun triggerBiometricLogin() {
        val targetUser = username.ifBlank { lastUsername }
        val act = fragmentActivity ?: return
        BiometricHelper.authenticate(
            activity = act,
            title = context.getString(R.string.login_biometric_title),
            subtitle = if (targetUser.isNotBlank()) context.getString(R.string.login_biometric_sub_user_fmt, targetUser) else context.getString(R.string.login_biometric_sub_quick),
            negativeButtonText = context.getString(R.string.login_biometric_pwd_fallback),
            onSuccess = {
                val result = AuthManager.loginWithBiometric(context, targetUser)
                result.onSuccess { auth ->
                    Toast.makeText(context, context.getString(R.string.login_success_toast), Toast.LENGTH_SHORT).show()
                    onLoginSuccess(auth.username)
                }.onFailure { err ->
                    errorMessage = err.message ?: context.getString(R.string.login_biometric_failed)
                }
            },
            onError = { _, _ ->
                // User pressed negative button or canceled; stay on normal login screen
            }
        )
    }

    var hasAutoPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(canUseBiometric, fragmentActivity) {
        if (canUseBiometric && fragmentActivity != null && !hasAutoPrompted) {
            hasAutoPrompted = true
            triggerBiometricLogin()
        }
    }

    fun handleLogin() {
        focusManager.clearFocus()
        if (username.isBlank()) {
            errorMessage = context.getString(R.string.login_empty_username)
            return
        }
        if (password.isBlank()) {
            errorMessage = context.getString(R.string.login_empty_password)
            return
        }

        errorMessage = null
        isLoading = true

        coroutineScope.launch {
            val result = AuthManager.login(
                context = context,
                username = username,
                password = password,
                serverUrl = serverUrl.takeIf { it.isNotBlank() }
            )

            isLoading = false
            result.onSuccess { auth ->
                Toast.makeText(context, context.getString(R.string.login_success_toast), Toast.LENGTH_SHORT).show()
                onLoginSuccess(auth.username)
            }.onFailure { err ->
                errorMessage = err.message ?: context.getString(R.string.login_invalid_credentials)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Teal50,
                        Color(0xFFF8FAFC),
                        Color.White
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Logo & Brand Header
            Surface(
                shape = CircleShape,
                color = PrimaryTeal,
                shadowElevation = 8.dp,
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = "Ledger App Logo",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.login_welcome_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.login_welcome_sub),
                fontSize = 13.sp,
                color = Slate600
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Main Login Card
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 3.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Username Field
                    Text(
                        text = stringResource(R.string.login_username_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate700
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            errorMessage = null
                        },
                        placeholder = { Text(stringResource(R.string.login_username_placeholder), color = Slate400, fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = stringResource(R.string.login_username_label),
                                tint = if (username.isNotBlank()) PrimaryTeal else Slate400
                            )
                        },
                        trailingIcon = {
                            if (username.isNotEmpty()) {
                                IconButton(onClick = { username = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.common_cancel),
                                        tint = Slate400,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = Slate200,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Slate50
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Password Field
                    Text(
                        text = stringResource(R.string.login_password_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate700
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        placeholder = { Text(stringResource(R.string.login_password_placeholder), color = Slate400, fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = stringResource(R.string.login_password_label),
                                tint = if (password.isNotBlank()) PrimaryTeal else Slate400
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = Slate400
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { handleLogin() }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = Slate200,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Slate50
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )


                    // Error Message Banner
                    AnimatedVisibility(visible = errorMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFEF2F2),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECACA)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = ExpenseRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = ExpenseRed,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Login Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { handleLogin() },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryTeal,
                                disabledContainerColor = Slate300
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.login_logging_in),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.login_btn),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (canUseBiometric) {
                            Spacer(modifier = Modifier.width(12.dp))
                            FilledTonalIconButton(
                                onClick = { triggerBiometricLogin() },
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, PrimaryTealSubtle, RoundedCornerShape(14.dp)),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Teal50,
                                    contentColor = PrimaryTeal
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = stringResource(R.string.login_face_fingerprint_desc),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Server Configuration Expander
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(
                            onClick = { showServerSettings = !showServerSettings }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = Slate500,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.login_server_settings),
                                fontSize = 12.sp,
                                color = Slate500
                            )
                        }
                    }

                    AnimatedVisibility(visible = showServerSettings) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .background(Slate50, RoundedCornerShape(12.dp))
                                .border(1.dp, Slate200, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.login_server_url_label),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate700
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = {
                                    serverUrl = it
                                    NetworkHelper.setServerUrl(context, it.trim())
                                },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.login_server_url_hint),
                                        color = Slate400,
                                        fontSize = 12.sp
                                    )
                                },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryTeal,
                                    unfocusedBorderColor = Slate300,
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
