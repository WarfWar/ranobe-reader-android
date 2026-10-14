package com.warfwar.ranobereader

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val HOME = "https://mtlnovel.me/"

private data class Chapter(
    val url: String, val title: String, val text: String,
    val previous: String?, val next: String?
)

private fun checkedUrl(value: String): String {
    val uri = URI(value.trim())
    val host = uri.host?.lowercase(Locale.ROOT)
    require(uri.scheme.equals("https", true) &&
        (host == "mtlnovel.me" || host?.endsWith(".mtlnovel.me") == true)) {
        "Нужна ссылка https://mtlnovel.me/ на главу."
    }
    return uri.toASCIIString()
}

private fun navigation(doc: Document, next: Boolean): String? {
    val words = if (next) listOf("next", "следующ", "далее", "вперёд", "→", "»", "›")
                else listOf("previous", "prev", "предыдущ", "назад", "←", "«", "‹")
    val rel = if (next) "next" else "prev"
    val link = doc.select("a[href]").firstOrNull { a ->
        a.attr("rel").split(" ").contains(rel) ||
        words.any { a.text().lowercase(Locale.ROOT).contains(it) }
    } ?: return null
    return link.absUrl("href").takeIf { runCatching { checkedUrl(it) }.isSuccess }
}

private fun textFrom(element: Element): String {
    val clean = element.clone()
    clean.select("script,style,noscript,nav,footer,header,aside,form,iframe,.ads,.advertisement,.share,.pagination").remove()
    val paragraphs = clean.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
    if (paragraphs.size >= 2) return paragraphs.joinToString("\n\n")
    clean.select("br").append(" \n")
    return clean.wholeText().lines().map { it.trim() }
        .filter { it.isNotEmpty() }.joinToString("\n\n")
        .ifBlank { clean.text() }
}

private suspend fun getChapter(address: String): Chapter = withContext(Dispatchers.IO) {
    val response = Jsoup.connect(checkedUrl(address))
        .userAgent("Mozilla/5.0 (Android; RanobeReader/0.1)")
        .timeout(20000).followRedirects(true).execute()
    require(response.statusCode() in 200..299) {
        "HTTP " + response.statusCode()
    }
    val doc = response.parse()
    val url = checkedUrl(response.url().toString())
    val selectors = listOf(
        "#chaptercontent", "#chapter-content", ".chapter-content",
        ".chapter-body", ".reading-content", ".read-content",
        ".entry-content", "article", "main"
    )
    val body = selectors.asSequence().flatMap { doc.select(it).asSequence() }
        .map { textFrom(it) }.firstOrNull { it.length >= 150 }
        ?: doc.select("p").map { it.text().trim() }
            .filter { it.length >= 45 }.joinToString("\n\n")
    require(body.length >= 150) {
        "Текст главы не найден. Откройте конкретную главу в браузере сайта."
    }
    Chapter(
        url, doc.selectFirst("h1")?.text()?.ifBlank { doc.title() }
            ?: doc.title().ifBlank { "Глава" },
        body, navigation(doc, false), navigation(doc, true)
    )
}

private fun textChunks(text: String, limit: Int = 1400): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    text.split("\n\n").forEach { paragraph ->
        var remainder = paragraph
        while (remainder.isNotEmpty()) {
            if (current.isNotEmpty() && current.length + remainder.length + 2 > limit) {
                parts += current.toString()
                current.clear()
            }
            val available = limit - current.length - if (current.isEmpty()) 0 else 2
            val size = minOf(remainder.length, available)
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(remainder.take(size))
            remainder = remainder.drop(size)
            if (remainder.isNotEmpty()) {
                parts += current.toString()
                current.clear()
            }
        }
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
}

private suspend fun translate(text: String, progress: (String) -> Unit): String {
    val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.RUSSIAN).build()
    val translator = Translation.getClient(options)
    try {
        progress("Загрузка модели перевода…")
        translator.downloadModelIfNeeded().await()
        val pieces = textChunks(text)
        val result = mutableListOf<String>()
        pieces.forEachIndexed { index, part ->
            result += translator.translate(part).await()
            progress("Переведено: " + (index + 1) + "/" + pieces.size)
        }
        return result.joinToString("\n\n")
    } finally {
        translator.close()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ReaderScreen() } }
    }
}

@Composable
private fun ReaderScreen() {
    var address by rememberSaveable { mutableStateOf(HOME) }
    var browserAddress by rememberSaveable { mutableStateOf(HOME) }
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var translation by remember { mutableStateOf<String?>(null) }
    var size by rememberSaveable { mutableFloatStateOf(18f) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Откройте сайт или вставьте ссылку на главу.") }
    val scope = rememberCoroutineScope()

    fun loadChapter(url: String) {
        scope.launch {
            busy = true
            showBrowser = false
            translation = null
            status = "Загрузка…"
            try {
                val loaded = getChapter(url)
                chapter = loaded
                address = loaded.url
                status = "Глава загружена."
            } catch (e: Exception) {
                status = "Не удалось загрузить: " + (e.localizedMessage ?: "неизвестная ошибка")
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Ranobe Reader", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = address, onValueChange = { address = it },
            label = { Text("Ссылка на mtlnovel.me") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { loadChapter(address) }, enabled = !busy) { Text("Читать") }
            OutlinedButton(onClick = {
                browserAddress = runCatching { checkedUrl(address) }.getOrDefault(HOME)
                showBrowser = !showBrowser
            }, enabled = !busy) {
                Text(if (showBrowser) "Закрыть сайт" else "Открыть сайт")
            }
        }
        if (showBrowser) {
            Text("Найдите главу на сайте и нажмите «Читать открытую страницу».")
            Button(onClick = { loadChapter(browserAddress) }, enabled = !busy) {
                Text("Читать открытую страницу")
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url != null) browserAddress = url
                            }
                        }
                        loadUrl(browserAddress)
                    }
                }
            )
        } else {
            Text(status, style = MaterialTheme.typography.bodySmall)
            chapter?.let { item ->
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        item.previous?.let { loadChapter(it) }
                    }, enabled = item.previous != null && !busy) { Text("←") }
                    Button(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                translation = translate(item.text) { status = it }
                                status = "Перевод готов."
                            } catch (e: Exception) {
                                status = "Ошибка перевода: " + (e.localizedMessage ?: "неизвестная ошибка")
                            } finally { busy = false }
                        }
                    }, enabled = !busy) { Text("Перевести") }
                    OutlinedButton(onClick = {
                        item.next?.let { loadChapter(it) }
                    }, enabled = item.next != null && !busy) { Text("→") }
                    if (translation != null) {
                        OutlinedButton(onClick = { translation = null }) {
                            Text("EN")
                        }
                    }
                }
                Text("Размер шрифта: " + size.toInt())
                Slider(value = size, onValueChange = { size = it }, valueRange = 14f..30f)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        text = translation ?: item.text,
                        fontSize = size.sp, lineHeight = (size * 1.55f).sp
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
