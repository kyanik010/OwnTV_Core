from pathlib import Path
import subprocess

PLAYER_PATH = "player-core/src/main/java/tv/own/owntv/player/OwnTVPlayer.kt"
ENGINE_PATH = "player-core/src/main/java/tv/own/owntv/player/PlaybackEngine.kt"


def classify_technical_literals() -> None:
    literals = [
        "aid",
        "http-header-fields",
        "user-agent",
        "track-list/count",
        "track-list/$it/id",
        "track-list/$i/type",
        "track-list/$i/id",
        "track-list/$i/external",
        "audio-add",
        "audio-remove",
        "audio",
        "select",
        "no",
    ]
    for text in literals:
        subprocess.run(
            [
                "python",
                "tools/i18n/check_hardcoded_strings.py",
                "classify-safe",
                "--path",
                PLAYER_PATH,
                "--text",
                text,
                "--category",
                "technical",
            ],
            check=True,
        )


def patch_player() -> bool:
    path = Path(PLAYER_PATH)
    source = path.read_text()
    changed = False

    if "private val _audioMixEnabled" not in source:
        marker = "private var audioDelaySec = 0.0"
        if marker not in source:
            raise SystemExit("audio delay marker not found")
        fields = """private val _audioMixEnabled = MutableStateFlow(false)
    val audioMixEnabled: StateFlow<Boolean> = _audioMixEnabled.asStateFlow()
    private var audioMixOriginalAid: Int? = null
    private var audioMixExternalAid: Int? = null

    """
        source = source.replace(marker, fields + marker, 1)
        changed = True

    if "fun audioMixEnable(" not in source:
        marker = "    fun selectAudio(mpvId: Int) {"
        if marker not in source:
            raise SystemExit("selectAudio marker not found")
        methods = """    fun audioMixEnable(url: String, headers: String? = null, userAgent: String? = null) {
        if (url.isBlank()) return
        mpvAsync {
            if (exoActive || !initialized) return@mpvAsync
            if (_audioMixEnabled.value) audioMixDisableInternal()
            val originalAid = getPropertyInt("aid")
            val originalHeaders = getPropertyString("http-header-fields") ?: ""
            val originalUserAgent = getPropertyString("user-agent") ?: ""
            val countBefore = getPropertyInt("track-list/count") ?: 0
            val idsBefore = (0 until countBefore).mapNotNull { getPropertyInt("track-list/$it/id") }.toSet()
            audioMixOriginalAid = originalAid
            if (headers != null) setPropertyString("http-header-fields", headers)
            if (!userAgent.isNullOrBlank()) setPropertyString("user-agent", userAgent)
            try {
                command(arrayOf("audio-add", url, "select", "", ""))
                var externalId: Int? = null
                repeat(50) {
                    delay(100)
                    val count = getPropertyInt("track-list/count") ?: 0
                    for (i in 0 until count) {
                        if (getPropertyString("track-list/$i/type") != "audio") continue
                        val id = getPropertyInt("track-list/$i/id") ?: continue
                        val external = getPropertyBoolean("track-list/$i/external") ?: false
                        if (external && id !in idsBefore) { externalId = id; break }
                    }
                    if (externalId != null) return@repeat
                }
                val id = externalId ?: return@mpvAsync
                audioMixExternalAid = id
                setPropertyInt("aid", id)
                _audioMixEnabled.value = true
            } finally {
                setPropertyString("http-header-fields", originalHeaders)
                setPropertyString("user-agent", originalUserAgent)
            }
        }
    }

    fun audioMixDisable() { mpvAsync { audioMixDisableInternal() } }

    private fun audioMixDisableInternal() {
        if (!initialized) return
        audioMixExternalAid?.let { command(arrayOf("audio-remove", it.toString())) }
        if (audioMixOriginalAid != null) setPropertyInt("aid", audioMixOriginalAid!!)
        else setPropertyString("aid", "no")
        audioMixExternalAid = null
        audioMixOriginalAid = null
        _audioMixEnabled.value = false
    }

"""
        source = source.replace(marker, methods + marker, 1)
        changed = True

    updated = source.replace("coerceIn(-5_000, 5_000)", "coerceIn(-10_000, 10_000)")
    if updated != source:
        changed = True
    path.write_text(updated)
    return changed


def patch_engine() -> bool:
    path = Path(ENGINE_PATH)
    source = path.read_text()
    changed = False

    if "val audioMixEnabled: StateFlow<Boolean>" not in source:
        marker = "    fun selectAudio(id: Int)"
        if marker not in source:
            raise SystemExit("PlaybackEngine selectAudio marker not found")
        api = """    val audioMixEnabled: StateFlow<Boolean> get() = FALSE_FLOW
    fun audioMixEnable(url: String, headers: String? = null, userAgent: String? = null) {}
    fun audioMixDisable() {}
"""
        source = source.replace(marker, api + marker, 1)
        changed = True

    if "override val audioMixEnabled" not in source:
        marker = "    override fun selectAudio(id: Int) = p.selectAudio(id)"
        if marker not in source:
            raise SystemExit("MpvPlaybackEngine selectAudio marker not found")
        delegate = """    override val audioMixEnabled get() = p.audioMixEnabled
    override fun audioMixEnable(url: String, headers: String?, userAgent: String?) = p.audioMixEnable(url, headers, userAgent)
    override fun audioMixDisable() = p.audioMixDisable()
"""
        source = source.replace(marker, delegate + marker, 1)
        changed = True

    path.write_text(source)
    return changed


player_changed = patch_player()
engine_changed = patch_engine()
if player_changed or engine_changed:
    classify_technical_literals()
print("AudioMix Core patch applied")
