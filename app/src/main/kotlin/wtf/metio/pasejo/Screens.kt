// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import wtf.metio.pasejo.core.FillMatch
import wtf.metio.pasejo.core.StoreView

/** One set-up field: its value and whether the organization's policy fixes it. */
data class SetupField(val value: String, val managed: Boolean = false)

/** What a person enters to set up a device, as the set-up screen hands it on. */
data class SetupValues(val server: String, val org: String, val genesis: String, val checkpoint: String)

/** The approval check on the locked screen; [canUnlock] only for an active device. */
data class LockStatus(val text: String, val canUnlock: Boolean, val tone: Tone = Tone.INFO)

/** What the entry screen shows in place of a value or a code. */
sealed interface Shown {
    data object Hidden : Shown
    data class Value(val text: String) : Shown
    data class Message(val text: String) : Shown
}

/**
 * The command an approver runs to approve this device; the fingerprint is
 * quoted, since it holds spaces.
 */
fun approveCommand(device: String, fingerprint: String, org: String?): String = buildString {
    append("pasejo org approve ").append(device).append(" --fingerprint '").append(fingerprint).append('\'')
    if (org != null) append(" --org ").append(org)
}

/** The names of a store an entry list shows for [query]: each name once, sorted. */
fun entryNames(store: StoreView, query: String): List<String> =
    (store.secrets + store.otp).distinct().sorted().filter { it.contains(query.trim(), ignoreCase = true) }

/** A fill candidate's second line: the user name and the store it comes from. */
fun matchDetail(match: FillMatch): String =
    listOf(match.username, match.storeLabel).filter { it.isNotEmpty() }.joinToString(" · ")

@Composable
fun SetupScreen(
    server: SetupField,
    org: SetupField,
    genesis: SetupField,
    checkpoint: SetupField,
    tier: Tier,
    onContinue: (SetupValues) -> Unit,
) = Page(title = null) {
    var serverValue by rememberSaveable { mutableStateOf(server.value) }
    var orgValue by rememberSaveable { mutableStateOf(org.value) }
    var genesisValue by rememberSaveable { mutableStateOf(genesis.value) }
    var checkpointValue by rememberSaveable { mutableStateOf(checkpoint.value) }
    Hero(stringResource(R.string.setup_title), stringResource(R.string.setup_explanation))
    SetupInput("setup-server", R.string.setup_server, serverValue, server.managed, KeyboardType.Uri) { serverValue = it }
    SetupInput("setup-org", R.string.setup_org, orgValue, org.managed) { orgValue = it }
    SetupInput("setup-genesis", R.string.setup_genesis, genesisValue, genesis.managed) { genesisValue = it }
    SetupInput("setup-checkpoint", R.string.setup_checkpoint, checkpointValue, checkpoint.managed) { checkpointValue = it }
    TierNotice(tier)
    Button(
        onClick = { onContinue(SetupValues(serverValue.trim(), orgValue.trim(), genesisValue.trim(), checkpointValue.trim())) },
        enabled = listOf(serverValue, orgValue, genesisValue).all { it.isNotBlank() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.action_continue)) }
}

@Composable
private fun SetupInput(
    tag: String,
    label: Int,
    value: String,
    managed: Boolean,
    keyboard: KeyboardType = KeyboardType.Ascii,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(stringResource(label)) },
    enabled = !managed,
    singleLine = true,
    trailingIcon = if (managed) ({ Icon(Icons.Filled.Lock, contentDescription = null) }) else null,
    supportingText = if (managed) ({ Text(stringResource(R.string.setup_managed)) }) else null,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
    modifier = Modifier.fillMaxWidth().testTag(tag),
)

@Composable
fun SignInScreen(server: String, onSignIn: () -> Unit, onReset: () -> Unit) = Page(title = null) {
    Hero(stringResource(R.string.sign_in_title), stringResource(R.string.sign_in_explanation, server))
    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_sign_in)) }
    ResetButton(onReset)
}

@Composable
fun LockedScreen(
    fingerprint: String,
    command: String,
    tier: Tier?,
    status: LockStatus?,
    onUnlock: () -> Unit,
    onReset: () -> Unit,
) = Page(title = null) {
    Hero(stringResource(R.string.locked_title))
    if (status == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Notice(stringResource(R.string.locked_checking))
    } else {
        Notice(status.text, status.tone, icon = if (status.tone == Tone.INFO) Icons.Filled.Info else Icons.Filled.Warning)
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.locked_fingerprint), style = MaterialTheme.typography.bodyMedium)
            Grouped(fingerprint, Modifier.testTag("fingerprint"))
            Text(stringResource(R.string.locked_approve), style = MaterialTheme.typography.bodyMedium)
            Text(
                command,
                modifier = Modifier.testTag("approve"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    if (tier != null) TierNotice(tier)
    Button(
        onClick = onUnlock,
        enabled = status?.canUnlock == true,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.action_unlock))
    }
    ResetButton(onReset)
}

/** Where this phone keeps, or will keep, the device keys. */
@Composable
private fun TierNotice(tier: Tier) =
    Notice(stringResource(if (tier == Tier.HARDWARE) R.string.tier_hardware else R.string.tier_sealed), icon = Icons.Filled.Lock)

@Composable
private fun ResetButton(onReset: () -> Unit) = TextButton(
    onClick = onReset,
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    modifier = Modifier.fillMaxWidth(),
) { Text(stringResource(R.string.action_reset)) }

