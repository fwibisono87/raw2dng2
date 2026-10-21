package com.raw2dng

object Native {
    init { System.loadLibrary("raw2dng") }

    @JvmStatic external fun probe(inFd: Int): String
    @JvmStatic external fun preview(inFd: Int, maxEdge: Int): ByteArray?
    @JvmStatic external fun toDng(inFd: Int, outFd: Int, fileName: String, embedOriginal: Boolean): String
    @JvmStatic external fun toJpeg(
        inFd: Int,
        outFd: Int,
        source: Int,
        longEdge: Int,
        maxBytes: Int,
        quality: Int
    ): String
}
