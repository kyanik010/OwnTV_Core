#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>
#include <mutex>
#include <thread>
#include <atomic>
#include <string>
#include <algorithm>
#include <chrono>
#include <cstdlib>

#define TAG "OwnTV-GstAudioMix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct AudioMixState {
    GstElement* pipeline = nullptr;
    GstElement* videoSource = nullptr;
    GstElement* audioSource = nullptr;
    GstElement* multiQueue = nullptr;
    GstElement* videoQueue = nullptr;
    GstElement* audioQueue = nullptr;
    GstElement* videoSink = nullptr;
    GstElement* audioConvert = nullptr;
    GstElement* audioResample = nullptr;
    GstElement* audioTempo = nullptr;
    GstElement* audioSink = nullptr;
    GstPad* videoMultiSink = nullptr;
    GstPad* audioMultiSink = nullptr;
    GstPad* videoMultiSrc = nullptr;
    GstPad* audioMultiSrc = nullptr;
    GstBus* bus = nullptr;
    GstClock* clock = nullptr;
    ANativeWindow* window = nullptr;
    std::thread busThread;
    std::thread syncThread;
    std::mutex mutex;
    std::atomic<bool> syncRunning{false};
    std::atomic<gint64> videoPts{GST_CLOCK_TIME_NONE};
    std::atomic<gint64> audioPts{GST_CLOCK_TIME_NONE};
    gint64 audioTsOffset = 0;
    gint64 targetAudioTsOffset = 0;
    bool syncPrimed = false;
    std::string userAgent;
    std::atomic<bool> running{false};
    double audioRate = 1.0;
    gint64 audioDelayNs = 0;
    gint64 manualDelayNs = 0;
};

static AudioMixState g_state;

static bool is_media_type(GstPad* pad, const char* prefix) {
    GstCaps* caps = gst_pad_get_current_caps(pad);
    if (!caps) caps = gst_pad_query_caps(pad, nullptr);
    if (!caps || gst_caps_is_empty(caps)) {
        if (caps) gst_caps_unref(caps);
        return false;
    }
    const GstStructure* s = gst_caps_get_structure(caps, 0);
    const char* name = gst_structure_get_name(s);
    const bool match = name && g_str_has_prefix(name, prefix);
    gst_caps_unref(caps);
    return match;
}

static GstPadProbeReturn pts_probe(GstPad*, GstPadProbeInfo* info, gpointer userData) {
    if (!(GST_PAD_PROBE_INFO_TYPE(info) & GST_PAD_PROBE_TYPE_BUFFER)) return GST_PAD_PROBE_OK;
    GstBuffer* buffer = GST_PAD_PROBE_INFO_BUFFER(info);
    if (!buffer) return GST_PAD_PROBE_OK;
    const GstClockTime pts = GST_BUFFER_PTS(buffer);
    if (!GST_CLOCK_TIME_IS_VALID(pts)) return GST_PAD_PROBE_OK;
    if (GPOINTER_TO_INT(userData) == 1) g_state.videoPts.store((gint64) pts);
    else g_state.audioPts.store((gint64) pts);
    return GST_PAD_PROBE_OK;
}

static void configure_http_source(GstElement*, GstElement* source, gpointer) {
    if (g_state.userAgent.empty()) return;
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "user-agent")) {
        g_object_set(source, "user-agent", g_state.userAgent.c_str(), nullptr);
    }
}

static void link_dynamic_pad(GstElement* source, GstPad* pad, gpointer userData) {
    const bool video = GPOINTER_TO_INT(userData) == 1;
    if (video && !is_media_type(pad, "video/")) return;
    if (!video && !is_media_type(pad, "audio/")) return;

    std::lock_guard<std::mutex> lock(g_state.mutex);
    GstPad* target = video ? g_state.videoMultiSink : g_state.audioMultiSink;
    if (!target || GST_PAD_IS_LINKED(target)) return;

    if (gst_pad_link(pad, target) == GST_PAD_LINK_OK) {
        gst_pad_add_probe(pad, GST_PAD_PROBE_TYPE_BUFFER, pts_probe, GINT_TO_POINTER(video ? 1 : 0), nullptr);
        LOGI("linked %s source pad into multiqueue", video ? "video" : "audio");
    } else {
        LOGE("failed to link %s source pad", video ? "video" : "audio");
    }
}

