package com.mudassir.vidstash

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * The player's source for online videos: YouTube media is read in pieces ([RangeReader]),
 * like the downloader does. Any other http(s) link goes to the normal HTTP source.
 */
@OptIn(UnstableApi::class)
class YtDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = YtDataSource()
    }

    private var uri: Uri? = null
    private var reader: RangeReader? = null
    private var other: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val total = RangeReader.totalFor(url)
        uri = dataSpec.uri
        if (total <= 0) {
            val d = DefaultHttpDataSource.Factory().setUserAgent(OkHttpDownloader.USER_AGENT)
                .setAllowCrossProtocolRedirects(true).createDataSource()
            other = d
            return d.open(dataSpec)
        }
        transferInitializing(dataSpec)
        val length = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total - dataSpec.position
        reader = RangeReader(url, dataSpec.position, length, total)
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        other?.let { return it.read(buffer, offset, length) }
        val n = reader?.read(buffer, offset, length) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        other?.let {
            other = null
            it.close()
            return
        }
        reader?.let {
            reader = null
            it.close()
            transferEnded()
        }
    }
}
