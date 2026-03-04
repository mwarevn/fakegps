package io.github.mwarevn.movingsimulation.network

import retrofit2.http.GET

data class ServerStatus(
    val status: String
)

interface StatusService {
    @GET("server")
    suspend fun getStatus(): List<ServerStatus>
}
