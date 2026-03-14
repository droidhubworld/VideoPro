#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <android/log.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <libavutil/display.h>
#include <libavutil/eval.h>
}

#define LOG_TAG "FFmpegNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidhubworld_videopro_utils_FFmpegNative_extractFrame(JNIEnv *env, jobject thiz,
                                                                jstring video_path, jlong time_ms,
                                                                jobject bitmap) {
    const char *path = env->GetStringUTFChars(video_path, nullptr);
    AVFormatContext *fmt_ctx = nullptr;
    if (avformat_open_input(&fmt_ctx, path, nullptr, nullptr) < 0) {
        LOGE("Could not open source file %s", path);
        env->ReleaseStringUTFChars(video_path, path);
        return JNI_FALSE;
    }

    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        LOGE("Could not find stream information");
        avformat_close_input(&fmt_ctx);
        env->ReleaseStringUTFChars(video_path, path);
        return JNI_FALSE;
    }

    int video_stream_index = -1;
    for (int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_index = i;
            break;
        }
    }

    if (video_stream_index == -1) {
        LOGE("Could not find video stream");
        avformat_close_input(&fmt_ctx);
        env->ReleaseStringUTFChars(video_path, path);
        return JNI_FALSE;
    }

    AVCodecParameters *codec_par = fmt_ctx->streams[video_stream_index]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(codec_par->codec_id);
    AVCodecContext *codec_ctx = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(codec_ctx, codec_par);
    avcodec_open2(codec_ctx, codec, nullptr);

    // Seek to the requested time
    int64_t target_pts = (int64_t)(time_ms * (double)fmt_ctx->streams[video_stream_index]->time_base.den / (1000.0 * fmt_ctx->streams[video_stream_index]->time_base.num));
    av_seek_frame(fmt_ctx, video_stream_index, target_pts, AVSEEK_FLAG_BACKWARD);

    AVFrame *frame = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    bool frame_found = false;

    while (av_read_frame(fmt_ctx, packet) >= 0) {
        if (packet->stream_index == video_stream_index) {
            if (avcodec_send_packet(codec_ctx, packet) == 0) {
                if (avcodec_receive_frame(codec_ctx, frame) == 0) {
                    frame_found = true;
                    break;
                }
            }
        }
        av_packet_unref(packet);
    }

    if (frame_found) {
        AndroidBitmapInfo info;
        void *pixels;
        AndroidBitmap_getInfo(env, bitmap, &info);
        AndroidBitmap_lockPixels(env, bitmap, &pixels);

        SwsContext *sws_ctx = sws_getContext(frame->width, frame->height, (AVPixelFormat)frame->format,
                                             info.width, info.height, AV_PIX_FMT_RGBA,
                                             SWS_BILINEAR, nullptr, nullptr, nullptr);

        uint8_t *dest[4] = {(uint8_t *)pixels, nullptr, nullptr, nullptr};
        int dest_linesize[4] = {(int)info.stride, 0, 0, 0};
        sws_scale(sws_ctx, frame->data, frame->linesize, 0, frame->height, dest, dest_linesize);

        sws_freeContext(sws_ctx);
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codec_ctx);
    avformat_close_input(&fmt_ctx);
    env->ReleaseStringUTFChars(video_path, path);

    return frame_found ? JNI_TRUE : JNI_FALSE;
}
