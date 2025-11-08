package com.rutv.util

import android.os.Build
import timber.log.Timber
import java.security.SecureRandom
import javax.net.ssl.*
import javax.net.ssl.SSLSocketFactory

/**
 * SSL/TLS configuration utility
 * Fixes SSL handshake issues and TLS protocol compatibility
 */
object SSLConfig {

    /**
     * Create a properly configured SSL context
     * Ensures TLS 1.2+ support and proper cipher suites
     */
    fun createSSLContext(): SSLContext {
        return try {
            // Use TLS 1.2 or higher (TLSv1.3 on newer Android)
            val protocol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "TLSv1.3"
            } else {
                "TLSv1.2"
            }

            val sslContext = SSLContext.getInstance(protocol)

            // Use default trust manager and key manager
            // This trusts system certificates
            sslContext.init(null, null, SecureRandom())

            logDebug { "SSL Context created with protocol: $protocol" }
            sslContext
        } catch (e: Exception) {
            Timber.e(e, "Failed to create SSL context, using default")
            // Fallback to default TLS
            SSLContext.getDefault()
        }
    }

    /**
     * Create an SSLSocketFactory with proper configuration
     */
    fun createSSLSocketFactory(): SSLSocketFactory {
        val sslContext = createSSLContext()
        val factory = sslContext.socketFactory

        return object : SSLSocketFactory() {
            private val delegate = factory

            override fun createSocket(): java.net.Socket {
                return configureSocket(delegate.createSocket())
            }

            override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket {
                return configureSocket(delegate.createSocket(s, host, port, autoClose))
            }

            override fun createSocket(host: String?, port: Int): java.net.Socket {
                return configureSocket(delegate.createSocket(host, port))
            }

            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket {
                return configureSocket(delegate.createSocket(host, port, localHost, localPort))
            }

            override fun createSocket(address: java.net.InetAddress?, port: Int): java.net.Socket {
                return configureSocket(delegate.createSocket(address, port))
            }

            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket {
                return configureSocket(delegate.createSocket(address, port, localAddress, localPort))
            }

            override fun getDefaultCipherSuites(): Array<String> {
                return delegate.defaultCipherSuites
            }

            override fun getSupportedCipherSuites(): Array<String> {
                return delegate.supportedCipherSuites
            }

            private fun configureSocket(socket: java.net.Socket): java.net.Socket {
                if (socket is SSLSocket) {
                    // Enable TLS protocols - prioritize TLS 1.2 and 1.3
                    val protocols = arrayOf("TLSv1.3", "TLSv1.2")
                    try {
                        socket.enabledProtocols = protocols.filter { protocol ->
                            socket.supportedProtocols.contains(protocol)
                        }.toTypedArray()
                    } catch (e: Exception) {
                        Timber.w(e, "Could not set TLS protocols, using default")
                    }

                    // Set socket options for better reliability
                    try {
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                    } catch (e: Exception) {
                        Timber.w(e, "Could not set socket options")
                    }
                }
                return socket
            }
        }
    }

    /**
     * Initialize default SSL socket factory for HttpURLConnection
     * This affects all HttpURLConnection instances
     */
    fun initializeDefaultSSLSocketFactory() {
        try {
            val sslSocketFactory = createSSLSocketFactory()
            HttpsURLConnection.setDefaultSSLSocketFactory(sslSocketFactory)

            // Hostname verifier is already set to default by the system, no need to change it

            logDebug { "Default SSL Socket Factory initialized" }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize default SSL socket factory")
        }
    }
}
