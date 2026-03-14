package com.droidhubworld.videopro.utils

import android.graphics.Bitmap

/**
 * Created by Anand
 * on 13/03/26
 **/

object FFmpegNative {
    init {
        System.loadLibrary("videopro")
    }

    external fun stringFromJNI(): String
    external fun getFFmpegVersion(): String

    /**
     * Extracts a frame from the video at the given path at the specified timestamp.
     * @param videoPath path to the video file
     * @param timeMs timestamp in milliseconds
     * @param bitmap the bitmap to fill with the frame data. It should be of ARGB_8888 config.
     * @return true if successful, false otherwise
     */
    external fun extractFrame(videoPath: String, timeMs: Long, bitmap: Bitmap): Boolean
    
    /**
     * Trims a video from startMs to endMs.
     * @param inputPath path to the input video file
     * @param outputPath path to the output video file
     * @param startMs start time in milliseconds
     * @param endMs end time in milliseconds
     * @return true if successful, false otherwise
     */
    external fun trimVideo(inputPath: String, outputPath: String, startMs: Long, endMs: Long): Boolean
}