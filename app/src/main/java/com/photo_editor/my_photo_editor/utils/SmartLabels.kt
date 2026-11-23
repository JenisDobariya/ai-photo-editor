package com.photo_editor.my_photo_editor.utils

import java.util.Locale

/**
 * Turns raw on-device image labels into search matches and clean "Things" albums.
 * Labels are stored lower-case and '|' separated, strongest first (see MediaIndex.labels).
 */
object SmartLabels {

    /** Everyday words people type → labels the on-device model actually produces. */
    private val synonyms: Map<String, List<String>> = mapOf(
        "people" to listOf("selfie", "smile", "crowd", "team", "bride", "baby", "child", "beard"),
        "person" to listOf("selfie", "smile", "crowd", "team", "bride", "baby", "child", "beard"),
        "selfie" to listOf("selfie", "smile"),
        "friends" to listOf("crowd", "team", "smile", "selfie", "party"),
        "family" to listOf("baby", "child", "smile", "crowd"),
        "kid" to listOf("baby", "child", "toy"),
        "kids" to listOf("baby", "child", "toy"),
        "wedding" to listOf("bride", "wedding", "veil", "flower"),
        "pet" to listOf("pet", "dog", "cat", "bird", "fish", "horse"),
        "pets" to listOf("pet", "dog", "cat", "bird", "fish", "horse"),
        "animal" to listOf("pet", "dog", "cat", "bird", "horse", "butterfly", "insect", "fish", "cattle"),
        "animals" to listOf("pet", "dog", "cat", "bird", "horse", "butterfly", "insect", "fish", "cattle"),
        "puppy" to listOf("dog", "pet"),
        "kitten" to listOf("cat", "pet"),
        "food" to listOf("food", "cake", "cookie", "fast food", "bread", "pizza", "dessert", "fruit", "vegetable", "dish", "meal", "cuisine"),
        "meal" to listOf("food", "dish", "cuisine", "meal"),
        "dessert" to listOf("cake", "cookie", "dessert", "ice cream", "chocolate"),
        "drink" to listOf("drink", "coffee", "tea", "juice", "wine", "beer", "cocktail"),
        "nature" to listOf("plant", "flower", "tree", "sky", "mountain", "lake", "river", "beach", "waterfall", "forest", "leaf", "garden", "grass", "cloud", "sunset"),
        "sea" to listOf("sea", "beach", "ocean", "wave", "sand"),
        "beach" to listOf("beach", "sand", "sea", "ocean", "wave"),
        "mountains" to listOf("mountain", "hill", "cliff"),
        "sky" to listOf("sky", "cloud", "sunset", "sunrise"),
        "sunset" to listOf("sunset", "sunrise", "dusk", "sky"),
        "flowers" to listOf("flower", "petal", "plant"),
        "car" to listOf("car", "vehicle", "wheel", "motorcycle", "bus", "truck"),
        "cars" to listOf("car", "vehicle", "wheel"),
        "vehicle" to listOf("car", "vehicle", "motorcycle", "bus", "truck", "bicycle", "train", "airplane"),
        "bike" to listOf("bicycle", "motorcycle"),
        "travel" to listOf("airplane", "vehicle", "road", "bridge", "building", "skyscraper", "monument", "tower"),
        "city" to listOf("building", "skyscraper", "road", "bridge", "tower", "city"),
        "building" to listOf("building", "skyscraper", "house", "tower", "monument", "church", "temple"),
        "party" to listOf("party", "balloon", "cake", "event", "fun", "concert"),
        "birthday" to listOf("cake", "balloon", "party", "candle"),
        "music" to listOf("musical instrument", "guitar", "piano", "concert", "music"),
        "sport" to listOf("sports", "ball", "stadium", "cricket", "football", "soccer", "bicycle"),
        "sports" to listOf("sports", "ball", "stadium", "cricket", "football", "soccer"),
        "document" to listOf("paper", "receipt", "poster", "menu", "newspaper", "handwriting", "book"),
        "documents" to listOf("paper", "receipt", "poster", "menu", "newspaper", "handwriting", "book"),
        "night" to listOf("night", "darkness", "moon", "star", "fireworks"),
        "snow" to listOf("snow", "ice", "winter"),
        "festival" to listOf("fireworks", "candle", "lantern", "flower", "event")
    )

    /** Labels too vague to make a useful album on their own. */
    private val noisyLabels = setOf(
        "pattern", "font", "rectangle", "circle", "darkness", "event", "fun", "sleeve", "jacket",
        "jeans", "shoe", "hand", "nail", "eyelash", "hair", "skin", "flesh", "wheel", "cool",
        "tableware", "cutlery", "glasses", "sunglasses", "musical", "room", "interaction", "comics",
        "sitting", "standing", "smile", "metal", "wood", "textile", "denim", "polo", "bag", "cap", "hat", "net",
        "outerwear", "leisure", "community", "beard", "moustache", "bangle", "love", "pocket", "lipstick"
    )

    /** Pretty, plural-aware album titles for common labels. */
    private val titles = mapOf(
        "dog" to "Dogs", "cat" to "Cats", "pet" to "Pets", "bird" to "Birds", "horse" to "Horses",
        "flower" to "Flowers", "plant" to "Plants", "tree" to "Trees", "car" to "Cars",
        "mountain" to "Mountains", "selfie" to "Selfies", "baby" to "Babies", "cake" to "Cakes",
        "food" to "Food", "sky" to "Sky", "beach" to "Beach", "sunset" to "Sunsets", "building" to "Buildings",
        "vehicle" to "Vehicles", "toy" to "Toys", "screenshot" to "Screenshots", "lake" to "Lakes",
        "river" to "Rivers", "waterfall" to "Waterfalls", "monument" to "Monuments", "temple" to "Temples",
        "crowd" to "Crowds", "bicycle" to "Bicycles", "motorcycle" to "Motorcycles", "book" to "Books",
        "paper" to "Documents", "receipt" to "Receipts", "poster" to "Posters", "fireworks" to "Fireworks"
    )

    fun split(labels: String?): List<String> =
        if (labels.isNullOrEmpty()) emptyList() else labels.split('|')

    /** Whether one search [term] (lower-case) matches a photo's stored [labels]. */
    fun matches(term: String, labels: String?): Boolean {
        if (labels.isNullOrEmpty() || term.length < 2) return false
        val wanted = synonyms[term] ?: synonyms[term.removeSuffix("s")]
        val photoLabels = split(labels)
        return photoLabels.any { label ->
            if (wanted != null && wanted.any { it == label }) return@any true
            // "dog" → "dog", "flow" → "flower", "musical" → "musical instrument"
            label.startsWith(term) || label.split(' ').any { it.startsWith(term) } ||
                    (term.length > 3 && term.endsWith("s") && label == term.dropLast(1))
        }
    }

    fun isAlbumWorthy(label: String) = label.isNotBlank() && label !in noisyLabels

    fun title(label: String): String = titles[label] ?: label.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
