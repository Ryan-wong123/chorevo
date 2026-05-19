package com.example.chorevo.dancelibrarylist

data class DanceComment(
    val commentId: String = "",
    val uid: String = "",
    val username: String = "",
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis()
)