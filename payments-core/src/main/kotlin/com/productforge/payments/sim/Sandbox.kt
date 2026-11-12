package com.productforge.payments.sim

import com.productforge.payments.RestPaymentGateway
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Builds a [RestPaymentGateway] that talks to a [GatewaySimulator] instead of the network. */
object Sandbox {

    const val BASE_URL = "https://sandbox.gateway.invalid"

    @JvmStatic
    @JvmOverloads
    fun gateway(simulator: GatewaySimulator, apiKey: String = "sandbox-key"): RestPaymentGateway {
        val client = OkHttpClient.Builder()
            .addInterceptor(simulator)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return RestPaymentGateway(BASE_URL, apiKey, client)
    }
}
