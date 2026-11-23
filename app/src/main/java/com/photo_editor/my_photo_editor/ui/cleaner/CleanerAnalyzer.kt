package com.photo_editor.my_photo_editor.ui.cleaner

import android.net.Uri
import com.photo_editor.my_photo_editor.db.MediaIndex
import com.photo_editor.my_photo_editor.repository.SmartIndexRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, CPU-only analysis of the library + photo index into cleaner categories.
 * Call on [kotlinx.coroutines.Dispatchers.Default]. Tuned to avoid false positives.
 */
object CleanerAnalyzer {

    /** Exact copies: file sizes may differ this much (re-saved metadata). */
    private const val DUPLICATE_SIZE_TOLERANCE = 0.02
    /** Similar photos: max dHash distance and time window. */
    private const val SIMILAR_MAX_DISTANCE = 6
    private const val SIMILAR_FLAT_MAX_DISTANCE = 1
    private const val SIMILAR_WINDOW_MS = 10L * 60L * 1000L
    private const val SIMILAR_ASPECT_TOLERANCE = 0.05
    /** Blurry: Laplacian variance threshold and minimum short side. */
    private const val BLUR_THRESHOLD = 45f
    private const val BLUR_MIN_SHORT_SIDE = 400
    /** Large videos: 50 MB (SI units, matching Formatter's "50 MB"). */
    const val LARGE_VIDEO_BYTES = 50_000_000L

    private class Candidate(val media: CleanerMedia, val index: MediaIndex) {
        val width: Int = if (media.width > 0) media.width else index.width
        val height: Int = if (media.height > 0) media.height else index.height
        val pixels: Long get() = width.toLong() * height.toLong()
        val hash: Long get() = index.dHash
    }

    fun analyze(library: List<CleanerMedia>, index: List<MediaIndex>): CleanerAnalysis {
        val indexByUri = HashMap<String, MediaIndex>(index.size * 2)
        index.forEach { indexByUri[it.uri] = it }

        // Photos whose analysis matches the current file version.
        val photos = ArrayList<Candidate>()
        for (m in library) {
            if (m.isVideo || m.size <= 0L) continue
            val entry = indexByUri[m.uri.toString()] ?: continue
            if (entry.size != m.size || entry.dateAdded != m.dateAddedSec) continue
            photos.add(Candidate(m, entry))
        }

        val duplicates = findDuplicates(photos)
        val dupUris = HashSet<Uri>()
        duplicates.forEach { g -> g.items.forEach { dupUris.add(it.uri) } }
        val similar = findSimilar(photos.filter { it.media.uri !in dupUris && !it.media.isScreenshot })

        val blurry = photos.asSequence()
            .filter { isBlurry(it) }
            .sortedBy { it.index.sharpness }
            .map { it.media }
            .toList()

        val screenshots = library.asSequence()
            .filter { !it.isVideo && it.isScreenshot }
            .sortedBy { it.timeMs }
            .toList()

        val largeVideos = library.asSequence()
            .filter { it.isVideo && it.size >= LARGE_VIDEO_BYTES }
            .sortedByDescending { it.size }
            .toList()

        val results = linkedMapOf(
            CleanerCategory.DUPLICATES to groupedResult(CleanerCategory.DUPLICATES, duplicates),
            CleanerCategory.SIMILAR to groupedResult(CleanerCategory.SIMILAR, similar),
            CleanerCategory.BLURRY to flatResult(CleanerCategory.BLURRY, blurry),
            CleanerCategory.SCREENSHOTS to flatResult(CleanerCategory.SCREENSHOTS, screenshots),
            CleanerCategory.LARGE_VIDEOS to flatResult(CleanerCategory.LARGE_VIDEOS, largeVideos)
        )

        // Total: every suggested item counted once, even when it appears in two categories.
        val reclaimable = HashMap<Uri, Long>()
        results.values.forEach { r ->
            if (r.category.grouped) {
                r.groups.forEach { g -> g.items.forEach { if (it.uri != g.best.uri) reclaimable[it.uri] = it.size } }
            } else {
                r.items.forEach { reclaimable[it.uri] = it.size }
            }
        }
        return CleanerAnalysis(results, reclaimable.values.sum())
    }

    // ------------------------------------------------------------------ categories

    /** Same dHash, same dimensions (when known) and file sizes within 2 %. */
    private fun findDuplicates(photos: List<Candidate>): List<CleanerGroup> {
        val groups = ArrayList<CleanerGroup>()
        val byHash = HashMap<Long, MutableList<Candidate>>()
        photos.forEach { byHash.getOrPut(it.hash) { ArrayList(2) }.add(it) }
        for ((hash, bucket) in byHash) {
            if (bucket.size < 2) continue
            // Flat pictures (all black / white) have almost no hash information: demand identical files.
            val flat = isFlatHash(hash)
            val sorted = bucket.sortedBy { it.media.size }
            val uf = UnionFind(sorted.size)
            for (i in sorted.indices) {
                val a = sorted[i]
                for (j in i + 1 until sorted.size) {
                    val b = sorted[j]
                    if (!sizesClose(a.media.size, b.media.size)) break // sorted: later ones are even larger
                    if (flat && a.media.size != b.media.size) continue
                    if (!sameDimensions(a, b)) continue
                    uf.union(i, j)
                }
            }
            uf.clusters().forEach { members -> groups.add(makeGroup(members.map { sorted[it] })) }
        }
        return groups
    }

