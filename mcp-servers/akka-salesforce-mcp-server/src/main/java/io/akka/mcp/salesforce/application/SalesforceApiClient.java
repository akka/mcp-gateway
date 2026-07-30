package io.akka.mcp.salesforce.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.salesforce.domain.ContentDocumentRef;
import io.akka.mcp.salesforce.domain.OpportunityCombinedAttachments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper around the Salesforce REST API. Stateless — caller supplies the user token per call.
 * All methods use the token's own permissions so users can only access what they can normally see.
 */
public class SalesforceApiClient {

    private static final Logger log = LoggerFactory.getLogger(SalesforceApiClient.class);
    private static final String API_VERSION = "v61.0";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String token;
    private final String instanceUrl;

    public SalesforceApiClient(String token) {
        this.token = token;
        this.instanceUrl = resolveInstanceUrl();
    }

    /**
     * Salesforce org instance host (e.g. {@code https://your-org.my.salesforce.com}), read from the
     * {@code SALESFORCE_INSTANCE_URL} environment variable. No default — it is org-specific.
     */
    private static String resolveInstanceUrl() {
        var url = System.getenv("SALESFORCE_INSTANCE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("SALESFORCE_INSTANCE_URL environment variable is required");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** One file linked to an Opportunity, with its full download URL but no bytes yet. */
    public record AttachmentUrl(String name, String fileExtension, String url) {}

    /** The downloaded bytes (base64-encoded) and content type of a single file. */
    public record DownloadedAttachment(String contentType, String base64Data) {}

    /**
     * Lists the Salesforce Files (ContentDocuments) linked to the given Opportunity as full download
     * URLs, without downloading any binary data — pass a returned url to {@link #downloadAttachment}
     * to fetch that file's bytes.
     */
    public List<AttachmentUrl> listCombinedAttachmentsForOpportunity(String opportunityId) throws Exception {
        log.info("listCombinedAttachmentsForOpportunity: opportunityId={}", opportunityId);
        var files = new ArrayList<AttachmentUrl>();
        for (ContentDocumentRef ref : opportunityCombinedAttachments(opportunityId).contentDocuments()) {
            if (ref.versionDataUrl().isEmpty()) {
                log.info("listCombinedAttachmentsForOpportunity: skipping contentDocument id={} title={}, no versionDataUrl",
                        ref.id().orElse("?"), ref.title());
                continue;
            }
            files.add(new AttachmentUrl(ref.title(), ref.fileExtension().orElse(null), ref.versionDataUrl().get()));
        }
        log.info("listCombinedAttachmentsForOpportunity: opportunityId={} returning {} file(s)", opportunityId, files.size());
        return files;
    }

    /**
     * Downloads a single file's binary data from its full download URL, as returned by
     * {@link #listCombinedAttachmentsForOpportunity}.
     */
    public DownloadedAttachment downloadAttachment(String fileUrl) throws Exception {
        log.info("downloadAttachment: fileUrl={}", fileUrl);
        if (!fileUrl.startsWith(instanceUrl + "/")) {
            throw new SalesforceApiException("Refusing to download from a non-Salesforce url: " + fileUrl);
        }
        var download = fetchBytes(fileUrl);
        String contentType = download.getValue().orElse("application/octet-stream");
        String base64Data = Base64.getEncoder().encodeToString(download.getKey());
        return new DownloadedAttachment(contentType, base64Data);
    }

    /** A Master Agreement record for an Account, with its raw agreement link if set. */
    public record MasterAgreementRef(
            String id,
            String name,
            String createdDate,
            String accountId,
            String accountName,
            String linkToAgreement) {}

    /**
     * Lists Master Agreements for the given Account, most recent first, including each record's raw
     * Links_to_Agreements__c URL (an external link, not fetched or proxied by this server).
     */
    public List<MasterAgreementRef> listMasterAgreementsForAccount(String accountId) throws Exception {
        String soql = "SELECT Id, Name, CreatedDate, Account_Name__r.Name, Account_Name__r.Id, "
                + "Links_to_Agreements__c "
                + "FROM Master_Agreement__c "
                + "WHERE Account_Name__c = '" + escape(accountId) + "' "
                + "ORDER BY CreatedDate DESC";

        log.info("listMasterAgreementsForAccount: SOQL={}", soql);
        var result = new ArrayList<MasterAgreementRef>();
        for (JsonNode record : query(soql).path("records")) {
            JsonNode account = record.path("Account_Name__r");
            var ref = new MasterAgreementRef(
                    record.path("Id").asText(),
                    record.path("Name").asText(),
                    record.path("CreatedDate").asText(),
                    optText(account, "Id").orElse(null),
                    optText(account, "Name").orElse(null),
                    optText(record, "Links_to_Agreements__c").orElse(null));
            log.info("listMasterAgreementsForAccount: found masterAgreement id={} name={} linkToAgreement={}",
                    ref.id(), ref.name(), ref.linkToAgreement());
            result.add(ref);
        }
        log.info("listMasterAgreementsForAccount: accountId={} found {} master agreement(s)", accountId, result.size());
        return result;
    }

    /**
     * Runs the nested ContentDocumentLinks query against the given Opportunity, resolving each
     * linked file's versionDataUrl (instance URL + the path Salesforce returns) but without
     * downloading any binary data yet.
     */
    public OpportunityCombinedAttachments opportunityCombinedAttachments(String opportunityId) throws Exception {
        String soql = "SELECT Id, Name, "
                + "(SELECT ContentDocument.Id, ContentDocument.Title, ContentDocument.FileExtension, "
                + "ContentDocument.LatestPublishedVersionId, ContentDocument.LatestPublishedVersion.VersionData "
                + "FROM ContentDocumentLinks) "
                + "FROM Opportunity WHERE Id = '" + escape(opportunityId) + "'";

        log.info("opportunityCombinedAttachments: SOQL={}", soql);
        var opportunities = query(soql).path("records").iterator();
        if (!opportunities.hasNext()) {
            log.info("opportunityCombinedAttachments: opportunityId={} not found", opportunityId);
            return new OpportunityCombinedAttachments(Optional.empty(), "", List.of());
        }

        JsonNode opportunity = opportunities.next();
        log.info("opportunityCombinedAttachments: opportunityId={} name={}",
                opportunityId, opportunity.path("Name").asText());

        var contentDocuments = new ArrayList<ContentDocumentRef>();
        for (JsonNode link : opportunity.path("ContentDocumentLinks").path("records")) {
            JsonNode doc = link.path("ContentDocument");
            var parsedRef = new ContentDocumentRef(
                    optText(doc, "Id"),
                    doc.path("Title").asText(),
                    optText(doc, "FileExtension"),
                    optText(doc, "LatestPublishedVersionId"),
                    optPath(doc, "LatestPublishedVersion", "VersionData"),
                    Optional.empty());
            var ref = parsedRef.versionDataPath()
                    .map(path -> parsedRef.withVersionDataUrl(instanceUrl + path))
                    .orElse(parsedRef);
            log.info("opportunityCombinedAttachments: found contentDocument id={} title={} fileExtension={} versionDataUrl={}",
                    ref.id().orElse("?"), ref.title(), ref.fileExtension().orElse("?"), ref.versionDataUrl().orElse("<none>"));
            contentDocuments.add(ref);
        }
        log.info("opportunityCombinedAttachments: opportunityId={} found {} contentDocument(s)",
                opportunityId, contentDocuments.size());
        return new OpportunityCombinedAttachments(optText(opportunity, "Id"), opportunity.path("Name").asText(), contentDocuments);
    }

    private static Optional<String> optText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? Optional.empty() : Optional.of(value.asText());
    }

    private static Optional<String> optPath(JsonNode node, String... fields) {
        JsonNode current = node;
        for (String field : fields) current = current.path(field);
        return current.isMissingNode() || current.isNull() ? Optional.empty() : Optional.of(current.asText());
    }

    private JsonNode query(String soql) throws Exception {
        String url = instanceUrl + "/services/data/" + API_VERSION + "/query?q=" + encode(soql);
        log.info("query: GET {}", url);
        var resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        log.info("query: response status={} body={}", resp.statusCode(), resp.body());
        var json = MAPPER.readTree(resp.body());
        if (resp.statusCode() >= 300) {
            throw new SalesforceApiException(describeError(json));
        }
        return json;
    }

    /**
     * Downloads a binary body (e.g. ContentVersion VersionData) from an absolute Salesforce URL,
     * returning the raw bytes together with the response's Content-Type.
     */
    private Map.Entry<byte[], Optional<String>> fetchBytes(String url) throws Exception {
        log.info("fetchBytes: GET {}", url);
        var resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        log.info("fetchBytes: response status={} contentType={} bytes={}",
                resp.statusCode(), resp.headers().firstValue("Content-Type").orElse("?"), resp.body().length);
        if (resp.statusCode() >= 300) {
            throw new SalesforceApiException(
                    "Failed to download body from " + url + " (status " + resp.statusCode() + ")");
        }
        var contentType = resp.headers().firstValue("Content-Type").map(SalesforceApiClient::stripCharset);
        return Map.entry(resp.body(), contentType);
    }

    private static String stripCharset(String contentType) {
        int i = contentType.indexOf(';');
        return i < 0 ? contentType.trim() : contentType.substring(0, i).trim();
    }

    private static String describeError(JsonNode json) {
        if (json.isArray() && json.size() > 0) {
            return json.get(0).path("message").asText("Salesforce API error");
        }
        return "Salesforce API error";
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String escape(String v) {
        return v.replace("'", "\\'");
    }

    public static class SalesforceApiException extends RuntimeException {
        public SalesforceApiException(String message) {
            super(message);
        }
    }
}
