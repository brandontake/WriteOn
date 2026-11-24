package com.ezpnix.writeon.presentation.screens.home.viewmodel

/**
 * TST-05: the non-UI decisions of Home's batch tag deletion.
 *
 * The tag dialog's delete action trims and deduplicates the requested names, decides which toast a
 * single name earns by checking whether it is still in the tag index after the write, and resets the
 * drawer filter when the deleted tag was the active one. Those rules used to live inline in
 * `HomeViewModel.deleteTags`, where they needed a ViewModel, a Room database and a Toast to test.
 * They are pure here so every branch is covered without a device.
 *
 * The view model's delete path follows the same rules; this function pins them so a change cannot
 * slip in untested. Delegating the call is behavior neutral because it reproduces the inline rules
 * exactly (case-sensitive `distinct`, trimmed blanks dropped, case-insensitive index and filter
 * matching).
 */
internal data class TagDeletionPlan(
    /** Trimmed, non-blank requested tags, in request order, exactly as they will be deleted. */
    val targets: List<String>,
    val feedback: TagDeletionFeedback,
    /** True when the drawer is showing one of [targets] and must fall back to "all". */
    val resetFilterToAll: Boolean,
) {
    /** True when no usable tag was requested; the caller must not run the delete or show a toast. */
    val isEmpty: Boolean get() = targets.isEmpty()
}

/** Which message the deletion earns, mirroring Home's three toast branches. */
internal sealed interface TagDeletionFeedback {
    /** No usable tag was requested; Home returns before showing anything. */
    data object None : TagDeletionFeedback

    /** Exactly one target: "deleted" or "still in use" depending on the post-delete index. */
    data class Single(val tag: String, val stillInUse: Boolean) : TagDeletionFeedback

    /** Several targets share the one plural message. */
    data object Multiple : TagDeletionFeedback
}

/**
 * Decides what Home's tag deletion should do for [requestedTags].
 *
 * @param currentFilter the drawer's active filter (`"all"`, `"pinned"`, [FILTER_UNTAGGED] or a tag).
 * @param remainingTags the tag index *after* the delete; a single target is "still in use" when it
 *   is present there, which happens when other notes carry the same tag under different casing.
 */
internal fun planTagDeletion(
    requestedTags: List<String>,
    currentFilter: String,
    remainingTags: Collection<String>,
): TagDeletionPlan {
    val targets = requestedTags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (targets.isEmpty()) {
        return TagDeletionPlan(emptyList(), TagDeletionFeedback.None, resetFilterToAll = false)
    }

    val targetsLowercase = targets.mapTo(HashSet()) { it.lowercase() }
    val remainingLowercase = remainingTags.mapTo(HashSet()) { it.lowercase() }
    val feedback = targets.singleOrNull()?.let { single ->
        TagDeletionFeedback.Single(
            tag = single,
            stillInUse = single.lowercase() in remainingLowercase,
        )
    } ?: TagDeletionFeedback.Multiple

    return TagDeletionPlan(
        targets = targets,
        feedback = feedback,
        resetFilterToAll = currentFilter.lowercase() in targetsLowercase,
    )
}
