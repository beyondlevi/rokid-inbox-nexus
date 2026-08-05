package com.rokid.inbox.nexus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.rokid.inbox.nexus.contacts.ContactDirectory
import com.rokid.inbox.nexus.model.ChannelKind
import com.rokid.inbox.nexus.model.QuickMessage

/**
 * Phone-side settings screen, built only on the NexusUi/BusTheme kit (no XML).
 * Manages the channel boxes, the OpenAI key and the quick replies, all stored
 * encrypted on-device. Changes take effect the next time the plugin is opened
 * on the glasses (the plugin rebuilds its channels on each open). Ends with the
 * mandatory uninstall row.
 */
class InboxSettingsActivity : Activity() {

    private lateinit var store: InboxConfigStore
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = InboxConfigStore(this)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        content = NexusUi.contentColumn(this)
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@InboxSettingsActivity,
                    NexusPluginIcons.drawableFor("chat"),
                    "Rokid Inbox",
                    "Inbox multi-canal · v${appVersion()}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@InboxSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        render()
    }

    /** Rebuild the whole content column from the current stored config. */
    private fun render() {
        content.removeAllViews()

        addView(NexusUi.cardBody(this, "Configure os canais e a IA. As mudancas valem na proxima vez que voce abrir o Inbox nos oculos."))
        gap(18)

        renderChannels()
        gap(24)
        renderAddChannel()
        gap(24)
        renderOpenAi()
        gap(24)
        renderStt()
        gap(24)
        renderCardDav()
        gap(24)
        renderQuickMessages()
        gap(24)

        addView(NexusUi.sectionRow(this, "Plugin"))
        gap(10)
        addView(NexusUi.uninstallCard(this, "Rokid Inbox") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        })
    }

    /* ---------------- connected channels ---------------- */

    private fun renderChannels() {
        addView(NexusUi.sectionRow(this, "Canais conectados"))
        gap(10)
        val boxes = store.getBoxes()
        if (boxes.isEmpty()) {
            addView(NexusUi.cardBody(this, "Nenhum canal ainda. Adicione um abaixo."))
            return
        }
        for (box in boxes) {
            val card = NexusUi.card(this)
            card.addView(NexusUi.rowTitle(this, box.name.ifBlank { box.kind.name }), NexusUi.block())
            card.addView(NexusUi.rowSub(this, kindLabel(box.kind)), NexusUi.block())
            card.addView(BusTheme.gap(this, 8))
            val remove = NexusUi.textButton(this, "Remover", true)
            remove.setOnClickListener {
                store.deleteBox(box.id)
                toast("Canal removido")
                render()
            }
            card.addView(remove, NexusUi.block())
            addView(card)
            gap(10)
        }
    }

    /* ---------------- add channel ---------------- */

    private fun renderAddChannel() {
        addView(NexusUi.sectionRow(this, "Adicionar canal"))
        gap(10)

        addWhatsAppCard()
        gap(10)
        addTelegramCard()
        gap(10)
        addGitHubCard()
        gap(10)
        addGmailCard()
    }

    private fun addWhatsAppCard() {
        val card = NexusUi.card(this)
        card.addView(NexusUi.cardTitle(this, "WhatsApp (Evolution API)"), NexusUi.block())
        val name = field("Nome (opcional)")
        val url = field("URL do servidor Evolution")
        val instance = field("Instancia")
        val key = field("API key")
        listOf(name, url, instance, key).forEach { card.addView(it, NexusUi.block()) }
        card.addView(BusTheme.gap(this, 8))
        card.addView(addButton {
            if (url.text.isBlank() || instance.text.isBlank() || key.text.isBlank()) {
                toast("Preencha URL, instancia e API key"); return@addButton
            }
            saveBox(ChannelKind.WHATSAPP, name.text.toString().ifBlank { "WhatsApp" }, mapOf(
                "serverUrl" to url.text.toString().trim(),
                "instance" to instance.text.toString().trim(),
                "apiKey" to key.text.toString().trim(),
            ))
        }, NexusUi.block())
        addView(card)
    }

    private fun addTelegramCard() {
        val card = NexusUi.card(this)
        card.addView(NexusUi.cardTitle(this, "Telegram (bridge GramJS)"), NexusUi.block())
        val name = field("Nome (opcional)")
        val url = field("URL do bridge")
        val key = field("API key")
        listOf(name, url, key).forEach { card.addView(it, NexusUi.block()) }
        card.addView(BusTheme.gap(this, 8))
        card.addView(addButton {
            if (url.text.isBlank() || key.text.isBlank()) {
                toast("Preencha URL e API key"); return@addButton
            }
            saveBox(ChannelKind.TELEGRAM, name.text.toString().ifBlank { "Telegram" }, mapOf(
                "serverUrl" to url.text.toString().trim(),
                "apiKey" to key.text.toString().trim(),
            ))
        }, NexusUi.block())
        addView(card)
    }

    private fun addGitHubCard() {
        val card = NexusUi.card(this)
        card.addView(NexusUi.cardTitle(this, "GitHub PRs (somente leitura)"), NexusUi.block())
        val name = field("Nome (opcional)")
        val token = field("Token (PAT)")
        val query = field("Query (padrao: is:open is:pr involves:@me)")
        listOf(name, token, query).forEach { card.addView(it, NexusUi.block()) }
        card.addView(BusTheme.gap(this, 8))
        card.addView(addButton {
            if (token.text.isBlank()) { toast("Informe o token"); return@addButton }
            saveBox(ChannelKind.GITHUB, name.text.toString().ifBlank { "GitHub" }, mapOf(
                "token" to token.text.toString().trim(),
                "query" to query.text.toString().trim(),
            ))
        }, NexusUi.block())
        addView(card)
    }

    private fun addGmailCard() {
        val card = NexusUi.card(this)
        card.addView(NexusUi.cardTitle(this, "Gmail (OAuth, somente leitura)"), NexusUi.block())
        val name = field("Nome (opcional)")
        val clientId = field("Client ID")
        val clientSecret = field("Client Secret")
        val refresh = field("Refresh Token")
        listOf(name, clientId, clientSecret, refresh).forEach { card.addView(it, NexusUi.block()) }
        card.addView(BusTheme.gap(this, 8))
        card.addView(addButton {
            if (clientId.text.isBlank() || clientSecret.text.isBlank() || refresh.text.isBlank()) {
                toast("Preencha Client ID, Secret e Refresh Token"); return@addButton
            }
            saveBox(ChannelKind.GMAIL, name.text.toString().ifBlank { "Gmail" }, mapOf(
                "clientId" to clientId.text.toString().trim(),
                "clientSecret" to clientSecret.text.toString().trim(),
                "refreshToken" to refresh.text.toString().trim(),
            ))
        }, NexusUi.block())
        addView(card)
    }

    private fun saveBox(kind: ChannelKind, name: String, config: Map<String, String>) {
        store.addBox(BoxConfig(id = InboxConfigStore.newBoxId(kind), kind = kind, name = name, config = config))
        toast("Canal adicionado")
        render()
    }

    /* ---------------- OpenAI ---------------- */

    private fun renderOpenAi() {
        addView(NexusUi.sectionRow(this, "IA (OpenAI)"))
        gap(10)
        val card = NexusUi.card(this)
        card.addView(NexusUi.cardBody(this, "Chave usada para descrever imagens e arquivos (Descrever IA)."), NexusUi.block())
        val key = field("sk-...")
        key.setText(store.getOpenAiKey())
        card.addView(key, NexusUi.block())
        card.addView(BusTheme.gap(this, 8))
        val save = NexusUi.pillButton(this, "Salvar chave", true)
        save.setOnClickListener {
            store.setOpenAiKey(key.text.toString().trim())
            toast("Chave salva")
        }
        card.addView(save, NexusUi.block())
        addView(card)
    }

    /* ---------------- voice / STT ---------------- */

    private fun renderStt() {
        addView(NexusUi.sectionRow(this, "Voz (ditado / STT)"))
        gap(10)
        val card = NexusUi.card(this)
        card.addView(
            NexusUi.cardBody(
                this,
                "Dite respostas pelos oculos. Usa a mesma chave OpenAI acima e requer o microfone " +
                    "aprovado em Plugin access (o app Nexus pede na primeira vez).",
            ),
            NexusUi.block(),
        )
        val enabled = store.isSttEnabled()
        val toggle = NexusUi.pillButton(this, if (enabled) "Ditado: ATIVADO" else "Ditado: DESATIVADO", enabled)
        toggle.setOnClickListener {
            store.setSttEnabled(!enabled)
            toast(if (!enabled) "Ditado ativado" else "Ditado desativado")
            content.post { render() } // defer rebuild; never tear down the view mid-click
        }
        card.addView(toggle, NexusUi.block())
        card.addView(BusTheme.gap(this, 8))
        val lang = field("Idioma (ex: pt, en) — vazio = automatico")
        lang.setText(store.getSttLanguage())
        card.addView(lang, NexusUi.block())
        val model = field("Modelo (${SpeechToText.MODELS.values.joinToString(" / ")})")
        model.setText(store.getSttModel())
        card.addView(model, NexusUi.block())
        card.addView(BusTheme.gap(this, 8))
        val save = NexusUi.pillButton(this, "Salvar voz", true)
        save.setOnClickListener {
            store.setSttLanguage(lang.text.toString().trim())
            store.setSttModel(model.text.toString().trim())
            toast("Config de voz salva")
        }
        card.addView(save, NexusUi.block())
        addView(card)
    }

    /* ---------------- CardDAV contact directory ---------------- */

    private var davSyncing = false

    private fun renderCardDav() {
        addView(NexusUi.sectionRow(this, "Agenda de contatos (CardDAV)"))
        gap(10)
        val card = NexusUi.card(this)
        card.addView(
            NexusUi.cardBody(
                this,
                "Conecte sua agenda por CardDAV (Dex, iCloud, Google, Nextcloud...) para mostrar " +
                    "o nome salvo de cada contato do WhatsApp em vez de \"Contato 000000\". O cruzamento " +
                    "e por numero de telefone e fica so no celular.",
            ),
            NexusUi.block(),
        )
        val server = field("Servidor (ex: https://sync.getdex.com)")
        server.setText(store.getCardDavServer())
        val user = field("Usuario")
        user.setText(store.getCardDavUser())
        val pass = field("Senha")
        pass.setText(store.getCardDavPassword())
        listOf(server, user, pass).forEach { card.addView(it, NexusUi.block()) }
        card.addView(BusTheme.gap(this, 8))

        val save = NexusUi.pillButton(this, "Salvar credenciais", true)
        save.setOnClickListener {
            if (server.text.isBlank() || user.text.isBlank() || pass.text.isBlank()) {
                toast("Preencha servidor, usuario e senha"); return@setOnClickListener
            }
            // A credential change invalidates the cached collection/sync-token.
            store.setCardDavCollection(""); store.setCardDavSyncToken("")
            store.setCardDavCredentials(server.text.toString(), user.text.toString(), pass.text.toString())
            toast("Credenciais salvas")
        }
        card.addView(save, NexusUi.block())
        card.addView(BusTheme.gap(this, 8))

        val syncBtn = NexusUi.pillButton(this, if (davSyncing) "Sincronizando..." else "Sincronizar agora", false)
        syncBtn.isEnabled = !davSyncing
        syncBtn.setOnClickListener {
            if (davSyncing) return@setOnClickListener
            if (server.text.isBlank() || user.text.isBlank() || pass.text.isBlank()) {
                toast("Preencha e salve as credenciais primeiro"); return@setOnClickListener
            }
            store.setCardDavCredentials(server.text.toString(), user.text.toString(), pass.text.toString())
            startCardDavSync()
        }
        card.addView(syncBtn, NexusUi.block())

        card.addView(BusTheme.gap(this, 8))
        card.addView(NexusUi.rowSub(this, cardDavStatus()), NexusUi.block())

        if (store.hasCardDav()) {
            card.addView(BusTheme.gap(this, 8))
            val clear = NexusUi.textButton(this, "Desconectar agenda", true)
            clear.setOnClickListener {
                ContactDirectory(this).clearDirectory()
                store.clearCardDav()
                toast("Agenda desconectada")
                content.post { render() }
            }
            card.addView(clear, NexusUi.block())
        }
        addView(card)
    }

    private fun startCardDavSync() {
        davSyncing = true
        content.post { render() }
        Thread {
            val result = runCatching {
                ContactDirectory(this).sync(
                    store.getCardDavServer(), store.getCardDavUser(), store.getCardDavPassword(),
                )
            }
            runOnUiThread {
                davSyncing = false
                result
                    .onSuccess { toast(it.message) }
                    .onFailure { toast("Falha ao sincronizar: ${it.message?.take(140)}") }
                render()
            }
        }.start()
    }

    private fun cardDavStatus(): String {
        if (!store.hasCardDav()) return "Nao configurado."
        val last = store.getCardDavLastSync()
        return if (last <= 0) {
            "Credenciais salvas. Toque em Sincronizar agora."
        } else {
            val when_ = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(last))
            val count = ContactDirectory(this).contactCount
            "Ultima sincronizacao: $when_ · $count contatos"
        }
    }

    /* ---------------- quick replies ---------------- */

    private fun renderQuickMessages() {
        addView(NexusUi.sectionRow(this, "Respostas rapidas"))
        gap(10)
        val card = NexusUi.card(this)
        card.addView(NexusUi.cardBody(this, "Uma por linha, no formato Titulo=Mensagem."), NexusUi.block())
        val field = field("Titulo=Mensagem")
        field.setSingleLine(false)
        field.minLines = 4
        field.setText(store.getQuickMessages().joinToString("\n") { "${it.title}=${it.body}" })
        card.addView(field, NexusUi.block())
        card.addView(BusTheme.gap(this, 8))
        val save = NexusUi.pillButton(this, "Salvar respostas", true)
        save.setOnClickListener {
            val list = field.text.toString().lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val title = line.substring(0, i).trim()
                val body = line.substring(i + 1).trim()
                if (title.isBlank() || body.isBlank()) null else QuickMessage(title, body)
            }
            store.setQuickMessages(list)
            toast("Respostas salvas (${list.size})")
        }
        card.addView(save, NexusUi.block())
        addView(card)
    }

    /* ---------------- helpers ---------------- */

    private fun field(hint: String): EditText = NexusUi.field(this, hint)

    private fun addButton(onClick: () -> Unit): View =
        NexusUi.pillButton(this, "Adicionar", false).also { it.setOnClickListener { onClick() } }

    private fun addView(v: View) = content.addView(v, NexusUi.block())
    private fun gap(dp: Int) = content.addView(BusTheme.gap(this, dp))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Read the versionName from the package so the header never drifts from the build. */
    private fun appVersion(): String =
        runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "?" }

    private fun kindLabel(kind: ChannelKind): String = when (kind) {
        ChannelKind.WHATSAPP -> "WhatsApp"
        ChannelKind.TELEGRAM -> "Telegram"
        ChannelKind.GMAIL -> "Gmail (leitura)"
        ChannelKind.GITHUB -> "GitHub PRs (leitura)"
    }
}
