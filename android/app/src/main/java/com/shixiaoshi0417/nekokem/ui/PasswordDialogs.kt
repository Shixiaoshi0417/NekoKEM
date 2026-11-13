package com.shixiaoshi0417.nekokem.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.shixiaoshi0417.nekokem.R
import com.shixiaoshi0417.nekokem.keys.PasswordManagerCredentials
import java.security.MessageDigest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * What a password dialog offers around the system password manager.
 * [credentialId] picks the saved entry to fill (null offers every NekoKEM
 * entry); [offerSave] adds the "save after it succeeds" choice.
 */
data class PasswordManagerOptions(
    val credentialId: String?,
    val canFill: Boolean = true,
    val offerSave: Boolean = false,
)

@Composable
fun GeneratePasswordDialog(
    onDismiss: () -> Unit,
    /** The password, and whether to save it to the password manager once the key exists. */
    onConfirm: (ByteArray, Boolean) -> Unit,
) {
    var password by remember { mutableStateOf(ByteArray(0)) }
    var confirmation by remember { mutableStateOf(ByteArray(0)) }
    var errorResource by remember { mutableStateOf<Int?>(null) }
    var saveToPasswordManager by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            password.fill(0)
            confirmation.fill(0)
        }
    }

    AppAlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = {
            password.fill(0)
            confirmation.fill(0)
            password = ByteArray(0)
            confirmation = ByteArray(0)
            onDismiss()
        },
        title = { Text(stringResource(R.string.generate_password_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.generate_password_message))
                TemporaryPasswordField(
                    value = password,
                    onValueChanged = { updated ->
                        password.fill(0)
                        password = updated
                        errorResource = null
                    },
                    labelResource = R.string.password_label,
                    isError = errorResource != null,
                )
                TemporaryPasswordField(
                    value = confirmation,
                    onValueChanged = { updated ->
                        confirmation.fill(0)
                        confirmation = updated
                        errorResource = null
                    },
                    labelResource = R.string.password_confirmation_label,
                    isError = errorResource != null,
                )
                errorResource?.let { resource ->
                    DialogError(resource)
                }
                if (PasswordManagerCredentials.isSupported) {
                    SaveToPasswordManagerOption(saveToPasswordManager) {
                        saveToPasswordManager = it
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.action_generate),
                onClick = {
                    when {
                        password.isEmpty() -> {
                            errorResource = R.string.error_password_empty
                        }

                        !MessageDigest.isEqual(password, confirmation) -> {
                            errorResource = R.string.error_passwords_mismatch
                        }

                        else -> {
                            val submitted = password.copyOf()
                            password.fill(0)
                            confirmation.fill(0)
                            password = ByteArray(0)
                            confirmation = ByteArray(0)
                            onConfirm(submitted, saveToPasswordManager)
                        }
                    }
                },
            )
        },
        dismissButton = {
            DialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = {
                    password.fill(0)
                    confirmation.fill(0)
                    password = ByteArray(0)
                    confirmation = ByteArray(0)
                    onDismiss()
                },
            )
        },
    )
}

