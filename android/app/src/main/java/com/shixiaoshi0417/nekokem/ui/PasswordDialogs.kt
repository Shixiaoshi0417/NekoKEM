package com.shixiaoshi0417.nekokem.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.nekokem.R
import java.security.MessageDigest

@Composable
fun GeneratePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
) {
    var password by remember { mutableStateOf(ByteArray(0)) }
    var confirmation by remember { mutableStateOf(ByteArray(0)) }
    var errorResource by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            password.fill(0)
            confirmation.fill(0)
        }
    }

    AlertDialog(
        onDismissRequest = {
            password.fill(0)
            confirmation.fill(0)
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
            }
        },
        confirmButton = {
            TextButton(
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
                            onConfirm(submitted)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.action_generate))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password.fill(0)
                    confirmation.fill(0)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun SinglePasswordDialog(
    @StringRes titleResource: Int,
    @StringRes messageResource: Int,
    @StringRes labelResource: Int = R.string.password_label,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
) {
    var password by remember { mutableStateOf(ByteArray(0)) }
    var showEmptyError by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { password.fill(0) }
    }

    AlertDialog(
        onDismissRequest = {
            password.fill(0)
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
                    },
                    labelResource = labelResource,
                    isError = showEmptyError,
                )
                if (showEmptyError) {
                    DialogError(R.string.error_password_empty)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (password.isEmpty()) {
                        showEmptyError = true
                    } else {
                        val submitted = password.copyOf()
                        password.fill(0)
                        password = ByteArray(0)
                        onConfirm(submitted)
                    }
                },
            ) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    password.fill(0)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_cancel))
            }
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

@Composable
private fun DialogError(@StringRes resource: Int) {
    Text(
        modifier = Modifier.padding(top = 6.dp),
        text = stringResource(resource),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
