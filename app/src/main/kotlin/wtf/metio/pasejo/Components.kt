// SPDX-FileCopyrightText: The pasejo Authors
// SPDX-License-Identifier: 0BSD

package wtf.metio.pasejo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The widest a column of text and fields grows on tablets and in landscape. */
private val ContentWidth = 600.dp

/**
 * One screen: a top bar with the title, a back arrow when [onBack] is set,
 * and [content] in a column below it. Without a [title] there is no top bar,
 * for the screens before the stores open, which show the mark instead. With
 * [scroll] the column scrolls; without it, [content] fills the space and
 * scrolls by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String?,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bar = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        topBar = {
            if (title != null) {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = actions,
                    scrollBehavior = bar,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            val column = Modifier.widthIn(max = ContentWidth).fillMaxWidth()
            Column(
                modifier = if (scroll) column.verticalScroll(rememberScrollState()).padding(16.dp) else column.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/** A screen that waits for the core: a spinner and what it waits for. */
@Composable
fun Busy(text: String) = Surface(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mark(size = 72.dp)
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

/** How much a [Notice] wants attention. */
enum class Tone { INFO, WARNING, ERROR }

/** A tinted card that explains something next to the fields it concerns. */
@Composable
fun Notice(text: String, tone: Tone = Tone.INFO, icon: ImageVector? = null) {
    val colours = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        Tone.INFO -> colours.secondaryContainer to colours.onSecondaryContainer
        Tone.WARNING -> colours.tertiaryContainer to colours.onTertiaryContainer
        Tone.ERROR -> colours.errorContainer to colours.onErrorContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A heading for a group of fields or values. */
@Composable
fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

/** A value people compare or read aloud, in fixed-width groups of four. */
@Composable
fun Grouped(value: String, modifier: Modifier = Modifier) = Text(
    value.chunked(4).joinToString(" "),
    modifier = modifier,
    style = MaterialTheme.typography.titleMedium,
    fontFamily = FontFamily.Monospace,
)

/** pasejo's mark on a circle, drawn from the launcher icon's foreground. */
@Composable
fun Mark(
    size: Dp = 56.dp,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    foreground: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) = Image(
    painter = painterResource(R.drawable.ic_mark),
    contentDescription = null,
    colorFilter = ColorFilter.tint(foreground),
    modifier = Modifier.size(size).clip(CircleShape).background(background),
)

/** The mark above a headline, for the screens before the stores open. */
@Composable
fun Hero(title: String, text: String? = null) = Column(
    Modifier.fillMaxWidth().padding(top = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    Mark(size = 88.dp)
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A round tonal badge with the first letter of [name], to tell list rows apart. */
@Composable
fun Monogram(name: String) = Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier = Modifier.size(40.dp),
) {
    Box(contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", style = MaterialTheme.typography.titleMedium)
    }
}

/** Vertical room between groups inside a [Page]. */
@Composable
fun Gap(height: Dp = 8.dp) = Spacer(Modifier.height(height))