@Composable
fun StoresScreen(
    stores: List<StoreView>,
    offline: Boolean,
    offerAutofill: Boolean,
    unlockChoices: List<Int>,
    unlockSeconds: Int,
    onChooseUnlock: (Int) -> Unit,
    onOpen: (StoreView) -> Unit,
    onAutofill: () -> Unit,
    onLock: () -> Unit,
) = Page(
    title = stringResource(R.string.stores_title),
    actions = {
        IconButton(onClick = onLock) { Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.action_lock)) }
    },
) {
    if (offline) Notice(stringResource(R.string.offline_notice), Tone.WARNING, Icons.Filled.Warning)
    if (stores.isEmpty()) {
        Text(
            stringResource(R.string.stores_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Card(Modifier.fillMaxWidth()) {
            stores.forEachIndexed { index, store ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(store.label) },
                    leadingContent = { Monogram(store.label) },
                    modifier = Modifier.clickable { onOpen(store) },
                )
            }
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.unlock_period))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (seconds in unlockChoices) {
                    FilterChip(
                        selected = seconds == unlockSeconds,
                        onClick = { onChooseUnlock(seconds) },
                        label = { Text(unlockLabel(seconds)) },
                    )
                }
            }
        }
    }
    if (offerAutofill) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.autofill_setup), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onAutofill) { Text(stringResource(R.string.action_autofill)) }
            }
        }
    }
}

@Composable
private fun unlockLabel(seconds: Int): String =
    if (seconds % 60 == 0) stringResource(R.string.unlock_minutes, seconds / 60) else stringResource(R.string.unlock_seconds, seconds)

@Composable
fun EntriesScreen(store: StoreView, onOpen: (String) -> Unit, onBack: () -> Unit) =
    Page(title = store.label, onBack = onBack, scroll = false) {
        var query by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.entries_filter)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("filter"),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(entryNames(store, query)) { name ->
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = { Monogram(name.substringAfterLast('/')) },
                    modifier = Modifier.clickable { onOpen(name) },
                )
            }
        }
    }

@Composable
fun EntryScreen(
    name: String,
    secret: Boolean,
    otp: Boolean,
    allowCopy: Boolean,
    value: Shown,
    code: Shown,
    onReveal: () -> Unit,
    onCopyPassword: () -> Unit,
    onShowCode: () -> Unit,
    onCopyCode: () -> Unit,
    onBack: () -> Unit,
) = Page(title = name, onBack = onBack) {
    if (secret) {
        ValueCard(R.string.entry_secret, value, "value") {
            FilledTonalButton(onClick = onReveal) { Text(stringResource(R.string.action_reveal)) }
            if (allowCopy) OutlinedButton(onClick = onCopyPassword) { Text(stringResource(R.string.action_copy_password)) }
        }
    }
    if (otp) {
        ValueCard(R.string.entry_otp, code, "code") {
            FilledTonalButton(onClick = onShowCode) { Text(stringResource(R.string.action_show_code)) }
            if (allowCopy) OutlinedButton(onClick = onCopyCode) { Text(stringResource(R.string.action_copy_code)) }
        }
    }
}

@Composable
private fun ValueCard(title: Int, shown: Shown, tag: String, buttons: @Composable () -> Unit) =
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(title))
            val modifier = Modifier.testTag(tag)
            when (shown) {
                Shown.Hidden -> Text(stringResource(R.string.entry_hidden), modifier, style = MaterialTheme.typography.titleLarge)
                is Shown.Value -> Text(shown.text, modifier, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                is Shown.Message -> Text(shown.text, modifier, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }

/**
 * A one-time code counting down from [remainingSeconds], which calls
 * [onExpired] once the code is no longer valid. Each fetch passes a new
 * [generation], so a fetch that returns the same code restarts the count.
 */
@Composable
fun countdown(code: String, remainingSeconds: Long, generation: Int, onExpired: () -> Unit): String {
    var left by remember(generation) { mutableIntStateOf(remainingSeconds.toInt().coerceAtLeast(1)) }
    LaunchedEffect(generation) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        onExpired()
    }
    return stringResource(R.string.entry_code, code.chunked(3).joinToString(" "), left)
}

@Composable
fun FailedScreen(message: String, action: String, onAction: () -> Unit) = Page(title = null) {
    Hero(stringResource(R.string.error_title))
    Notice(message, Tone.ERROR, Icons.Filled.Warning)
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
)

@Composable
fun FillMessageScreen(text: String, onCancel: () -> Unit) = Page(title = null) {
    Hero(stringResource(R.string.fill_title), text)
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) }
}

@Composable
fun FillPickerScreen(
    request: String,
    offline: Boolean,
    matches: List<FillMatch>,
    lookalikes: List<String>,
    onChoose: (FillMatch) -> Unit,
    onCancel: () -> Unit,
) = Page(title = stringResource(R.string.fill_title), onBack = onCancel) {
    Text(request, style = MaterialTheme.typography.titleMedium)
    if (offline) Notice(stringResource(R.string.offline_notice), Tone.WARNING, Icons.Filled.Warning)
    if (matches.isEmpty()) {
        if (lookalikes.isNotEmpty()) {
            Notice(stringResource(R.string.fill_lookalikes, lookalikes.joinToString(", ")), Tone.ERROR, Icons.Filled.Warning)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.fill_no_matches), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            matches.forEachIndexed { index, match ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(match.name) },
                    supportingContent = { Text(matchDetail(match)) },
                    leadingContent = { Monogram(match.name.substringAfterLast('/')) },
                    modifier = Modifier.clickable { onChoose(match) },
                )
            }
        }
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) }
}
