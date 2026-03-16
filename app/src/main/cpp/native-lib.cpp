#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <android/log.h>
#include <map>
#include <mutex>
#include <vector>
#include <sstream>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <libavutil/display.h>
#include <libavutil/eval.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/opt.h>
}

#define LOG_TAG "FFmpegNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Context for faster scrubbing
struct VideoContext {
    AVFormatContext *fmt_ctx = nullptr;
    AVCodecContext *codec_ctx = nullptr;
    int video_stream_index = -1;
    SwsContext *sws_ctx = nullptr;
};

std::map<std::string, VideoContext*> context_cache;
std::mutex cache_mutex;

extern "C" JNIEXPORT jstring JNICALL
Java_com_droidhubworld_videopro_utils_FFmpegNative_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_droidhubworld_videopro_utils_FFmpegNative_getFFmpegVersion(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(av_version_info());
}

VideoContext* get_or_create_context(const char* path) {
    std::lock_guard<std::mutex> lock(cache_mutex);
    if (context_cache.find(path) != context_cache.end()) {
        return context_cache[path];
    }

    VideoContext* ctx = new VideoContext();
    if (avformat_open_input(&ctx->fmt_ctx, path, nullptr, nullptr) < 0) {
        delete ctx;
        return nullptr;
    }

    if (avformat_find_stream_info(ctx->fmt_ctx, nullptr) < 0) {
        avformat_close_input(&ctx->fmt_ctx);
        delete ctx;
        return nullptr;
    }

    for (int i = 0; i < ctx->fmt_ctx->nb_streams; i++) {
        if (ctx->fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            ctx->video_stream_index = i;
            break;
        }
    }

    if (ctx->video_stream_index == -1) {
        avformat_close_input(&ctx->fmt_ctx);
        delete ctx;
        return nullptr;
    }

    AVCodecParameters *codec_par = ctx->fmt_ctx->streams[ctx->video_stream_index]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(codec_par->codec_id);
    ctx->codec_ctx = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(ctx->codec_ctx, codec_par);
    avcodec_open2(ctx->codec_ctx, codec, nullptr);

    context_cache[path] = ctx;
    return ctx;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidhubworld_videopro_utils_FFmpegNative_extractFrame(JNIEnv *env, jobject thiz,
                                                                jstring video_path, jlong time_ms,
                                                                jobject bitmap) {
    const char *path = env->GetStringUTFChars(video_path, nullptr);
    VideoContext* ctx = get_or_create_context(path);

    if (!ctx) {
        env->ReleaseStringUTFChars(video_path, path);
        return JNI_FALSE;
    }

    // Seek to the requested time
    int64_t target_pts = (int64_t)(time_ms * (double)ctx->fmt_ctx->streams[ctx->video_stream_index]->time_base.den / (1000.0 * ctx->fmt_ctx->streams[ctx->video_stream_index]->time_base.num));

    av_seek_frame(ctx->fmt_ctx, ctx->video_stream_index, target_pts, AVSEEK_FLAG_BACKWARD);
    avcodec_flush_buffers(ctx->codec_ctx);

    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    bool frame_found = false;

    int attempts = 0;
    while (av_read_frame(ctx->fmt_ctx, packet) >= 0 && attempts < 50) {
        if (packet->stream_index == ctx->video_stream_index) {
            if (avcodec_send_packet(ctx->codec_ctx, packet) == 0) {
                if (avcodec_receive_frame(ctx->codec_ctx, frame) == 0) {
                    frame_found = true;
                    break;
                }
            }
        }
        av_packet_unref(packet);
        attempts++;
    }

    if (frame_found) {
        AndroidBitmapInfo info;
        void *pixels;
        AndroidBitmap_getInfo(env, bitmap, &info);
        AndroidBitmap_lockPixels(env, bitmap, &pixels);

        if (!ctx->sws_ctx) {
            ctx->sws_ctx = sws_getContext(frame->width, frame->height, (AVPixelFormat)frame->format,
                                                 info.width, info.height, AV_PIX_FMT_RGBA,
                                                 SWS_BILINEAR, nullptr, nullptr, nullptr);
        }

        uint8_t *dest[4] = {(uint8_t *)pixels, nullptr, nullptr, nullptr};
        int dest_linesize[4] = {(int)info.stride, 0, 0, 0};
        sws_scale(ctx->sws_ctx, frame->data, frame->linesize, 0, frame->height, dest, dest_linesize);

        AndroidBitmap_unlockPixels(env, bitmap);
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
    env->ReleaseStringUTFChars(video_path, path);

    return frame_found ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidhubworld_videopro_utils_FFmpegNative_trimVideo(JNIEnv *env, jobject thiz, jstring input_path, jstring output_path, jlong start_ms, jlong end_ms) {
    const char *in_filename = env->GetStringUTFChars(input_path, nullptr);
    const char *out_filename = env->GetStringUTFChars(output_path, nullptr);

    AVFormatContext *ifmt_ctx = nullptr;
    AVFormatContext *ofmt_ctx = nullptr;
    int ret;
    int *stream_mapping = nullptr;
    int stream_mapping_size = 0;
    int64_t *dts_start_offsets = nullptr;
    int64_t *pts_start_offsets = nullptr;
    bool *stream_offsets_set = nullptr;
    AVPacket *pkt = nullptr;

    if ((ret = avformat_open_input(&ifmt_ctx, in_filename, nullptr, nullptr)) < 0) {
        LOGE("Could not open source file %s", in_filename);
        goto end;
    }
    if ((ret = avformat_find_stream_info(ifmt_ctx, nullptr)) < 0) {
        LOGE("Could not find stream information");
        goto end;
    }

    avformat_alloc_output_context2(&ofmt_ctx, nullptr, nullptr, out_filename);
    if (!ofmt_ctx) {
        ret = AVERROR_UNKNOWN;
        goto end;
    }

    stream_mapping_size = ifmt_ctx->nb_streams;
    stream_mapping = (int *)av_calloc(stream_mapping_size, sizeof(*stream_mapping));
    if (!stream_mapping) {
        ret = AVERROR(ENOMEM);
        goto end;
    }

    {
        int stream_index = 0;
        for (int i = 0; i < stream_mapping_size; i++) {
            AVStream *in_stream = ifmt_ctx->streams[i];
            if (in_stream->codecpar->codec_type != AVMEDIA_TYPE_VIDEO &&
                in_stream->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
                stream_mapping[i] = -1;
                continue;
            }
            stream_mapping[i] = stream_index++;
            AVStream *out_stream = avformat_new_stream(ofmt_ctx, nullptr);
            if (!out_stream) {
                ret = AVERROR_UNKNOWN;
                goto end;
            }
            if ((ret = avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar)) < 0) {
                goto end;
            }
            out_stream->codecpar->codec_tag = 0;
        }
    }

    if (!(ofmt_ctx->oformat->flags & AVFMT_NOFILE)) {
        if ((ret = avio_open(&ofmt_ctx->pb, out_filename, AVIO_FLAG_WRITE)) < 0) {
            goto end;
        }
    }

    if ((ret = avformat_write_header(ofmt_ctx, nullptr)) < 0) {
        goto end;
    }

    if (start_ms > 0) {
        int64_t seek_target = start_ms * AV_TIME_BASE / 1000;
        ret = av_seek_frame(ifmt_ctx, -1, seek_target, AVSEEK_FLAG_BACKWARD);
    }

    dts_start_offsets = (int64_t *)av_calloc(stream_mapping_size, sizeof(int64_t));
    pts_start_offsets = (int64_t *)av_calloc(stream_mapping_size, sizeof(int64_t));
    stream_offsets_set = (bool *)av_calloc(stream_mapping_size, sizeof(bool));

    pkt = av_packet_alloc();
    while (av_read_frame(ifmt_ctx, pkt) >= 0) {
        AVStream *in_stream = ifmt_ctx->streams[pkt->stream_index];
        if (pkt->stream_index >= stream_mapping_size || stream_mapping[pkt->stream_index] < 0) {
            av_packet_unref(pkt);
            continue;
        }
        AVStream *out_stream = ofmt_ctx->streams[stream_mapping[pkt->stream_index]];

        int64_t current_pts_ms = pkt->pts * 1000 * in_stream->time_base.num / in_stream->time_base.den;
        if (end_ms > 0 && current_pts_ms > end_ms) {
            av_packet_unref(pkt);
            break;
        }

        if (!stream_offsets_set[pkt->stream_index]) {
            dts_start_offsets[pkt->stream_index] = pkt->dts;
            pts_start_offsets[pkt->stream_index] = pkt->pts;
            stream_offsets_set[pkt->stream_index] = true;
        }

        pkt->pts -= pts_start_offsets[pkt->stream_index];
        pkt->dts -= dts_start_offsets[pkt->stream_index];

        pkt->pts = av_rescale_q_rnd(pkt->pts, in_stream->time_base, out_stream->time_base, (AVRounding)(AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX));
        pkt->dts = av_rescale_q_rnd(pkt->dts, in_stream->time_base, out_stream->time_base, (AVRounding)(AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX));
        pkt->duration = av_rescale_q(pkt->duration, in_stream->time_base, out_stream->time_base);
        pkt->pos = -1;
        pkt->stream_index = stream_mapping[pkt->stream_index];

        av_interleaved_write_frame(ofmt_ctx, pkt);
        av_packet_unref(pkt);
    }
    av_write_trailer(ofmt_ctx);

end:
    if (pkt) av_packet_free(&pkt);
    if (dts_start_offsets) av_freep(&dts_start_offsets);
    if (pts_start_offsets) av_freep(&pts_start_offsets);
    if (stream_offsets_set) av_freep(&stream_offsets_set);

    if (ifmt_ctx) avformat_close_input(&ifmt_ctx);
    if (ofmt_ctx && !(ofmt_ctx->oformat->flags & AVFMT_NOFILE)) avio_closep(&ofmt_ctx->pb);
    if (ofmt_ctx) avformat_free_context(ofmt_ctx);
    if (stream_mapping) av_freep(&stream_mapping);

    env->ReleaseStringUTFChars(input_path, in_filename);
    env->ReleaseStringUTFChars(output_path, out_filename);

    return ret >= 0 || ret == AVERROR_EOF ? JNI_TRUE : JNI_FALSE;
}

// Simplified function to apply transitions by building a complex filter string
// and assuming the caller will use a full transcoding loop.
// For now, we return the filter string in logs to verify it\u0027s being built correctly.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidhubworld_videopro_utils_FFmpegNative_exportWithTransitions(JNIEnv *env, jobject thiz,
                                                                         jobjectArray input_paths,
                                                                         jstring output_path,
                                                                         jlongArray trim_starts,
                                                                         jlongArray trim_ends,
                                                                         jobjectArray transitions,
                                                                         jlongArray transition_durations) {
    int input_count = env->GetArrayLength(input_paths);

    std::stringstream filter;
    jlong *starts = env->GetLongArrayElements(trim_starts, nullptr);
    jlong *ends = env->GetLongArrayElements(trim_ends, nullptr);
    jlong *trans_durs = env->GetLongArrayElements(transition_durations, nullptr);

    // Build complex filter string for xfade
    // [0:v]trim=start=S0:end=E0,setpts=PTS-STARTPTS[v0];
    for (int i = 0; i < input_count; ++i) {
        filter << "[" << i << ":v]trim=start=" << (starts[i]/1000.0) << ":end=" << (ends[i]/1000.0)
               << ",setpts=PTS-STARTPTS,format=yuv420p[v" << i << "];";
    }

    double current_offset = (ends[0] - starts[0]) / 1000.0;
    std::string last_v = "v0";

    for (int i = 0; i < input_count - 1; ++i) {
        jstring trans_type_js = (jstring)env->GetObjectArrayElement(transitions, i);
        const char *trans_type = env->GetStringUTFChars(trans_type_js, nullptr);
        double duration = trans_durs[i] / 1000.0;

        current_offset -= duration;

        std::string out_v = "v_tmp_" + std::to_string(i);
        filter << "[" << last_v << "][v" << (i+1) << "]xfade=transition=" << trans_type
               << ":duration=" << duration << ":offset=" << current_offset << "[" << out_v << "];";

        last_v = out_v;
        current_offset += (ends[i+1] - starts[i+1]) / 1000.0;

        env->ReleaseStringUTFChars(trans_type_js, trans_type);
    }

    filter << "[" << last_v << "]format=yuv420p[v]";

    LOGI("Built complex filter: %s", filter.str().c_str());

    // Release elements
    env->ReleaseLongArrayElements(trim_starts, starts, JNI_ABORT);
    env->ReleaseLongArrayElements(trim_ends, ends, JNI_ABORT);
    env->ReleaseLongArrayElements(transition_durations, trans_durs, JNI_ABORT);

    // To actually APPLY the transition, we must use avfilter_graph_parse_ptr and
    // run a full decode-filter-encode loop.
    // For now, we return false to trigger the Media3 fallback which we will enhance with effects.

    return JNI_FALSE;
}
