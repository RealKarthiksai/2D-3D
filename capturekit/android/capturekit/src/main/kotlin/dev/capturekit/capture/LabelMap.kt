package dev.capturekit.capture

/**
 * COCO class indices of the EfficientDet-Lite0 detector (0-based, person = 0, "???" gaps kept; ADR 0017) mapped to the
 * core's object classes (understanding.hpp ObjectClass ids). Only furniture/obstacle classes the core understands are
 * kept; everything else maps to [UNKNOWN] and is dropped by the worker. Offset 0 was checked by running the model on
 * COCO val2017 000000039769 (cats on a couch: classes 16 cat, 62 couch, 74 remote).
 */
object LabelMap {
    const val UNKNOWN = 0
    const val CHAIR = 1
    const val COUCH = 2
    const val BED = 3
    const val DINING_TABLE = 4
    const val TV = 5
    const val POTTED_PLANT = 6
    const val LAPTOP = 7

    /** Same names as the core's kObjectClassNames, indexed by core class id. */
    val coreNames = listOf("unknown", "chair", "couch", "bed", "dining_table", "tv", "potted_plant", "laptop")

    private val cocoToCore = mapOf(
        61 to CHAIR, 62 to COUCH, 63 to POTTED_PLANT, 64 to BED, 66 to DINING_TABLE, 71 to TV, 72 to LAPTOP,
    )

    /** Core class id for a detector class index (a float from the model output), [UNKNOWN] when not mapped. */
    fun coreClass(cocoIndex: Int): Int = cocoToCore[cocoIndex] ?: UNKNOWN

    fun coreName(coreClass: Int): String = coreNames.getOrElse(coreClass) { "unknown" }
}
