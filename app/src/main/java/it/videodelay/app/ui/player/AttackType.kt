package it.videodelay.app.ui.player

import it.videodelay.app.R

/** Zona di attacco a pallavolo, usata per raggruppare e colorare i tipi nel popup MARK e in galleria. */
enum class AttackZone {
    POSTO4, POSTO3, POSTO2, POSTO1, SECONDA_LINEA
}

/** Colore associato alla zona, condiviso tra popup MARK e galleria clip. */
fun AttackZone.colorRes(): Int = when (this) {
    AttackZone.POSTO4 -> R.color.colorSecondary
    AttackZone.POSTO3 -> R.color.mark_yellow
    AttackZone.POSTO2 -> R.color.live_red
    AttackZone.POSTO1 -> R.color.colorPrimary
    AttackZone.SECONDA_LINEA -> R.color.accent_blue
}

/**
 * Tipo di attacco pallavolo selezionabile dal popup MARK. [code] è la sigla visualizzata,
 * [label] la descrizione estesa.
 */
data class AttackType(val code: String, val label: String, val zone: AttackZone)

object AttackTypes {
    val ALL = listOf(
        // Posto 4
        AttackType("4.H", "Alta in 4 (4.H)", AttackZone.POSTO4),
        AttackType("4.V", "Veloce in 4 (4.V)", AttackZone.POSTO4),
        // Posto 3
        AttackType("3.F", "Fast in 3 (3.F)", AttackZone.POSTO3),
        AttackType("3.1", "Primo Tempo 3.1", AttackZone.POSTO3),
        AttackType("3.2", "Mezza 3.2", AttackZone.POSTO3),
        AttackType("3.7", "Sette 3.7", AttackZone.POSTO3),
        // Posto 2
        AttackType("2.H", "Alta in 2 (2.H)", AttackZone.POSTO2),
        AttackType("2.V", "Veloce in 2 (2.V)", AttackZone.POSTO2),
        // Posto 1 (separato da P e O)
        AttackType("1.0", "Posto 1 (1.0)", AttackZone.POSTO1),
        AttackType("1.G", "Servizio / Spostata (1.G)", AttackZone.POSTO1),
        // Pipe e Opposto (separati da 1.0 e 1.G)
        AttackType("P", "Pipe (P)", AttackZone.SECONDA_LINEA),
        AttackType("O", "Opposto (O)", AttackZone.SECONDA_LINEA)
    )
}
