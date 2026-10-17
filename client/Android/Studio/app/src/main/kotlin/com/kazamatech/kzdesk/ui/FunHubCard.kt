package com.kazamatech.kzdesk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.ui.components.KzOverlay
import com.kazamatech.kzdesk.ui.components.KzPillButton
import kotlinx.coroutines.launch

private const val COMMUNITY_JOKE_MAX_CHARS = 280
private const val INITIAL_JOKES_VISIBLE = 3

/** Flat home feed for anonymous, shared community jokes. */
@Composable
internal fun FunHubCard(onHide: (() -> Unit)? = null) {
    val context = LocalContext.current
    val api = remember(context) { CommunityJokesApi(context) }
    val scope = rememberCoroutineScope()

    // Cache-first (permintaan pemilik: buka feed langsung ada isinya, tidak
    // ada layar "Memuat…"): cache lokal dirender dulu, jaringan menyegar di
    // belakang layar.
    var jokes by remember(api) { mutableStateOf(api.cachedFeed()) }
    var draft by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var submitting by remember { mutableStateOf(false) }
    var activeReactionId by remember { mutableStateOf<String?>(null) }
    var commentingId by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<CommunityJoke?>(null) }
    var reportedIds by remember { mutableStateOf(emptySet<String>()) }
    var showAll by remember { mutableStateOf(false) }
    var composerOpen by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<String?>(null) }
    var formMessage by remember { mutableStateOf<String?>(null) }
    var formMessageIsError by remember { mutableStateOf(false) }
    var feedMessage by remember { mutableStateOf<String?>(null) }
    var feedMessageIsError by remember { mutableStateOf(false) }

    fun showCommunityError(error: Throwable, loadingFeed: Boolean = false): String {
        val reason = (error as? CommunityJokesException)?.reason.orEmpty()
        return when {
            loadingFeed && (reason == "not_found" || reason == "http_404") ->
                kzNow("Feed server belum tersedia (404). Deploy Worker terbaru, lalu coba lagi.", "The feed endpoint isn't deployed yet (404). Deploy the latest Worker, then retry.")
            loadingFeed && reason == "unavailable" ->
                kzNow("Feed sementara tidak tersedia di server. Coba lagi sebentar.", "The feed server is temporarily unavailable. Please retry shortly.")
            reason == "rate_limited" -> kzNow("Tunggu sebentar sebelum mengirim lagi.", "Please wait a little before posting again.")
            reason == "invalid_text" -> kzNow("Teks harus 3–280 karakter dan tidak boleh berisi tautan.", "Text must be 3–280 characters and cannot contain links.")
            reason == "invalid_client_id" -> kzNow("Identitas anonim tidak valid. Coba muat ulang aplikasi.", "The anonymous ID is invalid. Try reopening the app.")
            !loadingFeed && reason == "not_found" -> kzNow("Postingan sudah tidak tersedia.", "This post is no longer available.")
            reason == "network" -> kzNow("Jaringan gagal menjangkau server. Periksa internet lalu coba lagi.", "Couldn't reach the server. Check your connection and retry.")
            loadingFeed -> kzNow("Feed gagal dimuat. Periksa jaringan lalu coba lagi.", "Couldn't load the feed. Check your connection and retry.")
            else -> kzNow("Aksi gagal. Coba lagi.", "That action failed. Please retry.")
        }
    }

    fun updateJoke(updated: CommunityJoke) {
        jokes = jokes.map { old ->
            if (old.id != updated.id) return@map old
            // Respons reaksi tidak memuat komentar; jangan sampai hilang.
            updated.copy(
                comments = updated.comments.ifEmpty { old.comments },
                commentsTotal = maxOf(updated.commentsTotal, old.commentsTotal),
            )
        }
    }

    LaunchedEffect(api, refreshKey) {
        refreshing = jokes.isEmpty()
        feedError = null
        try {
            val fresh = api.latest()
            jokes = fresh
            api.cacheFeed(fresh)
            showAll = false
        } catch (error: Exception) {
            if (jokes.isEmpty()) feedError = showCommunityError(error, loadingFeed = true)
        } finally {
            refreshing = false
        }
    }

    val trimmedDraft = draft.trim()
    val characterCount = draft.codePointCount(0, draft.length)
    val trimmedCharacterCount = trimmedDraft.codePointCount(0, trimmedDraft.length)
    val containsLink = COMMUNITY_LINK_PATTERN.containsMatchIn(draft)
    val canSubmit = trimmedCharacterCount >= 3 && !containsLink && !submitting

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(kz("Jokes komunitas", "Community jokes"), style = MaterialTheme.typography.titleSmall)
                Text(
                    kz("Anonim · dibagikan ke semua pengguna", "Anonymous · shared with everyone"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (refreshing) kz("Memuat…", "Loading…") else kz("Muat ulang", "Refresh"),
                    modifier = Modifier
                        .clickable(enabled = !refreshing) { refreshKey += 1 }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Hanya di kartu Beranda. Di layar Feed (tujuan nav sendiri)
                // onHide bernilai null karena feed bukan kartu opsional.
                if (onHide != null) {
                    Text(
                        kz("Sembunyikan", "Hide"),
                        modifier = Modifier
                            .clickable(onClick = onHide)
                            .padding(start = 8.dp, top = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        feedMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (feedMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (feedError != null && jokes.isNotEmpty()) {
            Text(feedError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        when {
            refreshing && jokes.isEmpty() -> Text(
                kz("Memuat jokes komunitas…", "Loading community jokes…"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            feedError != null && jokes.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(feedError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                KzPillButton(
                    text = kz("Coba lagi", "Try again"),
                    onClick = { refreshKey += 1 },
                    primary = false,
                    compact = true,
                )
            }
            jokes.isEmpty() -> Text(
                kz("Belum ada postingan. Kirim yang pertama.", "Nothing here yet. Post the first one."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val visibleJokes = if (showAll) jokes else jokes.take(INITIAL_JOKES_VISIBLE)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleJokes.forEach { joke ->
                        CommunityJokeItem(
                            joke = joke,
                            isReactionBusy = activeReactionId == joke.id,
                            alreadyReported = joke.id in reportedIds,
                            onReact = { emoji ->
                                if (activeReactionId == null) {
                                    scope.launch {
                                        activeReactionId = joke.id
                                        feedMessage = null
                                        try {
                                            updateJoke(api.react(joke.id, emoji))
                                        } catch (error: Exception) {
                                            feedMessage = showCommunityError(error)
                                            feedMessageIsError = true
                                        } finally {
                                            activeReactionId = null
                                        }
                                    }
                                }
                            },
                            onReport = { reportTarget = joke },
                            isCommentBusy = commentingId == joke.id,
                            onComment = { text ->
                                if (commentingId == null) {
                                    scope.launch {
                                        commentingId = joke.id
                                        feedMessage = null
                                        try {
                                            val posted = api.comment(joke.id, text)
                                            updateJoke(
                                                joke.copy(
                                                    comments = (listOf(posted) + joke.comments).take(5),
                                                    commentsTotal = joke.commentsTotal + 1,
                                                ),
                                            )
                                        } catch (error: Exception) {
                                            feedMessage = showCommunityError(error)
                                            feedMessageIsError = true
                                        } finally {
                                            commentingId = null
                                        }
                                    }
                                }
                            },
                        )
                    }
                    if (jokes.size > INITIAL_JOKES_VISIBLE) {
                        Text(
                            if (showAll) kz("Tampilkan lebih sedikit", "Show less")
                            else kz("Tampilkan {0} lainnya", "Show {0} more", jokes.size - INITIAL_JOKES_VISIBLE),
                            modifier = Modifier
                                .align(Alignment.End)
                                .clickable { showAll = !showAll }
                                .padding(vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        if (composerOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(kz("Kirim jokes", "Post a joke"), style = MaterialTheme.typography.labelLarge)
                    Text(
                        kz("Batal", "Cancel"),
                        modifier = Modifier.clickable(enabled = !submitting) { composerOpen = false }.padding(4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CommunityJokeInput(
                    value = draft,
                    onValueChange = { next ->
                        val nextCount = next.codePointCount(0, next.length)
                        if (nextCount <= COMMUNITY_JOKE_MAX_CHARS) {
                            draft = next
                            formMessage = null
                        }
                    },
                    placeholder = kz("Tulis singkat. Tanpa nama atau tautan.", "Keep it short. No names or links."),
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val helper = when {
                        containsLink -> kz("Tautan tidak diterima", "Links aren't allowed")
                        else -> kz("{0}/280 karakter", "{0}/280 characters", characterCount)
                    }
                    Text(
                        helper,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (containsLink) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    KzPillButton(
                        text = if (submitting) kz("Mengirim…", "Posting…") else kz("Kirim anonim", "Post anonymously"),
                        onClick = {
                            if (canSubmit) {
                                scope.launch {
                                    submitting = true
                                    formMessage = null
                                    try {
                                        val posted = api.submit(trimmedDraft)
                                        jokes = (listOf(posted) + jokes.filterNot { it.id == posted.id }).take(20)
                                        draft = ""
                                        showAll = false
                                        composerOpen = false
                                        formMessage = kzNow("Terkirim dan tampil di feed komunitas.", "Posted to the community feed.")
                                        formMessageIsError = false
                                    } catch (error: Exception) {
                                        formMessage = showCommunityError(error)
                                        formMessageIsError = true
                                    } finally {
                                        submitting = false
                                    }
                                }
                            }
                        },
                        enabled = canSubmit,
                        compact = true,
                    )
                }
                Text(
                    kz("Tanpa akun. Spam atau konten bermasalah dapat disembunyikan.", "No account needed. Spam or abusive posts may be hidden."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            KzPillButton(
                text = kz("Tulis jokes", "Write a joke"),
                onClick = { composerOpen = true },
                primary = false,
                compact = true,
            )
        }
        formMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (formMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }

    reportTarget?.let { joke ->
        KzOverlay(
            title = kz("Laporkan postingan", "Report this post"),
            onDismiss = { if (!reporting) reportTarget = null },
        ) {
            Text(
                kz("Laporan anonim membantu menjaga feed. Postingan yang mendapat cukup laporan akan disembunyikan otomatis.", "Anonymous reports help keep the feed useful. Posts with enough reports are hidden automatically."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KzPillButton(
                    text = kz("Batal", "Cancel"),
                    onClick = { reportTarget = null },
                    enabled = !reporting,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    text = if (reporting) kz("Mengirim…", "Sending…") else kz("Kirim laporan", "Submit report"),
                    onClick = {
                        if (!reporting) {
                            reporting = true
                            scope.launch {
                                try {
                                    val hidden = api.report(joke.id)
                                    reportedIds = reportedIds + joke.id
                                    if (hidden) jokes = jokes.filterNot { it.id == joke.id }
                                    feedMessage = kzNow("Laporan anonim diterima.", "Anonymous report received.")
                                    feedMessageIsError = false
                                } catch (error: Exception) {
                                    feedMessage = showCommunityError(error)
                                    feedMessageIsError = true
                                } finally {
                                    reporting = false
                                    reportTarget = null
                                }
                            }
                        }
                    },
                    enabled = !reporting,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CommunityJokeInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 116.dp),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
            maxLines = 4,
        )
    }
}

@Composable
private fun CommunityJokeItem(
    joke: CommunityJoke,
    isReactionBusy: Boolean,
    alreadyReported: Boolean,
    onReact: (String) -> Unit,
    onReport: () -> Unit,
    isCommentBusy: Boolean = false,
    onComment: (String) -> Unit = {},
) {
    var pickerOpen by remember(joke.id) { mutableStateOf(false) }
    var commentsOpen by remember(joke.id) { mutableStateOf(false) }
    var commentDraft by remember(joke.id) { mutableStateOf("") }

    @Composable
    fun ReactionChip(emoji: String) {
        val selected = joke.viewerReaction == emoji
        val count = joke.reactions[emoji] ?: 0
        Row(
            modifier = Modifier
                .clickable(enabled = !isReactionBusy) { onReact(emoji) }
                .padding(horizontal = 3.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$emoji $count",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(joke.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)

        // Reaksi cepat + tombol "+" untuk 24 emoji (grid 8x3, tanpa scroll).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommunityJokesApi.QUICK_REACTIONS.forEach { emoji -> ReactionChip(emoji) }
            Text(
                if (pickerOpen) "−" else "+",
                modifier = Modifier
                    .clickable(enabled = !isReactionBusy) { pickerOpen = !pickerOpen }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (pickerOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CommunityJokesApi.REACTIONS.chunked(8).forEach { rowEmojis ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        rowEmojis.forEach { emoji ->
                            Text(
                                emoji,
                                modifier = Modifier
                                    .clickable(enabled = !isReactionBusy) {
                                        onReact(emoji)
                                        pickerOpen = false
                                    }
                                    .padding(vertical = 4.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                joke.authorName ?: kz("Anonim", "Anonymous"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                kz("Komentar ({0})", "Comments ({0})", joke.commentsTotal),
                modifier = Modifier
                    .clickable { commentsOpen = !commentsOpen }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (alreadyReported) kz("Dilaporkan", "Reported") else kz("Laporkan", "Report"),
                modifier = if (alreadyReported) Modifier else Modifier
                    .clickable(onClick = onReport)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (commentsOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (joke.comments.isEmpty()) {
                    Text(
                        kz("Belum ada komentar. Jadilah yang pertama.", "No comments yet. Be the first."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                joke.comments.forEach { c ->
                    Column {
                        Text(
                            c.name ?: kz("Anonim", "Anonymous"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(c.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        value = commentDraft,
                        onValueChange = { if (it.codePointCount(0, it.length) <= 140) commentDraft = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 2,
                        decorationBox = { inner ->
                            if (commentDraft.isEmpty()) {
                                Text(
                                    kz("Tulis komentar…", "Write a comment…"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                    KzPillButton(
                        text = if (isCommentBusy) "…" else kz("Kirim", "Send"),
                        onClick = {
                            val clean = commentDraft.trim()
                            if (clean.isNotEmpty() && !isCommentBusy) {
                                onComment(clean)
                                commentDraft = ""
                            }
                        },
                        enabled = commentDraft.trim().isNotEmpty() && !isCommentBusy,
                        compact = true,
                    )
                }
            }
        }
    }
}

private val COMMUNITY_LINK_PATTERN = Regex("(?:https?://|www\\.)", RegexOption.IGNORE_CASE)
