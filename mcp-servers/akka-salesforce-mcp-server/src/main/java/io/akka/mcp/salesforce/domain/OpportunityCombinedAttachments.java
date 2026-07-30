package io.akka.mcp.salesforce.domain;

import java.util.List;
import java.util.Optional;

/**
 * Result of the nested ContentDocumentLinks query against an Opportunity.
 */
public record OpportunityCombinedAttachments(
        Optional<String> id,
        String name,
        List<ContentDocumentRef> contentDocuments) {
}
