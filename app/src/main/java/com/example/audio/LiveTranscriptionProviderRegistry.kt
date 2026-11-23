package com.example.audio

/**
 * Small provider-selection boundary. Credentials and network protocol details belong
 * in provider implementations, not in this registry.
 */
data class LiveTranscriptionProviderRegistration(
    val providerId: String,
    val mode: LiveProviderMode,
    val enabled: Boolean = true,
    val factory: () -> LiveTranscriptionProvider
)

class LiveTranscriptionProviderRegistry(registrations: List<LiveTranscriptionProviderRegistration>) {
    private val registrationsById = registrations.associateBy { it.providerId.trim() }

    init {
        require(registrations.all { it.providerId.isNotBlank() }) { "Provider ID must not be blank." }
        require(registrations.map { it.providerId.trim() }.distinct().size == registrations.size) {
            "Provider IDs must be unique."
        }
    }

    fun availableProviderIds(): List<String> =
        registrationsById.values.filter { it.enabled }.map { it.providerId }.sorted()

    /** Returns only a provider that is enabled and explicitly matches the requested mode. */
    fun create(providerId: String, requiredMode: LiveProviderMode? = null): LiveTranscriptionProvider? {
        val registration = registrationsById[providerId.trim()] ?: return null
        if (!registration.enabled || (requiredMode != null && registration.mode != requiredMode)) return null
        val provider = registration.factory()
        if (provider.mode != registration.mode) {
            throw IllegalStateException("Provider mode does not match its registration.")
        }
        return provider
    }
}
