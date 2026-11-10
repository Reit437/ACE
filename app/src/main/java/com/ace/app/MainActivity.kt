package com.ace.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.chaquo.python.Python
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val text: String, val isStreaming: Boolean = false)

// Мост для связи Python и интерфейса Android
// Мост для связи Python и интерфейса Android
class IOConsoleBridge(
    val onOutput: (String) -> Unit,
    val onInputRequest: () -> Unit
) {
    private var deferred: CompletableDeferred<String>? = null

    @Suppress("unused")
    fun printOutput(text: String) {
        // Блокируем поток Python, пока UI не обновится, чтобы сохранить порядок
        runBlocking(Dispatchers.Main) {
            onOutput(text)
        }
    }

    @Suppress("unused")
    fun readInput(): String {
        val d = CompletableDeferred<String>()
        deferred = d
        // Показываем поле ввода
        runBlocking(Dispatchers.Main) {
            onInputRequest()
        }
        // Ждем, пока пользователь введет текст
        return runBlocking { d.await() }
    }

    fun submit(text: String) {
        deferred?.complete(text)
        deferred = null
    }
}

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ace_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

        setContent {
            var currentScreen by remember { mutableStateOf("editor") }

            // Состояния консоли
            var showConsole by remember { mutableStateOf(false) }
            var consoleText by remember { mutableStateOf("") }
            var isRunning by remember { mutableStateOf(false) }
            var isWaitingForInput by remember { mutableStateOf(false) }
            var inputText by remember { mutableStateOf("") }
            var ioBridge by remember { mutableStateOf<IOConsoleBridge?>(null) }

            val messages = remember { mutableStateListOf<ChatMessage>() }
            val scope = rememberCoroutineScope()
            val focusManager = LocalFocusManager.current

            Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(),
                floatingActionButton = {
                    if (currentScreen == "editor") {
                        FloatingActionButton(
                            onClick = {
                                val currentWebView = webView ?: return@FloatingActionButton
                                currentWebView.evaluateJavascript("btoa(unescape(encodeURIComponent(editor.getValue())));") { json ->
                                    try {
                                        val base64Str = JSONTokener(json).nextValue() as String
                                        val code = String(Base64.decode(base64Str, Base64.DEFAULT), Charsets.UTF_8)

                                        showConsole = true
                                        consoleText = ""
                                        isRunning = true
                                        isWaitingForInput = false

                                        val bridge = IOConsoleBridge(
                                            onOutput = { text -> consoleText += text },
                                            onInputRequest = { isWaitingForInput = true }
                                        )
                                        ioBridge = bridge

                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                try {
                                                    Python.getInstance().getModule("main").callAttr("run_code", code, bridge).toString()
                                                } catch (e: Exception) { "Ошибка Kotlin: ${e.message}" }
                                            }
                                            consoleText += "\n[ $res ]"
                                            isRunning = false
                                        }
                                    } catch (e: Exception) {
                                        showConsole = true
                                        consoleText = "Не удалось получить код"
                                    }
                                }
                            }
                        ) { Icon(Icons.Default.PlayArrow, contentDescription = "Запустить") }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentScreen == "editor",
                            onClick = { focusManager.clearFocus(); currentScreen = "editor" },
                            icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Editor") },
                            label = { Text("Редактор") }
                        )
                        NavigationBarItem(
                            selected = currentScreen == "chat",
                            onClick = { focusManager.clearFocus(); currentScreen = "chat" },
                            icon = { Icon(Icons.Default.Send, contentDescription = "Chat") },
                            label = { Text("Ace") }
                        )
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                    EditorScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (currentScreen == "editor") 1f else 0f)
                            .alpha(if (currentScreen == "editor") 1f else 0f),
                        onWebViewCreated = {
                            webView = it
                            it.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val savedCode = prefs.getString("saved_code", "") ?: ""
                                    if (savedCode.isNotEmpty()) {
                                        val b64 = Base64.encodeToString(savedCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                        view?.evaluateJavascript("loadCode('$b64');", null)
                                    }
                                }
                            }
                        }
                    )

                    ChatScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (currentScreen == "chat") 1f else 0f)
                            .alpha(if (currentScreen == "chat") 1f else 0f),
                        messages = messages,
                        onSend = { prompt ->
                            val currentWebView = webView ?: return@ChatScreen

                            messages.add(ChatMessage("user", prompt))
                            val aiResponseIndex = messages.size
                            messages.add(ChatMessage("ai", "...", isStreaming = true))

                            currentWebView.evaluateJavascript("btoa(unescape(encodeURIComponent(editor.getValue())));") { json ->
                                val code = try {
                                    val base64Str = JSONTokener(json).nextValue() as String
                                    String(Base64.decode(base64Str, Base64.DEFAULT), Charsets.UTF_8)
                                } catch (e: Exception) { "" }

                                val promptWithCode = "Мой текущий код:\n```python\n$code\n```\n\nМой вопрос: $prompt"

                                scope.launch {
                                    sendToAce(deviceId, promptWithCode, code,
                                        onChunk = { chunk ->
                                            val currentText = messages[aiResponseIndex].text
                                            val newText = if (currentText == "...") chunk else currentText + chunk
                                            messages[aiResponseIndex] = ChatMessage("ai", newText, isStreaming = true)
                                        },
                                        onFinish = {
                                            val finalText = messages[aiResponseIndex].text
                                            messages[aiResponseIndex] = ChatMessage("ai", finalText, isStreaming = false)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }

            // ОКНО КОНСОЛИ
            if (showConsole) {
                AlertDialog(
                    onDismissRequest = { if (!isRunning) showConsole = false },
                    title = { Text("Консоль") },
                    text = {
                        Column {
                            // Вывод программы
                            Box(modifier = Modifier.height(250.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    text = consoleText,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // Поле ввода, если программа ждет
                            if (isWaitingForInput) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    label = { Text("Ввод данных") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    },
                    confirmButton = {
                        if (isWaitingForInput) {
                            TextButton(onClick = {
                                val text = inputText
                                inputText = ""
                                consoleText += "$text\n"
                                isWaitingForInput = false
                                ioBridge?.submit(text)
                            }) { Text("Отправить") }
                        } else if (!isRunning) {
                            TextButton(onClick = { showConsole = false }) { Text("Закрыть") }
                        }
                    }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView?.evaluateJavascript("btoa(unescape(encodeURIComponent(editor.getValue())));") { json ->
            try {
                val base64Str = JSONTokener(json).nextValue() as String
                val code = String(Base64.decode(base64Str, Base64.DEFAULT), Charsets.UTF_8)
                getSharedPreferences("ace_prefs", Context.MODE_PRIVATE)
                    .edit().putString("saved_code", code).apply()
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

// ... (Функции EditorScreen, ChatScreen и sendToAce оставляем без изменений, они такие же, как в предыдущем коде)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorScreen(modifier: Modifier = Modifier, onWebViewCreated: (WebView) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            object : WebView(context) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                    val connection = super.onCreateInputConnection(outAttrs)
                    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    return connection
                }
            }.apply {
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        Log.d("ACE_DEBUG", "JS: ${msg.message()}")
                        return true
                    }
                }

                val html = context.assets.open("editor.html").bufferedReader().use { it.readText() }
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                onWebViewCreated(this)
            }
        }
    )
}

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    messages: List<ChatMessage>,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(8.dp),
            reverseLayout = false
        ) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.widthIn(max = 340.dp)
                    ) {
                        Text(
                            text = msg.text,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = if (isUser) FontFamily.Default else FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Спроси Ace...") },
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSend(inputText.trim())
                        inputText = ""
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

suspend fun sendToAce(
    deviceId: String,
    prompt: String,
    code: String,
    onChunk: (String) -> Unit,
    onFinish: () -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val json = JSONObject().apply {
                put("device_id", deviceId); put("prompt", prompt); put("filename", "main.py"); put("code", code)
            }

            val request = Request.Builder()
                .url("https://bitter-breeze-1f39.reitveider.workers.dev")
                .post(json.toString().toRequestBody("application/json".toMediaType())).build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { onChunk("\n[Ошибка сервера: ${response.code}]"); onFinish() }
                return@withContext
            }

            val reader = response.body?.charStream()?.buffered() ?: return@withContext
            val fullText = StringBuilder()
            var usageJson = ""
            var line = reader.readLine()

            while (line != null) {
                if (line.startsWith("data: ")) {
                    val data = line.substring(6)
                    if (data != "[DONE]") {
                        try {
                            val obj = JSONObject(data)
                            val candidates = obj.optJSONArray("candidates")
                            if (candidates != null && candidates.length() > 0) {
                                val text = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
                                if (text.isNotEmpty()) {
                                    fullText.append(text)
                                    withContext(Dispatchers.Main) { onChunk(text) }
                                }
                            }
                            if (obj.has("usageMetadata")) usageJson = obj.getJSONObject("usageMetadata").toString()
                        } catch (e: Exception) { }
                    }
                }
                line = reader.readLine()
            }

            val saveJson = JSONObject().apply {
                put("device_id", deviceId); put("text", fullText.toString())
                if (usageJson.isNotEmpty()) put("usage", JSONObject(usageJson))
                put("duration_ms", 0)
            }
            val saveReq = Request.Builder()
                .url("https://bitter-breeze-1f39.reitveider.workers.dev/save")
                .post(saveJson.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(saveReq).execute().close()

            withContext(Dispatchers.Main) { onFinish() }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onChunk("\n[Ошибка сети: ${e.message}]"); onFinish()
            }
        }
    }
}