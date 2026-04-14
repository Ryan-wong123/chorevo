package com.example.chorevo.firebase

enum class AuthMode { LOGIN, SIGNUP }
enum class UserRole { USER }

data class UserProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val role: UserRole = UserRole.USER,
    val photoUrl: String? = null
)