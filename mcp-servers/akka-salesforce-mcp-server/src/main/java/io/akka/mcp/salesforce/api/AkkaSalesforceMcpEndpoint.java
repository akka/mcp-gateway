package io.akka.mcp.salesforce.api;

import akka.http.javadsl.model.HttpHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.Description;
import akka.javasdk.annotations.mcp.McpEndpoint;
import akka.javasdk.annotations.mcp.McpTool;
import akka.javasdk.mcp.AbstractMcpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.salesforce.application.SalesforceApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP endpoint complementing the hosted Salesforce MCP server with tools it does not provide.
 *
 * Callers must supply the user's Salesforce access token as a Bearer token:
 *   Authorization: Bearer 00D...
 *
 * All calls use the token's own permissions so users can only access data they normally can see.
 */
@Acl(allow = @Acl.Matcher(service = "mcp-gateway"))
@McpEndpoint(
        serverName = "akka-salesforce-mcp-server",
        serverVersion = "1.0.0",
        instructions = "To download an Opportunity's files: first call listCombinedAttachmentsForOpportunity "
                + "to get each file's name and full download url, then call "
                + "downloadCombinedAttachmentForOpportunity once per url to fetch its bytes. That tool "
                + "returns base64-encoded bytes — decode each and write it to disk as a real file "
                + "(using the name from the list step and the contentType from the download step) "
                + "rather than displaying the base64 text inline."
)
public class AkkaSalesforceMcpEndpoint extends AbstractMcpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AkkaSalesforceMcpEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @McpTool(
            name = "listCombinedAttachmentsForOpportunity",
            description = "Lists the Salesforce Files (Content Documents) linked to a Salesforce "
                    + "Opportunity. Returns a JSON array of entries, each with file name (name), file "
                    + "extension (fileExtension), and a full download url (url). No file bytes are "
                    + "downloaded by this tool — pass each url to downloadCombinedAttachmentForOpportunity "
                    + "to fetch that file's contents."
    )
    public String listCombinedAttachmentsForOpportunity(
            @Description("The Salesforce Opportunity ID, e.g. 006XXXXXXXXXXXXXXX") String opportunityId
    ) {
        log.info("listCombinedAttachmentsForOpportunity opportunityId={}", opportunityId);
        try {
            var salesforce = new SalesforceApiClient(extractBearerToken());
            var attachments = salesforce.listCombinedAttachmentsForOpportunity(opportunityId);
            return MAPPER.writeValueAsString(attachments);
        } catch (SalesforceApiClient.SalesforceApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list attachments for opportunity " + opportunityId, e);
        }
    }

    @McpTool(
            name = "downloadCombinedAttachmentForOpportunity",
            description = "Downloads a single Salesforce file's contents given the full download url "
                    + "returned by listCombinedAttachmentsForOpportunity. Returns a JSON object with "
                    + "MIME content type (contentType) and base64-encoded file bytes (base64Data). The "
                    + "calling agent should decode base64Data and write it to disk as a real file, using "
                    + "the file name already known from the list step — do not just print or return the "
                    + "base64 text to the user."
    )
    public String downloadCombinedAttachmentForOpportunity(
            @Description("The full file download url, as returned by listCombinedAttachmentsForOpportunity") String fileUrl
    ) {
        log.info("downloadCombinedAttachmentForOpportunity fileUrl={}", fileUrl);
        try {
            var salesforce = new SalesforceApiClient(extractBearerToken());
            var attachment = salesforce.downloadAttachment(fileUrl);
            return MAPPER.writeValueAsString(attachment);
        } catch (SalesforceApiClient.SalesforceApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to download attachment from " + fileUrl, e);
        }
    }

    @McpTool(
            name = "listMasterAgreementsForAccount",
            description = "Lists Master Agreement records for a Salesforce Account, most recent first. "
                    + "Returns a JSON array of entries, each with id, name, createdDate, accountId, "
                    + "accountName, and linkToAgreement (an external URL to the agreement document, or "
                    + "null if not set). This tool does not download or proxy that link's contents — "
                    + "the calling agent should fetch linkToAgreement directly if it needs the file."
    )
    public String listMasterAgreementsForAccount(
            @Description("The Salesforce Account ID, e.g. 001XXXXXXXXXXXXXXX") String accountId
    ) {
        log.info("listMasterAgreementsForAccount accountId={}", accountId);
        try {
            var salesforce = new SalesforceApiClient(extractBearerToken());
            var agreements = salesforce.listMasterAgreementsForAccount(accountId);
            return MAPPER.writeValueAsString(agreements);
        } catch (SalesforceApiClient.SalesforceApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list master agreements for account " + accountId, e);
        }
    }

    private String extractBearerToken() {
        return requestContext().requestHeader("Authorization")
                .map(HttpHeader::value)
                .filter(v -> v.toLowerCase().startsWith("bearer "))
                .map(v -> v.substring(7).trim())
                .orElseThrow(() -> new RuntimeException("Unauthorized: missing Bearer token"));
    }
}
