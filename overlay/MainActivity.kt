package com.example.llama

import android.annotation.SuppressLint
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Lume Chat Local: sem API remota, sem permissao de internet. */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var assets: WebViewAssetLoader
    private val engineReady = CompletableDeferred<InferenceEngine>()
    private var loadedPath: String? = null
    private var loadedChatId: String? = null
    private var loadedMode: String? = null
    private var generationJob: Job? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var exportData: String? = null
    private val prefs by lazy { getSharedPreferences("lume-local", MODE_PRIVATE) }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSelectedFile(uri)
    }
    private val jsonPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileChooser?.onReceiveValue(if (uri == null) null else arrayOf(uri))
        fileChooser = null
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = exportData
        exportData = null
        if (uri != null && data != null) lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                jsCall("lumeNativeToast", "Backup salvo no celular.")
            } catch (e: Exception) { jsCall("lumeNativeToast", "Falha ao salvar: ${e.message}") }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assets = WebViewAssetLoader.Builder().addPathHandler(
            "/assets/", WebViewAssetLoader.AssetsPathHandler(this)
        ).build()
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.addJavascriptInterface(Bridge(), "LumeNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                return request?.url?.let { assets.shouldInterceptRequest(it) }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                return request?.url?.host != "appassets.androidplatform.net"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                modelStatus()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileChooser?.onReceiveValue(null)
                fileChooser = callback
                jsonPicker.launch(arrayOf("application/json", "*/*"))
                return true
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val engine = AiChat.getInferenceEngine(applicationContext)
                engine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error
                }
                engineReady.complete(engine)
            } catch (e: Exception) { engineReady.completeExceptionally(e) }
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun chooseModel() = runOnUiThread {
            if (generationJob?.isActive == true) {
                jsCall("lumeNativeToast", "Espere a resposta terminar para trocar a IA.")
            } else modelPicker.launch(arrayOf("*/*"))
        }
        @JavascriptInterface fun modelStatus() = runOnUiThread { this@MainActivity.modelStatus() }
        @JavascriptInterface fun copyText(text: String) = runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Lume código", text))
        }
        @JavascriptInterface fun openModelPage(url: String) = runOnUiThread {
            if (!url.startsWith("https://huggingface.co/bartowski/Qwen2.5-Coder-")) return@runOnUiThread
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (e: Exception) { jsCall("lumeNativeToast", "Abra huggingface.co no navegador para baixar a IA.") }
        }
        @JavascriptInterface fun exportBackup(data: String, name: String) = runOnUiThread {
            exportData = data
            backupPicker.launch(name.take(80).ifBlank { "lume-chat-backup.json" })
        }
        @JavascriptInterface fun generate(id: String, chatId: String, mode: String, history: String) = runOnUiThread {
            if (generationJob?.isActive == true) {
                jsCall("lumeNativeResponse", id, false, "A IA já está preparando uma resposta.")
            } else {
                generationJob = lifecycleScope.launch {
                    try {
                        val path = prefs.getString("model", null) ?: throw IllegalStateException(
                            "Escolha o arquivo da IA GGUF em Configurar IA."
                        )
                        if (!File(path).isFile) throw IllegalStateException("Arquivo do modelo não encontrado.")
                        val engine = engineReady.await()
                        val response = withContext(Dispatchers.Default) {
                            if (loadedPath != path || loadedChatId != chatId || loadedMode != mode) {
                                if (loadedPath != null) engine.cleanUp()
                                engine.loadModel(path)
                                engine.setSystemPrompt(systemPromptFor(mode))
                                loadedPath = path
                                loadedChatId = chatId
                                loadedMode = mode
                                // Ao trocar de chat, inclui um resumo das mensagens importadas/anteriores.
                                val conversation = makePrompt(history, includeHistory = true)
                                val buffer = StringBuilder()
                                engine.sendUserPrompt(conversation, predictLength = 950).collect { buffer.append(it) }
                                buffer.toString().trim()
                            } else {
                                val last = makePrompt(history, includeHistory = false)
                                val buffer = StringBuilder()
                                engine.sendUserPrompt(last, predictLength = 950).collect { buffer.append(it) }
                                buffer.toString().trim()
                            }
                        }
                        if (response.isEmpty()) throw IllegalStateException("A IA não retornou resposta. Tente novamente.")
                        jsCall("lumeNativeResponse", id, true, response)
                    } catch (e: Exception) {
                        jsCall("lumeNativeResponse", id, false, e.message ?: "Falha ao executar o modelo local.")
                    }
                }
            }
        }
    }

    private fun systemPromptFor(mode: String): String {
        val specific = when (mode) {
            "godot" -> "Criação de jogos Godot 4 com GDScript 2.0. Inclua nós, cenas, caminhos dos scripts, código e passos de configuração dos Input Maps. Priorize editor Android quando possível."
            "android" -> "Desenvolvimento de apps Android com Kotlin, XML e Gradle. Separe código por nome de arquivo, inclua manifest/permissões e passos de build. Não invente APIs."
            "web" -> "Desenvolvimento web com HTML, CSS e JavaScript. Forneça arquivos completos, responsivos, sem APIs simuladas disfarçadas de reais."
            "python" -> "Programação Python. Forneça funções executáveis, dependências, exemplo de uso e testes simples."
            else -> "Ajude com programação, arquitetura, depuração e protótipos."
        }
        return "Você é Lume Coder, assistente de programação local. Responda em português brasileiro. " +
            specific + " Quando gerar código, explique onde salvá-lo, como executá-lo e limites do exemplo. " +
            "Se o pedido for grande demais, entregue primeiro um protótipo funcional, sem fingir ter compilado, testado ou acessado arquivos. " +
            "Se não tiver certeza, diga isso de maneira breve."
    }

    private fun makePrompt(history: String, includeHistory: Boolean): String {
        if (history.length > 120_000) throw IllegalArgumentException("Histórico muito grande para o modelo local")
        val msgs = JSONArray(history)
        if (msgs.length() == 0) throw IllegalArgumentException("Conversa vazia")
        val last = msgs.optJSONObject(msgs.length() - 1)?.optString("content")?.take(4000).orEmpty()
        if (!includeHistory || msgs.length() == 1) return last
        val context = StringBuilder("Histórico da conversa para referência:\n")
        val start = maxOf(0, msgs.length() - 13)
        for (i in start until msgs.length() - 1) {
            val m = msgs.optJSONObject(i) ?: continue
            val label = if (m.optString("role") == "user") "Usuário" else "Assistente"
            context.append(label).append(": ").append(m.optString("content").take(500)).append("\n")
        }
        return (context.toString().takeLast(4200) + "\nMensagem atual do usuário: " + last).takeLast(6500)
    }

    private fun loadSelectedFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                var name = "modelo.gguf"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
                if (!name.lowercase().endsWith(".gguf")) throw IllegalArgumentException("Escolha um arquivo .gguf")
                jsCall("lumeNativeToast", "Copiando a IA para o aplicativo...")
                val target = File(filesDir, "lume-model.gguf")
                val temp = File(filesDir, "lume-model-import.tmp")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { source ->
                        temp.outputStream().use { source.copyTo(it, 64 * 1024) }
                    } ?: throw IllegalStateException("Não foi possível abrir o arquivo")
                    val header = temp.inputStream().use { inp -> ByteArray(4).also { inp.read(it) } }
                    if (!header.contentEquals("GGUF".toByteArray())) {
                        temp.delete()
                        throw IllegalArgumentException("O arquivo não é um modelo GGUF válido")
                    }
                    if (temp.length() < 1_000_000L) {
                        temp.delete()
                        throw IllegalArgumentException("Modelo incompleto")
                    }
                }
                val engine = engineReady.await()
                withContext(Dispatchers.Default) { if (loadedPath != null) engine.cleanUp() }
                loadedPath = null
                loadedChatId = null
                loadedMode = null
                withContext(Dispatchers.IO) {
                    if (target.exists() && !target.delete()) throw IllegalStateException("Sem acesso ao modelo anterior")
                    if (!temp.renameTo(target)) throw IllegalStateException("Erro ao instalar modelo")
                }
                prefs.edit().putString("model", target.absolutePath).apply()
                jsCall("lumeNativeToast", "IA instalada! Você pode conversar sem internet.")
                modelStatus()
            } catch (e: Exception) {
                jsCall("lumeNativeToast", "Erro ao carregar IA: ${e.message}")
            }
        }
    }

    private fun modelStatus() {
        val path = prefs.getString("model", null)
        val ready = path != null && File(path).isFile
        jsCall("lumeNativeModelStatus", ready,
            if (ready) "Modelo instalado · ${File(path!!).name} · offline" else "Nenhum modelo instalado · selecione o arquivo Qwen2.5-Coder GGUF")
    }

    private fun jsCall(function: String, vararg args: Any) {
        val params = args.joinToString(",") { a ->
            if (a is Boolean) a.toString() else JSONObject.quote(a.toString())
        }
        runOnUiThread { web.evaluateJavascript("window.$function && window.$function($params)", null) }
    }

    override fun onDestroy() {
        generationJob?.cancel()
        // Recursos nativos sao liberados quando o processo Android termina.
        web.removeJavascriptInterface("LumeNative")
        web.destroy()
        super.onDestroy()
    }
}
