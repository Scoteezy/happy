package com.slopus.happy.net

/**
 * One immutable account request snapshot. The graph constructs both the bound
 * client and token from ONE confirmed SignedIn value, before a job can suspend.
 * In-flight work keeps its originating host even after selection changes.
 * Not a data class: its default string representation must not print the token.
 */
class AuthenticatedHttp(val http: HappyHttp, val token: String)