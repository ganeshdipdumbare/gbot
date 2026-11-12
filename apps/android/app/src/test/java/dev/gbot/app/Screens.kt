package dev.gbot.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.ui.GbotTheme
import dev.gbot.app.ui.chat.ChatScreen
import dev.gbot.app.ui.screens.BotSettingsScreen
import dev.gbot.app.ui.screens.ComputerScreen
import dev.gbot.app.ui.screens.HatchScreen
import dev.gbot.app.ui.screens.HomeScreen
import dev.gbot.app.ui.screens.LoginScreen
import dev.gbot.app.ui.settings.SettingsHome
import dev.gbot.app.ui.settings.SettingsTab
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Renders the main screens against a fake gbot server and saves them as
 * PNGs in build/shots, so the design can be reviewed without a phone. It
 * also exercises the real JSON decoding (e.g. null model capabilities).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class Screens {
    @get:Rule val rule = createComposeRule()
    private val server = MockWebServer()

    private fun ago(min: Long) = Instant.now().minusSeconds(min * 60).toString()

    private val routes: Map<String, String> by lazy {
        mapOf(
            "/api/me" to """{"id":"u1","email":"ganesh@example.com","name":"Ganesh D","defaultModel":"mp1:gemini-2.5-flash","approvalMode":"auto","spaceId":"s1"}""",
            "/api/setup" to """{"fresh":false,"signup":true}""",
            "/api/bots" to """{"bots":[
              {"id":"b1","name":"Pixel","title":"Research","look":{"seed":"pixel"},"pinned":true,"createdAt":"${ago(9000)}","lastMessage":{"id":"m9","botId":"b1","role":"bot","content":"Here are the three flights under €200 for Friday.","createdAt":"${ago(3)}"}},
              {"id":"b2","name":"Nori","title":"Inbox helper","look":{"shape":"cloud","hue":"mint","eyes":"dot"},"createdAt":"${ago(9000)}","lastMessage":{"id":"m8","botId":"b2","role":"bot","content":"**Done.** I archived 42 newsletters.","createdAt":"${ago(90)}"}},
              {"id":"b3","name":"Juno","title":"Code reviews","look":{"shape":"hexagon","hue":"coral","eyes":"wide"},"createdAt":"${ago(9000)}","lastMessage":{"id":"m7","botId":"b3","role":"user","content":"Review PR 214 please","createdAt":"${ago(400)}"}},
              {"id":"b4","name":"Otto","title":"","look":{"shape":"arch","hue":"sun","eyes":"pill"},"createdAt":"${ago(9000)}"}
            ],"presence":{"b3":{"state":"working","detail":"Reading the diff"},"b2":{"state":"waiting"}}}""",
            "/api/groups" to """{"groups":[{"id":"g1","name":"Launch team","botIds":["b1","b2","b3"],"createdAt":"${ago(9000)}","lastMessage":{"id":"gm1","groupId":"g1","botId":"b1","role":"bot","content":"Juno, can you check the copy?","createdAt":"${ago(30)}"}}]}""",
            "/api/models" to """{"models":[
              {"id":"ollama:lfm3-omni","label":"lfm3-omni","provider":"ollama","local":true,"tools":true,"caps":["tools","vision"],"best":true},
              {"id":"mp1:gemini-2.5-flash","label":"gemini-2.5-flash","provider":"Google AI Studio","local":false,"tools":true,"caps":["tools","vision","reasoning"]},
              {"id":"mp1:gemma-3-27b-it","label":"gemma-3-27b-it","provider":"Google AI Studio","local":false,"tools":false,"caps":null}
            ],"ollamaHost":"http://127.0.0.1:11434"}""",
            "/api/providers" to """{"providers":[
              {"id":"p1","name":"Google AI Studio","kind":"gemini","baseUrl":"https://generativelanguage.googleapis.com/v1beta","hasKey":true,"local":false,
               "models":["gemini-2.5-flash","gemini-2.5-pro","gemma-3-27b-it"],"caps":{"gemini-2.5-flash":["tools","vision","reasoning"],"gemini-2.5-pro":["tools","vision","reasoning"],"gemma-3-27b-it":null},
               "enabled":true,"status":"connected","createdAt":"${ago(100)}"},
              {"id":"p2","name":"My GPU box","kind":"openai","baseUrl":"http://localhost:1234/v1","hasKey":false,"local":true,"models":[],"caps":{},"enabled":false,"status":"off","createdAt":"${ago(100)}"}
            ],"codex":{"available":true,"allowed":true}}""",
            "/api/bots/b1/messages" to """{"messages":[
              {"id":"m1","botId":"b1","role":"user","content":"Find me flights to Lisbon on Friday under €200","createdAt":"${ago(6)}"},
              {"id":"m2","botId":"b1","role":"event","content":"","data":{"kind":"activity","steps":[{"label":"Searched","detail":"flights Berlin Lisbon Friday","ok":true},{"label":"Opened","detail":"google.com/travel/flights","ok":true},{"label":"Read","detail":"3 results","ok":true}]},"createdAt":"${ago(5)}"},
              {"id":"m3","botId":"b1","role":"bot","content":"Here are the **three flights** under €200:\n\n1. **TAP 541** — 07:10 → 09:15, €149\n2. **easyJet 4410** — 12:30 → 14:40, €172\n3. **Ryanair 8812** — 18:05 → 20:10, €119\n\n| Airline | Price |\n|---|---|\n| Ryanair | €119 |\n| TAP | €149 |\n\nWant me to hold one? `booking` needs your OK.","createdAt":"${ago(4)}"},
              {"id":"m4","botId":"b1","role":"card","content":"Open the booking page for Ryanair 8812","data":{"kind":"approval","status":"pending","tool":"browser_open","args":{"url":"https://www.ryanair.com/booking"}},"createdAt":"${ago(3)}"}
            ]}""",
            "/api/bots/b1/queue" to """{"items":[]}""",
            "/api/bots/b1/computer" to """{"state":"asleep","controller":"bot","network":true,"available":true,"backend":"microsandbox"}""",
            "/api/bots/b1/routines" to """{"routines":[{"id":"r1","botId":"b1","name":"Morning briefing","prompt":"Summarize","kind":"schedule","when":"Weekdays at 8:00","active":true}]}""",
            "/api/bots/b1/sharing" to """{"visibility":"private","members":[],"spaceMembers":[]}""",
            "/api/groups/g1/messages" to """{"messages":[{"id":"gm0","groupId":"g1","role":"user","content":"Plan the launch post","createdAt":"${ago(32)}"},{"id":"gm1","groupId":"g1","botId":"b1","role":"bot","content":"Juno, can you check the copy?","createdAt":"${ago(30)}"}]}""",
            "/api/groups/g1/queue" to """{"items":[]}""",
            "/api/bots/b4/messages" to """{"messages":[
              {"id":"o1","botId":"b4","role":"user","content":"Get Nori and Juno's take on the launch, and let Pixel know it's Friday","createdAt":"${ago(8)}"},
              {"id":"o2","botId":"b4","role":"event","content":"Messaged Nori","data":{"kind":"botmsg","ask":"ak_1","from":"b4","to":"b2","message":"Anything in the inbox that blocks a Friday launch?","expect":"ask","round":"rd_1","root":"rt_1","depth":1,"status":"replied","reply":"Nothing blocking. Two customers asked for the date — I'll reply once it's set.","joined":true,"tookMs":41000},"createdAt":"${ago(7)}"},
              {"id":"o3","botId":"b4","role":"event","content":"Messaged Juno","data":{"kind":"botmsg","ask":"ak_2","from":"b4","to":"b3","message":"Is PR 214 safe to ship Friday?","expect":"ask","round":"rd_1","root":"rt_1","depth":1,"status":"replied","reply":"**Yes**, after one fix: the migration needs an index. I left a comment.","joined":true,"tookMs":41000},"createdAt":"${ago(7)}"},
              {"id":"o4","botId":"b4","role":"event","content":"Messaged Pixel","data":{"kind":"botmsg","ask":"ak_3","from":"b4","to":"b1","message":"Launch is Friday.","expect":"tell","root":"rt_1","depth":1,"status":"delivered"},"createdAt":"${ago(7)}"},
              {"id":"o5","botId":"b4","role":"bot","content":"Friday works. Nori sees nothing blocking in the inbox, and Juno says PR 214 is safe once the migration gets an index. I let Pixel know.","createdAt":"${ago(6)}"},
              {"id":"o6","botId":"b4","role":"user","content":"Ask them both for a one-line launch tweet","createdAt":"${ago(2)}"},
              {"id":"o7","botId":"b4","role":"event","content":"Messaged Nori","data":{"kind":"botmsg","ask":"ak_4","from":"b4","to":"b2","message":"One-line launch tweet?","expect":"ask","round":"rd_2","root":"rt_2","depth":1,"status":"replied","reply":"Ship day: gbot bots now work as a team."},"createdAt":"${ago(1)}"},
              {"id":"o8","botId":"b4","role":"event","content":"Messaged Juno","data":{"kind":"botmsg","ask":"ak_5","from":"b4","to":"b3","message":"One-line launch tweet?","expect":"ask","round":"rd_2","root":"rt_2","depth":1,"status":"working"},"createdAt":"${ago(1)}"}
            ]}""",
            "/api/bots/b4/queue" to """{"items":[]}""",
            "/api/bots/b4/computer" to """{"state":"asleep","controller":"bot","network":true,"available":true,"backend":"microsandbox"}""",
            "/api/settings/rules" to """{"rules":[{"tool":"browser_open","rule":"allow"}]}""",
            "/api/mcp" to """{"servers":[{"id":"x1","name":"Notion","url":"https://mcp.notion.com/mcp","oauth":true,"enabled":true,"status":"connected","tools":["m1234abcd__search","m1234abcd__create_page"]}]}""",
            "/api/slack" to """{"eventUrl":"https://bots.example.com/slack/events","installs":[
              {"id":"s1","teamId":"T0123ABC","teamName":"Acme","createdAt":"${ago(500)}","links":[
                {"installId":"s1","channelId":"C0123ABC","channel":"build","botId":"b3","createdAt":"${ago(400)}"}
              ]}
            ]}""",
            "/api/voice" to """{"transcribe":false,"speak":false}""",
            "/api/skills" to """{"skills":[]}""",
        )
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = routes[path] ?: return MockResponse.Builder().code(404).body("""{"error":"not found"}""").build()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        val app = GbotApp.instance
        app.http.server = server.url("/").toString().trimEnd('/')
        runBlocking {
            app.store.setMe(app.api.me())
            app.store.loadAll()
            app.store.loadModels()
        }
    }

    @After fun tearDown() { server.close() }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { GbotTheme { content() } }
        repeat(6) { rule.waitForIdle(); Thread.sleep(150) }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shot("home") { HomeScreen(rememberNavController()) }
    @Test fun chat() = shot("chat") { ChatScreen(rememberNavController(), "b1") }
    @Test fun group() = shot("group") { ChatScreen(rememberNavController(), "g:g1") }
    @Test fun botSettings() = shot("bot-settings") { BotSettingsScreen(rememberNavController(), "b1") }
    @Test fun models() = shot("models") { SettingsTab(rememberNavController(), "models") }
    @Test fun settings() = shot("settings") { SettingsHome(rememberNavController()) }
    @Test fun general() = shot("general") { SettingsTab(rememberNavController(), "general") }
    @Test fun integrations() = shot("integrations") { SettingsTab(rememberNavController(), "integrations") }
    @Test fun computer() = shot("computer") { ComputerScreen(rememberNavController(), "b1", false) }
    @Test fun hatch() = shot("hatch") { HatchScreen(rememberNavController()) }
    @Test fun login() = shot("login") { LoginScreen({}, {}) }
    @Test fun appearance() = shot("appearance") { SettingsTab(rememberNavController(), "appearance") }
    @Test fun slack() = shot("slack") { SettingsTab(rememberNavController(), "slack") }
    @Test fun asks() = shot("asks") { ChatScreen(rememberNavController(), "b4") }

    /** Clearing a chat asks first, and offers to forget what the bot learned too. */
    @Test fun clearChat() {
        shot("bot-settings-clear") { BotSettingsScreen(rememberNavController(), "b1") }
        rule.onNodeWithText("Clear chat history", useUnmergedTree = true).performScrollTo().performClick()
        repeat(4) { rule.waitForIdle(); Thread.sleep(150) }
        val bmp = rule.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap()
        File(File("build/shots").apply { mkdirs() }, "clear-chat.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A round's popup: every question and answer in one place. */
    @Test fun roundPopup() {
        shot("asks-before-popup") { ChatScreen(rememberNavController(), "b4") }
        rule.onAllNodesWithText("Asked Nori and Juno", useUnmergedTree = true)[0].performClick()
        repeat(4) { rule.waitForIdle(); Thread.sleep(150) }
        val bmp = rule.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap()
        File(File("build/shots").apply { mkdirs() }, "asks-popup.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The same roster with the theme forced, not left to the host's setting. */
    @Test fun homeDark() {
        GbotApp.instance.setTheme("dark")
        try {
            shot("home-dark") { HomeScreen(rememberNavController()) }
        } finally {
            GbotApp.instance.setTheme("system")
        }
    }
}
