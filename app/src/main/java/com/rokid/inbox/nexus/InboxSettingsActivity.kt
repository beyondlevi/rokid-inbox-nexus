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
                    "Inbox multi-canal · v1.0",
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

    private fun kindLabel(kind: ChannelKind): String = when (kind) {
        ChannelKind.WHATSAPP -> "WhatsApp"
        ChannelKind.TELEGRAM -> "Telegram"
        ChannelKind.GMAIL -> "Gmail (leitura)"
        ChannelKind.GITHUB -> "GitHub PRs (leitura)"
    }
}
