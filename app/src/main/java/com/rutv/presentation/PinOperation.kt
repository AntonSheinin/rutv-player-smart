package com.rutv.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class PinRequest(val session: String, val attempt: Int)

enum class PinStatus { Idle, Pending, Succeeded, Failed }
enum class PinFailure { Invalid, Wrong, Storage }

data class PinOperation(
    val request: PinRequest? = null,
    val status: PinStatus = PinStatus.Idle,
    val failure: PinFailure? = null,
    val busy: Boolean = false
)

class PinRejected(val failure: PinFailure) : Exception()

/** Main-thread owner: reserve before launch, retain terminal identity after releasing ownership. */
class PinOperationRunner(
    private val scope: CoroutineScope,
    private val publish: (PinOperation) -> Unit
) {
    private var active: PinRequest? = null
    private var job: Job? = null

    fun start(request: PinRequest, block: suspend () -> Unit): Boolean {
        if (active != null) return false
        active = request
        publish(PinOperation(request, PinStatus.Pending, busy = true))
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var terminal = PinOperation(request)
            try {
                block()
                terminal = PinOperation(request, PinStatus.Succeeded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PinRejected) {
                terminal = PinOperation(request, PinStatus.Failed, error.failure)
            } catch (_: Exception) {
                terminal = PinOperation(request, PinStatus.Failed, PinFailure.Storage)
            } finally {
                if (active == request) {
                    active = null
                    job = null
                    publish(terminal)
                }
            }
        }
        job = task
        // Cancellation can happen before the scheduled coroutine body ever enters its finally.
        task.invokeOnCompletion {
            if (active == request) {
                active = null
                job = null
                publish(PinOperation(request))
            }
        }
        task.start()
        return true
    }

    fun cancel() {
        job?.cancel()
    }
}
