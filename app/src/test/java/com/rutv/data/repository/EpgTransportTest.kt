package com.rutv.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgTransportTest {
    private class Server(private val respond: (Socket) -> Unit) : java.io.Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile private var connection: Socket? = null
        val url = "http://127.0.0.1:${listener.localPort}"
        init {
            executor.submit {
                listener.accept().use { socket ->
                    connection = socket
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    var header = ""
                    while (!header.endsWith("\r\n\r\n")) {
                        val ch = input.read()
                        check(ch >= 0)
                        header += ch.toChar()
                    }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                        .find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) }
                    respond(socket)
                }
            }
        }
        override fun close() {
            connection?.close()
            listener.close()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test fun readsCompressedEmptyResponse() = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write("{\"epg\":{\"one\":[]}}".toByteArray()) }
        }.toByteArray()
        Server { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nContent-Encoding: gzip\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }.use { server ->
            val result = EpgRemoteDataSource(Gson()).fetch(server.url, listOf("one"), 0, 300)
            assertTrue(result.programs.getValue("one").isEmpty())
        }
    }

    @Test fun cancellationOfStalledResponseCompletesPromptly() = runBlocking {
        repeat(5) {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        Server { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n{".toByteArray())
                flush()
            }
            entered.countDown()
            finish.await(5, TimeUnit.SECONDS)
        }.use { server ->
            val job = launch(Dispatchers.IO) { EpgRemoteDataSource(Gson()).fetch(server.url, listOf("one"), 0, 300) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(2000) { job.join() }
            } finally { finish.countDown() }
        }
        }
    }
}
