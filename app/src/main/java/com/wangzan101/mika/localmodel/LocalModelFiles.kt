package com.wangzan101.mika.localmodel

import android.content.Context
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.jev.JevModelCatalog
import com.wangzan101.mika.jev.JevModelFiles
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** 与 Jev 共用经过测试的续传、重定向白名单和最终路径 SHA 校验实现。 */
class LocalModelFiles private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "models/main")
    private val client = NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS))
    private val files = LocalModelCatalog.models.associate { spec ->
        val dir = File(root, "${spec.id}/${spec.revision}")
        spec.id to JevModelFiles(dir, client, listOf(spec.artifact), JevModelCatalog::allows, { dir.usableSpace })
    }
    private fun managed(spec: LocalModelSpec) = files.getValue(spec.id)
    fun modelFile(spec: LocalModelSpec): File = managed(spec).model
    fun present(spec: LocalModelSpec) = managed(spec).present()
    fun downloadedBytes(spec: LocalModelSpec) = managed(spec).downloadedBytes()
    suspend fun verify(spec: LocalModelSpec) = managed(spec).verify()
    suspend fun download(spec: LocalModelSpec, progress: (Long, Long) -> Unit) = managed(spec).download(progress)
    fun delete(spec: LocalModelSpec) = managed(spec).delete()
    companion object {
        @Volatile private var instance: LocalModelFiles? = null
        fun get(context: Context): LocalModelFiles = instance ?: synchronized(this) {
            instance ?: LocalModelFiles(context).also { instance = it }
        }
    }
}
