package my.robots.core.model

/**
 * Estado do "heartbeat" (pulso) de um robô conectado.
 *
 * - ALIVE: chegou alguma coisa do robô há pouco tempo (ele está respondendo de verdade).
 * - STALE: a conexão continua aberta, mas faz tempo que o robô não manda nada.
 * - DISCONNECTED: sem conexão.
 *
 * Quem calcula é o KawasakiTerminalManager (:core:network); fica aqui no :core:model para
 * que a bolinha de status (HeartbeatDot, no :core:designsystem) possa ser usada por
 * qualquer tela.
 */
enum class HeartbeatState {
    ALIVE, STALE, DISCONNECTED
}