@Composable
fun SinglePasswordDialog(
    @StringRes titleResource: Int,
    @StringRes messageResource: Int,
    @StringRes labelResource: Int = R.string.password_label,
    passwordManager: PasswordManagerOptions? = null,
    onDismiss: () -> Unit,
    /** The password, and whether to save it to the password manager after it is accepted. */
    onConfirm: (ByteArray, Boolean) -> Unit,
) {
    var password by remember { mutableStateOf(ByteArray(0)) }
    var showEmptyError by remember { mutableStateOf(false) }
    var managerMessage by remember { mutableStateOf<Int?>(null) }
    var saveToPasswordManager by remember { mutableStateOf(false) }
    val manager = passwordManager?.takeIf { PasswordManagerCredentials.isSupported }
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { password.fill(0) }
    }

    AppAlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = {
            password.fill(0)
            password = ByteArray(0)
            onDismiss()
        },
        title = { Text(stringResource(titleResource)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(messageResource))
                TemporaryPasswordField(
                    value = password,
                    onValueChanged = { updated ->
                        password.fill(0)
                        password = updated
                        showEmptyError = false
                        managerMessage = null
                    },
                    labelResource = labelResource,
                    isError = showEmptyError,
                )
                if (showEmptyError) {
                    DialogError(R.string.error_password_empty)
                }
                managerMessage?.let { DialogError(it) }
                if (manager != null && manager.canFill && activity != null) {
                    FillFromPasswordManagerButton {
                        scope.launch {
                            when (
                                val result = PasswordManagerCredentials.fill(
                                    activity,
                                    manager.credentialId,
                                )
                            ) {
                                is PasswordManagerCredentials.FillResult.Filled -> {
                                    password.fill(0)
                                    password = result.password
                                    showEmptyError = false
                                    managerMessage = null
                                }
                                PasswordManagerCredentials.FillResult.Cancelled -> Unit
                                PasswordManagerCredentials.FillResult.NotFound ->
                                    managerMessage = R.string.password_manager_not_found
                                PasswordManagerCredentials.FillResult.Unavailable ->
                                    managerMessage = R.string.password_manager_unavailable
                                PasswordManagerCredentials.FillResult.Failed ->
                                    managerMessage = R.string.password_manager_failed
                            }
                        }
                    }
                }
                if (manager != null && manager.offerSave) {
                    SaveToPasswordManagerOption(saveToPasswordManager) {
                        saveToPasswordManager = it
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.action_continue),
                onClick = {
                    if (password.isEmpty()) {
                        showEmptyError = true
                    } else {
                        val submitted = password.copyOf()
                        password.fill(0)
                        password = ByteArray(0)
                        onConfirm(submitted, manager?.offerSave == true && saveToPasswordManager)
                    }
                },
            )
        },
        dismissButton = {
            DialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = {
                    password.fill(0)
                    password = ByteArray(0)
                    onDismiss()
                },
            )
        },
    )
}

@Composable
private fun TemporaryPasswordField(
    value: ByteArray,
    onValueChanged: (ByteArray) -> Unit,
    @StringRes labelResource: Int,
    isError: Boolean,
) {
    PasswordKeyboard {
        if (isMiuixStyle()) {
            // MIUIX fields have no error state; the message below the fields says what is wrong.
            top.yukonga.miuix.kmp.basic.TextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                value = value.decodeToString(),
                onValueChange = { onValueChanged(it.encodeToByteArray()) },
                singleLine = true,
                label = stringResource(labelResource),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
            )
            return@PasswordKeyboard
        }
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            value = value.decodeToString(),
            onValueChange = { onValueChanged(it.encodeToByteArray()) },
            singleLine = true,
            isError = isError,
            label = { Text(stringResource(labelResource)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
        )
    }
}

/**
 * Asks the keyboard to treat the field as a password: no suggestions and no
 * personalized learning, in addition to the password input type. Keyboards
 * decide how they honor these flags.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PasswordKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing {
                nextHandler.startInputMethod(
                    object : PlatformTextInputMethodRequest {
                        override fun createInputConnection(
                            outAttributes: EditorInfo,
                        ): InputConnection {
                            val connection = request.createInputConnection(outAttributes)
                            outAttributes.inputType = outAttributes.inputType or
                                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            outAttributes.imeOptions = outAttributes.imeOptions or
                                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                            return connection
                        }
                    },
                )
            }
        },
        content = content,
    )
}

/** A secondary action under the field that asks the password manager for the password. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FillFromPasswordManagerButton(onClick: () -> Unit) {
    val text = stringResource(R.string.password_manager_fill)
    if (isMiuixStyle()) {
        top.yukonga.miuix.kmp.basic.TextButton(
            text = text,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        return
    }
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(text)
    }
}

/** A check box: save the password to the password manager once it is accepted. */
@Composable
private fun SaveToPasswordManagerOption(checked: Boolean, onChange: (Boolean) -> Unit) {
    val miuix = isMiuixStyle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (miuix) {
            top.yukonga.miuix.kmp.basic.Checkbox(state = ToggleableState(checked), onClick = null)
        } else {
            Checkbox(checked = checked, onCheckedChange = null)
        }
        Text(
            text = stringResource(R.string.password_manager_save),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            color = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DialogError(@StringRes resource: Int) {
    Text(
        modifier = Modifier.padding(top = 6.dp),
        text = stringResource(resource),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
