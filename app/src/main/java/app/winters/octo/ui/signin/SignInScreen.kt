package app.winters.octo.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassInput
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun SignInScreen(vm: SignInViewModel = hiltViewModel()) {
    var reveal by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(OctoColors.Background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Octo", style = OctoType.display, color = OctoColors.TextPrimary)
            Text(
                "Sign in to your music server",
                style = OctoType.bodySmall,
                color = OctoColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(32.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .glassPanel(CardShape)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassInput(
                    value = vm.address,
                    onValueChange = { vm.address = it },
                    placeholder = "Server address",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                GlassInput(
                    value = vm.username,
                    onValueChange = { vm.username = it },
                    placeholder = "Username",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    contentType = ContentType.Username,
                )
                GlassInput(
                    value = vm.password,
                    onValueChange = { vm.password = it },
                    placeholder = "Password",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    contentType = ContentType.Password,
                    trailing = {
                        Text(
                            if (reveal) "Hide" else "Show",
                            style = OctoType.label,
                            color = OctoColors.Accent,
                            modifier = Modifier
                                .clickable { reveal = !reveal }
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        )
                    },
                )
            }

            if (vm.insecure) {
                Text(
                    "This address isn't encrypted. Your password goes as a one-time token, " +
                        "but the rest can be read on the way.",
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            AccentButton(
                text = "Sign in",
                onClick = vm::submit,
                loading = vm.busy,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
            )

            vm.error?.let {
                Text(
                    it,
                    style = OctoType.bodySmall,
                    color = OctoColors.Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