static void bus_loop() {
    while (g_state.running.load()) {
        GstMessage* msg = gst_bus_timed_pop_filtered(
            g_state.bus,
            200 * GST_MSECOND,
            static_cast<GstMessageType>(
                GST_MESSAGE_ERROR |
                GST_MESSAGE_WARNING |
                GST_MESSAGE_EOS |
                GST_MESSAGE_BUFFERING |
                GST_MESSAGE_STATE_CHANGED |
                GST_MESSAGE_CLOCK_LOST |
                GST_MESSAGE_NEW_CLOCK
            )
        );
        if (!msg) continue;

        switch (GST_MESSAGE_TYPE(msg)) {
            case GST_MESSAGE_ERROR: {
                GError* error = nullptr;
                gchar* debug = nullptr;
                gst_message_parse_error(msg, &error, &debug);
                LOGE("GStreamer ERROR: %s | %s",
                     error ? error->message : "unknown",
                     debug ? debug : "no-debug");
                g_clear_error(&error);
                g_free(debug);
                break;
            }
            case GST_MESSAGE_WARNING: {
                GError* error = nullptr;
                gchar* debug = nullptr;
                gst_message_parse_warning(msg, &error, &debug);
                LOGW("GStreamer WARNING: %s | %s",
                     error ? error->message : "unknown",
                     debug ? debug : "no-debug");
                g_clear_error(&error);
                g_free(debug);
                break;
            }
            case GST_MESSAGE_BUFFERING: {
                gint percent = 0;
                gst_message_parse_buffering(msg, &percent);
                LOGI("AudioMix buffering=%d%%", percent);
                break;
            }
            case GST_MESSAGE_CLOCK_LOST:
                LOGW("AudioMix clock lost; restarting pipeline clock selection");
                gst_element_set_state(g_state.pipeline, GST_STATE_PAUSED);
                gst_element_set_state(g_state.pipeline, GST_STATE_PLAYING);
                break;
            case GST_MESSAGE_NEW_CLOCK:
                LOGI("AudioMix selected a new global GstClock");
                break;
            case GST_MESSAGE_EOS:
                LOGW("AudioMix received EOS");
                break;
            default:
                break;
        }
        gst_message_unref(msg);
    }
}

static void sync_loop() {
    while (g_state.syncRunning.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        const gint64 v = g_state.videoPts.load();
        const gint64 a = g_state.audioPts.load();
        if (!GST_CLOCK_TIME_IS_VALID(v) || !GST_CLOCK_TIME_IS_VALID(a)) continue;
        const gint64 rawDelta = v - a;
        if (std::llabs(rawDelta) > (gint64)(10 * GST_SECOND)) continue;
        std::unique_lock<std::mutex> lock(g_state.mutex, std::try_to_lock);
        if (!lock.owns_lock()) continue;
        if (!g_state.syncRunning.load() || !g_state.pipeline || !g_state.audioSink) continue;
        g_state.targetAudioTsOffset = rawDelta;
        if (!g_state.syncPrimed) {
            g_state.audioTsOffset = rawDelta;
            g_state.syncPrimed = true;
        } else {
            const gint64 error = g_state.targetAudioTsOffset - g_state.audioTsOffset;
            const gint64 step = std::clamp(error, (gint64)(-20 * GST_MSECOND), (gint64)(20 * GST_MSECOND));
            if (std::llabs(error) > (gint64)(5 * GST_MSECOND)) g_state.audioTsOffset += step;
        }
        if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_state.audioSink), "ts-offset"))
            g_object_set(g_state.audioSink, "ts-offset", g_state.audioTsOffset + g_state.manualDelayNs, nullptr);
        if (g_state.audioTempo) {
            const double correction = std::clamp(-((double)(g_state.targetAudioTsOffset - g_state.audioTsOffset) / 20000000.0), -0.0015, 0.0015);
            g_object_set(g_state.audioTempo, "tempo", 1.0 + correction, nullptr);
        }
    }
}

