package dev.cse3000.rq3

/**
 * Whitelist of email domains we trust to indicate a real company affiliation.
 * Anything outside this list is treated as personal/unknown and dropped — we
 * favour false negatives (missing a corporate affiliation) over false positives
 * (minting a fake "company" from `someone-vanity.dev`).
 */
@Suppress("unused")
val BUSINESS_EMAIL_DOMAINS = setOf(
    "jetbrains.com",
    "google.com",
    "apple.com",
    "microsoft.com",
    "amazon.com",
    "meta.com",
    "redhat.com",
    "oracle.com",
    "ibm.com",
    "nvidia.com",
    "intel.com",
    "gradle.com",
    "gradle.org",
)