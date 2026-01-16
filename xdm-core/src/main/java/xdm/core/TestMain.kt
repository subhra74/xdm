package xdm.core

import xdm.core.downloaders.web.http.HttpDownloadTask

class TestMain {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            HttpDownloadTask().start()
        }
    }
}