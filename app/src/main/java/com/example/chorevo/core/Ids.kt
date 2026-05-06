package com.example.chorevo.core

import java.util.UUID

object Ids {
    /** Handles new session id. */
    fun newSessionId(): String = UUID.randomUUID().toString()
}
