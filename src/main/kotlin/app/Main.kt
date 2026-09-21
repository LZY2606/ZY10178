package app

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    val port = args.indexOf("--port").let { index ->
        if (index >= 0 && index + 1 < args.size) args[index + 1].toInt() else 5518
    }
    val dbPath = args.indexOf("--db").let { index ->
        if (index >= 0 && index + 1 < args.size) args[index + 1] else "data/thermal-bench.sqlite"
    }
    val fixture = FixtureLoader.loadFromClasspath()
    Repository.file(dbPath).use { repository ->
        repository.importIfEmpty(fixture)
        embeddedServer(Netty, port = port, host = "127.0.0.1") {
            configureWeb(repository, fixture)
        }.start(wait = true)
    }
}
