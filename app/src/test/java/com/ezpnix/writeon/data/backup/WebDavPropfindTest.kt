package com.ezpnix.writeon.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SYN-11: a `Depth: 0` PROPFIND answer is only about the URL it names.
 *
 * Two holes are pinned here:
 *  - a folder configured without a trailing slash answers `HEAD 200` like a file; the PROPFIND
 *    probe must report `resourcetype = collection` so the publication decision refuses it;
 *  - a multistatus whose `DAV:href` names another path must not contribute validators for the
 *    requested file - it is unknown, not present.
 */
@RunWith(RobolectricTestRunner::class)
class WebDavPropfindTest {

    private val fileUrl = "https://dav.example/remote.php/dav/files/user/notes.zip"
    private val folderWithoutSlash = "https://dav.example/remote.php/dav/files/user/notes"

    @Test
    fun `a collection without a trailing slash is detected and refused`() {
        val xml = multistatus(
            href = "/remote.php/dav/files/user/notes/",
            props = "<D:resourcetype><D:collection/></D:resourcetype>",
        )

        val state = WebDavPropfind.parse(xml, folderWithoutSlash)

        assertTrue("a folder is a collection even when the request URL lacks the slash", state is RemoteArchiveState.Present)
        assertTrue((state as RemoteArchiveState.Present).isCollection)
        assertEquals(
            CanonicalPublication.Refused(CanonicalPublication.RefusalReason.COLLECTION_URL),
            WebDavPreconditions.decide(
                remote = state,
                knownEtag = null,
                knownLastModified = null,
                forceOverwrite = false,
            ),
        )
    }

    @Test
    fun `a response for a different href is unknown`() {
        val xml = multistatus(
            href = "/remote.php/dav/files/user/other-notes.zip",
            props = "<D:getetag>\"abc\"</D:getetag>",
        )

        val state = WebDavPropfind.parse(xml, fileUrl)

        assertTrue("validators for a different resource must not describe this one", state is RemoteArchiveState.Unknown)
    }

    @Test
    fun `a response without an href is unknown`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:propstat>
                  <D:prop><D:getetag>"abc"</D:getetag></D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertTrue(WebDavPropfind.parse(xml, fileUrl) is RemoteArchiveState.Unknown)
    }

    @Test
    fun `a matching href with an etag is present`() {
        val xml = multistatus(
            href = "/remote.php/dav/files/user/notes%20backup.zip",
            props = "<D:getetag>\"abc\"</D:getetag><D:getcontentlength>42</D:getcontentlength>",
        )

        val state = WebDavPropfind.parse(
            xml,
            "https://dav.example/remote.php/dav/files/user/notes%20backup.zip",
        )

        assertTrue(state is RemoteArchiveState.Present)
        val present = state as RemoteArchiveState.Present
        assertEquals("\"abc\"", present.etag)
        assertEquals(42L, present.contentLength ?: -1L)
    }

    @Test
    fun `an absolute href still matches the requested file url`() {
        val xml = multistatus(
            href = "https://dav.example/remote.php/dav/files/user/notes.zip",
            props = "<D:getetag>\"abc\"</D:getetag>",
        )

        val state = WebDavPropfind.parse(xml, fileUrl)

        assertTrue(state is RemoteArchiveState.Present)
        assertEquals("\"abc\"", (state as RemoteArchiveState.Present).etag)
        assertNull((state as RemoteArchiveState.Present).lastModified)
    }

    private fun multistatus(href: String, props: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>$href</D:href>
            <D:propstat>
              <D:prop>$props</D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()
}
