package com.nuvio.tv.data.ryot

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OkHttpRyotTransport(client: OkHttpClient) : RyotTransport {
    private val client = client.newBuilder()
        // A redirect would turn the POST into a GET and drop the Authorization header.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): RyotHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .post(body.toRequestBody(JSON))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (it.isRedirect) {
                                val target = it.header("Location").orEmpty()
                                throw RyotApiException(
                                    "Ryot server redirected to $target. Use that address as the server URL.",
                                    it.code
                                )
                            }
                            val text = it.body?.string().orEmpty()
                            if (continuation.isActive) continuation.resume(RyotHttpResponse(it.code, text))
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class RyotCredentials(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val username: String
)

data class RyotAuthState(
    val profileId: Int,
    val serverUrl: String? = null,
    val username: String? = null,
    val credentials: RyotCredentials? = null,
    /** Set when Ryot rejected the stored token; the account needs connecting again. */
    val error: String? = null
) {
    val isAuthenticated: Boolean get() = credentials != null
}

/**
 * Per-profile Ryot credentials, encrypted at rest like Nuvio's other tracker tokens.
 * The server URL and username survive a rejected token, so reconnecting is quick on a remote.
 */
class RyotAuthStore(context: Context) : ProfileScopedCredentialStore {
    private val preferences = ProtectedProfilePreferences(context, "ryot_auth", "com.nuvio.tv.ryot.credentials.v1")
    private val lock = Any()
    private val mutableState = MutableStateFlow(load(1))

    val state: StateFlow<RyotAuthState> = mutableState.asStateFlow()

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (mutableState.value.profileId != profileId) mutableState.value = load(profileId)
    }

    fun credentials(): RyotCredentials? = mutableState.value.credentials

    fun save(credentials: RyotCredentials, profileId: Int) = synchronized(lock) {
        write(profileId, RyotAuthState(profileId, credentials.serverUrl, credentials.username, credentials))
    }

    /** Keeps the server and username for the next attempt, drops the token. */
    fun markRejected(profileId: Int, message: String) = synchronized(lock) {
        val current = if (mutableState.value.profileId == profileId) mutableState.value else load(profileId)
        write(profileId, current.copy(credentials = null, error = message))
    }

    fun disconnect(profileId: Int) = synchronized(lock) {
        val current = if (mutableState.value.profileId == profileId) mutableState.value else load(profileId)
        write(profileId, RyotAuthState(profileId, current.serverUrl, current.username))
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        preferences.write(profileId, null)
        if (mutableState.value.profileId == profileId) mutableState.value = RyotAuthState(profileId)
    }

    override fun clearAllProfiles() = synchronized(lock) {
        preferences.clear()
        mutableState.value = RyotAuthState(mutableState.value.profileId)
    }

    private fun write(profileId: Int, state: RyotAuthState) {
        preferences.write(profileId, encode(state))
        if (mutableState.value.profileId == profileId) mutableState.value = state
    }

    private fun load(profileId: Int): RyotAuthState {
        val raw = runCatching { preferences.read(profileId) }.getOrNull() ?: return RyotAuthState(profileId)
        return runCatching {
            val json = RyotJson.parseToJsonElement(raw).jsonObject
            val server = json.string("serverUrl")
            val username = json.string("username")
            val token = json.string("token")
            val userId = json.string("userId")
            RyotAuthState(
                profileId = profileId,
                serverUrl = server,
                username = username,
                credentials = if (server != null && token != null && userId != null) {
                    RyotCredentials(server, token, userId, username ?: userId)
                } else null,
                error = json.string("error")
            )
        }.getOrElse { RyotAuthState(profileId) }
    }

    private fun encode(state: RyotAuthState): String = buildJsonObject {
        state.serverUrl?.let { put("serverUrl", it) }
        state.username?.let { put("username", it) }
        state.credentials?.let {
            put("token", it.token)
            put("userId", it.userId)
        }
        state.error?.let { put("error", it) }
    }.toString()
}
