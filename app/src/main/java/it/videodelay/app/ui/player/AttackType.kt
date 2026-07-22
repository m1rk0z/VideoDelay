package it.videodelay.app.ui.player

import it.videodelay.app.R

/** Zona di attacco a pallavolo, usata per raggruppare e colorare i tipi nel popup MARK e in galleria. */
enum class AttackZone {
    POSTO4, POSTO3, POSTO2, SECONDA_LINEA
}

/** Colore associato alla zona, condiviso tra popup MARK e galleria clip. */
fun AttackZone.colorRes(): Int = when (this) {
    AttackZone.POSTO4 -> R.color.colorSecondary
    AttackZone.POSTO3 -> R.color.mark_yellow
    AttackZone.POSTO2 -> R.color.live_red
    AttackZone.SECONDA_LINEA -> R.color.colorPrimary
}

/**
 * Tipo di attacco pallavolo selezionabile dal popup MARK. [code] è anche il nome della cartella
 * media, [label] la descrizione estesa mostrata nella conferma dopo il salvataggio della clip.
 */
data class AttackType(val code: String, val label: String, val zone: AttackZone)

object AttackTypes {
    val ALL = listOf(
        AttackType("V4", "Velocità 4 / Tesa in 4", AttackZone.POSTO4),
        AttackType("H4", "High 4 / Alta in 4", AttackZone.POSTO4),
        AttackType("V3", "Veloce 3 / Primo Tempo", AttackZone.POSTO3),
        AttackType("V7", "Sette / Spostata", AttackZone.POSTO3),
        AttackType("V5", "Incrocio / Corta dietro", AttackZone.POSTO3),
        AttackType("V2", "Velocità 2 / Tesa in 2", AttackZone.POSTO2),
        AttackType("H2", "High 2 / Alta in 2", AttackZone.POSTO2),
        AttackType("VF", "Veloce Fast", AttackZone.POSTO2),
        AttackType("VP", "Veloce Pipe", AttackZone.SECONDA_LINEA),
        AttackType("VB", "Veloce Back", AttackZone.SECONDA_LINEA)
    )
}
