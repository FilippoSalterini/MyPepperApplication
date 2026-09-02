package com.example.mypepperapplication.conversation

/**
 * Traduzione delle classi COCO di YOLOv8 in italiano, articolo incluso.
 *
 * Le label restituite dal detector e usate nei comandi `track:$label` restano
 * in inglese: questa tabella serve unicamente al TTS, perché Pepper non dica
 * "Ho trovato bottle". La conversione avviene quindi solo al momento di parlare.
 */
object LabelIt {

    private val map = mapOf(
        "person" to "una persona", "bicycle" to "la bicicletta", "car" to "la macchina",
        "motorcycle" to "la moto", "airplane" to "l'aereo", "bus" to "l'autobus",
        "train" to "il treno", "truck" to "il camion", "boat" to "la barca",
        "traffic light" to "il semaforo", "fire hydrant" to "l'idrante",
        "stop sign" to "il segnale di stop", "parking meter" to "il parchimetro",
        "bench" to "la panchina", "bird" to "l'uccello", "cat" to "il gatto",
        "dog" to "il cane", "horse" to "il cavallo", "sheep" to "la pecora",
        "cow" to "la mucca", "elephant" to "l'elefante", "bear" to "l'orso",
        "zebra" to "la zebra", "giraffe" to "la giraffa", "backpack" to "lo zaino",
        "umbrella" to "l'ombrello", "handbag" to "la borsa", "tie" to "la cravatta",
        "suitcase" to "la valigia", "frisbee" to "il frisbee", "skis" to "gli sci",
        "snowboard" to "lo snowboard", "sports ball" to "la palla", "kite" to "l'aquilone",
        "baseball bat" to "la mazza da baseball", "baseball glove" to "il guantone da baseball",
        "skateboard" to "lo skateboard", "surfboard" to "la tavola da surf",
        "tennis racket" to "la racchetta da tennis", "bottle" to "la bottiglia",
        "wine glass" to "il bicchiere di vino", "cup" to "la tazza", "fork" to "la forchetta",
        "knife" to "il coltello", "spoon" to "il cucchiaio", "bowl" to "la ciotola",
        "banana" to "la banana", "apple" to "la mela", "sandwich" to "il panino",
        "orange" to "l'arancia", "broccoli" to "i broccoli", "carrot" to "la carota",
        "hot dog" to "l'hot dog", "pizza" to "la pizza", "donut" to "la ciambella",
        "cake" to "la torta", "chair" to "la sedia", "couch" to "il divano",
        "potted plant" to "la pianta", "bed" to "il letto", "dining table" to "il tavolo",
        "toilet" to "il water", "tv" to "la televisione", "laptop" to "il computer",
        "mouse" to "il mouse", "remote" to "il telecomando", "keyboard" to "la tastiera",
        "cell phone" to "il telefono", "microwave" to "il microonde", "oven" to "il forno",
        "toaster" to "il tostapane", "sink" to "il lavandino",
        "refrigerator" to "il frigorifero", "book" to "il libro", "clock" to "l'orologio",
        "vase" to "il vaso", "scissors" to "le forbici", "teddy bear" to "l'orsacchiotto",
        "hair drier" to "il phon", "toothbrush" to "lo spazzolino"
    )

    /** Nome italiano con articolo; se la label non è mappata restituisce la label stessa. */
    fun of(label: String): String = map[label.lowercase()] ?: label
}