    /** Burst / near-identical shots: dHash distance <= 6 and taken within 10 minutes. */
    private fun findSimilar(photos: List<Candidate>): List<CleanerGroup> {
        if (photos.size < 2) return emptyList()
        val sorted = photos.sortedBy { it.media.timeMs }
        val times = LongArray(sorted.size) { sorted[it].media.timeMs }
        val hashes = LongArray(sorted.size) { sorted[it].hash }
        val uf = UnionFind(sorted.size)
        for (i in sorted.indices) {
            val flatI = isFlatHash(hashes[i])
            var j = i + 1
            while (j < sorted.size && times[j] - times[i] <= SIMILAR_WINDOW_MS) {
                val limit = if (flatI || isFlatHash(hashes[j])) SIMILAR_FLAT_MAX_DISTANCE else SIMILAR_MAX_DISTANCE
                if (SmartIndexRepository.hammingDistance(hashes[i], hashes[j]) <= limit &&
                    similarAspect(sorted[i], sorted[j])
                ) {
                    uf.union(i, j)
                }
                j++
            }
        }
        return uf.clusters().map { members -> makeGroup(members.map { sorted[it] }) }
    }

    private fun isBlurry(c: Candidate): Boolean {
        if (c.index.sharpness >= BLUR_THRESHOLD) return false
        if (c.width <= 0 || c.height <= 0) return false
        if (min(c.width, c.height) < BLUR_MIN_SHORT_SIDE) return false
        // Screenshots and graphics are often flat (low Laplacian variance) without being blurry.
        if (c.media.isScreenshot) return false
        val mime = c.media.mimeType?.lowercase()
        if (mime == "image/png" || mime == "image/gif") return false
        return true
    }

    // ------------------------------------------------------------------ helpers

    /** Keep: highest resolution, then sharpest, then largest file, then the oldest original. */
    private val bestComparator: Comparator<Candidate> =
        compareByDescending<Candidate> { it.pixels }
            .thenByDescending { it.index.sharpness }
            .thenByDescending { it.media.size }
            .thenBy { it.media.timeMs }

    private fun makeGroup(members: List<Candidate>): CleanerGroup {
        val best = members.minWith(bestComparator)
        val others = members.filter { it !== best }.sortedBy { it.media.timeMs }
        val items = ArrayList<CleanerMedia>(members.size)
        items.add(best.media)
        others.forEach { items.add(it.media) }
        val id = members.minOf { it.media.uri.toString() }
        return CleanerGroup(id = id, items = items, best = best.media)
    }

    private fun groupedResult(category: CleanerCategory, groups: List<CleanerGroup>): CategoryResult {
        val sorted = groups.sortedWith(compareByDescending<CleanerGroup> { it.extraBytes }.thenByDescending { it.best.timeMs })
        return CategoryResult(
            category = category,
            groups = sorted,
            reclaimableBytes = sorted.sumOf { it.extraBytes },
            reclaimableCount = sorted.sumOf { it.items.size - 1 }
        )
    }

    private fun flatResult(category: CleanerCategory, items: List<CleanerMedia>) = CategoryResult(
        category = category,
        items = items,
        reclaimableBytes = items.sumOf { it.size },
        reclaimableCount = items.size
    )

    private fun sizesClose(a: Long, b: Long): Boolean {
        if (a == b) return true
        val larger = max(a, b).toDouble()
        return abs(a - b) <= larger * DUPLICATE_SIZE_TOLERANCE
    }

    private fun sameDimensions(a: Candidate, b: Candidate): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return true
        return a.width == b.width && a.height == b.height
    }

    private fun similarAspect(a: Candidate, b: Candidate): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return true
        val ra = a.width.toDouble() / a.height
        val rb = b.width.toDouble() / b.height
        return abs(ra - rb) <= max(ra, rb) * SIMILAR_ASPECT_TOLERANCE
    }

    private fun isFlatHash(hash: Long): Boolean {
        val bits = java.lang.Long.bitCount(hash)
        return bits <= 2 || bits >= 62
    }

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        private val rank = IntArray(n)

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> { parent[rb] = ra; rank[ra]++ }
            }
        }

        /** Sets with at least two members. */
        fun clusters(): List<List<Int>> {
            val map = HashMap<Int, MutableList<Int>>()
            for (i in parent.indices) map.getOrPut(find(i)) { ArrayList(2) }.add(i)
            return map.values.filter { it.size >= 2 }
        }
    }
}
