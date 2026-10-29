package com.slopus.happy.model

/** happy-ios HomeDockKind: the selected computer's creation modes, in order. */
enum class HomeDockKind {
    Session, Bot, Task;

    companion object {
        fun offered(supportsBots: Boolean, supportsTasks: Boolean): List<HomeDockKind> =
            listOf(Session) + (if (supportsBots) listOf(Bot) else emptyList()) +
                (if (supportsTasks) listOf(Task) else emptyList())

        fun current(createsBot: Boolean, createsTask: Boolean): HomeDockKind =
            if (createsBot) Bot else if (createsTask) Task else Session
    }
}