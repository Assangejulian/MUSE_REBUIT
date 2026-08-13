package com.muse.app

import android.content.Context
import android.net.ConnectivityManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object AndroidHttp {
    fun client(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (network != null) {
            runCatching {
                builder.socketFactory(network.socketFactory)
                builder.dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        val bound = runCatching { network.getAllByName(hostname).toList() }.getOrNull()
                        return if (!bound.isNullOrEmpty()) bound else Dns.SYSTEM.lookup(hostname)
                    }
                })
            }
        }
        return builder.build()
    }
}