static void clear_state_locked() {
    if (g_state.pipeline) {
        gst_element_set_state(g_state.pipeline, GST_STATE_NULL);
    }
    if (g_state.busThread.joinable()) {
        g_state.running.store(false);
        g_state.busThread.join();
    }
    if (g_state.syncThread.joinable()) {
        g_state.syncRunning.store(false);
        g_state.syncThread.join();
    }
    if (g_state.bus) {
        gst_object_unref(g_state.bus);
        g_state.bus = nullptr;
    }
    if (g_state.clock) {
        gst_object_unref(g_state.clock);
        g_state.clock = nullptr;
    }
    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = nullptr;
    }
    g_state.pipeline = nullptr;
    g_state.videoSource = nullptr;
    g_state.audioSource = nullptr;
    g_state.multiQueue = nullptr;
    g_state.videoQueue = nullptr;
    g_state.audioQueue = nullptr;
    g_state.videoSink = nullptr;
    g_state.audioConvert = nullptr;
    g_state.audioResample = nullptr;
    g_state.audioTempo = nullptr;
    g_state.audioSink = nullptr;
    g_state.videoMultiSink = nullptr;
    g_state.audioMultiSink = nullptr;
    g_state.videoMultiSrc = nullptr;
    g_state.audioMultiSrc = nullptr;
    g_state.videoPts.store(GST_CLOCK_TIME_NONE);
    g_state.audioPts.store(GST_CLOCK_TIME_NONE);
    g_state.audioTsOffset = 0;
    g_state.targetAudioTsOffset = 0;
    g_state.syncPrimed = false;
    g_state.userAgent.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_tv_own_owntv_player_GStreamerAudioMixEngine_nativeInit(JNIEnv*, jobject) {
    static std::once_flag once;
    std::call_once(once, [] {
        gst_init(nullptr, nullptr);
        LOGI("GStreamer initialized: %s", gst_version_string());
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_tv_own_owntv_player_GStreamerAudioMixEngine_nativeStart(
    JNIEnv* env, jobject, jobject surface, jstring videoUrl, jstring audioUrl, jstring userAgent) {

    const char* video = env->GetStringUTFChars(videoUrl, nullptr);
    const char* audio = env->GetStringUTFChars(audioUrl, nullptr);
    const char* ua = userAgent ? env->GetStringUTFChars(userAgent, nullptr) : nullptr;
    if (!video || !audio || (userAgent && !ua)) {
        if (video) env->ReleaseStringUTFChars(videoUrl, video);
        if (audio) env->ReleaseStringUTFChars(audioUrl, audio);
        if (ua) env->ReleaseStringUTFChars(userAgent, ua);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_state.mutex);
    clear_state_locked();
    if (ua) g_state.userAgent = ua;

    g_state.window = ANativeWindow_fromSurface(env, surface);
    if (!g_state.window) {
        LOGE("Unable to obtain ANativeWindow");
        env->ReleaseStringUTFChars(videoUrl, video);
        env->ReleaseStringUTFChars(audioUrl, audio);
        return JNI_FALSE;
    }

    g_state.pipeline = gst_pipeline_new("owntv-audiomix");
    g_state.videoSource = gst_element_factory_make("uridecodebin", "video-source");
    g_state.audioSource = gst_element_factory_make("uridecodebin", "audio-source");
    g_state.multiQueue = gst_element_factory_make("multiqueue", "audiomix-multiqueue");
    g_state.videoQueue = gst_element_factory_make("queue2", "video-queue");
    g_state.audioQueue = gst_element_factory_make("queue2", "audio-queue");
    g_state.videoSink = gst_element_factory_make("glimagesink", "video-sink");
    g_state.audioConvert = gst_element_factory_make("audioconvert", "audio-convert");
    g_state.audioResample = gst_element_factory_make("audioresample", "audio-resample");
    g_state.audioTempo = gst_element_factory_make("pitch", "audio-tempo");
    g_state.audioSink = gst_element_factory_make("openslessink", "audio-sink");

    if (!g_state.pipeline || !g_state.videoSource || !g_state.audioSource ||
        !g_state.multiQueue || !g_state.videoQueue || !g_state.audioQueue ||
        !g_state.videoSink || !g_state.audioConvert || !g_state.audioResample ||
        !g_state.audioTempo || !g_state.audioSink) {
        LOGE("Required GStreamer element is missing");
        clear_state_locked();
        env->ReleaseStringUTFChars(videoUrl, video);
        env->ReleaseStringUTFChars(audioUrl, audio);
        return JNI_FALSE;
    }

    g_object_set(g_state.videoSource, "uri", video, nullptr);
    g_object_set(g_state.audioSource, "uri", audio, nullptr);
    g_signal_connect(g_state.videoSource, "source-setup", G_CALLBACK(configure_http_source), nullptr);
    g_signal_connect(g_state.audioSource, "source-setup", G_CALLBACK(configure_http_source), nullptr);

    // Network resilience: one shared multiqueue with a generous live window. The queue is not a
    // substitute for source recovery; it is the first line against short network jitter/starvation.
    g_object_set(
        g_state.multiQueue,
        "max-size-time", static_cast<guint64>(8 * GST_SECOND),
        "max-size-bytes", static_cast<guint>(32 * 1024 * 1024),
        "max-size-buffers", 0,
        "low-percent", 20,
        "high-percent", 80,
        "use-buffering", TRUE,
        "sync-by-running-time", TRUE,
        nullptr
    );
    g_object_set(g_state.videoQueue, "max-size-time", static_cast<guint64>(4 * GST_SECOND),
                 "use-buffering", TRUE, nullptr);
    g_object_set(g_state.audioQueue, "max-size-time", static_cast<guint64>(4 * GST_SECOND),
                 "use-buffering", TRUE, nullptr);

    g_object_set(g_state.videoSink, "sync", TRUE, "force-aspect-ratio", TRUE, nullptr);
    // Do not let the audio sink select a second independent timing domain. The pipeline owns the clock.
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_state.audioSink), "provide-clock")) {
        g_object_set(g_state.audioSink, "provide-clock", FALSE, nullptr);
    }

    g_state.audioRate = 1.0;
    g_object_set(g_state.audioTempo, "tempo", 1.0, "pitch", 1.0, nullptr);

    gst_bin_add_many(
        GST_BIN(g_state.pipeline),
        g_state.videoSource, g_state.audioSource, g_state.multiQueue,
        g_state.videoQueue, g_state.audioQueue,
        g_state.videoSink, g_state.audioConvert, g_state.audioResample,
        g_state.audioTempo, g_state.audioSink, nullptr
    );

    g_state.videoMultiSink = gst_element_request_pad_simple(g_state.multiQueue, "sink_%u");
    g_state.audioMultiSink = gst_element_request_pad_simple(g_state.multiQueue, "sink_%u");
    g_state.videoMultiSrc = gst_element_get_static_pad(g_state.multiQueue, "src_0");
    g_state.audioMultiSrc = gst_element_get_static_pad(g_state.multiQueue, "src_1");

    if (!g_state.videoMultiSink || !g_state.audioMultiSink ||
        !g_state.videoMultiSrc || !g_state.audioMultiSrc ||
        gst_pad_link(g_state.videoMultiSrc, gst_element_get_static_pad(g_state.videoQueue, "sink")) != GST_PAD_LINK_OK ||
        gst_pad_link(g_state.audioMultiSrc, gst_element_get_static_pad(g_state.audioQueue, "sink")) != GST_PAD_LINK_OK ||
        !gst_element_link_many(g_state.audioQueue, g_state.audioConvert, g_state.audioResample,
                               g_state.audioTempo, g_state.audioSink, nullptr) ||
        !gst_element_link(g_state.videoQueue, g_state.videoSink)) {
        LOGE("AudioMix pipeline linking failed");
        clear_state_locked();
        env->ReleaseStringUTFChars(videoUrl, video);
        env->ReleaseStringUTFChars(audioUrl, audio);
        return JNI_FALSE;
    }

    g_signal_connect(g_state.videoSource, "pad-added", G_CALLBACK(link_dynamic_pad), GINT_TO_POINTER(1));
    g_signal_connect(g_state.audioSource, "pad-added", G_CALLBACK(link_dynamic_pad), GINT_TO_POINTER(0));

    gst_video_overlay_set_window_handle(
        GST_VIDEO_OVERLAY(g_state.videoSink),
        reinterpret_cast<guintptr>(g_state.window)
    );

    // Explicit single clock: both live branches are driven from the same monotonic clock.
    g_state.clock = gst_system_clock_obtain();
    gst_pipeline_use_clock(GST_PIPELINE(g_state.pipeline), g_state.clock);

    g_state.bus = gst_element_get_bus(g_state.pipeline);
    g_state.running.store(true);
    g_state.busThread = std::thread(bus_loop);
    g_state.syncRunning.store(true);
    g_state.syncThread = std::thread(sync_loop);

    const GstStateChangeReturn ret =
        gst_element_set_state(g_state.pipeline, GST_STATE_PLAYING);

    env->ReleaseStringUTFChars(videoUrl, video);
    env->ReleaseStringUTFChars(audioUrl, audio);
    if (ua) env->ReleaseStringUTFChars(userAgent, ua);

    if (ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("AudioMix failed to enter PLAYING");
        clear_state_locked();
        return JNI_FALSE;
    }

    LOGI("AudioMix started: ONE pipeline / video source + audio source");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_tv_own_owntv_player_GStreamerAudioMixEngine_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    clear_state_locked();
}

extern "C" JNIEXPORT void JNICALL
Java_tv_own_owntv_player_GStreamerAudioMixEngine_nativeSetAudioRate(JNIEnv*, jobject, jdouble rate) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.audioTempo) return;
    g_state.audioRate = rate;
    // SoundTouch-backed pitch exposes writable tempo. The Sync Controller will move this only
    // inside a very small 0.997..1.003 window, preserving pitch while slowly correcting drift.
    g_object_set(g_state.audioTempo, "tempo", rate, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_tv_own_owntv_player_GStreamerAudioMixEngine_nativeSetAudioDelay(JNIEnv*, jobject, jlong delayMs) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.audioSink) return;
    g_state.audioDelayNs = static_cast<gint64>(delayMs) * GST_MSECOND;
    g_state.manualDelayNs = g_state.audioDelayNs;
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_state.audioSink), "ts-offset")) {
        g_object_set(g_state.audioSink, "ts-offset", g_state.audioTsOffset + g_state.manualDelayNs, nullptr);
    }
}
