package com.attriax.sdk.apple

import platform.AdSupport.ASIdentifierManager

/**
 * The ATT-authorized IDFA supplier (IDFA rung).
 *
 * Returns the IDFA (`ASIdentifierManager.advertisingIdentifier`) ONLY when
 * [attAuthorized] is true: Apple ZEROES the IDFA
 * (`00000000-0000-0000-0000-000000000000`) unless ATT is `.authorized`, so reading it
 * without authorization is useless. A zero / empty id also returns null. When null,
 * device-identity resolution falls through IDFV → persistent storage. The
 * `collectAdvertisingId` config gate is applied by [AttriaxIosDeviceIdSources] before
 * this is ever consulted.
 */
internal fun attriaxAttGatedIdfa(attAuthorized: Boolean): String? {
    if (!attAuthorized) return null
    val idfa = ASIdentifierManager.sharedManager().advertisingIdentifier?.UUIDString ?: return null
    if (idfa.isEmpty() || idfa == ZERO_IDFA) return null
    return idfa
}

private const val ZERO_IDFA = "00000000-0000-0000-0000-000000000000"
