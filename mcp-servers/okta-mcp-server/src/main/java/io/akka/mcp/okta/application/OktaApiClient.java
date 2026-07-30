package io.akka.mcp.okta.application;

import com.okta.sdk.authc.credentials.TokenClientCredentials;
import com.okta.sdk.client.Clients;
import com.okta.sdk.resource.api.GroupApi;
import com.okta.sdk.resource.api.UserApi;
import com.okta.sdk.resource.client.ApiClient;
import com.okta.sdk.resource.model.Application;
import com.okta.sdk.resource.model.Group;
import com.okta.sdk.resource.model.User;
import com.okta.sdk.resource.model.UserBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thin read-only wrapper around the Okta Management API (users, groups) using the
 * official Okta Java SDK. Authenticates with a single org-wide SSWS API token
 * configured for this service (OKTA_ORG_URL / OKTA_API_TOKEN) rather than a
 * per-caller token, since Okta admin API tokens are org-scoped, not per-user.
 */
public class OktaApiClient {

    private final UserApi userApi;
    private final GroupApi groupApi;

    public OktaApiClient(String orgUrl, String apiToken) {
        ApiClient apiClient = Clients.builder()
                .setOrgUrl(orgUrl)
                .setClientCredentials(new TokenClientCredentials(apiToken))
                .build();
        this.userApi = new UserApi(apiClient);
        this.groupApi = new GroupApi(apiClient);
    }

    public List<Map<String, Object>> listUsers(String q, String filter, String search, int limit) throws Exception {
        List<User> users = userApi.listUsers(null, search, filter, q, null, Math.min(limit, 200), null, null, null, null);
        return users.stream().map(OktaApiClient::userSummary).collect(Collectors.toList());
    }

    public Map<String, Object> getUser(String userIdOrLogin) throws Exception {
        User user = userApi.getUser(userIdOrLogin, null, null);
        return userDetail(user);
    }

    public List<Map<String, Object>> listUserBlocks(String userIdOrLogin) throws Exception {
        List<UserBlock> blocks = userApi.listUserBlocks(userIdOrLogin);
        return blocks.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", b.getType() != null ? b.getType().getValue() : null);
            m.put("appliesTo", b.getAppliesTo() != null ? b.getAppliesTo().getValue() : null);
            return m;
        }).collect(Collectors.toList());
    }

    public List<Map<String, Object>> listGroups(String q, String filter, String search, int limit) throws Exception {
        List<Group> groups = groupApi.listGroups(search, filter, q, null, Math.min(limit, 200), null, null, null);
        return groups.stream().map(OktaApiClient::groupSummary).collect(Collectors.toList());
    }

    public Map<String, Object> getGroup(String groupId) throws Exception {
        Group group = groupApi.getGroup(groupId);
        return groupSummary(group);
    }

    public List<Map<String, Object>> listGroupMembers(String groupId, int limit) throws Exception {
        List<User> users = groupApi.listGroupUsers(groupId, null, Math.min(limit, 200));
        return users.stream().map(OktaApiClient::userSummary).collect(Collectors.toList());
    }

    public List<Map<String, Object>> listGroupApplications(String groupId, int limit) throws Exception {
        List<Application> apps = groupApi.listAssignedApplicationsForGroup(groupId, null, Math.min(limit, 200));
        return apps.stream().map(app -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", app.getId());
            m.put("label", app.getLabel());
            m.put("signOnMode", app.getSignOnMode() != null ? app.getSignOnMode().getValue() : null);
            m.put("status", app.getStatus() != null ? app.getStatus().getValue() : null);
            return m;
        }).collect(Collectors.toList());
    }

    private static Map<String, Object> userSummary(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("status", user.getStatus() != null ? user.getStatus().getValue() : null);
        m.put("created", user.getCreated() != null ? user.getCreated().toString() : null);
        m.put("lastLogin", user.getLastLogin() != null ? user.getLastLogin().toString() : null);
        if (user.getProfile() != null) {
            m.put("login", user.getProfile().getLogin());
            m.put("email", user.getProfile().getEmail());
            m.put("firstName", user.getProfile().getFirstName());
            m.put("lastName", user.getProfile().getLastName());
        }
        return m;
    }

    private static Map<String, Object> userDetail(User user) {
        Map<String, Object> m = userSummary(user);
        m.put("lastUpdated", user.getLastUpdated() != null ? user.getLastUpdated().toString() : null);
        m.put("statusChanged", user.getStatusChanged() != null ? user.getStatusChanged().toString() : null);
        m.put("passwordChanged", user.getPasswordChanged() != null ? user.getPasswordChanged().toString() : null);
        if (user.getProfile() != null) {
            var profile = user.getProfile();
            m.put("mobilePhone", profile.getMobilePhone());
            m.put("department", profile.getDepartment());
            m.put("title", profile.getTitle());
            m.put("manager", profile.getManager());
            m.put("organization", profile.getOrganization());
            m.put("locale", profile.getLocale());
            m.put("timezone", profile.getTimezone());
        }
        return m;
    }

    private static Map<String, Object> groupSummary(Group group) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", group.getId());
        m.put("created", group.getCreated() != null ? group.getCreated().toString() : null);
        m.put("lastUpdated", group.getLastUpdated() != null ? group.getLastUpdated().toString() : null);
        m.put("lastMembershipUpdated", group.getLastMembershipUpdated() != null ? group.getLastMembershipUpdated().toString() : null);
        m.put("type", group.getType() != null ? group.getType().getValue() : null);
        if (group.getProfile() != null) {
            m.put("name", group.getProfile().getName());
            m.put("description", group.getProfile().getDescription());
        }
        return m;
    }
}
