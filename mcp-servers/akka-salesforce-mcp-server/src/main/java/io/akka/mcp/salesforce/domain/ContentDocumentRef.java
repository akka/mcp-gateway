package io.akka.mcp.salesforce.domain;

import java.util.Optional;

/**
 * A Salesforce File (ContentDocument) linked to an Opportunity, as returned by the nested
 * ContentDocumentLinks query, before its binary contents have been downloaded.
 */
public record ContentDocumentRef(
        Optional<String> id,
        String title,
        Optional<String> fileExtension,
        Optional<String> latestPublishedVersionId,
        Optional<String> versionDataPath,
        Optional<String> versionDataUrl) {

    public ContentDocumentRef withVersionDataUrl(String url) {
        return new ContentDocumentRef(id, title, fileExtension, latestPublishedVersionId, versionDataPath, Optional.of(url));
    }
}
